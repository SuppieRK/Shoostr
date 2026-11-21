package io.github.suppierk.shoostr.openapi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.net.URISyntaxException;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OpenApiUiTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "openapi.json",
        "https://example.test/openapi.json",
        "//example.test/openapi.json",
        "///openapi.json",
        "/docs//openapi.json",
        "/docs/../openapi.json",
        "/docs/./openapi.json",
        "/openapi.json?version=1",
        "/openapi.json#section",
        "/%6fpenapi.json"
      })
  void rejectsNonliteralSpecificationPaths(String path) {
    var docs = new OpenApi("API", "1");

    var failure =
        assertThrows(IllegalArgumentException.class, () -> docs.uiHandler("/docs/", path));

    assertEquals("UI resources require absolute literal same-origin paths", failure.getMessage());
  }

  @Test
  void reportsMalformedSpecificationPathBeforeCheckingThePageTrailingSlash() {
    var docs = new OpenApi("API", "1");

    var failure =
        assertThrows(IllegalArgumentException.class, () -> docs.uiHandler("/docs", "/{spec}"));

    assertInstanceOf(URISyntaxException.class, failure.getCause());
  }

  @Test
  void permitsColonInLocalSpecificationPath() {
    var docs = new OpenApi("API", "1");

    assertDoesNotThrow(() -> docs.uiHandler("/", "/docs/openapi:v1.json"));
  }

  @Test
  void keepsPublishedResourcesWhenPreparationFails() throws Exception {
    var ui = new OpenApiUi("/docs/", "/openapi.json");
    var stylesheet = new byte[] {1, 2};
    ui.prepare(stylesheet, new byte[] {3, 4});

    assertThrows(NullPointerException.class, () -> ui.prepare(new byte[] {5, 6}, null));

    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.routes().get("/docs/swagger-ui.css", ui);

      try (var test = TestServer.start(app)) {
        var result = test.send(request -> request.path("/docs/swagger-ui.css"));

        assertEquals(200, result.statusCode());
        assertArrayEquals(stylesheet, result.body());
      }
    }
  }

  @Test
  void servesEveryPreparedUiResourceUnchangedAcrossConcurrentRepeatedRequests() throws Exception {
    var docs = new OpenApi("API", "1");
    var ui = docs.uiHandler("/docs/", "/openapi.json");
    var paths = expectedContentTypes();

    {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close) {
        app.withExtension(docs, operation -> operation.hidden())
            .routes(routes -> {
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
                  clients.submit(() -> {
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
  }

  private Map<String, String> expectedContentTypes() {
    var entries = new LinkedHashMap<String, String>();
    entries.put("/docs/", "text/html");
    entries.put("/docs/swagger-ui.css", "text/css");
    entries.put("/docs/swagger-ui-bundle.js", "text/javascript");
    entries.put("/docs/swagger-initializer.js", "text/javascript");

    return Map.copyOf(entries);
  }

  @Test
  void servesLocalAllowlistedResourcesThroughExplicitPageAndCatchAllRoutes() throws Exception {
    var docs = new OpenApi("API", "1");
    var ui = docs.uiHandler("/docs/", "/openapi.json");

    {
      var app = new Shoostr();

      try (Closeable appCleanup1 = app::close) {
        app.withExtension(docs, operation -> operation.hidden())
            .routes(routes -> {
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
          assertEquals(
              200, test.send(request -> request.path("/docs/swagger-ui.css")).statusCode());

          var bundle = test.send(request -> request.path("/docs/swagger-ui-bundle.js"));

          assertEquals(200, bundle.statusCode());
          assertTrue(bundle.body().length > new Options().getMaxResponseBytes());

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
}
