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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class McpToolTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void completesANativePingWithEitherSynchronousExecutionSetting(boolean immediate)
      throws Exception {
    var security =
        new McpHttpSecurity(
            Set.of(),
            _ -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (_, _) -> {});
    var tool = McpSchema.Tool.builder("ping-client", Map.of("type", "object")).build();
    var mcp =
        Mcp.sync(
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
                                (exchange, _) -> {
                                  exchange.ping();
                                  return McpSchema.CallToolResult.builder()
                                      .addTextContent("native ping returned")
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
                .build()) {
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

  @Test
  void invokesANativeToolThroughTheOfficialStreamableHttpClient() throws Exception {
    var security =
        new McpHttpSecurity(
            Set.of(),
            _ -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (_, _) -> {});
    var tool =
        McpSchema.Tool.builder("greet", Map.of("type", "object", "properties", Map.of()))
            .description("Returns a greeting")
            .build();
    var mcp =
        Mcp.sync(
            security,
            transport ->
                McpServer.sync(transport)
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .tools(
                        McpServerFeatures.SyncToolSpecification.builder()
                            .tool(tool)
                            .callHandler(
                                (_, _) ->
                                    McpSchema.CallToolResult.builder()
                                        .addTextContent("hello from Shoostr")
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
                .requestTimeout(Duration.ofSeconds(5))
                .initializationTimeout(Duration.ofSeconds(5))
                .build()) {
      client.initialize();
      var result =
          client.callTool(McpSchema.CallToolRequest.builder("greet").arguments(Map.of()).build());
      assertEquals(
          "hello from Shoostr",
          assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst()).text());
      assertTrue(client.closeGracefully());
    }
  }

  @Test
  void invokesAnAsyncNativeToolThroughTheOfficialStreamableHttpClient() throws Exception {
    var security =
        new McpHttpSecurity(
            Set.of(),
            _ -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (_, _) -> {});
    var tool =
        McpSchema.Tool.builder("greet", Map.of("type", "object", "properties", Map.of()))
            .description("Returns a greeting")
            .build();
    var mcp =
        Mcp.async(
            security,
            transport ->
                McpServer.async(transport)
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .tools(
                        McpServerFeatures.AsyncToolSpecification.builder()
                            .tool(tool)
                            .callHandler(
                                (_, _) ->
                                    reactor.core.publisher.Mono.just(
                                        McpSchema.CallToolResult.builder()
                                            .addTextContent("hello from async Shoostr")
                                            .build()))
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
                .build()) {
      client.initialize();
      var result =
          client.callTool(McpSchema.CallToolRequest.builder("greet").arguments(Map.of()).build());
      assertEquals(
          "hello from async Shoostr",
          assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst()).text());
      assertTrue(client.closeGracefully());
    }
  }
}
