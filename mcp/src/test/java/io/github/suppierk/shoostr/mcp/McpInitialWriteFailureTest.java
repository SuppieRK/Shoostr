package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Mono;

class McpInitialWriteFailureTest {
  @Test
  @Timeout(20)
  void failsQueuedNativeSendsWhenTheInitialHttpFlushFails() throws Exception {
    var queued = new CountDownLatch(1);
    var nativeSendFailed = new CountDownLatch(1);
    var failFirstFlush = new AtomicBoolean(true);
    var security =
        new McpHttpSecurity(
            Set.of(),
            _ -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (_, _) -> {});
    var tool = McpSchema.Tool.builder("ping-client", Map.of("type", "object")).build();
    var mcp =
        Mcp.async(
            security,
            transport ->
                McpServer.async(transport)
                    .requestTimeout(Duration.ofSeconds(10))
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .tools(
                        McpServerFeatures.AsyncToolSpecification.builder()
                            .tool(tool)
                            .callHandler(
                                (exchange, _) ->
                                    Mono.when(
                                            exchange
                                                .ping()
                                                .doOnError(_ -> nativeSendFailed.countDown()),
                                            Mono.fromRunnable(queued::countDown))
                                        .thenReturn(
                                            McpSchema.CallToolResult.builder()
                                                .addTextContent("pong")
                                                .build()))
                            .build())
                    .build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));
    app.beforeResponseFlush(
        (request, _) -> {
          if (request.header("X-Fail-Initial-Flush").isPresent()
              && failFirstFlush.getAndSet(false)) {
            if (!queued.await(5, TimeUnit.SECONDS)) {
              throw new IllegalStateException(
                  "Native ping was not queued before the initial flush");
            }

            throw new IOException("fixture initial HTTP flush failure");
          }
        });

    try (var test = TestServer.start(app)) {
      var initialized =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .method("POST")
                      .header("Content-Type", "application/json")
                      .header("Accept", "application/json, text/event-stream")
                      .body(
                          """
              {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                "protocolVersion":"2025-11-25","capabilities":{},
                "clientInfo":{"name":"initial-failure-fixture","version":"1.0"}}}
              """
                              .getBytes(StandardCharsets.UTF_8)));
      var sessionId = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var response =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .method("POST")
                      .header("Content-Type", "application/json")
                      .header("Accept", "application/json, text/event-stream")
                      .header("Mcp-Session-Id", sessionId)
                      .header("MCP-Protocol-Version", "2025-11-25")
                      .header("X-Fail-Initial-Flush", "true")
                      .body(
                          """
              {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                "name":"ping-client","arguments":{}}}
              """
                              .getBytes(StandardCharsets.UTF_8)));
      assertEquals(500, response.statusCode());
      assertTrue(
          nativeSendFailed.await(3, TimeUnit.SECONDS),
          "An initial write failure must fail the queued send, not wait for the SDK timeout");
    }
  }
}
