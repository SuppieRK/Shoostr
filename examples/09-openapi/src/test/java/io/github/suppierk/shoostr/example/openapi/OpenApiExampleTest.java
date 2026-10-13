package io.github.suppierk.shoostr.example.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import io.swagger.v3.core.util.Json31;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenApiExampleTest {
  @Test
  void acceptsAuthenticatedTypedPostAfterRejectingUnreadBody() throws Exception {
    try (var test = TestServer.start(OpenApiExample.application())) {
      assertEquals(
          "healthy",
          test.send(request -> request.path("/health"), HttpResponse.BodyHandlers.ofString())
              .body());
      assertEquals(200, test.send(request -> request.path("/users/123")).statusCode());
      assertEquals(404, test.send(request -> request.path("/users/alice")).statusCode());
      assertEquals(
          401,
          test.send(
                  request ->
                      request
                          .path("/users")
                          .method("POST")
                          .body("{\"name\":\"Alice\"}".getBytes(StandardCharsets.UTF_8)))
              .statusCode());
      var created =
          test.send(
              request ->
                  request
                      .path("/users")
                      .method("POST")
                      .header(
                          "Authorization",
                          "Basic "
                              + Base64.getEncoder()
                                  .encodeToString(
                                      "demo:local-only".getBytes(StandardCharsets.UTF_8)))
                      .header("Content-Type", "application/json")
                      .body("{\"name\":\"Alice\"}".getBytes(StandardCharsets.UTF_8)));
      assertEquals(201, created.statusCode());
      assertEquals("Alice", Json31.mapper().readTree(created.body()).path("name").asText());
    }
  }

  @Test
  void documentsConditionalEndpointsOnlyInTheirSelectedInternalDocument() throws Exception {
    try (var test = TestServer.start(OpenApiExample.application())) {
      var authorization =
          "Basic "
              + Base64.getEncoder()
                  .encodeToString("demo:local-only".getBytes(StandardCharsets.UTF_8));
      var publicDocument =
          Json31.mapper()
              .readTree(
                  test.send(
                          request ->
                              request.path("/openapi.json").header("Authorization", authorization))
                      .body());
      var internalDocument =
          Json31.mapper()
              .readTree(
                  test.send(
                          request ->
                              request
                                  .path("/internal-openapi.json")
                                  .header("Authorization", authorization))
                      .body());
      assertTrue(publicDocument.path("paths").has("/users/{id}"));
      assertFalse(publicDocument.path("paths").has("/admin/ping"));
      assertEquals(2, internalDocument.path("paths").size());
      assertTrue(internalDocument.path("paths").has("/admin/ping"));
      assertTrue(internalDocument.path("paths").has("/dev-only"));
      assertFalse(publicDocument.path("paths").has("/dev-only"));
      assertEquals(200, test.send(request -> request.path("/dev-only")).statusCode());
    }
  }

  @Test
  void requiresAuthenticationForEveryDocumentationBootstrapResource() throws Exception {
    try (var test = TestServer.start(OpenApiExample.application())) {
      for (var path :
          List.of(
              "/docs/",
              "/docs/swagger-ui.css",
              "/docs/swagger-ui-bundle.js",
              "/docs/swagger-initializer.js",
              "/openapi.json",
              "/internal-openapi.json")) {
        var rejected = test.send(request -> request.path(path));
        assertEquals(401, rejected.statusCode(), path);
        assertTrue(
            rejected.headers().firstValue("WWW-Authenticate").orElseThrow().startsWith("Basic"));
        assertEquals(
            200,
            test.send(
                    request ->
                        request
                            .path(path)
                            .header(
                                "Authorization",
                                "Basic "
                                    + Base64.getEncoder()
                                        .encodeToString(
                                            "demo:local-only".getBytes(StandardCharsets.UTF_8))))
                .statusCode(),
            path);
      }
    }
  }
}
