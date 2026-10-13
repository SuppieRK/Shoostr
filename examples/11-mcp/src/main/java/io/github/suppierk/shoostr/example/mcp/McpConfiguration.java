package io.github.suppierk.shoostr.example.mcp;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.mcp.Mcp;
import io.github.suppierk.shoostr.mcp.McpHttpSecurity;
import io.github.suppierk.shoostr.mcp.McpIdentity;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
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
  static Mcp<?> create(Options options) {
    var security =
        new McpHttpSecurity(
            Set.of(
                System.getenv()
                    .getOrDefault("MCP_PUBLIC_ORIGIN", "http://127.0.0.1:" + options.port())),
            _ -> new McpIdentity("public-example", McpTransportContext.EMPTY, null),
            (_, _) -> {});
    return Mcp.sync(
        security,
        transport ->
            McpServer.sync(transport)
                .serverInfo("shoostr-mcp-example", "1.0")
                .requestTimeout(Duration.ofSeconds(30))
                .capabilities(
                    McpSchema.ServerCapabilities.builder()
                        .tools(false)
                        .resources(true, false)
                        .prompts(false)
                        .completions()
                        .build())
                .tools(CompatibilityFixtures.tools())
                .resources(CompatibilityFixtures.resources())
                .resourceTemplates(CompatibilityFixtures.resourceTemplates())
                .prompts(CompatibilityFixtures.prompts())
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
