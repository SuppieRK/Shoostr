package io.github.suppierk.shoostr.openapi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.Extension;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.testing.TestServer;
import io.swagger.v3.oas.models.media.JsonSchema;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.Test;

class OpenApiLifecycleTest {
  @Test
  void servesFrozenDocumentsConcurrentlyWithoutInvokingDocumentedEndpoints() throws Exception {
    var docs = new OpenApi("API", "1");
    var called = new AtomicInteger();
    var retained = new AtomicReference<OpenApiOperation>();

    try (var app = new Shoostr()) {
      app.extensions(docs)
          .routes(
              routes -> {
                routes.get(
                    "/endpoint",
                    (ignored, ignored2) -> called.incrementAndGet(),
                    e -> retained.set(e.with(docs).summary("Endpoint")));
                routes.get(
                    "/openapi.json",
                    docs.handler(),
                    e -> e.with(docs).summary("Own specification"));
              });

      try (var test = TestServer.start(app);
          var clients = Executors.newVirtualThreadPerTaskExecutor()) {
        var original = test.send(request -> request.path("/openapi.json")).body();
        var results = new ArrayList<Future<byte[]>>();
        for (int i = 0; i < 24; i++) {
          results.add(
              clients.submit(() -> test.send(request -> request.path("/openapi.json")).body()));
        }
        for (var result : results) {
          assertArrayEquals(original, result.get(10, TimeUnit.SECONDS));
        }
        assertEquals(0, called.get());
        var operation = retained.get();
        assertThrows(IllegalStateException.class, () -> operation.summary("Late mutation"));
      }
    }
  }

  @Test
  void closesInstalledExtensionsOnPreparationFailureWithoutStartingTheServer() throws Exception {
    var closed = new AtomicInteger();
    var listening = new AtomicInteger();
    var observer = new ClosingExtension(closed);
    var docs = new OpenApi("API", "1");

    try (var app =
        new Shoostr()
            .modifyServer(
                nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0))) {
      app.extensions(observer, docs)
          .modifyServer(
              server ->
                  server.addEventListener(
                      new LifeCycle.Listener() {
                        @Override
                        public void lifeCycleStarting(LifeCycle event) {
                          listening.incrementAndGet();
                        }
                      }))
          .routes(
              routes ->
                  routes.get(
                      "/broken",
                      (ignored3, ignored4) -> {},
                      e ->
                          e.with(docs)
                              .response(
                                  200,
                                  "Broken",
                                  MediaType.APPLICATION_JSON,
                                  new JsonSchema().$ref("#/components/schemas/Missing"))));
      assertThrows(IllegalArgumentException.class, app::start);
      assertEquals(0, listening.get());
      assertEquals(1, closed.get());
    }

    assertEquals(1, closed.get());
  }

  private record ClosingExtension(AtomicInteger closes) implements Extension<Void> {
    private ClosingExtension {
      Objects.requireNonNull(closes);
    }

    @Override
    public void close() {
      closes.incrementAndGet();
    }
  }
}
