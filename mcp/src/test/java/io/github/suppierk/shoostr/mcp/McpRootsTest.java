package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
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
    var mcp =
        Mcp.sync(
            new McpHttpSecurity(
                Set.of(),
                _ -> new McpIdentity("public", McpTransportContext.EMPTY, null),
                (_, _) -> {}),
            transport ->
                McpServer.sync(transport)
                    .serverInfo("roots", "1.0")
                    .requestTimeout(Duration.ofSeconds(5))
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .rootsChangeHandler(
                        (_, roots) -> {
                          if (roots.contains(second)) {
                            changed.complete(roots);
                          }
                        })
                    .tools(
                        McpServerFeatures.SyncToolSpecification.builder()
                            .tool(McpSchema.Tool.builder("roots", Map.of("type", "object")).build())
                            .callHandler(
                                (exchange, _) ->
                                    McpSchema.CallToolResult.builder()
                                        .addTextContent(exchange.listRoots().roots().toString())
                                        .build())
                            .build())
                    .build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app);
        var client =
            McpClient.sync(
                    HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                        .endpoint("/mcp")
                        .build())
                .capabilities(McpSchema.ClientCapabilities.builder().roots(true).build())
                .roots(first)
                .requestTimeout(Duration.ofSeconds(5))
                .initializationTimeout(Duration.ofSeconds(5))
                .build()) {
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
}
