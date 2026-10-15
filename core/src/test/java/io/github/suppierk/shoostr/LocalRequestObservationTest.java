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

class LocalRequestObservationTest {
  @Test
  void inheritsTheSameFactoryOnceAndCompletesWithoutAppObservers() throws Exception {
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

    try (var app = new Shoostr()) {
      app.routes()
          .path(
              "/api",
              routes ->
                  routes.get(
                      "/ok",
                      (_, response) -> response.text("ok"),
                      e -> e.observe(factory).observe(factory)),
              e -> e.observe(factory));

      try (var test = TestServer.start(app)) {
        assertEquals(200, test.send(request -> request.path("/api/ok")).statusCode());
        assertEquals(200, completed.get(5, TimeUnit.SECONDS).statusCode());
        assertEquals(1, started.get());
        assertEquals(1, closed.get());
      }
    }
  }

  @Test
  void isolatesLocalObservationFailuresWithoutRejectingTheEndpoint() throws Exception {
    var completed = new CompletableFuture<RequestOutcome>();
    var current = new ThreadLocal<String>();

    try (var app = new Shoostr()) {
      app.routes()
          .get(
              "/ok",
              (_, response) -> response.text(current.get()),
              e -> {
                e.observe(
                    _ -> {
                      throw new IllegalStateException("factory");
                    });
                e.observe(
                    _ ->
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
                e.observe(
                    _ -> {
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

    try (var app = new Shoostr()) {
      app.routes().get("/ok", (_, _) -> {}, retained::set);
      assertThrows(IllegalStateException.class, () -> retained.get().observe(_ -> _ -> {}));
    }
  }
}
