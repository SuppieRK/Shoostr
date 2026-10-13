package io.github.suppierk.shoostr.example.mcp.oauth;

import io.github.suppierk.shoostr.mcp.Mcp;
import io.github.suppierk.shoostr.mcp.McpHttpSecurity;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Configures native SDK tools independently from HTTP routes and authentication. */
final class OAuthMcpConfiguration {
  /** Prevents construction of the SDK configuration factory. */
  private OAuthMcpConfiguration() {}

  /**
   * Creates one app-owned MCP server with an explicitly supplied HTTP policy.
   *
   * @param security eager identity and operation-permission policy
   * @return MCP extension to install before registering its routes
   */
  static Mcp<?> create(McpHttpSecurity security) {
    // JDK atomic state belongs to this example; concurrent SDK tool calls share the counter.
    var calls = new AtomicInteger();
    // Shoostr provides HTTP transport/security; McpServer and feature descriptors are official SDK
    // APIs.
    return Mcp.sync(
        security,
        transport ->
            McpServer.sync(transport)
                .serverInfo("shoostr-mcp-oauth-example", "1.0")
                .requestTimeout(Duration.ofSeconds(30))
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                // A tool is an SDK operation with its own input schema and callback, not an HTTP
                // route.
                .tools(
                    McpServerFeatures.SyncToolSpecification.builder()
                        .tool(
                            McpSchema.Tool.builder("increment", Map.of("type", "object"))
                                .description("Records a tool invocation; requires mcp:tools")
                                .build())
                        .callHandler(
                            (exchange, _) ->
                                McpSchema.CallToolResult.builder()
                                    .addTextContent(
                                        "Tool invocation "
                                            + calls.incrementAndGet()
                                            + " by "
                                            + exchange.transportContext().get("subject"))
                                    .build())
                        .build())
                .build());
  }
}
