package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
  void rejectsProtectionCallbacksAfterRegistrationCloses() throws Exception {
    var invoked = new AtomicBoolean();

    try (var app = new Shoostr()) {
      var routes = app.routes();
      routes.close();
      assertThrows(
          IllegalStateException.class, () -> routes.protect((_, _) -> {}, _ -> invoked.set(true)));
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
      guarded
          .routes()
          .path(
              "/api//",
              routes ->
                  routes.protect(
                      (_, _) -> {},
                      secured -> secured.get("", (_, response) -> response.text("guarded"))));
      plain.start();
      guarded.start();

      assertEquals(200, send(client, plain, "/api/").statusCode());
      assertEquals(404, send(client, plain, "/api").statusCode());
      assertEquals(200, send(client, guarded, "/api/").statusCode());
      assertEquals(404, send(client, guarded, "/api").statusCode());
    }
  }

  @Test
  void runsInheritedPoliciesInOrderBeforeTheHandlerAndPreventsPolicyStreaming() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .protect(
              (request, response) -> {
                request.attribute("order", "outer");
                assertThrows(IllegalStateException.class, () -> response.startStream("text/plain"));
              },
              outer ->
                  outer.path(
                      "/api",
                      paths ->
                          paths.protect(
                              (request, _) ->
                                  request.attribute(
                                      "order", request.attribute("order").orElseThrow() + ",inner"),
                              inner ->
                                  inner.get(
                                      "/order",
                                      (request, response) ->
                                          response.text(
                                              request.attribute("order").orElseThrow()
                                                  + ",handler")))));
      app.start();

      assertEquals("outer,inner,handler", send(client, app, "/api/order").body());
    }
  }

  @Test
  void retainsAuthenticationChallengesThroughGlobalErrorRendering() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.exception(
          AuthenticationRequiredException.class,
          (_, _, response) -> response.text("login required"));
      app.routes()
          .protect(
              (_, _) -> {
                throw new AuthenticationRequiredException("Bearer realm=\"api\"");
              },
              secured -> secured.get("/private", (_, response) -> response.text("secret")));
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
      app.routes()
          .protect(
              (_, _) -> {
                throw new ForbiddenException();
              },
              secured -> secured.staticFiles("/files", directory));
      app.start();

      assertEquals(403, send(client, app, "/files/secret.txt").statusCode());
    }
  }

  @Test
  void protectsScopedRoutesWithoutProtectingPublicSiblings() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .path(
              "/api",
              routes -> {
                routes.protect(
                    (_, _) -> {
                      throw new ForbiddenException();
                    },
                    secured -> secured.get("/private", (_, response) -> response.text("secret")));
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
