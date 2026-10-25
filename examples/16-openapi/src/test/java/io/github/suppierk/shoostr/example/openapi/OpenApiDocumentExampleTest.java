package io.github.suppierk.shoostr.example.openapi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.openapi.OpenApi;
import io.github.suppierk.shoostr.openapi.OpenApiOperation;
import io.github.suppierk.shoostr.testing.TestServer;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.media.JsonSchema;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OpenApiDocumentExampleTest {
  @Test
  void servesAnExplicitSchemaAndComposedOperationWithoutScanningClasses() throws Exception {
    try (var test = TestServer.start(OpenApiDocumentExample.application())) {
      var response = test.send(request -> request.path("/openapi.json"));
      assertEquals(200, response.statusCode());
      var document = Json31.mapper().readTree(response.body());
      assertEquals("3.1.1", document.path("openapi").asText());
      assertEquals("Items API", document.path("info").path("title").asText());
      var operation = document.path("paths").path("/api/items/{id}").path("get");
      assertEquals("findItem", operation.path("operationId").asText());
      assertEquals("Find an item", operation.path("summary").asText());
      assertEquals("id", operation.path("parameters").get(0).path("name").asText());
      assertTrue(operation.path("parameters").get(0).path("required").asBoolean());
      assertEquals(
          "#/components/schemas/Item",
          operation
              .path("responses")
              .path("200")
              .path("content")
              .path("application/json")
              .path("schema")
              .path("$ref")
              .asText());
      assertEquals(
          "string",
          document
              .path("components")
              .path("schemas")
              .path("Item")
              .path("properties")
              .path("name")
              .path("type")
              .asText());
      var item = test.send(request -> request.path("/api/items/42"));
      assertEquals(200, item.statusCode());
      assertEquals("Item 42", Json31.mapper().readTree(item.body()).path("name").asText());
    }
  }

  @Test
  void documentsAndHandlesAnExplicitJsonRequestBody() throws Exception {
    try (var test = TestServer.start(OpenApiDocumentExample.application())) {
      var response =
          test.send(
              request ->
                  request
                      .method("POST")
                      .path("/api/items")
                      .header("Content-Type", "application/json")
                      .body("{\"name\":\"Notebook\"}".getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(201, response.statusCode());
      assertEquals("Notebook", Json31.mapper().readTree(response.body()).path("name").asText());
      var document =
          Json31.mapper().readTree(test.send(request -> request.path("/openapi.json")).body());
      var operation = document.path("paths").path("/api/items").path("post");
      assertTrue(operation.path("requestBody").path("required").asBoolean());
      assertEquals(
          "#/components/schemas/Item",
          operation
              .path("requestBody")
              .path("content")
              .path("application/json")
              .path("schema")
              .path("$ref")
              .asText());
      assertEquals("Created", operation.path("responses").path("201").path("description").asText());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "[]",
        "{\"name\":\" \"}",
        "{\"name\":42}",
        "not-json",
        "{\"name\":\"first\"}{\"name\":\"second\"}"
      })
  void validatesIncomingDataInTheHandlerRatherThanRelyingOnItsSchema(String body) throws Exception {
    try (var test = TestServer.start(OpenApiDocumentExample.application())) {
      assertEquals(
          400,
          test.send(
                  request ->
                      request
                          .method("POST")
                          .path("/api/items")
                          .header("Content-Type", "application/json")
                          .body(body.getBytes(StandardCharsets.UTF_8)))
              .statusCode());
    }
  }

  @Test
  void keepsSelectedOperationsInTheirOwnDocument() throws Exception {
    try (var test = TestServer.start(OpenApiDocumentExample.application())) {
      var response = test.send(request -> request.path("/internal-openapi.json"));
      assertEquals(200, response.statusCode());
      var internal = Json31.mapper().readTree(response.body());
      var external =
          Json31.mapper().readTree(test.send(request -> request.path("/openapi.json")).body());
      assertEquals("Operations API", internal.path("info").path("title").asText());
      assertTrue(internal.path("paths").has("/operations/status"));
      assertFalse(internal.path("paths").has("/api/items/{id}"));
      assertFalse(external.path("paths").has("/operations/status"));
      assertFalse(external.path("paths").has("/openapi.json"));
    }
  }

  @Test
  void freezesDetachedMetadataWithoutInvokingHandlersOrAvailabilityConditions() throws Exception {
    var docs = new OpenApi("Snapshot API", "1.0");
    var schema = new JsonSchema();
    schema.setTypes(Set.of("object"));
    docs.schema("Snapshot", schema);
    schema.setTypes(Set.of("array"));
    var checks = new AtomicInteger();
    var invocations = new AtomicInteger();
    var retained = new AtomicReference<OpenApiOperation>();

    try (var app = new Shoostr().extensions(docs)) {
      app.routes().get("/openapi.json", docs.handler());
      app.routes()
          .when(
              () -> {
                checks.incrementAndGet();
                return false;
              },
              preview ->
                  preview.get(
                      "/preview",
                      (ignored, response) -> {
                        invocations.incrementAndGet();
                        response.text("preview");
                      },
                      extensions -> retained.set(extensions.with(docs).summary("Preview"))));

      try (var test = TestServer.start(app)) {
        var first = test.send(request -> request.path("/openapi.json"));
        assertEquals(200, first.statusCode());
        var document = Json31.mapper().readTree(first.body());
        assertTrue(document.path("paths").has("/preview"));
        assertEquals(
            "object",
            document.path("components").path("schemas").path("Snapshot").path("type").asText());
        assertEquals(0, checks.get());
        assertEquals(0, invocations.get());
        var configuration = retained.get();
        assertThrows(IllegalStateException.class, () -> configuration.summary("Late change"));
        assertArrayEquals(first.body(), test.send(request -> request.path("/openapi.json")).body());
        assertEquals(0, checks.get());
        assertEquals(404, test.send(request -> request.path("/preview")).statusCode());
        assertEquals(1, checks.get());
        assertEquals(0, invocations.get());
      }
    }
  }
}
