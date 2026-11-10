package io.github.suppierk.shoostr.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.Handler;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.MediaType;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.io.Closeable;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class OpenApiValidationTest {
  @ParameterizedTest
  @MethodSource("schemaDictionaryEntries")
  void rejectsMissingReferencesWithinSchemaDictionaries(String keyword, String entry)
      throws Exception {
    var schema = new JsonSchema();
    schema.setTypes(Set.of("object"));
    var missing = new JsonSchema().$ref("#/components/schemas/Missing");
    if ("patternProperties".equals(keyword)) {
      schema.setPatternProperties(Map.of(entry, missing));
    } else {
      schema.setDependentSchemas(Map.of(entry, missing));
    }

    var docs = new OpenApi("API", "1");
    docs.schema("Container", schema);

    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup = app::close) {
        app.withExtension(docs);

        assertThrows(IllegalArgumentException.class, app::start);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectsNativeRequestBodiesWithoutMediaDeclarations(boolean emptyContent) throws Exception {
    var body = new RequestBody().description("Payload");
    if (emptyContent) {
      body.content(new Content());
    }

    var docs = new OpenApi("API", "1");

    {
      var app = new Shoostr();

      try (Closeable appCleanup1 = app::close) {

        var routes = app.routes();

        assertThrows(
            IllegalArgumentException.class,
            () ->
                routes.post(
                    "/users",
                    (ignored, ignoredResponse) -> {},
                    lifecycle -> lifecycle.withExtension(docs).requestBody(body)));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"integer", "content"})
  void rejectsConstrainedPathParametersWithoutAnExplicitStringSchema(String declaration)
      throws Exception {
    var parameter = new Parameter().name("id").in("path").required(true);
    if ("integer".equals(declaration)) {
      parameter.schema(new JsonSchema().types(Set.of("integer")));
    } else {
      parameter.content(
          new Content()
              .addMediaType(
                  "text/plain",
                  new io.swagger.v3.oas.models.media.MediaType()
                      .schema(new JsonSchema().types(Set.of("string")))));
    }

    var docs = new OpenApi("API", "1");

    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup2 = app::close) {
        app.routes()
            .get(
                "/users/{id:[0-9]+}",
                (ignoredRequest, ignoredResponse) -> {},
                lifecycle -> lifecycle.withExtension(docs).parameter(parameter));

        assertThrows(IllegalArgumentException.class, app::start);
      }
    }
  }

  @Test
  void rejectsParametersWithoutSchemaOrContentDuringConfiguration() throws Exception {
    var docs = new OpenApi("API", "1");
    var parameter = new Parameter().name("page").in("query");

    {
      var app = new Shoostr();

      try (Closeable appCleanup3 = app::close) {

        var routes = app.routes();

        assertThrows(
            IllegalArgumentException.class,
            () ->
                routes.get(
                    "/users",
                    (ignoredRequest, ignoredResponse) -> {},
                    lifecycle -> lifecycle.withExtension(docs).parameter(parameter)));
      }
    }
  }

  @Test
  void rejectsPublishedCatchAllRoutesInsteadOfApproximatingTheirMeaning() throws Exception {
    var docs = new OpenApi("API", "1");

    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup4 = app::close) {
        app.routes(
            routes ->
                routes.get(
                    "/files/{*tail}",
                    (ignoredRequest, ignoredResponse) -> {},
                    lifecycle -> lifecycle.withExtension(docs).summary("Files")));

        assertThrows(IllegalArgumentException.class, app::start);
      }
    }
  }

  @Test
  void rejectsPathParametersThatDoNotBelongToTheActualTemplate() throws Exception {
    var docs = new OpenApi("API", "1");

    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup5 = app::close) {
        app.routes(
            routes ->
                routes.get(
                    "/users/{id}",
                    (ignoredRequest, ignoredResponse) -> {},
                    lifecycle ->
                        lifecycle.withExtension(docs).pathParameter("other", "Wrong parameter")));

        assertThrows(IllegalArgumentException.class, app::start);
      }
    }
  }

  @Test
  void rejectsHttpMethodsThatOpenApiCannotRepresent() throws Exception {
    var docs = new OpenApi("API", "1");

    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup6 = app::close) {
        app.routes(
            routes ->
                routes.route(
                    "CONNECT",
                    "/tunnel",
                    (ignoredRequest, ignoredResponse) -> {},
                    lifecycle -> lifecycle.withExtension(docs).summary("Tunnel")));

        assertThrows(IllegalArgumentException.class, app::start);
      }
    }
  }

  @Test
  void rejectsSecuritySchemesMissingTheirRequiredType() throws Exception {
    try (var docs = new OpenApi("API", "1")) {
      var scheme = new SecurityScheme();

      assertThrows(IllegalArgumentException.class, () -> docs.securityScheme("broken", scheme));
    }
  }

  @Test
  void rejectsNativeResponsesWithoutRequiredDescriptionsDuringConfiguration() throws Exception {
    var docs = new OpenApi("API", "1");
    var response = new ApiResponse();

    {
      var app = new Shoostr();

      try (Closeable appCleanup7 = app::close) {

        var routes = app.routes();

        assertThrows(
            IllegalArgumentException.class,
            () ->
                routes.get(
                    "/users",
                    (ignoredRequest, ignoredResponse) -> {},
                    lifecycle -> lifecycle.withExtension(docs).response(200, response)));
      }
    }
  }

  @Test
  void rejectsDuplicateExplicitOperationIdentifiersWithinOneDocument() throws Exception {
    var docs = new OpenApi("API", "1");
    Handler shared = (ignoredRequest, ignoredResponse) -> {};

    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup8 = app::close) {
        app.routes(
            routes -> {
              routes.get(
                  "/first", shared, lifecycle -> lifecycle.withExtension(docs).operationId("read"));
              routes.get(
                  "/second",
                  shared,
                  lifecycle -> lifecycle.withExtension(docs).operationId("read"));
            });

        var failure = assertThrows(IllegalArgumentException.class, app::start);
        assertEquals("Duplicate OpenAPI operationId: read", failure.getMessage());
      }
    }
  }

  @Test
  void rejectsUnknownLocalSchemaReferencesBeforeListening() throws Exception {
    var docs = new OpenApi("API", "1");

    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup9 = app::close) {
        app.routes(
            routes ->
                routes.get(
                    "/users",
                    (ignoredRequest, ignoredResponse) -> {},
                    lifecycle ->
                        lifecycle
                            .withExtension(docs)
                            .response(
                                200,
                                "User",
                                MediaType.APPLICATION_JSON,
                                new JsonSchema().$ref("#/components/schemas/Missing"))));

        var failure = assertThrows(IllegalArgumentException.class, app::start);
        assertEquals(
            "Unknown local OpenAPI reference: #/components/schemas/Missing", failure.getMessage());
      }
    }
  }

  @Test
  void rejectsUnknownSecuritySchemesBeforeListening() throws Exception {
    var docs = new OpenApi("API", "1");

    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup10 = app::close) {
        app.routes(
            routes ->
                routes.get(
                    "/users",
                    (ignoredRequest, ignoredResponse) -> {},
                    lifecycle -> lifecycle.withExtension(docs).security("missing")));

        var failure = assertThrows(IllegalArgumentException.class, app::start);
        assertEquals("Unknown OpenAPI security scheme: missing", failure.getMessage());
      }
    }
  }

  private static Stream<Arguments> schemaDictionaryEntries() {
    return Stream.of("patternProperties", "dependentSchemas")
        .flatMap(
            keyword ->
                Stream.of("example", "default", "x-name")
                    .map(entry -> Arguments.of(keyword, entry)));
  }
}
