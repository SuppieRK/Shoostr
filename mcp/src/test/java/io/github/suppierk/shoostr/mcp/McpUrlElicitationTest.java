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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class McpUrlElicitationTest {
  @Test
  @Timeout(20)
  void deliversNativeUrlElicitationCompletionAfterTheToolResponseEnded() throws Exception {
    var session = new CompletableFuture<String>();
    var completed = new CompletableFuture<McpSchema.ElicitationCompleteNotification>();
    var listening = new CountDownLatch(1);
    var mcp =
        Mcp.sync(
            new McpHttpSecurity(
                Set.of(),
                _ -> new McpIdentity("public", McpTransportContext.EMPTY, null),
                (_, _) -> {}),
            transport ->
                McpServer.sync(transport)
                    .requestTimeout(Duration.ofSeconds(5))
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .tools(
                        McpServerFeatures.SyncToolSpecification.builder()
                            .tool(
                                McpSchema.Tool.builder("authorize", Map.of("type", "object"))
                                    .build())
                            .callHandler(
                                (exchange, _) -> {
                                  session.complete(exchange.sessionId());
                                  var result =
                                      exchange.createElicitation(
                                          McpSchema.ElicitUrlRequest.builder(
                                                  "Authorize the external service",
                                                  "https://service.example/authorize",
                                                  "authorization-complete")
                                              .build());
                                  return McpSchema.CallToolResult.builder()
                                      .addTextContent(result.action().name())
                                      .build();
                                })
                            .build())
                    .build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));
    app.beforeResponseFlush(
        (request, _) -> {
          if ("GET".equals(request.method())) {
            listening.countDown();
          }
        });

    try (var test = TestServer.start(app);
        var client =
            McpClient.sync(
                    HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                        .endpoint("/mcp")
                        .build())
                .capabilities(
                    McpSchema.ClientCapabilities.builder().elicitation(false, true).build())
                .urlElicitation(
                    _ ->
                        McpSchema.ElicitResult.builder(McpSchema.ElicitResult.Action.ACCEPT)
                            .build())
                .elicitationCompleteConsumer(completed::complete)
                .requestTimeout(Duration.ofSeconds(5))
                .initializationTimeout(Duration.ofSeconds(5))
                .build()) {
      client.initialize();
      assertTrue(listening.await(5, TimeUnit.SECONDS));
      var result =
          client.callTool(
              McpSchema.CallToolRequest.builder("authorize").arguments(Map.of()).build());
      assertEquals(
          "ACCEPT",
          assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst()).text());

      mcp.server()
          .sendElicitationComplete(
              session.get(5, TimeUnit.SECONDS),
              new McpSchema.ElicitationCompleteNotification("authorization-complete"));
      assertEquals("authorization-complete", completed.get(5, TimeUnit.SECONDS).elicitationId());
      assertTrue(client.closeGracefully());
    }
  }

  @ParameterizedTest
  @EnumSource(McpSchema.ElicitResult.Action.class)
  @Timeout(20)
  void preservesTheNativeClientsUrlElicitationDecision(McpSchema.ElicitResult.Action action)
      throws Exception {
    var received = new CompletableFuture<McpSchema.ElicitUrlRequest>();
    var mcp =
        Mcp.sync(
            new McpHttpSecurity(
                Set.of(),
                _ -> new McpIdentity("public", McpTransportContext.EMPTY, null),
                (_, _) -> {}),
            transport ->
                McpServer.sync(transport)
                    .serverInfo("url-elicitation", "1.0")
                    .requestTimeout(Duration.ofSeconds(5))
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .tools(
                        McpServerFeatures.SyncToolSpecification.builder()
                            .tool(
                                McpSchema.Tool.builder("authorize", Map.of("type", "object"))
                                    .build())
                            .callHandler(
                                (exchange, _) -> {
                                  var result =
                                      exchange.createElicitation(
                                          McpSchema.ElicitUrlRequest.builder(
                                                  "Authorize the external service",
                                                  "https://service.example/authorize",
                                                  "authorization-1")
                                              .build());
                                  return McpSchema.CallToolResult.builder()
                                      .addTextContent(result.action().name())
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
                .capabilities(
                    McpSchema.ClientCapabilities.builder().elicitation(false, true).build())
                .urlElicitation(
                    request -> {
                      received.complete(request);
                      return McpSchema.ElicitResult.builder(action).build();
                    })
                .requestTimeout(Duration.ofSeconds(5))
                .initializationTimeout(Duration.ofSeconds(5))
                .build()) {
      client.initialize();
      var result =
          client.callTool(
              McpSchema.CallToolRequest.builder("authorize").arguments(Map.of()).build());
      assertEquals(
          action.name(),
          assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst()).text());
      var request = received.get(5, TimeUnit.SECONDS);
      assertEquals("https://service.example/authorize", request.url());
      assertEquals("authorization-1", request.elicitationId());
      assertTrue(client.closeGracefully());
    }
  }
}
