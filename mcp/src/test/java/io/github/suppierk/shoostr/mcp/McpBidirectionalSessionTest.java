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
    var mcp =
        Mcp.sync(
            new McpHttpSecurity(
                Set.of(),
                _ -> new McpIdentity("public", McpTransportContext.EMPTY, null),
                (_, _) -> {}),
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
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app);
        var client =
            McpClient.sync(
                    HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                        .endpoint("/mcp")
                        .build())
                .requestTimeout(Duration.ofSeconds(5))
                .initializationTimeout(Duration.ofSeconds(5))
                .build();
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
}
