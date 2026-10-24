package io.github.suppierk.shoostr.example.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.testing.TestServer;
import io.swagger.v3.core.util.Json31;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenApiUiExampleTest {
  @Test
  void requiresAuthenticationForThePageEveryAssetAndTheSpecification() throws Exception {
    try (var test = TestServer.start(OpenApiUiExample.application())) {
      for (var path :
          List.of(
              "/docs/",
              "/docs/swagger-ui.css",
              "/docs/swagger-ui-bundle.js",
              "/docs/swagger-initializer.js",
              "/openapi.json")) {
        var response = test.send(request -> request.path(path));
        assertEquals(401, response.statusCode(), path);
        assertTrue(
            response.headers().firstValue("WWW-Authenticate").orElseThrow().startsWith("Basic "));
      }
    }
  }

  @Test
  void servesLocalAssetsAndDisablesImplicitCredentialsForApiCalls() throws Exception {
    try (var test = TestServer.start(OpenApiUiExample.application())) {
      var page =
          test.send(
              request -> request.path("/docs/").header("Authorization", credentials()),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, page.statusCode());
      assertTrue(page.headers().firstValue("Content-Type").orElseThrow().startsWith("text/html"));
      assertTrue(page.body().contains("src=\"swagger-ui-bundle.js\""));
      assertTrue(page.body().contains("href=\"swagger-ui.css\""));
      var stylesheet =
          test.send(
              request ->
                  request.path("/docs/swagger-ui.css").header("Authorization", credentials()));
      assertEquals(200, stylesheet.statusCode());
      assertTrue(
          stylesheet.headers().firstValue("Content-Type").orElseThrow().startsWith("text/css"));
      var bundle =
          test.send(
              request ->
                  request
                      .path("/docs/swagger-ui-bundle.js")
                      .header("Authorization", credentials()));
      assertEquals(200, bundle.statusCode());
      assertTrue(bundle.body().length > Options.defaults().maxResponseBytes());
      var initializer =
          test.send(
              request ->
                  request
                      .path("/docs/swagger-initializer.js")
                      .header("Authorization", credentials()),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, initializer.statusCode());
      assertTrue(initializer.body().contains("\"url\":\"/openapi.json\""));
      assertTrue(initializer.body().contains("\"validatorUrl\":null"));
      assertTrue(initializer.body().contains("\"persistAuthorization\":false"));
      assertTrue(initializer.body().contains("if (!request.loadSpec)"));
      assertTrue(initializer.body().contains("request.credentials = 'omit'"));
      assertEquals(
          404,
          test.send(
                  request ->
                      request.path("/docs/unknown.js").header("Authorization", credentials()))
              .statusCode());
    }
  }

  @Test
  void protectsTheActualApiAndDescribesItsExplicitAuthorization() throws Exception {
    try (var test = TestServer.start(OpenApiUiExample.application())) {
      assertEquals(401, test.send(request -> request.path("/me")).statusCode());
      assertEquals(
          401,
          test.send(
                  request ->
                      request
                          .path("/me")
                          .header(
                              "Authorization",
                              "Basic "
                                  + Base64.getEncoder()
                                      .encodeToString(
                                          "reader:wrong".getBytes(StandardCharsets.UTF_8))))
              .statusCode());
      var identity =
          test.send(
              request -> request.path("/me").header("Authorization", credentials()),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, identity.statusCode());
      assertEquals("reader", identity.body());
      var specification =
          test.send(
              request -> request.path("/openapi.json").header("Authorization", credentials()));
      assertEquals(200, specification.statusCode());
      var document = Json31.mapper().readTree(specification.body());
      assertEquals(
          "basic",
          document
              .path("components")
              .path("securitySchemes")
              .path("basic")
              .path("scheme")
              .asText());
      assertTrue(
          document.path("paths").path("/me").path("get").path("security").get(0).has("basic"));
      assertFalse(document.path("paths").has("/docs/"));
      assertFalse(document.path("paths").has("/docs/{*asset}"));
      assertFalse(document.path("paths").has("/openapi.json"));
    }
  }

  private static String credentials() {
    return "Basic "
        + Base64.getEncoder().encodeToString("reader:local-only".getBytes(StandardCharsets.UTF_8));
  }
}
