package io.github.suppierk.shoostr.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.Handler;
import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.MediaType;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
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

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.extensions(docs);
      assertThrows(IllegalArgumentException.class, app::start);
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

    try (var app = new Shoostr()) {
      app.extensions(docs);
      var routes = app.routes();
      assertThrows(
          IllegalArgumentException.class,
          () -> routes.post("/users", (_, _) -> {}, e -> e.get(docs).requestBody(body)));
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

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.extensions(docs)
          .routes()
          .get("/users/{id:[0-9]+}", (_, _) -> {}, e -> e.get(docs).parameter(parameter));
      assertThrows(IllegalArgumentException.class, app::start);
    }
  }

  @Test
  void rejectsParametersWithoutSchemaOrContentDuringConfiguration() throws Exception {
    var docs = new OpenApi("API", "1");
    var parameter = new Parameter().name("page").in("query");

    try (var app = new Shoostr()) {
      app.extensions(docs);
      var routes = app.routes();
      assertThrows(
          IllegalArgumentException.class,
          () -> routes.get("/users", (_, _) -> {}, e -> e.get(docs).parameter(parameter)));
    }
  }

  @Test
  void rejectsPublishedCatchAllRoutesInsteadOfApproximatingTheirMeaning() throws Exception {
    var docs = new OpenApi("API", "1");

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.extensions(docs)
          .routes(
              routes ->
                  routes.get("/files/{*tail}", (_, _) -> {}, e -> e.get(docs).summary("Files")));
      assertThrows(IllegalArgumentException.class, app::start);
    }
  }

  @Test
  void rejectsPathParametersThatDoNotBelongToTheActualTemplate() throws Exception {
    var docs = new OpenApi("API", "1");

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.extensions(docs)
          .routes(
              routes ->
                  routes.get(
                      "/users/{id}",
                      (_, _) -> {},
                      e -> e.get(docs).pathParameter("other", "Wrong parameter")));
      assertThrows(IllegalArgumentException.class, app::start);
    }
  }

  @Test
  void rejectsHttpMethodsThatOpenApiCannotRepresent() throws Exception {
    var docs = new OpenApi("API", "1");

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.extensions(docs)
          .routes(
              routes ->
                  routes.route(
                      "CONNECT", "/tunnel", (_, _) -> {}, e -> e.get(docs).summary("Tunnel")));
      assertThrows(IllegalArgumentException.class, app::start);
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

    try (var app = new Shoostr()) {
      app.extensions(docs);
      var routes = app.routes();
      assertThrows(
          IllegalArgumentException.class,
          () -> routes.get("/users", (_, _) -> {}, e -> e.get(docs).response(200, response)));
    }
  }

  @Test
  void rejectsDuplicateExplicitOperationIdentifiersWithinOneDocument() throws Exception {
    var docs = new OpenApi("API", "1");
    Handler shared = (_, _) -> {};

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.extensions(docs)
          .routes(
              routes -> {
                routes.get("/first", shared, e -> e.get(docs).operationId("read"));
                routes.get("/second", shared, e -> e.get(docs).operationId("read"));
              });
      var failure = assertThrows(IllegalArgumentException.class, app::start);
      assertEquals("Duplicate OpenAPI operationId: read", failure.getMessage());
    }
  }

  @Test
  void rejectsUnknownLocalSchemaReferencesBeforeListening() throws Exception {
    var docs = new OpenApi("API", "1");

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.extensions(docs)
          .routes(
              routes ->
                  routes.get(
                      "/users",
                      (_, _) -> {},
                      e ->
                          e.get(docs)
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

  @Test
  void rejectsUnknownSecuritySchemesBeforeListening() throws Exception {
    var docs = new OpenApi("API", "1");

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.extensions(docs)
          .routes(
              routes -> routes.get("/users", (_, _) -> {}, e -> e.get(docs).security("missing")));
      var failure = assertThrows(IllegalArgumentException.class, app::start);
      assertEquals("Unknown OpenAPI security scheme: missing", failure.getMessage());
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
