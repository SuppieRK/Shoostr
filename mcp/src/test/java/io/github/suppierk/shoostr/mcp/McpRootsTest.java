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
import io.modelcontextprotocol.spec.McpSchema.Root;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class McpRootsTest {
  @Test
  @Timeout(20)
  void makesChangedClientRootsAvailableToTheNativeServer() throws Exception {
    var first = new McpSchema.Root("file:///workspace/first", "first");
    var second = new McpSchema.Root("file:///workspace/second", "second");
    var changed = new CompletableFuture<List<McpSchema.Root>>();
    var mcp = createMcpForMakesChangedClientRootsAvailableToTheNativeServer(second, changed);
    var app = new Shoostr();
    app.routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app);
        var client = openClientForMakesChangedClientRootsAvailableToTheNativeServer(test, first)) {
      client.initialize();
      client.addRoot(second);

      assertEquals(List.of(first, second), changed.get(5, TimeUnit.SECONDS));

      var result =
          client.callTool(McpSchema.CallToolRequest.builder("roots").arguments(Map.of()).build());

      assertEquals(
          List.of(first, second).toString(),
          assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst()).text());
      assertTrue(client.closeGracefully());
    }
  }

  private Mcp<McpSyncServer> createMcpForMakesChangedClientRootsAvailableToTheNativeServer(
      Root second, CompletableFuture<List<Root>> changed) {
    return Mcp.sync(
        new McpHttpSecurity(
            Set.of(),
            ignored -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (ignoredMcpIdentity, ignoredJsonRpcMessage) -> {}),
        transport ->
            McpServer.sync(transport)
                .serverInfo("roots", "1.0")
                .requestTimeout(Duration.ofSeconds(5))
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .rootsChangeHandler((ignoredMcpSyncServerExchange, roots) -> {
                  if (roots.contains(second)) {
                    changed.complete(roots);
                  }
                })
                .tools(
                    McpServerFeatures.SyncToolSpecification.builder()
                        .tool(McpSchema.Tool.builder("roots", Map.of("type", "object")).build())
                        .callHandler((exchange, ignoredCallToolRequest) ->
                            McpSchema.CallToolResult.builder()
                                .addTextContent(exchange.listRoots().roots().toString())
                                .build())
                        .build())
                .build());
  }

  private McpSyncClient openClientForMakesChangedClientRootsAvailableToTheNativeServer(
      TestServer test, Root first) {
    return clientPropertiesForMakesChangedClientRootsAvailableToTheNativeServer(test, first)
        .build();
  }

  private SyncSpec clientPropertiesForMakesChangedClientRootsAvailableToTheNativeServer(
      TestServer test, Root first) {
    return McpClient.sync(
            HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                .endpoint("/mcp")
                .build())
        .capabilities(McpSchema.ClientCapabilities.builder().roots(true).build())
        .roots(first)
        .requestTimeout(Duration.ofSeconds(5))
        .initializationTimeout(Duration.ofSeconds(5));
  }
}
