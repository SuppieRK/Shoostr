package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.suppierk.shoostr.extensions.AdmissionExtension;
import io.github.suppierk.shoostr.http.exceptions.AuthenticationRequiredException;
import io.github.suppierk.shoostr.http.exceptions.ForbiddenException;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RoutePolicyTest {
  @TempDir Path directory;

  @Test
  void rejectsExtensionConfigurationAfterRegistrationCloses() throws Exception {
    var invoked = new AtomicBoolean();

    try (var app = new Shoostr()) {
      var routes = app.routes();
      routes.close();
      assertThrows(
          IllegalStateException.class,
          () -> routes.get("/", (ignored, ignored2) -> {}, ignored3 -> invoked.set(true)));
      assertFalse(invoked.get());
    }
  }

  @Test
  void protectionPreservesTheAlreadyComposedGroupEndpoint() throws Exception {
    try (var plain = new Shoostr();
        var guarded = new Shoostr()) {
      plain
          .routes()
          .path("/api//", routes -> routes.get("", (ignored4, response) -> response.text("plain")));
      var admission = new AdmissionExtension((ignored5, ignored6) -> {});
      guarded
          .extensions(admission)
          .routes()
          .path(
              "/api//",
              routes ->
                  routes.get(
                      "",
                      (ignored7, response) -> response.text("guarded"),
                      e -> e.with(admission)));

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

  @Test
  void runsInheritedExtensionAdmissionInSelectionOrderBeforeTheHandler() throws Exception {
    var outer = new AdmissionExtension((request, ignored8) -> request.attribute("order", "outer"));
    var inner =
        new AdmissionExtension(
            (request, ignored9) ->
                request.attribute("order", request.attribute("order").orElseThrow() + ",inner"));

    try (var app = new Shoostr()) {
      app.extensions(outer, inner)
          .routes()
          .path(
              "/api",
              routes ->
                  routes.get(
                      "/order",
                      (request, response) ->
                          response.text(request.attribute("order").orElseThrow() + ",handler"),
                      e -> e.with(inner)),
              e -> e.with(outer));

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

  @Test
  void preventsExtensionAdmissionFromStartingAStream() throws Exception {
    var admission =
        new AdmissionExtension(
            (ignored10, response) ->
                assertThrows(
                    IllegalStateException.class, () -> response.startStream("text/plain")));

    try (var app = new Shoostr()) {
      app.extensions(admission)
          .routes()
          .get("/stream", (ignored11, response) -> response.text("finite"), e -> e.with(admission));

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

  @Test
  void retainsAuthenticationChallengesThroughGlobalErrorRendering() throws Exception {
    try (var app = new Shoostr()) {
      app.exception(
          AuthenticationRequiredException.class,
          (ignored12, ignored13, response) -> response.text("login required"));
      var auth =
          new AuthenticationExtension() {
            @Override
            public void handle(Request request, Response response) {
              throw new AuthenticationRequiredException("Bearer realm=\"api\"");
            }
          };
      app.authentication(auth)
          .routes()
          .get("/private", (ignored14, response) -> response.text("secret"), e -> e.with(auth));

      try (var test = TestServer.start(app)) {

        var result =
            test.send(
                request -> request.path("/private").timeout(Duration.ofSeconds(5)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, result.statusCode());
        assertEquals("login required", result.body());
        assertEquals(
            "Bearer realm=\"api\"", result.headers().firstValue("WWW-Authenticate").orElseThrow());
      }
    }
  }

  @Test
  void protectsStaticMountsInsidePolicyScopes() throws Exception {
    try (var stream = Files.newDirectoryStream(directory)) {
      assumeTrue(
          stream instanceof SecureDirectoryStream<?>,
          "Filesystem static mounts require SecureDirectoryStream support");
    }

    Files.writeString(directory.resolve("secret.txt"), "secret");

    try (var app = new Shoostr()) {
      var admission =
          new AdmissionExtension(
              (ignored15, ignored16) -> {
                throw new ForbiddenException();
              });
      app.extensions(admission)
          .routes()
          .path("/", secured -> secured.staticFiles("/files", directory), e -> e.with(admission));

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

  @Test
  void protectsScopedRoutesWithoutProtectingPublicSiblings() throws Exception {
    try (var app = new Shoostr()) {
      var admission =
          new AdmissionExtension(
              (ignored17, ignored18) -> {
                throw new ForbiddenException();
              });
      app.extensions(admission)
          .routes()
          .path(
              "/api",
              routes -> {
                routes.get(
                    "/private",
                    (ignored19, response) -> response.text("secret"),
                    e -> e.with(admission));
                routes.get("/public", (ignored20, response) -> response.text("public"));
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
