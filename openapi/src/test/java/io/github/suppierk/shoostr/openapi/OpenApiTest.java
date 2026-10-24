package io.github.suppierk.shoostr.openapi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Handler;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.testing.TestServer;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.Map;
import java.util.Set;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;

class OpenApiTest {
  @Test
  void keepsDocumentationIndependentWhenOneHandlerIsRegisteredAtTwoPaths() throws Exception {
    var docs = new OpenApi("API", "1");
    Handler shared = (ignored, response) -> response.text("shared");

    try (var app = new Shoostr()) {
      app.extensions(docs)
          .routes(
              routes -> {
                routes.get(
                    "/first",
                    shared,
                    e -> e.with(docs).summary("First operation").operationId("first"));
                routes.get(
                    "/second",
                    shared,
                    e -> e.with(docs).summary("Second operation").operationId("second"));
                routes.get("/openapi.json", docs.handler());
              });

      try (var test = TestServer.start(app)) {
        var paths =
            Json31.mapper()
                .readTree(test.send(request -> request.path("/openapi.json")).body())
                .path("paths");
        assertEquals("First operation", paths.path("/first").path("get").path("summary").asText());
        assertEquals(
            "Second operation", paths.path("/second").path("get").path("summary").asText());
        assertEquals("first", paths.path("/first").path("get").path("operationId").asText());
        assertEquals("second", paths.path("/second").path("get").path("operationId").asText());
      }
    }
  }

  @Test
  void permitsNativeRequestMediaDeclarationsWithoutInventingASchema() throws Exception {
    var docs = new OpenApi("API", "1");
    var body =
        new RequestBody()
            .content(
                new Content()
                    .addMediaType("text/plain", new io.swagger.v3.oas.models.media.MediaType()));

    try (var app = new Shoostr()) {
      app.extensions(docs)
          .routes(
              routes -> {
                routes.post(
                    "/text", (ignored2, ignored3) -> {}, e -> e.with(docs).requestBody(body));
                routes.get("/openapi.json", docs.handler());
              });

      try (var test = TestServer.start(app)) {
        var content =
            Json31.mapper()
                .readTree(test.send(request -> request.path("/openapi.json")).body())
                .path("paths")
                .path("/text")
                .path("post")
                .path("requestBody")
                .path("content");
        assertTrue(content.has("text/plain"));
        assertFalse(content.path("text/plain").has("schema"));
      }
    }
  }

  @Test
  void preservesValidRecursiveSchemaReferences() throws Exception {
    var docs = new OpenApi("API", "1");
    var node = new JsonSchema();
    node.setTypes(Set.of("object"));
    node.addProperty("next", new JsonSchema().$ref("#/components/schemas/Node"));
    docs.schema("Node", node);

    try (var app = new Shoostr()) {
      app.extensions(docs).routes().get("/openapi.json", docs.handler());

      try (var test = TestServer.start(app)) {
        var schema =
            Json31.mapper()
                .readTree(test.send(request -> request.path("/openapi.json")).body())
                .path("components")
                .path("schemas")
                .path("Node");
        assertEquals(
            "#/components/schemas/Node",
            schema.path("properties").path("next").path("$ref").asText());
      }
    }
  }

  @Test
  void treatsReferenceStringsInsideExampleDataAsLiteralPayload() throws Exception {
    var docs = new OpenApi("API", "1");
    var schema = new JsonSchema();
    schema.setTypes(Set.of("object"));
    schema.setExample(Map.of("$ref", "#/components/schemas/Missing"));
    docs.schema("Example", schema);

    try (var app = new Shoostr()) {
      app.extensions(docs).routes().get("/openapi.json", docs.handler());

      try (var test = TestServer.start(app)) {
        var example =
            Json31.mapper()
                .readTree(test.send(request -> request.path("/openapi.json")).body())
                .path("components")
                .path("schemas")
                .path("Example")
                .path("example");
        assertEquals("#/components/schemas/Missing", example.path("$ref").asText());
      }
    }
  }

  @Test
  void documentsExplicitRequestShapesWithoutAConfiguredCodec() throws Exception {
    var docs =
        new OpenApi("Users API", "1.0")
            .description("Example service")
            .server("https://api.example.test")
            .tag("users", "User operations");
    var schema = new JsonSchema().types(Set.of("object"));

    try (var app = new Shoostr()) {
      app.extensions(docs)
          .routes(
              routes -> {
                routes.post(
                    "/users",
                    (ignored4, ignored5) -> {},
                    e ->
                        e.with(docs)
                            .operationId("createUser")
                            .queryParameter(
                                "dryRun",
                                "Only validate",
                                new JsonSchema().types(Set.of("boolean")),
                                false)
                            .headerParameter(
                                "X-Request-ID",
                                "Correlation",
                                new JsonSchema().types(Set.of("string")),
                                false)
                            .requestBody(
                                "User to create", MediaType.APPLICATION_JSON, schema, true));
                routes.get("/openapi.json", docs.handler());
              });

      try (var test = TestServer.start(app)) {
        var document =
            Json31.mapper().readTree(test.send(request -> request.path("/openapi.json")).body());
        var operation = document.path("paths").path("/users").path("post");
        assertEquals("createUser", operation.path("operationId").asText());
        assertEquals("query", operation.path("parameters").path(0).path("in").asText());
        assertEquals("header", operation.path("parameters").path(1).path("in").asText());
        assertEquals(
            "object",
            operation
                .path("requestBody")
                .path("content")
                .path("application/json")
                .path("schema")
                .path("type")
                .asText());
        assertEquals("Example service", document.path("info").path("description").asText());
        assertEquals(
            "https://api.example.test", document.path("servers").path(0).path("url").asText());
      }
    }
  }

  @Test
  void replacesInheritedSecurityWithoutChangingRuntimeAuthentication() throws Exception {
    var docs = new OpenApi("Users API", "1.0");
    docs.securityScheme(
        "basic", new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic"));

    try (var app = new Shoostr()) {
      app.extensions(docs)
          .routes(
              routes -> {
                routes.path(
                    "/api",
                    group -> {
                      group.get(
                          "/anonymous", (ignored6, ignored7) -> {}, e -> e.with(docs).anonymous());
                      group.get("/documented", (ignored8, ignored9) -> {});
                    },
                    e -> e.with(docs).security("basic"));
                routes.get("/openapi.json", docs.handler());
              });

      try (var test = TestServer.start(app)) {
        var document =
            Json31.mapper().readTree(test.send(request -> request.path("/openapi.json")).body());
        assertEquals(
            0, document.path("paths").path("/api/anonymous").path("get").path("security").size());
        assertEquals(
            1, document.path("paths").path("/api/documented").path("get").path("security").size());
        assertEquals(
            "basic",
            document
                .path("components")
                .path("securitySchemes")
                .path("basic")
                .path("scheme")
                .asText());
        assertEquals(200, test.send(request -> request.path("/api/documented")).statusCode());
      }
    }
  }

  @Test
  void rejectsConstrainedAndPlainOperationsThatNormalizeToTheSamePath() throws Exception {
    var docs = new OpenApi("Users API", "1.0");

    try (var app = new Shoostr()) {
      app.extensions(docs)
          .routes(
              routes -> {
                routes.get(
                    "/users/{id:[0-9]+}",
                    (ignored10, ignored11) -> {},
                    e -> e.with(docs).pathParameter("id", "ID"));
                routes.get(
                    "/users/{id}",
                    (ignored12, ignored13) -> {},
                    e -> e.with(docs).pathParameter("id", "ID"));
              });
      var failure = assertThrows(IllegalArgumentException.class, app::start);
      assertEquals("Conflicting OpenAPI operation: GET /users/{id}", failure.getMessage());
    }
  }

  @Test
  void documentsConstrainedSegmentsAsExplicitRequiredPathParameters() throws Exception {
    var docs = new OpenApi("Users API", "1.0");

    try (var app =
        new Shoostr()
            .modifyServer(
                nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0))) {
      app.extensions(docs)
          .routes(
              routes -> {
                routes.get(
                    "/users/{id:[0-9]{1,32}}",
                    (ignored14, ignored15) -> {},
                    e -> e.with(docs).pathParameter("id", "User identifier"));
                routes.get("/openapi.json", docs.handler());
              });

      try (var test = TestServer.start(app)) {
        var paths =
            Json31.mapper()
                .readTree(test.send(request -> request.path("/openapi.json")).body())
                .path("paths");
        var parameter = paths.path("/users/{id}").path("get").path("parameters").path(0);
        assertEquals("id", parameter.path("name").asText());
        assertEquals("path", parameter.path("in").asText());
        assertTrue(parameter.path("required").asBoolean());
        assertEquals("^(?:[0-9]{1,32})$", parameter.path("schema").path("pattern").asText());
        assertEquals(1, paths.size());
      }
    }
  }

  @Test
  void intersectsDeclaredSchemaPatternWithRouteConstraintWithoutMutatingCallerSchema()
      throws Exception {
    var docs = new OpenApi("Users API", "1.0");
    var schema = new JsonSchema().types(Set.of("string")).pattern("^1").minLength(2);
    var parameter = new Parameter().name("id").in("path").required(true).schema(schema);

    try (var app = new Shoostr()) {
      app.extensions(docs)
          .routes(
              routes -> {
                routes.get(
                    "/users/{id:[0-9]+}",
                    (ignored16, ignored17) -> {},
                    e -> e.with(docs).parameter(parameter));
                routes.get("/openapi.json", docs.handler());
              });

      try (var test = TestServer.start(app)) {
        var intersection =
            Json31.mapper()
                .readTree(test.send(request -> request.path("/openapi.json")).body())
                .path("paths")
                .path("/users/{id}")
                .path("get")
                .path("parameters")
                .path(0)
                .path("schema")
                .path("allOf");
        assertEquals(2, intersection.size());
        assertEquals("string", intersection.path(0).path("type").asText());
        assertEquals("^1", intersection.path(0).path("pattern").asText());
        assertEquals(2, intersection.path(0).path("minLength").asInt());
        assertEquals("string", intersection.path(1).path("type").asText());
        assertEquals("^(?:[0-9]+)$", intersection.path(1).path("pattern").asText());
        assertEquals("^1", schema.getPattern());
        assertEquals(2, schema.getMinLength());
        assertNull(schema.getAllOf());
      }
    }
  }

  @Test
  void freezesExplicitReusableSchemasBeforeServing() throws Exception {
    var docs = new OpenApi("Users API", "1.0");
    var schema = new JsonSchema();
    schema.setTypes(Set.of("object"));
    schema.addProperty("name", new JsonSchema().types(Set.of("string", "null")));
    var user = docs.schema("User", schema);

    try (var app = new Shoostr()) {
      app.extensions(docs)
          .routes(
              routes -> {
                routes.get(
                    "/users",
                    (ignored18, ignored19) -> {},
                    e -> e.with(docs).response(200, "Users", MediaType.APPLICATION_JSON, user));
                routes.get("/openapi.json", docs.handler());
              });

      try (var test = TestServer.start(app)) {
        var original = test.send(request -> request.path("/openapi.json")).body();
        var document = Json31.mapper().readTree(original);
        assertEquals(
            "#/components/schemas/User",
            document
                .path("paths")
                .path("/users")
                .path("get")
                .path("responses")
                .path("200")
                .path("content")
                .path("application/json")
                .path("schema")
                .path("$ref")
                .asText());
        assertEquals(
            2,
            document
                .path("components")
                .path("schemas")
                .path("User")
                .path("properties")
                .path("name")
                .path("type")
                .size());
        schema.addProperty("password", new JsonSchema().types(Set.of("string")));
        assertArrayEquals(original, test.send(request -> request.path("/openapi.json")).body());
        var late = new JsonSchema();
        assertThrows(IllegalStateException.class, () -> docs.schema("Late", late));
      }
    }
  }

  @Test
  void overridesGroupFieldsWithoutChangingSiblingDefaults() throws Exception {
    var docs = new OpenApi("Users API", "1.0");

    try (var app = new Shoostr()) {
      app.extensions(docs)
          .routes(
              routes -> {
                routes.path(
                    "/api",
                    group -> {
                      group.get(
                          "/first",
                          (ignored20, ignored21) -> {},
                          e ->
                              e.with(docs)
                                  .summary("First")
                                  .tag("first")
                                  .response(200, "Child response", MediaType.TEXT_PLAIN));
                      group.get("/second", (ignored22, ignored23) -> {});
                      group.get(
                          "/hidden", (ignored24, ignored25) -> {}, e -> e.with(docs).hidden());
                    },
                    e ->
                        e.with(docs)
                            .summary("Group")
                            .description("Shared description")
                            .tag("group")
                            .response(200, "Group response", MediaType.TEXT_PLAIN)
                            .response(404, "Not found"));
                routes.get("/openapi.json", docs.handler());
              });

      try (var test = TestServer.start(app)) {
        var paths =
            Json31.mapper()
                .readTree(test.send(request -> request.path("/openapi.json")).body())
                .path("paths");
        var first = paths.path("/api/first").path("get");
        var second = paths.path("/api/second").path("get");
        assertEquals("First", first.path("summary").asText());
        assertEquals("Shared description", first.path("description").asText());
        assertEquals("group", first.path("tags").path(0).asText());
        assertEquals("first", first.path("tags").path(1).asText());
        assertEquals(
            "Child response", first.path("responses").path("200").path("description").asText());
        assertEquals("Not found", first.path("responses").path("404").path("description").asText());
        assertEquals("Group", second.path("summary").asText());
        assertEquals(1, second.path("tags").size());
        assertEquals(
            "Group response", second.path("responses").path("200").path("description").asText());
        assertFalse(paths.has("/api/hidden"));
      }
    }
  }

  @Test
  void servesOnlyExplicitlyDocumentedFinalBindings() throws Exception {
    var docs = new OpenApi("Users API", "1.0");

    try (var app = new Shoostr()) {
      app.extensions(docs)
          .routes(
              routes -> {
                routes.path(
                    "/api",
                    group -> {
                      group.get(
                          "/users",
                          (ignored26, response) -> response.text("users"),
                          e -> e.with(docs).summary("List users"));
                      group.get("/internal", (ignored27, ignored28) -> {});
                    });
                routes.get("/openapi.json", docs.handler());
              });

      try (var test = TestServer.start(app)) {
        var result = test.send(request -> request.path("/openapi.json"));
        assertEquals(200, result.statusCode());
        var document = Json31.mapper().readTree(result.body());
        assertEquals("3.1.1", document.path("openapi").asText());
        assertEquals("Users API", document.path("info").path("title").asText());
        assertEquals(
            "List users",
            document.path("paths").path("/api/users").path("get").path("summary").asText());
        assertEquals(1, document.path("paths").size());
        assertFalse(document.path("paths").path("/api/users").path("get").has("responses"));
      }
    }
  }
}
