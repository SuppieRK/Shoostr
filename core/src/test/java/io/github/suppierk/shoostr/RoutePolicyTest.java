package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.extensions.AdmissionExtension;
import io.github.suppierk.shoostr.http.exceptions.AuthenticationRequiredException;
import io.github.suppierk.shoostr.http.exceptions.ForbiddenException;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RoutePolicyTest {
  @TempDir Path directory;

  @Test
  void rejectsExtensionConfigurationAfterRegistrationCloses() throws Exception {
    var invoked = new AtomicBoolean();

    {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close) {
        var routes = app.routes();
        app.close();

        assertThrows(
            IllegalStateException.class,
            () ->
                routes.get(
                    "/", (ignored, ignoredResponse) -> {}, ignoredLifecycle -> invoked.set(true)));
        assertFalse(invoked.get());
      }
    }
  }

  @Test
  void protectionPreservesTheAlreadyComposedGroupEndpoint() throws Exception {
    {
      var plain = new Shoostr();

      try (Closeable plainCleanup = plain::close) {
        var guarded = new Shoostr();

        try (Closeable guardedCleanup = guarded::close) {
          plain
              .routes()
              .path(
                  "/api//",
                  routes -> routes.get("", (ignoredRequest, response) -> response.text("plain")));
          var admission = new AdmissionExtension((ignoredRequest, ignoredResponse) -> {});
          guarded
              .routes()
              .path(
                  "/api//",
                  routes ->
                      routes.get(
                          "",
                          (ignoredRequest, response) -> response.text("guarded"),
                          lifecycle -> lifecycle.withExtension(admission)));

          try (var plainTest = TestServer.start(plain);
              var guardedTest = TestServer.start(guarded)) {

            assertEquals(
                200,
                plainTest
                    .send(
                        request -> request.path("/api/").timeout(Duration.ofSeconds(5)),
                        HttpResponse.BodyHandlers.ofString())
                    .statusCode());
            assertEquals(
                404,
                plainTest
                    .send(
                        request -> request.path("/api").timeout(Duration.ofSeconds(5)),
                        HttpResponse.BodyHandlers.ofString())
                    .statusCode());
            assertEquals(
                200,
                guardedTest
                    .send(
                        request -> request.path("/api/").timeout(Duration.ofSeconds(5)),
                        HttpResponse.BodyHandlers.ofString())
                    .statusCode());
            assertEquals(
                404,
                guardedTest
                    .send(
                        request -> request.path("/api").timeout(Duration.ofSeconds(5)),
                        HttpResponse.BodyHandlers.ofString())
                    .statusCode());
          }
        }
      }
    }
  }

  @Test
  void runsInheritedExtensionAdmissionInSelectionOrderBeforeTheHandler() throws Exception {
    var outer =
        new AdmissionExtension((request, ignoredResponse) -> request.attribute("order", "outer"));
    var inner =
        new AdmissionExtension(
            (request, ignoredResponse) ->
                request.attribute("order", request.attribute("order").orElseThrow() + ",inner"));

    {
      var app = new Shoostr();

      try (Closeable appCleanup1 = app::close) {
        app.routes()
            .path(
                "/api",
                routes ->
                    routes.get(
                        "/order",
                        (request, response) ->
                            response.text(request.attribute("order").orElseThrow() + ",handler"),
                        lifecycle -> lifecycle.withExtension(inner)),
                lifecycle -> lifecycle.withExtension(outer));

        try (var test = TestServer.start(app)) {
          assertEquals(
              "outer,inner,handler",
              test.send(
                      request -> request.path("/api/order").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .body());
        }
      }
    }
  }

  @Test
  void preventsExtensionAdmissionFromStartingStream() throws Exception {
    var admission =
        new AdmissionExtension(
            (ignoredRequest, response) ->
                assertThrows(
                    IllegalStateException.class, () -> response.startStream("text/plain")));

    {
      var app = new Shoostr();

      try (Closeable appCleanup2 = app::close) {
        app.routes()
            .get(
                "/stream",
                (ignoredRequest, response) -> response.text("finite"),
                lifecycle -> lifecycle.withExtension(admission));

        try (var test = TestServer.start(app)) {
          assertEquals(
              "finite",
              test.send(
                      request -> request.path("/stream").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .body());
        }
      }
    }
  }

  @Test
  void retainsAuthenticationChallengesThroughGlobalErrorRendering() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup3 = app::close) {
        app.exception(
            AuthenticationRequiredException.class,
            (ignoredFailure, ignoredRequest, response) -> response.text("login required"));
        var auth = createAuthForRetainsAuthenticationChallengesThroughGlobalErrorRendering();
        app.routes()
            .get(
                "/private",
                (ignoredRequest, response) -> response.text("secret"),
                lifecycle -> lifecycle.withExtension(auth));

        try (var test = TestServer.start(app)) {

          var result =
              test.send(
                  request -> request.path("/private").timeout(Duration.ofSeconds(5)),
                  HttpResponse.BodyHandlers.ofString());

          assertEquals(401, result.statusCode());
          assertEquals("login required", result.body());
          assertEquals(
              "Bearer realm=\"api\"",
              result.headers().firstValue("WWW-Authenticate").orElseThrow());
        }
      }
    }
  }

  private AuthenticationExtension
      createAuthForRetainsAuthenticationChallengesThroughGlobalErrorRendering() {
    return new AuthenticationExtension() {
      @Override
      public void handle(Request request, Response response) {
        throw new AuthenticationRequiredException("Bearer realm=\"api\"");
      }
    };
  }

  @Test
  void protectsStaticMountsInsidePolicyScopes() throws Exception {
    Files.writeString(directory.resolve("secret.txt"), "secret");

    {
      var app = new Shoostr();

      try (Closeable appCleanup4 = app::close) {
        var admission =
            new AdmissionExtension(
                (ignoredRequest, ignoredResponse) -> {
                  throw new ForbiddenException();
                });
        app.routes()
            .path(
                "/",
                secured -> secured.staticFiles("/files", directory),
                lifecycle -> lifecycle.withExtension(admission));

        try (var test = TestServer.start(app)) {

          assertEquals(
              403,
              test.send(
                      request -> request.path("/files/secret.txt").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .statusCode());
        }
      }
    }
  }

  @Test
  void protectsScopedRoutesWithoutProtectingPublicSiblings() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup5 = app::close) {
        var admission =
            new AdmissionExtension(
                (ignoredRequest, ignoredResponse) -> {
                  throw new ForbiddenException();
                });
        app.routes()
            .path(
                "/api",
                routes -> {
                  routes.get(
                      "/private",
                      (ignoredRequest, response) -> response.text("secret"),
                      lifecycle -> lifecycle.withExtension(admission));
                  routes.get("/public", (ignoredRequest, response) -> response.text("public"));
                });

        try (var test = TestServer.start(app)) {

          assertEquals(
              403,
              test.send(
                      request -> request.path("/api/private").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .statusCode());
          assertEquals(
              "public",
              test.send(
                      request -> request.path("/api/public").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .body());
        }
      }
    }
  }
}
