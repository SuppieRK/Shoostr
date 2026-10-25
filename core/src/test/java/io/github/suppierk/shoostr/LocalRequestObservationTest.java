package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.testing.TestServer;
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
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void inheritsTheSameFactoryOnceAndCompletesWithoutAppObservers(boolean virtualApplication)
      throws Exception {
    var started = new AtomicInteger();
    var closed = new AtomicInteger();
    var completed = new CompletableFuture<RequestOutcome>();
    Function<Request, RequestObservation> factory =
        request -> {
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

    try (var app = withExecutionMode(new Shoostr(), virtualApplication)) {
      app.routes()
          .path(
              "/api",
              routes ->
                  routes.get(
                      "/ok",
                      (ignored, response) -> response.text("ok"),
                      e -> e.lifecycle().observe(factory).observe(factory)),
              e -> e.lifecycle().observe(factory));

      try (var test = TestServer.start(app)) {
        assertEquals(200, test.send(request -> request.path("/api/ok")).statusCode());
        assertEquals(200, completed.get(5, TimeUnit.SECONDS).statusCode());
        assertEquals(1, started.get());
        assertEquals(1, closed.get());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void isolatesLocalObservationFailuresWithoutRejectingTheEndpoint(boolean virtualApplication)
      throws Exception {
    var completed = new CompletableFuture<RequestOutcome>();
    var current = new ThreadLocal<String>();

    try (var app = withExecutionMode(new Shoostr(), virtualApplication)) {
      app.routes()
          .get(
              "/ok",
              (ignored2, response) -> response.text(current.get()),
              e -> {
                e.lifecycle()
                    .observe(
                        ignored3 -> {
                          throw new IllegalStateException("factory");
                        });
                e.lifecycle()
                    .observe(
                        ignored4 ->
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
                e.lifecycle()
                    .observe(
                        ignored5 -> {
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

  @Test
  void rejectsObservationRegistrationAfterTheCallbackReturns() throws Exception {
    var retained = new AtomicReference<Extensions>();
    var retainedLifecycle = new AtomicReference<Lifecycle>();

    try (var app = new Shoostr()) {
      app.routes()
          .get(
              "/ok",
              (ignored6, ignored7) -> {},
              extensions -> {
                retained.set(extensions);
                retainedLifecycle.set(extensions.lifecycle());
              });
      var configuration = retained.get();
      var lifecycle = retainedLifecycle.get();
      assertThrows(IllegalStateException.class, configuration::lifecycle);
      assertThrows(
          IllegalStateException.class, () -> lifecycle.observe(ignored8 -> ignored9 -> {}));
    }
  }

  private static Shoostr withExecutionMode(Shoostr app, boolean virtualApplication) {
    if (virtualApplication) {
      app.virtualThreads();
    }

    return app.onRequestHeaders(
        (request, response) ->
            assertEquals(virtualApplication, Thread.currentThread().isVirtual()));
  }
}
