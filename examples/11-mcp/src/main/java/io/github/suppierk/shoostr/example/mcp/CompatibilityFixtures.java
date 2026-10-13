package io.github.suppierk.shoostr.example.mcp;

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
  private static final Map<String, Object> NO_ARGUMENTS =
      Map.of("type", "object", "properties", Map.of());
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
                    .content(List.of(McpSchema.ImageContent.builder(PNG, "image/png").build()))
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
                                        .mimeType("text/plain")
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
                            McpSchema.ImageContent.builder(PNG, "image/png").build(),
                            McpSchema.EmbeddedResource.builder(
                                    McpSchema.TextResourceContents.builder(
                                            "test://mixed-content-resource", "{\"test\":\"data\"}")
                                        .mimeType("application/json")
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
                "object",
                "properties",
                Map.of("prompt", Map.of("type", "string")),
                "required",
                List.of("prompt")),
            (exchange, request) -> {
              // Sampling asks the MCP client to generate a message; this server supplies no LLM.
              var sampled =
                  exchange.createMessage(
                      McpSchema.CreateMessageRequest.builder(
                              List.of(
                                  McpSchema.SamplingMessage.builder(
                                          McpSchema.Role.USER,
                                          McpSchema.TextContent.builder(
                                                  stringArgument(request.arguments(), "prompt"))
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
                "object",
                "properties",
                Map.of("message", Map.of("type", "string")),
                "required",
                List.of("message")),
            (exchange, request) -> {
              // Elicitation asks the MCP client to collect user input using this explicit form
              // schema.
              var result =
                  exchange.createElicitation(
                      McpSchema.ElicitFormRequest.builder(
                              stringArgument(request.arguments(), "message"),
                              Map.of(
                                  "type",
                                  "object",
                                  "properties",
                                  Map.of(
                                      "username",
                                      Map.of("type", "string"),
                                      "email",
                                      Map.of("type", "string")),
                                  "required",
                                  List.of("username", "email")))
                          .build());
              return McpSchema.CallToolResult.builder()
                  .addTextContent("Action: " + result.action() + "; content: " + result.content())
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
                  .addTextContent("Action: " + result.action() + "; content: " + result.content())
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
                  .addTextContent("Action: " + result.action() + "; content: " + result.content())
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
                .mimeType("text/plain")
                .build(),
            (_, request) ->
                McpSchema.ReadResourceResult.builder(
                        List.of(
                            McpSchema.TextResourceContents.builder(
                                    request.uri(), "Static text content")
                                .mimeType("text/plain")
                                .build()))
                    .build()),
        new McpServerFeatures.SyncResourceSpecification(
            McpSchema.Resource.builder("test://static-binary", "Static image")
                .description("A one-pixel image")
                .mimeType("image/png")
                .build(),
            (_, request) ->
                McpSchema.ReadResourceResult.builder(
                        List.of(
                            McpSchema.BlobResourceContents.builder(request.uri(), PNG)
                                .mimeType("image/png")
                                .build()))
                    .build()),
        new McpServerFeatures.SyncResourceSpecification(
            McpSchema.Resource.builder("test://watched-resource", "Watched text")
                .description("A subscribable resource")
                .mimeType("text/plain")
                .build(),
            (_, request) ->
                McpSchema.ReadResourceResult.builder(
                        List.of(
                            McpSchema.TextResourceContents.builder(request.uri(), "Watched content")
                                .mimeType("text/plain")
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
                .mimeType("application/json")
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
                                .mimeType("application/json")
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
                                                .mimeType("text/plain")
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
                                    McpSchema.ImageContent.builder(PNG, "image/png").build())
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
        "object",
        "required",
        List.of("name", "age", "score", "status", "verified"),
        "properties",
        Map.of(
            "name", Map.of("type", "string", "default", "John Doe"),
            "age", Map.of("type", "integer", "default", 30),
            "score", Map.of("type", "number", "default", 95.5),
            "status",
                Map.of(
                    "type",
                    "string",
                    "enum",
                    List.of("active", "inactive", "pending"),
                    "default",
                    "active"),
            "verified", Map.of("type", "boolean", "default", true)));
  }

  /**
   * Describes single/multi-select and legacy enum forms without deprecated SDK classes.
   *
   * @return explicit form schema
   */
  private static Map<String, Object> enums() {
    return Map.of(
        "type",
        "object",
        "required",
        List.of("untitledSingle", "titledSingle", "legacyEnum", "untitledMulti", "titledMulti"),
        "properties",
        Map.of(
            "untitledSingle",
                Map.of("type", "string", "enum", List.of("option1", "option2", "option3")),
            "titledSingle",
                Map.of(
                    "type",
                    "string",
                    "oneOf",
                    List.of(
                        Map.of("const", "value1", "title", "First Option"),
                        Map.of("const", "value2", "title", "Second Option"),
                        Map.of("const", "value3", "title", "Third Option"))),
            "legacyEnum",
                Map.of(
                    "type",
                    "string",
                    "enum",
                    List.of("opt1", "opt2", "opt3"),
                    "enumNames",
                    List.of("Option One", "Option Two", "Option Three")),
            "untitledMulti",
                Map.of(
                    "type",
                    "array",
                    "items",
                    Map.of("type", "string", "enum", List.of("option1", "option2", "option3"))),
            "titledMulti",
                Map.of(
                    "type",
                    "array",
                    "items",
                    Map.of(
                        "anyOf",
                        List.of(
                            Map.of("const", "value1", "title", "First Choice"),
                            Map.of("const", "value2", "title", "Second Choice"),
                            Map.of("const", "value3", "title", "Third Choice"))))));
  }

  /**
   * Keeps the raw JSON Schema dialect, references and applicators intact on the wire.
   *
   * @return explicit JSON Schema 2020-12 input schema
   */
  private static Map<String, Object> jsonSchema() {
    return Map.of(
        "$schema", "https://json-schema.org/draft/2020-12/schema",
        "type", "object",
        "$defs",
            Map.of(
                "address",
                Map.of(
                    "$anchor",
                    "addressDef",
                    "type",
                    "object",
                    "properties",
                    Map.of("street", Map.of("type", "string"), "city", Map.of("type", "string")))),
        "properties",
            Map.of(
                "name",
                Map.of("type", "string"),
                "address",
                Map.of("$ref", "#/$defs/address"),
                "contactMethod",
                Map.of("type", "string", "enum", List.of("phone", "email")),
                "phone",
                Map.of("type", "string"),
                "email",
                Map.of("type", "string")),
        "allOf",
            List.of(
                Map.of(
                    "anyOf",
                    List.of(
                        Map.of("required", List.of("phone")),
                        Map.of("required", List.of("email"))))),
        "if",
            Map.of(
                "properties",
                Map.of("contactMethod", Map.of("const", "phone")),
                "required",
                List.of("contactMethod")),
        "then", Map.of("required", List.of("phone")),
        "else", Map.of("required", List.of("email")),
        "additionalProperties", false);
  }
}
