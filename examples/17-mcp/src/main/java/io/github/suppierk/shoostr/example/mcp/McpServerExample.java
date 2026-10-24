package io.github.suppierk.shoostr.example.mcp;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.mcp.Mcp;
import io.github.suppierk.shoostr.mcp.McpHttpSecurity;
import io.github.suppierk.shoostr.mcp.McpIdentity;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import reactor.core.publisher.Mono;

/** Builds a native MCP server with an explicitly mounted HTTP endpoint. */
public final class McpServerExample {
  /** Prevents construction of the factory. */
  private McpServerExample() {}

  /**
   * Creates an unstarted application with a synchronous MCP server.
   *
   * @return unstarted application
   */
  public static Shoostr application() {
    var mcp =
        Mcp.sync(
            publicSecurity(),
            transport ->
                McpServer.sync(transport)
                    .serverInfo("shoostr-guide", "1.0")
                    .requestTimeout(Duration.ofSeconds(5))
                    // Advertise features without subscriptions or catalog-change notifications.
                    .capabilities(
                        McpSchema.ServerCapabilities.builder()
                            .tools(false)
                            .resources(false, false)
                            .prompts(false)
                            .build())
                    .tools(
                        McpServerFeatures.SyncToolSpecification.builder()
                            .tool(greetingTool())
                            .callHandler((ignored, ignored2) -> greeting())
                            .build())
                    .resources(
                        new McpServerFeatures.SyncResourceSpecification(
                            guideResource(), (ignored3, request) -> guide(request.uri())))
                    .prompts(
                        new McpServerFeatures.SyncPromptSpecification(
                            explanationPrompt(), (ignored4, ignored5) -> explanation()))
                    .build());
    // Installation owns the server lifecycle; the HTTP mount is still an explicit registration.
    return new Shoostr().extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));
  }

  /**
   * Creates an unstarted application with an asynchronous MCP server.
   *
   * @return unstarted application
   */
  public static Shoostr asyncApplication() {
    // Publishers defer these results; they do not automatically offload blocking work.
    var mcp =
        Mcp.async(
            publicSecurity(),
            transport ->
                McpServer.async(transport)
                    .serverInfo("shoostr-guide", "1.0")
                    .requestTimeout(Duration.ofSeconds(5))
                    .capabilities(
                        McpSchema.ServerCapabilities.builder()
                            .tools(false)
                            .resources(false, false)
                            .prompts(false)
                            .build())
                    .tools(
                        McpServerFeatures.AsyncToolSpecification.builder()
                            .tool(greetingTool())
                            .callHandler(
                                (ignored6, ignored7) ->
                                    Mono.fromSupplier(McpServerExample::greeting))
                            .build())
                    .resources(
                        new McpServerFeatures.AsyncResourceSpecification(
                            guideResource(),
                            (ignored8, request) -> Mono.fromSupplier(() -> guide(request.uri()))))
                    .prompts(
                        new McpServerFeatures.AsyncPromptSpecification(
                            explanationPrompt(),
                            (ignored9, ignored10) ->
                                Mono.fromSupplier(McpServerExample::explanation)))
                    .build());
    return new Shoostr().extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));
  }

  /**
   * Allows a deliberately public catalog, with no token-derived identity or private state.
   *
   * @return explicit public transport policy
   */
  private static McpHttpSecurity publicSecurity() {
    // A shared identity is appropriate only for this deliberately public, nonsecret catalog.
    return new McpHttpSecurity(
        Set.of("https://client.example"),
        ignored11 -> new McpIdentity("public-catalog", McpTransportContext.EMPTY, null),
        (ignored12, ignored13) -> {});
  }

  /**
   * Describes a native SDK tool with an explicit JSON input schema.
   *
   * @return tool description
   */
  private static McpSchema.Tool greetingTool() {
    return McpSchema.Tool.builder("greet", Map.of("type", "object", "properties", Map.of()))
        .description("Return a greeting")
        .build();
  }

  /**
   * Returns the tool's protocol content, without an HTTP-specific response type.
   *
   * @return native tool result
   */
  private static McpSchema.CallToolResult greeting() {
    return McpSchema.CallToolResult.builder().addTextContent("Hello from Shoostr").build();
  }

  /**
   * Describes a resource whose URI belongs to MCP rather than the HTTP router.
   *
   * @return resource description
   */
  private static McpSchema.Resource guideResource() {
    // The native SDK resolves this resource URI; there is no matching HTTP /guide route.
    return McpSchema.Resource.builder("guide://start", "Getting started")
        .mimeType("text/plain")
        .build();
  }

  /**
   * Returns the content of the selected native resource.
   *
   * @param uri requested resource URI
   * @return native resource result
   */
  private static McpSchema.ReadResourceResult guide(String uri) {
    return McpSchema.ReadResourceResult.builder(
            List.of(
                McpSchema.TextResourceContents.builder(
                        uri, "Read resources for context; call tools to perform operations.")
                    .mimeType("text/plain")
                    .build()))
        .build();
  }

  /**
   * Describes a reusable prompt without requesting model generation.
   *
   * @return prompt description
   */
  private static McpSchema.Prompt explanationPrompt() {
    return McpSchema.Prompt.builder("explain").description("Explain MCP feature types").build();
  }

  /**
   * Supplies a prompt message for the client to use in its own conversation.
   *
   * @return native prompt result
   */
  private static McpSchema.GetPromptResult explanation() {
    return McpSchema.GetPromptResult.builder(
            List.of(
                McpSchema.PromptMessage.builder(
                        McpSchema.Role.USER,
                        McpSchema.TextContent.builder(
                                "Explain the difference between MCP tools and resources.")
                            .build())
                    .build()))
        .build();
  }
}
