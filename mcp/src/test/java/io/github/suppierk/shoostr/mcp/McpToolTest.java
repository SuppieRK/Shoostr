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
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

class McpToolTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void completesNativePingWithEitherSynchronousExecutionSetting(boolean immediate)
      throws Exception {
    var security =
        new McpHttpSecurity(
            Set.of(),
            ignored -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (ignoredMcpIdentity, ignoredJsonRpcMessage) -> {});
    var tool = McpSchema.Tool.builder("ping-client", Map.of("type", "object")).build();
    var mcp =
        createMcpForCompletesNativePingWithEitherSynchronousExecutionSetting(
            security, immediate, tool);
    var app = new Shoostr();
    app.routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app);
        var client = openClient(test)) {
      client.initialize();

      try {
        var result =
            client.callTool(
                McpSchema.CallToolRequest.builder("ping-client").arguments(Map.of()).build());

        assertEquals(
            "native ping returned",
            assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst()).text());
      } finally {
        assertTrue(client.closeGracefully());
      }
    }
  }

  private Mcp<McpSyncServer> createMcpForCompletesNativePingWithEitherSynchronousExecutionSetting(
      McpHttpSecurity security, boolean immediate, Tool tool) {
    return Mcp.sync(
        security,
        transport ->
            McpServer.sync(transport)
                .immediateExecution(immediate)
                .requestTimeout(Duration.ofSeconds(3))
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(
                    McpServerFeatures.SyncToolSpecification.builder()
                        .tool(tool)
                        .callHandler(
                            (exchange, ignoredCallToolRequest) -> {
                              exchange.ping();

                              return McpSchema.CallToolResult.builder()
                                  .addTextContent("native ping returned")
                                  .build();
                            })
                        .build())
                .build());
  }

  @Test
  void invokesNativeToolThroughTheOfficialStreamableHttpClient() throws Exception {
    var security =
        new McpHttpSecurity(
            Set.of(),
            ignoredRequest -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (ignoredMcpIdentity, ignoredJsonRpcMessage) -> {});
    var tool =
        McpSchema.Tool.builder("greet", Map.of("type", "object", "properties", Map.of()))
            .description("Returns a greeting")
            .build();
    var mcp = createMcpForInvokesNativeToolThroughTheOfficialStreamableHttpClient(security, tool);
    var app = new Shoostr();
    app.routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app);
        var client = openClient(test)) {
      client.initialize();
      var result =
          client.callTool(McpSchema.CallToolRequest.builder("greet").arguments(Map.of()).build());

      assertEquals(
          "hello from Shoostr",
          assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst()).text());
      assertTrue(client.closeGracefully());
    }
  }

  private Mcp<McpSyncServer> createMcpForInvokesNativeToolThroughTheOfficialStreamableHttpClient(
      McpHttpSecurity security, Tool tool) {
    return Mcp.sync(
        security,
        transport ->
            McpServer.sync(transport)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(
                    McpServerFeatures.SyncToolSpecification.builder()
                        .tool(tool)
                        .callHandler(
                            (ignoredMcpSyncServerExchange, ignoredCallToolRequest) ->
                                McpSchema.CallToolResult.builder()
                                    .addTextContent("hello from Shoostr")
                                    .build())
                        .build())
                .build());
  }

  @Test
  void invokesAnAsyncNativeToolThroughTheOfficialStreamableHttpClient() throws Exception {
    var security =
        new McpHttpSecurity(
            Set.of(),
            ignoredRequest -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (ignoredMcpIdentity, ignoredJsonRpcMessage) -> {});
    var tool =
        McpSchema.Tool.builder("greet", Map.of("type", "object", "properties", Map.of()))
            .description("Returns a greeting")
            .build();
    var mcp =
        createMcpForInvokesAnAsyncNativeToolThroughTheOfficialStreamableHttpClient(security, tool);
    var app = new Shoostr();
    app.routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app);
        var client = openClient(test)) {
      client.initialize();
      var result =
          client.callTool(McpSchema.CallToolRequest.builder("greet").arguments(Map.of()).build());

      assertEquals(
          "hello from async Shoostr",
          assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst()).text());
      assertTrue(client.closeGracefully());
    }
  }

  private Mcp<McpAsyncServer>
      createMcpForInvokesAnAsyncNativeToolThroughTheOfficialStreamableHttpClient(
          McpHttpSecurity security, Tool tool) {
    return Mcp.async(
        security,
        transport ->
            McpServer.async(transport)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(
                    McpServerFeatures.AsyncToolSpecification.builder()
                        .tool(tool)
                        .callHandler(
                            (ignoredMcpAsyncServerExchange, ignoredCallToolRequest) ->
                                Mono.just(
                                    McpSchema.CallToolResult.builder()
                                        .addTextContent("hello from async Shoostr")
                                        .build()))
                        .build())
                .build());
  }

  private McpSyncClient openClient(TestServer test) {
    return clientProperties(test).build();
  }

  private SyncSpec clientProperties(TestServer test) {
    return McpClient.sync(
            HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                .endpoint("/mcp")
                .build())
        .requestTimeout(Duration.ofSeconds(5))
        .initializationTimeout(Duration.ofSeconds(5));
  }
}
