package io.github.suppierk.shoostr.openapi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.Extension;
import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.testing.TestServer;
import io.swagger.v3.oas.models.media.JsonSchema;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.Test;

class OpenApiLifecycleTest {
  @Test
  void servesFrozenDocumentsConcurrentlyWithoutInvokingDocumentedEndpoints() throws Exception {
    var docs = new OpenApi("API", "1");
    var called = new AtomicInteger();
    var retained = new AtomicReference<OpenApiOperation>();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.extensions(docs)
          .routes(
              routes -> {
                routes.get(
                    "/endpoint",
                    (_, _) -> called.incrementAndGet(),
                    e -> retained.set(e.get(docs).summary("Endpoint")));
                routes.get(
                    "/openapi.json", docs.handler(), e -> e.get(docs).summary("Own specification"));
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
        assertThrows(IllegalStateException.class, () -> retained.get().summary("Late mutation"));
      }
    }
  }

  @Test
  void closesInstalledExtensionsOnPreparationFailureWithoutStartingTheServer() throws Exception {
    var closed = new AtomicInteger();
    var listening = new AtomicInteger();
    var observer =
        new Extension<Void>() {
          @Override
          public void close() {
            closed.incrementAndGet();
          }
        };
    var docs = new OpenApi("API", "1");

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
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
                      (_, _) -> {},
                      e ->
                          e.get(docs)
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
}
