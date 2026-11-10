package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpClient.SyncSpec;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class McpBidirectionalSessionTest {
  @Test
  @Timeout(20)
  void correlatesParallelNativeClientRequestsWithinOneSession() throws Exception {
    var mcp = createMcpForCorrelatesParallelNativeClientRequestsWithinOneSession();
    var app = new Shoostr();
    app.routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app);
        var client = openClientForCorrelatesParallelNativeClientRequestsWithinOneSession(test);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      client.initialize();
      var replies = new ArrayList<Future<McpSchema.CallToolResult>>();
      for (var index = 0; index < 6; index++) {
        var marker = "call-" + index;
        replies.add(
            executor.submit(
                () ->
                    client.callTool(
                        McpSchema.CallToolRequest.builder("ping")
                            .arguments(Map.of("marker", marker))
                            .build())));
      }
      for (var index = 0; index < replies.size(); index++) {
        var result = replies.get(index).get(5, TimeUnit.SECONDS);

        assertEquals(
            "call-" + index,
            assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst()).text());
      }

      assertTrue(client.closeGracefully());
    }
  }

  private Mcp<McpSyncServer> createMcpForCorrelatesParallelNativeClientRequestsWithinOneSession() {
    return Mcp.sync(
        new McpHttpSecurity(
            Set.of(),
            ignored -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (ignoredMcpIdentity, ignoredJSONRPCMessage) -> {}),
        transport ->
            McpServer.sync(transport)
                .serverInfo("bidirectional", "1.0")
                .requestTimeout(Duration.ofSeconds(5))
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(
                    McpServerFeatures.SyncToolSpecification.builder()
                        .tool(McpSchema.Tool.builder("ping", Map.of("type", "object")).build())
                        .callHandler(
                            (exchange, call) -> {
                              exchange.ping();

                              return McpSchema.CallToolResult.builder()
                                  .addTextContent((String) call.arguments().get("marker"))
                                  .build();
                            })
                        .build())
                .build());
  }

  private McpSyncClient openClientForCorrelatesParallelNativeClientRequestsWithinOneSession(
      TestServer test) {
    return clientPropertiesForCorrelatesParallelNativeClientRequestsWithinOneSession(test).build();
  }

  private SyncSpec clientPropertiesForCorrelatesParallelNativeClientRequestsWithinOneSession(
      TestServer test) {
    return McpClient.sync(
            HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                .endpoint("/mcp")
                .build())
        .requestTimeout(Duration.ofSeconds(5))
        .initializationTimeout(Duration.ofSeconds(5));
  }
}
