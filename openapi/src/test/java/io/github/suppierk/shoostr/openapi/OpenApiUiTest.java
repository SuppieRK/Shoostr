package io.github.suppierk.shoostr.openapi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OpenApiUiTest {
  @Test
  void servesEveryPreparedUiResourceUnchangedAcrossConcurrentRepeatedRequests() throws Exception {
    var docs = new OpenApi("API", "1");
    var ui = docs.uiHandler("/docs/", "/openapi.json");
    var paths =
        Map.of(
            "/docs/",
            "text/html",
            "/docs/swagger-ui.css",
            "text/css",
            "/docs/swagger-ui-bundle.js",
            "text/javascript",
            "/docs/swagger-initializer.js",
            "text/javascript");

    try (var app = new Shoostr()) {
      app.extensions(docs)
          .routes(
              routes -> {
                routes.get("/docs/", ui);
                routes.get("/docs/{*asset}", ui);
                routes.get("/openapi.json", docs.handler());
              });

      try (var test = TestServer.start(app);
          var clients = Executors.newVirtualThreadPerTaskExecutor()) {
        var originals = new HashMap<String, byte[]>();
        for (var path : paths.keySet()) {
          var initial = test.send(request -> request.path(path));
          assertEquals(200, initial.statusCode());
          assertTrue(
              initial
                  .headers()
                  .firstValue("Content-Type")
                  .orElseThrow()
                  .startsWith(paths.get(path)));
          originals.put(path, initial.body());
        }
        var start = new CountDownLatch(1);
        var results = new ArrayList<Future<?>>();
        for (int round = 0; round < 3; round++) {
          for (var path : paths.keySet()) {
            results.add(
                clients.submit(
                    () -> {
                      assertTrue(start.await(5, TimeUnit.SECONDS));
                      var result = test.send(request -> request.path(path));
                      assertEquals(200, result.statusCode());
                      assertTrue(
                          result
                              .headers()
                              .firstValue("Content-Type")
                              .orElseThrow()
                              .startsWith(paths.get(path)));
                      assertArrayEquals(originals.get(path), result.body());
                      return null;
                    }));
          }
        }
        start.countDown();
        for (var result : results) {
          result.get(10, TimeUnit.SECONDS);
        }
      }
    }
  }

  @Test
  void servesLocalAllowlistedResourcesThroughExplicitPageAndCatchAllRoutes() throws Exception {
    var docs = new OpenApi("API", "1");
    var ui = docs.uiHandler("/docs/", "/openapi.json");

    try (var app = new Shoostr()) {
      app.extensions(docs)
          .routes(
              routes -> {
                routes.path(
                    "/docs",
                    group -> {
                      group.get("/", ui);
                      group.get("/{*asset}", ui);
                    });
                routes.get("/openapi.json", docs.handler());
              });

      try (var test = TestServer.start(app)) {
        var page =
            test.send(request -> request.path("/docs/"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("swagger-ui-bundle.js"));
        assertTrue(page.body().contains("swagger-ui.css"));
        assertEquals(200, test.send(request -> request.path("/docs/swagger-ui.css")).statusCode());
        var bundle = test.send(request -> request.path("/docs/swagger-ui-bundle.js"));
        assertEquals(200, bundle.statusCode());
        assertTrue(bundle.body().length > Options.defaults().maxResponseBytes());
        var configuration =
            test.send(
                request -> request.path("/docs/swagger-initializer.js"),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(configuration.body().contains("/openapi.json"));
        assertTrue(configuration.body().contains("\"validatorUrl\":null"));
        assertEquals(404, test.send(request -> request.path("/docs/unknown.js")).statusCode());
      }
    }
  }
}
