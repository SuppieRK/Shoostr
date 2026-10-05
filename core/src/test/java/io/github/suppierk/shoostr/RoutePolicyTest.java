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
          IllegalStateException.class, () -> routes.get("/", (_, _) -> {}, _ -> invoked.set(true)));
      assertFalse(invoked.get());
    }
  }

  @Test
  void protectionPreservesTheAlreadyComposedGroupEndpoint() throws Exception {
    try (var plain = new Shoostr(Options.defaults().withPort(0));
        var guarded = new Shoostr(Options.defaults().withPort(0))) {
      plain
          .routes()
          .path("/api//", routes -> routes.get("", (_, response) -> response.text("plain")));
      var admission = new AdmissionExtension((_, _) -> {});
      guarded
          .extensions(admission)
          .routes()
          .path(
              "/api//",
              routes ->
                  routes.get("", (_, response) -> response.text("guarded"), e -> e.get(admission)));

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
    var outer = new AdmissionExtension((request, _) -> request.attribute("order", "outer"));
    var inner =
        new AdmissionExtension(
            (request, _) ->
                request.attribute("order", request.attribute("order").orElseThrow() + ",inner"));

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.extensions(outer, inner)
          .routes()
          .path(
              "/api",
              routes ->
                  routes.get(
                      "/order",
                      (request, response) ->
                          response.text(request.attribute("order").orElseThrow() + ",handler"),
                      e -> e.get(inner)),
              e -> e.get(outer));

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
            (_, response) ->
                assertThrows(
                    IllegalStateException.class, () -> response.startStream("text/plain")));

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.extensions(admission)
          .routes()
          .get("/stream", (_, response) -> response.text("finite"), e -> e.get(admission));

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
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.exception(
          AuthenticationRequiredException.class,
          (_, _, response) -> response.text("login required"));
      var auth =
          new AuthenticationExtension() {
            @Override
            public void handle(Request request, Response response) {
              throw new AuthenticationRequiredException("Bearer realm=\"api\"");
            }
          };
      app.authentication(auth)
          .routes()
          .get("/private", (_, response) -> response.text("secret"), e -> e.get(auth).required());

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

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      var admission =
          new AdmissionExtension(
              (_, _) -> {
                throw new ForbiddenException();
              });
      app.extensions(admission)
          .routes()
          .path("/", secured -> secured.staticFiles("/files", directory), e -> e.get(admission));

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
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      var admission =
          new AdmissionExtension(
              (_, _) -> {
                throw new ForbiddenException();
              });
      app.extensions(admission)
          .routes()
          .path(
              "/api",
              routes -> {
                routes.get(
                    "/private", (_, response) -> response.text("secret"), e -> e.get(admission));
                routes.get("/public", (_, response) -> response.text("public"));
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
