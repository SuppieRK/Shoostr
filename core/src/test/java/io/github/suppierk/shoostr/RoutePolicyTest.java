package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.suppierk.shoostr.extensions.AdmissionExtension;
import io.github.suppierk.shoostr.http.exceptions.AuthenticationRequiredException;
import io.github.suppierk.shoostr.http.exceptions.ForbiddenException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
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
        var guarded = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
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
      plain.start();
      guarded.start();

      assertEquals(200, send(client, plain, "/api/").statusCode());
      assertEquals(404, send(client, plain, "/api").statusCode());
      assertEquals(200, send(client, guarded, "/api/").statusCode());
      assertEquals(404, send(client, guarded, "/api").statusCode());
    }
  }

  @Test
  void runsInheritedExtensionAdmissionInSelectionOrderBeforeTheHandler() throws Exception {
    var outer = new AdmissionExtension((request, _) -> request.attribute("order", "outer"));
    var inner =
        new AdmissionExtension(
            (request, _) ->
                request.attribute("order", request.attribute("order").orElseThrow() + ",inner"));

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
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
      app.start();
      assertEquals("outer,inner,handler", send(client, app, "/api/order").body());
    }
  }

  @Test
  void preventsExtensionAdmissionFromStartingAStream() throws Exception {
    var admission =
        new AdmissionExtension(
            (_, response) ->
                assertThrows(
                    IllegalStateException.class, () -> response.startStream("text/plain")));

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.extensions(admission)
          .routes()
          .get("/stream", (_, response) -> response.text("finite"), e -> e.get(admission));
      app.start();
      assertEquals("finite", send(client, app, "/stream").body());
    }
  }

  @Test
  void retainsAuthenticationChallengesThroughGlobalErrorRendering() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
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
      app.start();

      var result = send(client, app, "/private");
      assertEquals(401, result.statusCode());
      assertEquals("login required", result.body());
      assertEquals(
          "Bearer realm=\"api\"", result.headers().firstValue("WWW-Authenticate").orElseThrow());
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

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      var admission =
          new AdmissionExtension(
              (_, _) -> {
                throw new ForbiddenException();
              });
      app.extensions(admission)
          .routes()
          .path("/", secured -> secured.staticFiles("/files", directory), e -> e.get(admission));
      app.start();

      assertEquals(403, send(client, app, "/files/secret.txt").statusCode());
    }
  }

  @Test
  void protectsScopedRoutesWithoutProtectingPublicSiblings() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
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
      app.start();

      assertEquals(403, send(client, app, "/api/private").statusCode());
      assertEquals("public", send(client, app, "/api/public").body());
    }
  }

  private static HttpResponse<String> send(HttpClient client, Shoostr app, String path)
      throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
