package io.github.suppierk.shoostr.example.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import io.swagger.v3.core.util.Json31;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

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
          sendCreatedRequestForAcceptsAuthenticatedTypedPostAfterRejectingUnreadBody(test);

      assertEquals(201, created.statusCode());
      assertEquals("Alice", Json31.mapper().readTree(created.body()).path("name").asString());
    }
  }

  private HttpResponse<byte[]>
      sendCreatedRequestForAcceptsAuthenticatedTypedPostAfterRejectingUnreadBody(TestServer test)
          throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/users")
                .method("POST")
                .header(
                    "Authorization",
                    "Basic "
                        + Base64.getEncoder()
                            .encodeToString("demo:local-only".getBytes(StandardCharsets.UTF_8)))
                .header("Content-Type", "application/json")
                .body("{\"name\":\"Alice\"}".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void rejectsMalformedAuthenticatedUserPayloadsWithBadRequest() throws Exception {
    var authorization =
        "Basic "
            + Base64.getEncoder()
                .encodeToString("demo:local-only".getBytes(StandardCharsets.UTF_8));

    try (var test = TestServer.start(OpenApiExample.application())) {
      for (var payload :
          List.of(
              "{",
              "{}",
              "{\"name\":42}",
              "{\"name\":\" \"}",
              "",
              "null",
              "{\"name\":\"Alice\"} {}")) {
        var response =
            sendResponseForRejectsMalformedAuthenticatedUserPayloadsWithBadRequest(
                test, authorization, payload);

        assertEquals(400, response.statusCode(), payload);
      }
    }
  }

  private HttpResponse<byte[]>
      sendResponseForRejectsMalformedAuthenticatedUserPayloadsWithBadRequest(
          TestServer test, String authorization, String payload)
          throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/users")
                .method("POST")
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .body(payload.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void documentsConditionalEndpointsOnlyInTheirSelectedInternalDocument() throws Exception {

    try (var test = TestServer.start(OpenApiExample.application())) {
      var authorization =
          "Basic "
              + Base64.getEncoder()
                  .encodeToString("demo:local-only".getBytes(StandardCharsets.UTF_8));
      var publicDocument =
          createPublicDocumentForDocumentsConditionalEndpointsOnlyInTheirSelectedInternalDocument(
              test, authorization);
      var internalDocument =
          createInternalDocumentForDocumentsConditionalEndpointsOnlyInTheirSelectedInternalDocument(
              test, authorization);

      assertTrue(publicDocument.path("paths").has("/users/{id}"));
      assertFalse(publicDocument.path("paths").has("/admin/ping"));
      assertEquals(2, internalDocument.path("paths").size());
      assertTrue(internalDocument.path("paths").has("/admin/ping"));
      assertTrue(internalDocument.path("paths").has("/dev-only"));
      assertFalse(publicDocument.path("paths").has("/dev-only"));
      assertEquals(200, test.send(request -> request.path("/dev-only")).statusCode());
    }
  }

  private JsonNode
      createPublicDocumentForDocumentsConditionalEndpointsOnlyInTheirSelectedInternalDocument(
          TestServer test, String authorization) throws IOException, InterruptedException {
    return Json31.mapper()
        .readTree(
            test.send(
                    request -> request.path("/openapi.json").header("Authorization", authorization))
                .body());
  }

  private JsonNode
      createInternalDocumentForDocumentsConditionalEndpointsOnlyInTheirSelectedInternalDocument(
          TestServer test, String authorization) throws IOException, InterruptedException {
    return Json31.mapper()
        .readTree(
            test.send(
                    request ->
                        request
                            .path("/internal-openapi.json")
                            .header("Authorization", authorization))
                .body());
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
