package io.github.suppierk.shoostr.example.mcp;

import io.github.suppierk.shoostr.http.MediaType;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** Native MCP examples using the names and fixed inputs required by the official test runner. */
final class CompatibilityFixtures {
  private static final String OBJECT = "object";
  private static final String PROPERTIES = "properties";
  private static final String STRING = "string";
  private static final String PROMPT = "prompt";
  private static final String REQUIRED = "required";
  private static final String MESSAGE = "message";
  private static final String EMAIL = "email";
  private static final String CONTENT_SEPARATOR = "; supplied fields: ";
  private static final String ACTION_PREFIX = "Form choice: ";
  private static final String DEFAULT = "default";
  private static final String CONST = "const";
  private static final String TITLE = "title";
  private static final String DELIVERY = "delivery";
  private static final String DESTINATION = "destination";
  private static final String COLLECTION_POINT = "collectionPoint";
  private static final String MIN_LENGTH = "minLength";
  private static final MediaType PNG_TYPE = MediaType.of("image/png");
  private static final Map<String, Object> NO_ARGUMENTS =
      Map.of("type", OBJECT, PROPERTIES, Map.of());
  private static final String PNG =
      "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAIAAABLbSncAAAAIklEQVR4XmOQ9bsBRP9PhAMRMpsBpwSmEISNWwJTiOp2AAA9XmwheCyshQAAAABJRU5ErkJggg==";
  private static final String WAV =
      "UklGRqQAAABXQVZFZm10IBAAAAABAAEAQB8AAIA+AAACABAAZGF0YYAAAAAAAPAH8A4sFAUXJheJFH8PoAi8AML4"
          + "o/E37CHpwOge6/XvsvaI/okGxg1iE7MWVRc0FZIQ+gk0Aiz61PIK7X/pnOh+6uvuXfUR/RwFjgyEEkoWbRf"
          + "KFZQRSguqA5z7EvTx7fPpkOjz6fHtEvSc+6oDSguUEcoVbRdKFoQSjgwcBQ==";

  /** Prevents instantiation of the fixed catalog. */
  private CompatibilityFixtures() {}

  /**
   * Registers the official suite's tools using native SDK descriptors and callbacks.
   *
   * @return immutable native tool registrations
   */
  static List<McpServerFeatures.SyncToolSpecification> tools() {
    // SDK tools pair an input schema with a callback invoked by MCP tools/call, not a Shoostr
    // route.
    return List.of(
        tool(
            "test_simple_text",
            NO_ARGUMENTS,
            (_, _) ->
                McpSchema.CallToolResult.builder()
                    .addTextContent("Welcome to Shoostr's MCP example.")
                    .build()),
        tool(
            "test_image_content",
            NO_ARGUMENTS,
            (_, _) ->
                McpSchema.CallToolResult.builder()
                    .content(List.of(McpSchema.ImageContent.builder(PNG, PNG_TYPE.value()).build()))
                    .build()),
        tool(
            "test_audio_content",
            NO_ARGUMENTS,
            (_, _) ->
                McpSchema.CallToolResult.builder()
                    .content(List.of(McpSchema.AudioContent.builder(WAV, "audio/wav").build()))
                    .build()),
        tool(
            "test_embedded_resource",
            NO_ARGUMENTS,
            (_, _) ->
                McpSchema.CallToolResult.builder()
                    .content(
                        List.of(
                            McpSchema.EmbeddedResource.builder(
                                    McpSchema.TextResourceContents.builder(
                                            "shoostr://guide",
                                            "This resource travels inside the tool result; it is not an HTTP download.")
                                        .mimeType(MediaType.TEXT_PLAIN.value())
                                        .build())
                                .build()))
                    .build()),
        tool(
            "test_multiple_content_types",
            NO_ARGUMENTS,
            (_, _) ->
                McpSchema.CallToolResult.builder()
                    .content(
                        List.of(
                            McpSchema.TextContent.builder("A guide, a checkerboard and a JSON note")
                                .build(),
                            McpSchema.ImageContent.builder(PNG, PNG_TYPE.value()).build(),
                            McpSchema.EmbeddedResource.builder(
                                    McpSchema.TextResourceContents.builder(
                                            "shoostr://delivery-note",
                                            "{\"parcel\":\"P7\",\"delivery\":\"pickup\"}")
                                        .mimeType(MediaType.APPLICATION_JSON.value())
                                        .build())
                                .build()))
                    .build()),
        tool(
            "test_error_handling",
            NO_ARGUMENTS,
            (_, _) ->
                McpSchema.CallToolResult.builder()
                    .isError(true)
                    .addTextContent("Demonstration rejected: no parcel was created or changed.")
                    .build()),
        tool(
            "test_tool_with_logging",
            NO_ARGUMENTS,
            (exchange, _) -> {
              // The SDK exchange sends protocol notifications to the connected MCP client.
              for (var message :
                  List.of(
                      "Opened example operation",
                      "Selected demonstration data",
                      "Closed example operation")) {
                exchange.loggingNotification(
                    McpSchema.LoggingMessageNotification.builder(
                            McpSchema.LoggingLevel.INFO, message)
                        .build());
              }

              return McpSchema.CallToolResult.builder()
                  .addTextContent("Three example log messages were sent.")
                  .build();
            }),
        tool(
            "test_tool_with_progress",
            NO_ARGUMENTS,
            (exchange, request) -> {
              var metadata = request.meta();
              var token = metadata == null ? null : metadata.get("progressToken");
              if (token != null) {
                for (var progress : List.of(0.0, 0.5, 1.0)) {
                  exchange.progressNotification(
                      McpSchema.ProgressNotification.builder(token, progress).total(1.0).build());
                }
              }

              return McpSchema.CallToolResult.builder()
                  .addTextContent("The example operation reached its final progress update.")
                  .build();
            }),
        tool(
            "test_sampling",
            Map.of(
                "type",
                OBJECT,
                PROPERTIES,
                Map.of(PROMPT, Map.of("type", STRING)),
                REQUIRED,
                List.of(PROMPT)),
            (exchange, request) -> {
              // Sampling asks the MCP client to generate a message; this server supplies no LLM.
              var sampled =
                  exchange.createMessage(
                      McpSchema.CreateMessageRequest.builder(
                              List.of(
                                  McpSchema.SamplingMessage.builder(
                                          McpSchema.Role.USER,
                                          McpSchema.TextContent.builder(
                                                  stringArgument(request.arguments(), PROMPT))
                                              .build())
                                      .build()),
                              128)
                          .build());
              return McpSchema.CallToolResult.builder()
                  .addTextContent(
                      "Client-generated answer: "
                          + ((McpSchema.TextContent) sampled.content()).text())
                  .build();
            }),
        tool(
            "test_elicitation",
            Map.of(
                "type",
                OBJECT,
                PROPERTIES,
                Map.of(MESSAGE, Map.of("type", STRING)),
                REQUIRED,
                List.of(MESSAGE)),
            (exchange, request) -> {
              // Elicitation asks the MCP client to collect user input using this explicit form
              // schema.
              var result =
                  exchange.createElicitation(
                      McpSchema.ElicitFormRequest.builder(
                              stringArgument(request.arguments(), MESSAGE),
                              Map.of(
                                  "type",
                                  OBJECT,
                                  PROPERTIES,
                                  Map.of(
                                      "username",
                                      Map.of(
                                          "type",
                                          STRING,
                                          "description",
                                          "Name to display in the delivery preview"),
                                      EMAIL,
                                      Map.of(
                                          "type",
                                          STRING,
                                          "description",
                                          "Example reply address; no email is sent")),
                                  REQUIRED,
                                  List.of("username", EMAIL)))
                          .build());
              return McpSchema.CallToolResult.builder()
                  .addTextContent(
                      ACTION_PREFIX + result.action() + CONTENT_SEPARATOR + result.content())
                  .build();
            }),
        tool(
            "test_elicitation_sep1034_defaults",
            NO_ARGUMENTS,
            (exchange, _) -> {
              var result =
                  exchange.createElicitation(
                      McpSchema.ElicitFormRequest.builder(
                              "Try each default type and submit your own values", defaults())
                          .build());
              return McpSchema.CallToolResult.builder()
                  .addTextContent(
                      ACTION_PREFIX + result.action() + CONTENT_SEPARATOR + result.content())
                  .build();
            }),
        tool(
            "test_elicitation_sep1330_enums",
            NO_ARGUMENTS,
            (exchange, _) -> {
              var result =
                  exchange.createElicitation(
                      McpSchema.ElicitFormRequest.builder(
                              "Choose from the single- and multi-select delivery examples", enums())
                          .build());
              return McpSchema.CallToolResult.builder()
                  .addTextContent(
                      ACTION_PREFIX + result.action() + CONTENT_SEPARATOR + result.content())
                  .build();
            }),
        tool(
            "json_schema_2020_12_tool",
            jsonSchema(),
            (_, _) ->
                McpSchema.CallToolResult.builder()
                    .addTextContent("Parcel delivery schema example; no shipment was created.")
                    .build()));
  }

  /**
   * Supplies static and subscribable resources without an adapter-side catalog.
   *
   * @return native resource registrations
   */
  static List<McpServerFeatures.SyncResourceSpecification> resources() {
    // SDK resources expose content addressed by MCP URIs, not files served at HTTP URL paths.
    return List.of(
        new McpServerFeatures.SyncResourceSpecification(
            McpSchema.Resource.builder("test://static-text", "Example guide")
                .description("A short guide to the three MCP feature types")
                .mimeType(MediaType.TEXT_PLAIN.value())
                .build(),
            (_, request) ->
                McpSchema.ReadResourceResult.builder(
                        List.of(
                            McpSchema.TextResourceContents.builder(
                                    request.uri(),
                                    "Call tools to run operations, read resources for data, and request prompts for messages.")
                                .mimeType(MediaType.TEXT_PLAIN.value())
                                .build()))
                    .build()),
        new McpServerFeatures.SyncResourceSpecification(
            McpSchema.Resource.builder("test://static-binary", "Example checkerboard")
                .description("An eight-by-eight blue and amber image generated for this example")
                .mimeType(PNG_TYPE.value())
                .build(),
            (_, request) ->
                McpSchema.ReadResourceResult.builder(
                        List.of(
                            McpSchema.BlobResourceContents.builder(request.uri(), PNG)
                                .mimeType(PNG_TYPE.value())
                                .build()))
                    .build()),
        new McpServerFeatures.SyncResourceSpecification(
            McpSchema.Resource.builder("test://watched-resource", "Example status")
                .description("A fixed status resource that accepts subscriptions")
                .mimeType(MediaType.TEXT_PLAIN.value())
                .build(),
            (_, request) ->
                McpSchema.ReadResourceResult.builder(
                        List.of(
                            McpSchema.TextResourceContents.builder(
                                    request.uri(), "The MCP example is ready.")
                                .mimeType(MediaType.TEXT_PLAIN.value())
                                .build()))
                    .build()));
  }

  /**
   * Resolves resource-template data with JSON escaping through the SDK mapper.
   *
   * @return native resource-template registration
   */
  static List<McpServerFeatures.SyncResourceTemplateSpecification> resourceTemplates() {
    // An SDK resource template resolves parameterized MCP URIs; Shoostr does not match these paths.
    return List.of(
        new McpServerFeatures.SyncResourceTemplateSpecification(
            McpSchema.ResourceTemplate.builder("test://template/{id}/data", "Parcel preview")
                .description("An illustrative parcel record containing the supplied identifier")
                .mimeType(MediaType.APPLICATION_JSON.value())
                .build(),
            (_, request) -> {
              var uri = request.uri();
              var id = uri.substring("test://template/".length(), uri.lastIndexOf('/'));

              try {
                var json =
                    McpJsonDefaults.getMapper()
                        .writeValueAsString(
                            Map.of("parcelId", id, DELIVERY, "pickup", COLLECTION_POINT, "P7"));
                return McpSchema.ReadResourceResult.builder(
                        List.of(
                            McpSchema.TextResourceContents.builder(uri, json)
                                .mimeType(MediaType.APPLICATION_JSON.value())
                                .build()))
                    .build();
              } catch (IOException failure) {
                throw new UncheckedIOException(failure);
              }
            }));
  }

  /**
   * Exposes native prompt results and their explicit arguments.
   *
   * @return native prompt registrations
   */
  static List<McpServerFeatures.SyncPromptSpecification> prompts() {
    // SDK prompts return reusable message templates with arguments supplied by the MCP client.
    return List.of(
        new McpServerFeatures.SyncPromptSpecification(
            McpSchema.Prompt.builder("test_simple_prompt")
                .description("Ask the client to explain the MCP feature types")
                .build(),
            (_, _) ->
                McpSchema.GetPromptResult.builder(
                        List.of(
                            McpSchema.PromptMessage.builder(
                                    McpSchema.Role.USER,
                                    McpSchema.TextContent.builder(
                                            "Explain how Shoostr exposes MCP tools, resources and prompts over HTTP.")
                                        .build())
                                .build()))
                    .build()),
        new McpServerFeatures.SyncPromptSpecification(
            McpSchema.Prompt.builder("test_prompt_with_arguments")
                .description(
                    "Compare two supplied parcel identifiers in a client-side conversation")
                .arguments(
                    List.of(
                        McpSchema.PromptArgument.builder("arg1").required(true).build(),
                        McpSchema.PromptArgument.builder("arg2").required(true).build()))
                .build(),
            (_, request) ->
                McpSchema.GetPromptResult.builder(
                        List.of(
                            McpSchema.PromptMessage.builder(
                                    McpSchema.Role.USER,
                                    McpSchema.TextContent.builder(
                                            "Compare parcel "
                                                + request.arguments().get("arg1")
                                                + " with parcel "
                                                + request.arguments().get("arg2")
                                                + " using the delivery preview.")
                                        .build())
                                .build()))
                    .build()),
        new McpServerFeatures.SyncPromptSpecification(
            McpSchema.Prompt.builder("test_prompt_with_embedded_resource")
                .description("Attach example delivery guidance at the supplied resource URI")
                .arguments(
                    List.of(McpSchema.PromptArgument.builder("resourceUri").required(true).build()))
                .build(),
            (_, request) ->
                McpSchema.GetPromptResult.builder(
                        List.of(
                            McpSchema.PromptMessage.builder(
                                    McpSchema.Role.USER,
                                    McpSchema.EmbeddedResource.builder(
                                            McpSchema.TextResourceContents.builder(
                                                    stringArgument(
                                                        request.arguments(), "resourceUri"),
                                                    "Review the delivery guidance attached to this message.")
                                                .mimeType(MediaType.TEXT_PLAIN.value())
                                                .build())
                                        .build())
                                .build()))
                    .build()),
        new McpServerFeatures.SyncPromptSpecification(
            McpSchema.Prompt.builder("test_prompt_with_image")
                .description("Present the example's generated checkerboard to the client")
                .build(),
            (_, _) ->
                McpSchema.GetPromptResult.builder(
                        List.of(
                            McpSchema.PromptMessage.builder(
                                    McpSchema.Role.USER,
                                    McpSchema.ImageContent.builder(PNG, PNG_TYPE.value()).build())
                                .build()))
                    .build()));
  }

  /**
   * Builds a native descriptor rather than introducing another configuration API.
   *
   * @param name official fixture name
   * @param schema explicit tool input schema
   * @param handler native SDK callback
   * @return native registration
   */
  private static McpServerFeatures.SyncToolSpecification tool(
      String name,
      Map<String, Object> schema,
      BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult>
          handler) {
    return McpServerFeatures.SyncToolSpecification.builder()
        .tool(
            McpSchema.Tool.builder(name, schema)
                .description("Shoostr MCP example: " + name)
                .build())
        .callHandler(handler)
        .build();
  }

  /**
   * Validates a required string argument before using it in a native callback.
   *
   * @param arguments native request arguments
   * @param name required argument name
   * @return supplied string
   * @throws IllegalArgumentException if the argument is absent or not a string
   */
  private static String stringArgument(Map<String, Object> arguments, String name) {
    if (!(arguments.get(name) instanceof String value)) {
      throw new IllegalArgumentException("Expected string argument: " + name);
    }

    return value;
  }

  /**
   * Preserves the five native JSON default types required by the official fixture.
   *
   * @return explicit form schema
   */
  private static Map<String, Object> defaults() {
    return Map.of(
        "type",
        OBJECT,
        REQUIRED,
        List.of("name", "age", "score", "status", "verified"),
        PROPERTIES,
        Map.of(
            "name", Map.of("type", STRING, DEFAULT, "John Doe"),
            "age", Map.of("type", "integer", DEFAULT, 30),
            "score", Map.of("type", "number", DEFAULT, 95.5),
            "status",
                Map.of("type", STRING, "enum", List.of("active", "inactive"), DEFAULT, "active"),
            "verified", Map.of("type", "boolean", DEFAULT, true)));
  }

  /**
   * Describes single/multi-select and legacy enum forms without deprecated SDK classes.
   *
   * @return explicit form schema
   */
  private static Map<String, Object> enums() {
    return Map.of(
        "type",
        OBJECT,
        REQUIRED,
        List.of("untitledSingle", "titledSingle", "legacyEnum", "untitledMulti", "titledMulti"),
        PROPERTIES,
        Map.of(
            "untitledSingle", Map.of("type", STRING, "enum", List.of("option1", "option2")),
            "titledSingle",
                Map.of(
                    "type",
                    STRING,
                    "oneOf",
                    List.of(
                        Map.of(CONST, "value1", TITLE, "Collect at the counter"),
                        Map.of(CONST, "value2", TITLE, "Deliver to the destination"))),
            "legacyEnum",
                Map.of(
                    "type",
                    STRING,
                    "enum",
                    List.of("opt1", "opt2"),
                    "enumNames",
                    List.of("Normal handling", "Fragile handling")),
            "untitledMulti",
                Map.of(
                    "type",
                    "array",
                    "items",
                    Map.of("type", STRING, "enum", List.of("option1", "option2"))),
            "titledMulti",
                Map.of(
                    "type",
                    "array",
                    "items",
                    Map.of(
                        "anyOf",
                        List.of(
                            Map.of(CONST, "value1", TITLE, "Add collection instructions"),
                            Map.of(CONST, "value2", TITLE, "Add delivery instructions"))))));
  }

  /**
   * Describes pickup and courier alternatives while demonstrating raw JSON Schema preservation.
   *
   * @return explicit JSON Schema 2020-12 input schema
   */
  private static Map<String, Object> jsonSchema() {
    return Map.of(
        "$schema",
        "https://json-schema.org/draft/2020-12/schema",
        "type",
        OBJECT,
        "$defs",
        Map.of(
            "address",
            Map.of(
                "$anchor",
                "parcelDestination",
                "type",
                OBJECT,
                PROPERTIES,
                Map.of(
                    "postalCode", Map.of("type", STRING, MIN_LENGTH, 1),
                    "countryCode", Map.of("type", STRING, "pattern", "^[A-Z]{2}$")),
                REQUIRED,
                List.of("postalCode", "countryCode"),
                "additionalProperties",
                false)),
        PROPERTIES,
        Map.of(
            "recipient",
            Map.of("type", STRING, MIN_LENGTH, 1),
            DELIVERY,
            Map.of("type", STRING, "enum", List.of("pickup", "courier")),
            DESTINATION,
            Map.of("$ref", "#parcelDestination"),
            COLLECTION_POINT,
            Map.of("type", STRING, MIN_LENGTH, 1)),
        REQUIRED,
        List.of("recipient", DELIVERY),
        "allOf",
        List.of(
            Map.of(
                "anyOf",
                List.of(
                    Map.of(REQUIRED, List.of(DESTINATION)),
                    Map.of(REQUIRED, List.of(COLLECTION_POINT))))),
        "if",
        Map.of(PROPERTIES, Map.of(DELIVERY, Map.of(CONST, "pickup")), REQUIRED, List.of(DELIVERY)),
        "then",
        Map.of(REQUIRED, List.of(COLLECTION_POINT)),
        "else",
        Map.of(REQUIRED, List.of(DESTINATION)),
        "additionalProperties",
        false);
  }
}
