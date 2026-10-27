package io.github.suppierk.shoostr.example.mcp;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.mcp.Mcp;
import io.github.suppierk.shoostr.mcp.McpHttpSecurity;
import io.github.suppierk.shoostr.mcp.McpIdentity;
import io.github.suppierk.shoostr.mcp.McpOperationLimits;
import io.github.suppierk.shoostr.mcp.McpSessionLimits;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;

/** Reads a nonsecret service status with explicitly bounded managed execution. */
public final class McpOperationsExample {
  /** Prevents instantiation of this example factory. */
  private McpOperationsExample() {}

  /**
   * Creates an unstarted public application with independent ordinary and control policies.
   *
   * @param readStatus blocking supplier of a nonsecret status snapshot
   * @param ordinary operation and managed execution capacities and deadline
   * @param control independently reserved reply and cancellation capacities and deadline
   * @return application whose eventual owner closes its MCP resources
   */
  public static Shoostr application(
      Callable<String> readStatus, McpOperationLimits ordinary, McpOperationLimits control) {
    Objects.requireNonNull(readStatus);
    var security =
        new McpHttpSecurity(
            Set.of(),
            ignored -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (identity, message) -> {});
    var mcp =
        Mcp.async(
            security,
            McpSessionLimits.defaults(),
            ordinary,
            control,
            (transport, work) ->
                McpServer.async(transport)
                    .serverInfo("bounded-status", "1.0")
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .tools(
                        McpServerFeatures.AsyncToolSpecification.builder()
                            .tool(
                                McpSchema.Tool.builder("status", Map.of("type", "object")).build())
                            // Compose the publisher so managed work retains operation ownership.
                            .callHandler(
                                (exchange, request) ->
                                    // The reply can progress while this request holds ordinary
                                    // capacity.
                                    exchange
                                        .ping()
                                        .then(
                                            work.call(
                                                () ->
                                                    McpSchema.CallToolResult.builder()
                                                        .addTextContent(readStatus.call())
                                                        .build())))
                            .build())
                    .build());
    var app = new Shoostr();
    return app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));
  }
}
