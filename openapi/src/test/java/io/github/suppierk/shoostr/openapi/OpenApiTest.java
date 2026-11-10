package io.github.suppierk.shoostr.openapi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamWriteConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class OpenApiTest {
  @Test
  void typedApplicationDefaultsKeepDocumentsAndSiblingOverridesIndependent() throws Exception {
    var publicDocs = new OpenApi("Public API", "1");
    var privateDocs = new OpenApi("Private API", "1");
    Handler endpoint = (request, response) -> response.text("ready");
    var app = new Shoostr();

    try (Closeable cleanup = app::close) {
      app.withExtension(publicDocs, operation -> operation.summary("Public default").tag("public"))
          .withExtension(
              privateDocs, operation -> operation.summary("Private default").tag("private"));
      app.routes()
          .get("/inherited", endpoint)
          .path(
              "/api",
              group ->
                  group
                      .get(
                          "/child",
                          endpoint,
                          lifecycle -> lifecycle.withExtension(publicDocs).summary("Public child"))
                      .get("/sibling", endpoint))
          .get("/public-only", endpoint, lifecycle -> lifecycle.withoutExtension(privateDocs))
          .get("/private-only", endpoint, lifecycle -> lifecycle.withoutExtension(publicDocs))
          .get(
              "/public.json",
              publicDocs.handler(),
              lifecycle -> {
                lifecycle.withoutExtension(publicDocs);
                lifecycle.withoutExtension(privateDocs);
              })
          .get(
              "/private.json",
              privateDocs.handler(),
              lifecycle -> {
                lifecycle.withoutExtension(publicDocs);
                lifecycle.withoutExtension(privateDocs);
              });

      try (var server = TestServer.start(app)) {
        var publicPaths =
            Json31.mapper()
                .readTree(server.send(request -> request.path("/public.json")).body())
                .path("paths");
        var privatePaths =
            Json31.mapper()
                .readTree(server.send(request -> request.path("/private.json")).body())
                .path("paths");

        assertEquals(4, publicPaths.size());
        assertEquals(4, privatePaths.size());
        assertEquals(
            "Public default",
            publicPaths.path("/inherited").path("get").path("summary").asString());
        assertEquals(
            "Public child", publicPaths.path("/api/child").path("get").path("summary").asString());
        assertEquals(
            "Public default",
            publicPaths.path("/api/sibling").path("get").path("summary").asString());
        assertEquals(
            "Private default",
            privatePaths.path("/inherited").path("get").path("summary").asString());
        assertEquals(
            "Private default",
            privatePaths.path("/api/child").path("get").path("summary").asString());
        assertEquals(
            "Private default",
            privatePaths.path("/api/sibling").path("get").path("summary").asString());
        assertEquals(
            "public", publicPaths.path("/api/child").path("get").path("tags").get(0).asString());
        assertEquals(
            "private", privatePaths.path("/api/child").path("get").path("tags").get(0).asString());
        assertTrue(publicPaths.has("/public-only"));
        assertFalse(publicPaths.has("/private-only"));
        assertTrue(privatePaths.has("/private-only"));
        assertFalse(privatePaths.has("/public-only"));
      }
    }
  }

  @Test
  void wrapsJacksonSerializationFailureAndClosesTheApplicationBeforeListening() throws Exception {
    var mapper = Json31.mapper();
    var closed = new AtomicInteger();

    try {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close) {
        var docs = new OpenApi("API", "1");
        app.withExtension(docs).afterClose(closed::incrementAndGet);
        app.routes().get("/openapi.json", docs.handler());
        Json31.mapper(
            new ObjectMapper(
                JsonFactory.builder()
                    .streamWriteConstraints(
                        StreamWriteConstraints.builder().maxNestingDepth(1).build())
                    .build()));

        var failure = assertThrows(IllegalStateException.class, app::start);
        assertEquals("Cannot serialize OpenAPI document", failure.getMessage());
        assertInstanceOf(JacksonException.class, failure.getCause());
        assertEquals(1, closed.get());
        assertThrows(IllegalStateException.class, app::port);
        assertThrows(IllegalStateException.class, app::start);
      }
    } finally {
      Json31.mapper(mapper);
    }

    assertEquals(1, closed.get());
  }

  @Test
  void keepsDocumentationIndependentWhenOneHandlerIsRegisteredAtTwoPaths() throws Exception {
    var docs = new OpenApi("API", "1");
    Handler shared = (ignored, response) -> response.text("shared");

    {
      var app = new Shoostr();

      try (Closeable appCleanup1 = app::close) {
        app.routes(
            routes -> {
              routes.get(
                  "/first",
                  shared,
                  lifecycle ->
                      lifecycle
                          .withExtension(docs)
                          .summary("First operation")
                          .operationId("first"));
              routes.get(
                  "/second",
                  shared,
                  lifecycle ->
                      lifecycle
                          .withExtension(docs)
                          .summary("Second operation")
                          .operationId("second"));
              routes.get("/openapi.json", docs.handler());
            });

        try (var test = TestServer.start(app)) {
          var paths =
              Json31.mapper()
                  .readTree(test.send(request -> request.path("/openapi.json")).body())
                  .path("paths");

          assertEquals(
              "First operation", paths.path("/first").path("get").path("summary").asString());
          assertEquals(
              "Second operation", paths.path("/second").path("get").path("summary").asString());
          assertEquals("first", paths.path("/first").path("get").path("operationId").asString());
          assertEquals("second", paths.path("/second").path("get").path("operationId").asString());
        }
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

    {
      var app = new Shoostr();

      try (Closeable appCleanup2 = app::close) {
        app.routes(
            routes -> {
              routes.post(
                  "/text",
                  (ignoredRequest, ignoredResponse) -> {},
                  lifecycle -> lifecycle.withExtension(docs).requestBody(body));
              routes.get("/openapi.json", docs.handler());
            });

        try (var test = TestServer.start(app)) {
          var content =
              createContentForPermitsNativeRequestMediaDeclarationsWithoutInventingASchema(test);

          assertTrue(content.has("text/plain"));
          assertFalse(content.path("text/plain").has("schema"));
        }
      }
    }
  }

  private JsonNode createContentForPermitsNativeRequestMediaDeclarationsWithoutInventingASchema(
      TestServer test) throws JacksonException, IOException, InterruptedException {
    return Json31.mapper()
        .readTree(test.send(request -> request.path("/openapi.json")).body())
        .path("paths")
        .path("/text")
        .path("post")
        .path("requestBody")
        .path("content");
  }

  @Test
  void preservesValidRecursiveSchemaReferences() throws Exception {
    var docs = new OpenApi("API", "1");
    var node = new JsonSchema();
    node.setTypes(Set.of("object"));
    node.addProperty("next", new JsonSchema().$ref("#/components/schemas/Node"));
    docs.schema("Node", node);

    {
      var app = new Shoostr();

      try (Closeable appCleanup3 = app::close) {
        app.withExtension(docs).routes().get("/openapi.json", docs.handler());

        try (var test = TestServer.start(app)) {
          var schema = createSchemaForPreservesValidRecursiveSchemaReferences(test);

          assertEquals(
              "#/components/schemas/Node",
              schema.path("properties").path("next").path("$ref").asString());
        }
      }
    }
  }

  private JsonNode createSchemaForPreservesValidRecursiveSchemaReferences(TestServer test)
      throws JacksonException, IOException, InterruptedException {
    return Json31.mapper()
        .readTree(test.send(request -> request.path("/openapi.json")).body())
        .path("components")
        .path("schemas")
        .path("Node");
  }

  @Test
  void treatsReferenceStringsInsideExampleDataAsLiteralPayload() throws Exception {
    var docs = new OpenApi("API", "1");
    var schema = new JsonSchema();
    schema.setTypes(Set.of("object"));
    schema.setExample(Map.of("$ref", "#/components/schemas/Missing"));
    docs.schema("Example", schema);

    {
      var app = new Shoostr();

      try (Closeable appCleanup4 = app::close) {
        app.withExtension(docs).routes().get("/openapi.json", docs.handler());

        try (var test = TestServer.start(app)) {
          var example =
              createExampleForTreatsReferenceStringsInsideExampleDataAsLiteralPayload(test);

          assertEquals("#/components/schemas/Missing", example.path("$ref").asString());
        }
      }
    }
  }

  private JsonNode createExampleForTreatsReferenceStringsInsideExampleDataAsLiteralPayload(
      TestServer test) throws JacksonException, IOException, InterruptedException {
    return Json31.mapper()
        .readTree(test.send(request -> request.path("/openapi.json")).body())
        .path("components")
        .path("schemas")
        .path("Example")
        .path("example");
  }

  @Test
  void documentsExplicitRequestShapesWithoutAConfiguredCodec() throws Exception {
    var docs =
        new OpenApi("Users API", "1.0")
            .description("Example service")
            .server("https://api.example.test")
            .tag("users", "User operations");
    var schema = new JsonSchema().types(Set.of("object"));

    {
      var app = new Shoostr();

      try (Closeable appCleanup5 = app::close) {
        app.routes(
            routes -> {
              routes.post(
                  "/users",
                  (ignoredRequest, ignoredResponse) -> {},
                  lifecycle ->
                      lifecycle
                          .withExtension(docs)
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
                          .requestBody("User to create", MediaType.APPLICATION_JSON, schema, true));
              routes.get("/openapi.json", docs.handler());
            });

        try (var test = TestServer.start(app)) {
          var document =
              Json31.mapper().readTree(test.send(request -> request.path("/openapi.json")).body());
          var operation = document.path("paths").path("/users").path("post");

          assertEquals("createUser", operation.path("operationId").asString());
          assertEquals("query", operation.path("parameters").path(0).path("in").asString());
          assertEquals("header", operation.path("parameters").path(1).path("in").asString());
          assertEquals(
              "object",
              operation
                  .path("requestBody")
                  .path("content")
                  .path("application/json")
                  .path("schema")
                  .path("type")
                  .asString());
          assertEquals("Example service", document.path("info").path("description").asString());
          assertEquals(
              "https://api.example.test", document.path("servers").path(0).path("url").asString());
        }
      }
    }
  }

  @Test
  void replacesInheritedSecurityWithoutChangingRuntimeAuthentication() throws Exception {
    var docs = new OpenApi("Users API", "1.0");
    docs.securityScheme(
        "basic", new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic"));

    {
      var app = new Shoostr();

      try (Closeable appCleanup6 = app::close) {
        app.routes(
            routes -> {
              routes.path(
                  "/api",
                  group -> {
                    group.get(
                        "/anonymous",
                        (ignoredRequest, ignoredResponse) -> {},
                        lifecycle -> lifecycle.withExtension(docs).anonymous());
                    group.get("/documented", (ignoredRequest, ignoredResponse) -> {});
                  },
                  lifecycle -> lifecycle.withExtension(docs).security("basic"));
              routes.get("/openapi.json", docs.handler());
            });

        try (var test = TestServer.start(app)) {
          var document =
              Json31.mapper().readTree(test.send(request -> request.path("/openapi.json")).body());

          assertEquals(
              0, document.path("paths").path("/api/anonymous").path("get").path("security").size());
          assertEquals(
              1,
              document.path("paths").path("/api/documented").path("get").path("security").size());
          assertEquals(
              "basic",
              document
                  .path("components")
                  .path("securitySchemes")
                  .path("basic")
                  .path("scheme")
                  .asString());
          assertEquals(200, test.send(request -> request.path("/api/documented")).statusCode());
        }
      }
    }
  }

  @Test
  void rejectsConstrainedAndPlainOperationsThatNormalizeToTheSamePath() throws Exception {
    var docs = new OpenApi("Users API", "1.0");

    {
      var app = new Shoostr();

      try (Closeable appCleanup7 = app::close) {
        app.routes(
            routes -> {
              routes.get(
                  "/users/{id:[0-9]+}",
                  (ignoredRequest, ignoredResponse) -> {},
                  lifecycle -> lifecycle.withExtension(docs).pathParameter("id", "ID"));
              routes.get(
                  "/users/{id}",
                  (ignoredRequest, ignoredResponse) -> {},
                  lifecycle -> lifecycle.withExtension(docs).pathParameter("id", "ID"));
            });

        var failure = assertThrows(IllegalArgumentException.class, app::start);
        assertEquals("Conflicting OpenAPI operation: GET /users/{id}", failure.getMessage());
      }
    }
  }

  @Test
  void documentsConstrainedSegmentsAsExplicitRequiredPathParameters() throws Exception {
    var docs = new OpenApi("Users API", "1.0");

    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup8 = app::close) {
        app.routes(
            routes -> {
              routes.get(
                  "/users/{id:[0-9]{1,32}}",
                  (ignoredRequest, ignoredResponse) -> {},
                  lifecycle ->
                      lifecycle.withExtension(docs).pathParameter("id", "User identifier"));
              routes.get("/openapi.json", docs.handler());
            });

        try (var test = TestServer.start(app)) {
          var paths =
              Json31.mapper()
                  .readTree(test.send(request -> request.path("/openapi.json")).body())
                  .path("paths");
          var parameter = paths.path("/users/{id}").path("get").path("parameters").path(0);

          assertEquals("id", parameter.path("name").asString());
          assertEquals("path", parameter.path("in").asString());
          assertTrue(parameter.path("required").asBoolean());
          assertEquals("^(?:[0-9]{1,32})$", parameter.path("schema").path("pattern").asString());
          assertEquals(1, paths.size());
        }
      }
    }
  }

  @Test
  void intersectsDeclaredSchemaPatternWithRouteConstraintWithoutMutatingCallerSchema()
      throws Exception {
    var docs = new OpenApi("Users API", "1.0");
    var schema = new JsonSchema().types(Set.of("string")).pattern("^1").minLength(2);
    var parameter = new Parameter().name("id").in("path").required(true).schema(schema);

    {
      var app = new Shoostr();

      try (Closeable appCleanup9 = app::close) {
        app.routes(
            routes -> {
              routes.get(
                  "/users/{id:[0-9]+}",
                  (ignoredRequest, ignoredResponse) -> {},
                  lifecycle -> lifecycle.withExtension(docs).parameter(parameter));
              routes.get("/openapi.json", docs.handler());
            });

        try (var test = TestServer.start(app)) {
          var intersection =
              createIntersectionForIntersectsDeclaredSchemaPatternWithRouteConstraintWithoutMutatingCallerSchema(
                  test);

          assertEquals(2, intersection.size());
          assertEquals("string", intersection.path(0).path("type").asString());
          assertEquals("^1", intersection.path(0).path("pattern").asString());
          assertEquals(2, intersection.path(0).path("minLength").asInt());
          assertEquals("string", intersection.path(1).path("type").asString());
          assertEquals("^(?:[0-9]+)$", intersection.path(1).path("pattern").asString());
          assertEquals("^1", schema.getPattern());
          assertEquals(2, schema.getMinLength());
          assertNull(schema.getAllOf());
        }
      }
    }
  }

  private JsonNode
      createIntersectionForIntersectsDeclaredSchemaPatternWithRouteConstraintWithoutMutatingCallerSchema(
          TestServer test) throws JacksonException, IOException, InterruptedException {
    return Json31.mapper()
        .readTree(test.send(request -> request.path("/openapi.json")).body())
        .path("paths")
        .path("/users/{id}")
        .path("get")
        .path("parameters")
        .path(0)
        .path("schema")
        .path("allOf");
  }

  @Test
  void freezesExplicitReusableSchemasBeforeServing() throws Exception {
    var docs = new OpenApi("Users API", "1.0");
    var schema = new JsonSchema();
    schema.setTypes(Set.of("object"));
    schema.addProperty("name", new JsonSchema().types(Set.of("string", "null")));
    var user = docs.schema("User", schema);

    {
      var app = new Shoostr();

      try (Closeable appCleanup10 = app::close) {
        app.routes(
            routes -> {
              routes.get(
                  "/users",
                  (ignoredRequest, ignoredResponse) -> {},
                  lifecycle ->
                      lifecycle
                          .withExtension(docs)
                          .response(200, "Users", MediaType.APPLICATION_JSON, user));
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
                  .asString());
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
  }

  @Test
  void overridesGroupFieldsWithoutChangingSiblingDefaults() throws Exception {
    var docs = new OpenApi("Users API", "1.0");

    {
      var app = new Shoostr();

      try (Closeable appCleanup11 = app::close) {
        app.routes(
            routes -> {
              routes.path(
                  "/api",
                  group -> {
                    group.get(
                        "/first",
                        (ignoredRequest, ignoredResponse) -> {},
                        lifecycle ->
                            lifecycle
                                .withExtension(docs)
                                .summary("First")
                                .tag("first")
                                .response(200, "Child response", MediaType.TEXT_PLAIN));
                    group.get("/second", (ignoredRequest, ignoredResponse) -> {});
                    group.get(
                        "/hidden",
                        (ignoredRequest, ignoredResponse) -> {},
                        lifecycle -> lifecycle.withExtension(docs).hidden());
                  },
                  lifecycle ->
                      lifecycle
                          .withExtension(docs)
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

          assertEquals("First", first.path("summary").asString());
          assertEquals("Shared description", first.path("description").asString());
          assertEquals("group", first.path("tags").path(0).asString());
          assertEquals("first", first.path("tags").path(1).asString());
          assertEquals(
              "Child response", first.path("responses").path("200").path("description").asString());
          assertEquals(
              "Not found", first.path("responses").path("404").path("description").asString());
          assertEquals("Group", second.path("summary").asString());
          assertEquals(1, second.path("tags").size());
          assertEquals(
              "Group response",
              second.path("responses").path("200").path("description").asString());
          assertFalse(paths.has("/api/hidden"));
        }
      }
    }
  }

  @Test
  void servesOnlyExplicitlyDocumentedFinalBindings() throws Exception {
    var docs = new OpenApi("Users API", "1.0");

    {
      var app = new Shoostr();

      try (Closeable appCleanup12 = app::close) {
        app.routes(
            routes -> {
              routes.path(
                  "/api",
                  group -> {
                    group.get(
                        "/users",
                        (ignoredRequest, response) -> response.text("users"),
                        lifecycle -> lifecycle.withExtension(docs).summary("List users"));
                    group.get("/internal", (ignoredRequest, ignoredResponse) -> {});
                  });
              routes.get("/openapi.json", docs.handler());
            });

        try (var test = TestServer.start(app)) {
          var result = test.send(request -> request.path("/openapi.json"));

          assertEquals(200, result.statusCode());

          var document = Json31.mapper().readTree(result.body());

          assertEquals("3.1.1", document.path("openapi").asString());
          assertEquals("Users API", document.path("info").path("title").asString());
          assertEquals(
              "List users",
              document.path("paths").path("/api/users").path("get").path("summary").asString());
          assertEquals(1, document.path("paths").size());
          assertFalse(document.path("paths").path("/api/users").path("get").has("responses"));
        }
      }
    }
  }
}
