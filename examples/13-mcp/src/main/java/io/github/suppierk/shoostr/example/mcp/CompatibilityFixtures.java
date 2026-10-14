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

/** Native compatibility catalog; adapted fixture data is credited in THIRD-PARTY-NOTICES.md. */
final class CompatibilityFixtures {
  private static final String OBJECT = "object";
  private static final String PROPERTIES = "properties";
  private static final String STRING = "string";
  private static final String PROMPT = "prompt";
  private static final String REQUIRED = "required";
  private static final String MESSAGE = "message";
  private static final String EMAIL = "email";
  private static final String CONTENT_SEPARATOR = "; content: ";
  private static final String ACTION_PREFIX = "Action: ";
  private static final String DEFAULT = "default";
  private static final String CONST = "const";
  private static final String TITLE = "title";
  private static final String CONTACT_METHOD = "contactMethod";
  private static final String PHONE = "phone";
  private static final MediaType PNG_TYPE = MediaType.of("image/png");
  private static final Map<String, Object> NO_ARGUMENTS =
      Map.of("type", OBJECT, PROPERTIES, Map.of());
  private static final String PNG =
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==";
  private static final String WAV = "UklGRiQAAABXQVZFZm10IBAAAAABAAEAQB8AAAB9AAACABAAZGF0YQAAAAA=";

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
                    .addTextContent("This is a simple text response for testing.")
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
                                            "test://embedded-resource", "Embedded resource content")
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
                            McpSchema.TextContent.builder("Mixed native content").build(),
                            McpSchema.ImageContent.builder(PNG, PNG_TYPE.value()).build(),
                            McpSchema.EmbeddedResource.builder(
                                    McpSchema.TextResourceContents.builder(
                                            "test://mixed-content-resource", "{\"test\":\"data\"}")
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
                    .addTextContent("This tool intentionally returns an error for testing")
                    .build()),
        tool(
            "test_tool_with_logging",
            NO_ARGUMENTS,
            (exchange, _) -> {
              // The SDK exchange sends protocol notifications to the connected MCP client.
              for (var message : List.of("Started", "Processing", "Completed")) {
                exchange.loggingNotification(
                    McpSchema.LoggingMessageNotification.builder(
                            McpSchema.LoggingLevel.INFO, message)
                        .build());
              }

              return McpSchema.CallToolResult.builder()
                  .addTextContent("Logged three events")
                  .build();
            }),
        tool(
            "test_tool_with_progress",
            NO_ARGUMENTS,
            (exchange, request) -> {
              var metadata = request.meta();
              var token = metadata == null ? null : metadata.get("progressToken");
              if (token != null) {
                for (var progress : List.of(0.0, 50.0, 100.0)) {
                  exchange.progressNotification(
                      McpSchema.ProgressNotification.builder(token, progress).total(100.0).build());
                }
              }

              return McpSchema.CallToolResult.builder()
                  .addTextContent("Completed progress")
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
                              100)
                          .build());
              return McpSchema.CallToolResult.builder()
                  .addTextContent(
                      "LLM response: " + ((McpSchema.TextContent) sampled.content()).text())
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
                                      Map.of("type", STRING),
                                      EMAIL,
                                      Map.of("type", STRING)),
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
                      McpSchema.ElicitFormRequest.builder("Complete the default values", defaults())
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
                      McpSchema.ElicitFormRequest.builder("Select your preferences", enums())
                          .build());
              return McpSchema.CallToolResult.builder()
                  .addTextContent(
                      ACTION_PREFIX + result.action() + CONTENT_SEPARATOR + result.content())
                  .build();
            }),
        tool(
            "json_schema_2020_12_tool",
            jsonSchema(),
            (_, _) -> McpSchema.CallToolResult.builder().addTextContent("ok").build()));
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
            McpSchema.Resource.builder("test://static-text", "Static text")
                .description("A text resource")
                .mimeType(MediaType.TEXT_PLAIN.value())
                .build(),
            (_, request) ->
                McpSchema.ReadResourceResult.builder(
                        List.of(
                            McpSchema.TextResourceContents.builder(
                                    request.uri(), "Static text content")
                                .mimeType(MediaType.TEXT_PLAIN.value())
                                .build()))
                    .build()),
        new McpServerFeatures.SyncResourceSpecification(
            McpSchema.Resource.builder("test://static-binary", "Static image")
                .description("A one-pixel image")
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
            McpSchema.Resource.builder("test://watched-resource", "Watched text")
                .description("A subscribable resource")
                .mimeType(MediaType.TEXT_PLAIN.value())
                .build(),
            (_, request) ->
                McpSchema.ReadResourceResult.builder(
                        List.of(
                            McpSchema.TextResourceContents.builder(request.uri(), "Watched content")
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
            McpSchema.ResourceTemplate.builder("test://template/{id}/data", "Template data")
                .description("JSON data for the requested ID")
                .mimeType(MediaType.APPLICATION_JSON.value())
                .build(),
            (_, request) -> {
              var uri = request.uri();
              var id = uri.substring("test://template/".length(), uri.lastIndexOf('/'));

              try {
                var json =
                    McpJsonDefaults.getMapper()
                        .writeValueAsString(
                            Map.of("id", id, "templateTest", true, "data", "Data for " + id));
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
            McpSchema.Prompt.builder("test_simple_prompt").description("A text prompt").build(),
            (_, _) ->
                McpSchema.GetPromptResult.builder(
                        List.of(
                            McpSchema.PromptMessage.builder(
                                    McpSchema.Role.USER,
                                    McpSchema.TextContent.builder("A simple prompt").build())
                                .build()))
                    .build()),
        new McpServerFeatures.SyncPromptSpecification(
            McpSchema.Prompt.builder("test_prompt_with_arguments")
                .description("A prompt with two explicit substitutions")
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
                                            "Arguments: "
                                                + request.arguments().get("arg1")
                                                + "; "
                                                + request.arguments().get("arg2"))
                                        .build())
                                .build()))
                    .build()),
        new McpServerFeatures.SyncPromptSpecification(
            McpSchema.Prompt.builder("test_prompt_with_embedded_resource")
                .description("A prompt containing the supplied resource URI")
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
                                                    "Embedded prompt resource")
                                                .mimeType(MediaType.TEXT_PLAIN.value())
                                                .build())
                                        .build())
                                .build()))
                    .build()),
        new McpServerFeatures.SyncPromptSpecification(
            McpSchema.Prompt.builder("test_prompt_with_image")
                .description("A prompt containing an image")
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
                .description("Conformance fixture: " + name)
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
                Map.of(
                    "type",
                    STRING,
                    "enum",
                    List.of("active", "inactive", "pending"),
                    DEFAULT,
                    "active"),
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
            "untitledSingle",
                Map.of("type", STRING, "enum", List.of("option1", "option2", "option3")),
            "titledSingle",
                Map.of(
                    "type",
                    STRING,
                    "oneOf",
                    List.of(
                        Map.of(CONST, "value1", TITLE, "First Option"),
                        Map.of(CONST, "value2", TITLE, "Second Option"),
                        Map.of(CONST, "value3", TITLE, "Third Option"))),
            "legacyEnum",
                Map.of(
                    "type",
                    STRING,
                    "enum",
                    List.of("opt1", "opt2", "opt3"),
                    "enumNames",
                    List.of("Option One", "Option Two", "Option Three")),
            "untitledMulti",
                Map.of(
                    "type",
                    "array",
                    "items",
                    Map.of("type", STRING, "enum", List.of("option1", "option2", "option3"))),
            "titledMulti",
                Map.of(
                    "type",
                    "array",
                    "items",
                    Map.of(
                        "anyOf",
                        List.of(
                            Map.of(CONST, "value1", TITLE, "First Choice"),
                            Map.of(CONST, "value2", TITLE, "Second Choice"),
                            Map.of(CONST, "value3", TITLE, "Third Choice"))))));
  }

  /**
   * Keeps the raw JSON Schema dialect, references and applicators intact on the wire.
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
                "addressDef",
                "type",
                OBJECT,
                PROPERTIES,
                Map.of("street", Map.of("type", STRING), "city", Map.of("type", STRING)))),
        PROPERTIES,
        Map.of(
            "name",
            Map.of("type", STRING),
            "address",
            Map.of("$ref", "#/$defs/address"),
            CONTACT_METHOD,
            Map.of("type", STRING, "enum", List.of(PHONE, EMAIL)),
            PHONE,
            Map.of("type", STRING),
            EMAIL,
            Map.of("type", STRING)),
        "allOf",
        List.of(
            Map.of(
                "anyOf",
                List.of(Map.of(REQUIRED, List.of(PHONE)), Map.of(REQUIRED, List.of(EMAIL))))),
        "if",
        Map.of(
            PROPERTIES,
            Map.of(CONTACT_METHOD, Map.of(CONST, PHONE)),
            REQUIRED,
            List.of(CONTACT_METHOD)),
        "then",
        Map.of(REQUIRED, List.of(PHONE)),
        "else",
        Map.of(REQUIRED, List.of(EMAIL)),
        "additionalProperties",
        false);
  }
}
