package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ObservationHandoffTest {
  @Test
  void restoresNestedObservationScopesToPreviousWorkerContext() throws Exception {
    var context = new ThreadLocal<String>();
    var events = new ArrayList<String>();
    var completions = new AtomicInteger();
    var finished = new CompletableFuture<Void>();
    var workers = Executors.newFixedThreadPool(1);
    workers.submit(() -> context.set("worker-base")).get(5, TimeUnit.SECONDS);

    try (workers;
        var app = new Shoostr(Options.defaults().withPort(0))) {
      for (var value : List.of("alpha", "beta")) {
        app.observe(
            _ -> {
              var original = context.get();
              context.set(value);
              return new RequestObservation() {
                @Override
                public AutoCloseable attach() {
                  var previous = context.get();
                  events.add(value);
                  context.set(value);
                  return () -> {
                    context.set(previous);
                    events.add("/" + value);
                  };
                }

                @Override
                public void close() {
                  context.set(original);
                }

                @Override
                public void complete(RequestOutcome outcome) {
                  completions.incrementAndGet();
                }
              };
            });
      }
      app.afterRequest(_ -> finished.complete(null));
      app.routes()
          .get(
              "/nested",
              workers,
              (_, response) -> response.text(Objects.requireNonNull(context.get())));

      try (var test = TestServer.start(app)) {
        assertEquals(
            "beta",
            test.send(request -> request.path("/nested"), HttpResponse.BodyHandlers.ofString())
                .body());
        finished.get(5, TimeUnit.SECONDS);
        assertEquals(List.of("alpha", "beta", "/beta", "/alpha"), events);
        assertEquals("worker-base", workers.submit(context::get).get(5, TimeUnit.SECONDS));
        assertEquals(2, completions.get());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"attach", "close"})
  void isolatesObservationScopeFailureFromLaterCleanupAndCompletion(String failurePhase)
      throws Exception {
    var finished = new CompletableFuture<Void>();
    var completions = new AtomicInteger();
    var laterClosed = new AtomicBoolean();
    var laterAttached = new AtomicBoolean();
    var workers = Executors.newFixedThreadPool(1);

    try (workers;
        var app = new Shoostr(Options.defaults().withPort(0))) {
      app.observe(
          _ ->
              new RequestObservation() {
                @Override
                public AutoCloseable attach() {
                  return () -> laterClosed.set(true);
                }

                @Override
                public void complete(RequestOutcome outcome) {
                  completions.incrementAndGet();
                }
              });
      app.observe(
          _ ->
              new RequestObservation() {
                @Override
                public AutoCloseable attach() {
                  if ("attach".equals(failurePhase)) {
                    throw new IllegalStateException("attach failed");
                  }

                  return () -> {
                    throw new IOException("close failed");
                  };
                }

                @Override
                public void complete(RequestOutcome outcome) {
                  completions.incrementAndGet();
                }
              });
      app.observe(
          _ ->
              new RequestObservation() {
                @Override
                public AutoCloseable attach() {
                  laterAttached.set(true);
                  return () -> {};
                }

                @Override
                public void complete(RequestOutcome outcome) {
                  completions.incrementAndGet();
                }
              });
      app.afterRequest(_ -> finished.complete(null));
      app.routes().get("/failure", workers, (_, response) -> response.text("ok"));

      try (var test = TestServer.start(app)) {
        assertEquals(200, test.send(request -> request.path("/failure")).statusCode());
        finished.get(5, TimeUnit.SECONDS);
        assertTrue(laterClosed.get());
        assertTrue(laterAttached.get());
        assertEquals(3, completions.get());
      }
    }
  }

  @Test
  void reattachesRejectedSubmissionContextOnAdmissionOwner() throws Exception {
    var context = new ThreadLocal<String>();
    var events = new ArrayList<String>();
    var sameOwner = new AtomicBoolean();
    var finished = new CompletableFuture<Void>();
    var workers = Executors.newFixedThreadPool(1);

    try (workers;
        var app = new Shoostr(Options.defaults().withPort(0))) {
      app.observe(
          _ -> {
            var owner = Thread.currentThread();
            context.set("observation");
            return new RequestObservation() {
              @Override
              public AutoCloseable attach() {
                sameOwner.set(owner == Thread.currentThread());
                events.add("attach");
                context.set("observation");
                return () -> {
                  context.remove();
                  events.add("attached-close");
                };
              }

              @Override
              public void close() {
                context.remove();
                events.add("original-close");
              }

              @Override
              public void complete(RequestOutcome outcome) {
                events.add("complete");
                finished.complete(null);
              }
            };
          });
      app.exception(
          RejectedExecutionException.class,
          (_, _, response) -> response.status(503).text(Objects.requireNonNull(context.get())));
      app.routes().get("/rejected", workers, (_, response) -> response.text("wrong"));

      try (var test = TestServer.start(app)) {
        workers.shutdown();
        var result =
            test.send(request -> request.path("/rejected"), HttpResponse.BodyHandlers.ofString());
        assertEquals(503, result.statusCode());
        assertEquals("observation", result.body());
        finished.get(5, TimeUnit.SECONDS);
        assertTrue(sameOwner.get());
        assertEquals(List.of("original-close", "attach", "attached-close", "complete"), events);
      }
    }
  }
}
