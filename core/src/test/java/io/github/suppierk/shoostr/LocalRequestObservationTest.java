package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LocalRequestObservationTest {
  private static Shoostr withExecutionMode(Options options, boolean virtualApplication) {
    options.setVirtualThreads(virtualApplication);

    return new Shoostr(options)
        .onRequestHeaders(
            (request, response) ->
                assertEquals(virtualApplication, Thread.currentThread().isVirtual()));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void inheritsTheSameFactoryOnceAndCompletesWithoutAppObservers(boolean virtualApplication)
      throws Exception {
    var started = new AtomicInteger();
    var closed = new AtomicInteger();
    var completed = new CompletableFuture<RequestOutcome>();
    Function<Request, RequestObservation> factory =
        createFactoryForInheritsTheSameFactoryOnceAndCompletesWithoutAppObservers(
            started, closed, completed);

    {
      var app = withExecutionMode(new Options(), virtualApplication);

      try (Closeable appCleanup = app::close) {
        app.routes()
            .path(
                "/api",
                routes ->
                    routes.get(
                        "/ok",
                        (ignored, response) -> response.text("ok"),
                        lifecycle -> lifecycle.observe(factory).observe(factory)),
                lifecycle -> lifecycle.observe(factory));

        try (var test = TestServer.start(app)) {
          assertEquals(200, test.send(request -> request.path("/api/ok")).statusCode());
          assertEquals(200, completed.get(5, TimeUnit.SECONDS).statusCode());
          assertEquals(1, started.get());
          assertEquals(1, closed.get());
        }
      }
    }
  }

  private Function<Request, RequestObservation>
      createFactoryForInheritsTheSameFactoryOnceAndCompletesWithoutAppObservers(
          AtomicInteger started,
          AtomicInteger closed,
          CompletableFuture<RequestOutcome> completed) {
    return request -> {
      assertEquals("/api/ok", request.routePattern().orElseThrow());

      started.incrementAndGet();

      return new RequestObservation() {
        @Override
        public void close() {
          closed.incrementAndGet();
        }

        @Override
        public void complete(RequestOutcome outcome) {
          completed.complete(outcome);
        }
      };
    };
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void isolatesLocalObservationFailuresWithoutRejectingTheEndpoint(boolean virtualApplication)
      throws Exception {
    var completed = new CompletableFuture<RequestOutcome>();
    var current = new ThreadLocal<String>();

    {
      var app = withExecutionMode(new Options(), virtualApplication);

      try (Closeable appCleanup1 = app::close) {
        app.routes()
            .get(
                "/ok",
                (ignoredRequest, response) -> response.text(current.get()),
                lifecycle -> {
                  lifecycle.observe(
                      ignoredRequest -> {
                        throw new IllegalStateException("factory");
                      });
                  lifecycle.observe(
                      ignoredRequest ->
                          new RequestObservation() {
                            @Override
                            public void close() {
                              throw new IllegalStateException("scope");
                            }

                            @Override
                            public void complete(RequestOutcome outcome) {
                              throw new IllegalStateException("completion");
                            }
                          });
                  lifecycle.observe(
                      ignoredRequest -> {
                        current.set("observed");

                        return new RequestObservation() {
                          @Override
                          public void close() {
                            current.remove();
                          }

                          @Override
                          public void complete(RequestOutcome outcome) {
                            completed.complete(outcome);
                          }
                        };
                      });
                });

        try (var test = TestServer.start(app)) {
          var result =
              test.send(request -> request.path("/ok"), HttpResponse.BodyHandlers.ofString());

          assertEquals(200, result.statusCode());
          assertEquals("observed", result.body());
          assertNull(completed.get(5, TimeUnit.SECONDS).applicationFailure());
        }
      }
    }
  }

  @Test
  void rejectsObservationRegistrationAfterTheCallbackReturns() throws Exception {
    var retained = new AtomicReference<Lifecycle>();
    var retainedLifecycle = new AtomicReference<Lifecycle>();

    {
      var app = new Shoostr();

      try (Closeable appCleanup2 = app::close) {
        app.routes()
            .get(
                "/ok",
                (ignoredRequest, ignoredResponse) -> {},
                extensions -> {
                  retained.set(extensions);
                  retainedLifecycle.set(extensions);
                });
        var configuration = retained.get();
        var lifecycle = retainedLifecycle.get();

        assertThrows(
            IllegalStateException.class,
            () -> configuration.beforeRouteHandler((request, response) -> {}));
        assertThrows(
            IllegalStateException.class,
            () -> lifecycle.observe(ignoredRequest -> ignoredRequestOutcome -> {}));
      }
    }
  }
}
