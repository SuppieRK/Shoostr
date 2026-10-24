package io.github.suppierk.shoostr.example.openapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import io.github.suppierk.shoostr.openapi.OpenApi;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.media.JsonSchema;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Describes selected operations with explicit schemas and registration metadata. */
public final class OpenApiDocumentExample {
  private static final String ITEMS_TAG = "items";

  /** Prevents construction of the factory. */
  private OpenApiDocumentExample() {}

  /**
   * Creates an unstarted application with a generated OpenAPI document.
   *
   * @return unstarted application
   */
  public static Shoostr application() {
    var docs = new OpenApi("Items API", "1.0").tag(ITEMS_TAG, "Item operations");
    // Each instance owns its own document; route selection decides which receives an operation.
    var internal = new OpenApi("Operations API", "1.0");
    // This schema describes input; it does not install runtime validation on the handler.
    var schema = new JsonSchema();
    schema.setTypes(Set.of("object"));
    schema.addProperty("name", new JsonSchema().types(Set.of("string")));
    schema.setRequired(List.of("name"));
    var item = docs.schema("Item", schema);
    var mapper = Json31.mapper().copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    return new Shoostr()
        .extensions(docs, internal)
        .routes(
            routes -> {
              // Serving a document is separate from selecting operations to describe in it.
              routes
                  .get("/openapi.json", docs.handler())
                  .get("/internal-openapi.json", internal.handler())
                  .get(
                      "/operations/status",
                      (ignored, response) -> response.text("ready"),
                      extensions ->
                          extensions
                              .with(internal)
                              .summary("Operational status")
                              .response(200, "Ready", MediaType.TEXT_PLAIN))
                  .post(
                      "/api/items",
                      (request, response) ->
                          response
                              .status(201)
                              .body(
                                  MediaType.APPLICATION_JSON,
                                  mapper.writeValueAsBytes(
                                      Map.of("name", itemName(mapper, request.bodyText())))),
                      extensions ->
                          extensions
                              .with(docs)
                              .summary("Create an item")
                              .operationId("createItem")
                              .tag(ITEMS_TAG)
                              .requestBody("Item to create", MediaType.APPLICATION_JSON, item, true)
                              .response(201, "Created", MediaType.APPLICATION_JSON, item))
                  .path(
                      "/api",
                      api ->
                          api.get(
                              "/items/{id:[0-9]+}",
                              (request, response) ->
                                  response.body(
                                      MediaType.APPLICATION_JSON,
                                      mapper.writeValueAsBytes(
                                          Map.of(
                                              "name",
                                              "Item " + request.pathParam("id").orElseThrow()))),
                              extensions ->
                                  extensions
                                      .with(docs)
                                      .summary("Find an item")
                                      .operationId("findItem")
                                      // Document the parameter explicitly, independently of the
                                      // route constraint.
                                      .pathParameter("id", "Numeric item identifier")
                                      .response(
                                          200, "Item found", MediaType.APPLICATION_JSON, item)),
                      extensions -> extensions.with(docs).tag(ITEMS_TAG));
            });
  }

  /**
   * Validates runtime input independently of OpenAPI's descriptive schema.
   *
   * @param mapper configured parser
   * @param body request JSON
   * @return nonblank item name
   * @throws BadRequestException if the JSON or item name is invalid
   */
  private static String itemName(ObjectMapper mapper, String body) {
    JsonNode input;

    // These application checks enforce the input contract; publishing the schema does not.

    try {
      input = mapper.readTree(body);
    } catch (JsonProcessingException failure) {
      throw new BadRequestException("Malformed item JSON", failure);
    }

    if (input == null
        || !input.isObject()
        || !input.path("name").isTextual()
        || input.path("name").asText().isBlank()) {
      throw new BadRequestException("A nonblank item name is required");
    }

    return input.path("name").asText();
  }
}
