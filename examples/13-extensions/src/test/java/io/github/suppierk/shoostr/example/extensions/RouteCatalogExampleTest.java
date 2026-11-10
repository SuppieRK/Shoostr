package io.github.suppierk.shoostr.example.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RouteCatalogExampleTest {
  @Test
  void publishesComposedRouteFactsWithIndependentInheritedDescriptions() throws Exception {

    try (var test = TestServer.start(RouteCatalogExample.application(() -> false))) {
      var response =
          test.send(request -> request.path("/catalog"), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals(
          """
          GET /api/items/{id} — Read an item
          GET /api/status — Check readiness
          GET /catalog — List route descriptions
          GET /preview — Preview content [conditional]""",
          response.body());
    }
  }

  @Test
  void excludesHealthFromDefaultCatalogFactsAndDiscoveryHeader() throws Exception {

    try (var test = TestServer.start(RouteCatalogExample.application(() -> false))) {
      var health =
          test.send(request -> request.path("/health"), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, health.statusCode());
      assertEquals("ok", health.body());
      assertEquals("absent", health.headers().firstValue("X-Route-Catalog").orElse("absent"));
      var catalog =
          test.send(request -> request.path("/catalog"), HttpResponse.BodyHandlers.ofString());
      assertFalse(catalog.body().contains("/health"));
    }
  }

  @Test
  void rejectsReuseWithoutDisruptingTheOwnerAndClearsItsCatalogAfterClosure() throws Exception {
    var catalog = new RouteCatalogExample.RouteCatalog();

    {
      var first = new Shoostr();

      try (Closeable firstCleanup = first::close) {
        var second = new Shoostr();

        try (Closeable secondCleanup = second::close) {

          first.withExtension(catalog);
          assertThrows(IllegalStateException.class, () -> second.withExtension(catalog));
          first
              .routes()
              .get(
                  "/kept",
                  (ignored, response) -> response.text("kept"),
                  lifecycle -> lifecycle.withExtension(catalog).summary("Kept"));

          try (var test = TestServer.start(first)) {
            assertEquals(200, test.send(request -> request.path("/kept")).statusCode());
            assertEquals("GET /kept — Kept", catalog.document());
          }

          assertEquals("", catalog.document());
        }
      }
    }
  }

  @Test
  void describesConditionalAvailabilityWithoutEvaluatingItUntilARequestMatches() throws Exception {
    var enabled = new AtomicBoolean();
    var checks = new AtomicInteger();

    try (var test =
        TestServer.start(
            RouteCatalogExample.application(
                () -> {
                  checks.incrementAndGet();
                  return enabled.get();
                }))) {
      assertEquals(0, checks.get());
      var before =
          test.send(request -> request.path("/catalog"), HttpResponse.BodyHandlers.ofString());
      assertEquals(0, checks.get());
      assertEquals(404, test.send(request -> request.path("/preview")).statusCode());
      assertEquals(1, checks.get());
      enabled.set(true);
      assertEquals(200, test.send(request -> request.path("/preview")).statusCode());
      assertEquals(2, checks.get());
      assertEquals(
          before.body(),
          test.send(request -> request.path("/catalog"), HttpResponse.BodyHandlers.ofString())
              .body());
    }
  }

  @Test
  void rejectsBlankDescriptionsAndKeepsCompiledMetadataIndependentOfLaterChanges()
      throws Exception {
    var catalog = new RouteCatalogExample.RouteCatalog();
    var retained = new AtomicReference<RouteCatalogExample.Description>();

    {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close) {
        app.routes()
            .get(
                "/snapshot",
                (ignored2, response) -> response.text("ok"),
                lifecycle -> {
                  var description = lifecycle.withExtension(catalog);
                  assertThrows(IllegalArgumentException.class, () -> description.summary(" "));
                  retained.set(description.summary("Original"));
                });

        try (var test = TestServer.start(app)) {
          retained.get().summary("Later change");
          assertEquals(200, test.send(request -> request.path("/snapshot")).statusCode());
          assertEquals("GET /snapshot — Original", catalog.document());
        }
      }
    }
  }
}
