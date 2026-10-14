package io.github.suppierk.shoostr.example.mcp;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.mcp.Mcp;
import io.github.suppierk.shoostr.mcp.McpHttpSecurity;
import io.github.suppierk.shoostr.mcp.McpIdentity;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/** Configures the native SDK and its HTTP security separately from application startup. */
final class McpConfiguration {
  /** Prevents construction of the configuration factory. */
  private McpConfiguration() {}

  /**
   * Creates an owned MCP extension using the SDK's native feature registrations.
   *
   * @param options listener settings supplying the local public URL's port
   * @return extension to install before mounting its routes
   */
  static Mcp<McpSyncServer> create(Options options) {
    // Shoostr's mcp module provides Origin checks, request identity and pre-dispatch permissions.
    // This public demo uses a fixed identity without requiring HTTP authentication.
    var security =
        new McpHttpSecurity(
            Set.of(
                System.getenv()
                    .getOrDefault("MCP_PUBLIC_ORIGIN", "http://127.0.0.1:" + options.port())),
            _ -> new McpIdentity("public-example", McpTransportContext.EMPTY, null),
            (_, _) -> {});
    // Shoostr owns the HTTP transport; the official SDK configures and dispatches MCP operations.
    return Mcp.sync(
        security,
        transport ->
            McpServer.sync(transport)
                .serverInfo("shoostr-mcp-example", "1.0")
                .requestTimeout(Duration.ofSeconds(30))
                // SDK capabilities advertise the features implemented by the registrations below.
                .capabilities(
                    McpSchema.ServerCapabilities.builder()
                        .tools(false)
                        .resources(true, false)
                        .prompts(false)
                        .completions()
                        .build())
                // CompatibilityFixtures is example-local; these are native SDK feature definitions.
                .tools(CompatibilityFixtures.tools())
                .resources(CompatibilityFixtures.resources())
                .resourceTemplates(CompatibilityFixtures.resourceTemplates())
                .prompts(CompatibilityFixtures.prompts())
                // SDK completions suggest values for prompt/resource arguments, not HTTP routes.
                .completions(
                    new McpServerFeatures.SyncCompletionSpecification(
                        new McpSchema.PromptReference("test_prompt_with_arguments"),
                        (_, _) ->
                            new McpSchema.CompleteResult(
                                new McpSchema.CompleteResult.CompleteCompletion(
                                    List.of(), 0, false))))
                .build());
  }
}
