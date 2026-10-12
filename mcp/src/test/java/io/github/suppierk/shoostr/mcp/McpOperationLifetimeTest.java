package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class McpOperationLifetimeTest {
  @Test
  @Timeout(20)
  void completesAnIssuedNativeRequestAfterItsHttpResponseFails() throws Exception {
    var breakResponse = Sinks.<Void>empty();
    var httpEnded = new CountDownLatch(1);
    var nativeCompleted = new CountDownLatch(1);
    var cancelled = new AtomicInteger();
    var failResponse = new AtomicBoolean();
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
                    .capabilities(
                        McpSchema.ServerCapabilities.builder().tools(false).logging().build())
                    .tools(
                        McpServerFeatures.AsyncToolSpecification.builder()
                            .tool(tool)
                            .callHandler(
                                (exchange, _) -> {
                                  var ping =
                                      exchange
                                          .ping()
                                          .doOnSuccess(_ -> nativeCompleted.countDown())
                                          .doOnCancel(cancelled::incrementAndGet);
                                  var notification =
                                      breakResponse
                                          .asMono()
                                          .then(
                                              exchange.loggingNotification(
                                                  McpSchema.LoggingMessageNotification.builder(
                                                          McpSchema.LoggingLevel.INFO,
                                                          "trigger failed HTTP flush")
                                                      .build()))
                                          .onErrorResume(_ -> Mono.empty());
                                  return Mono.when(ping, notification)
                                      .thenReturn(
                                          McpSchema.CallToolResult.builder()
                                              .addTextContent("native operation completed")
                                              .build());
                                })
                            .build())
                    .build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));
    app.beforeResponseFlush(
        (request, _) -> {
          if (request.header("X-Fail-Response").isPresent() && failResponse.get()) {
            throw new IOException("fixture HTTP response failure after the native ping was sent");
          }
        });
    app.afterRequest(
        outcome -> {
          if ("POST".equals(outcome.method()) && outcome.applicationFailure() != null) {
            httpEnded.countDown();
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
                "clientInfo":{"name":"operation-lifetime-fixture","version":"1.0"}}}
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
                      .header("X-Fail-Response", "true")
                      .body(
                          """
              {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                "name":"ping-client","arguments":{}}}
              """
                              .getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofInputStream());
      var mapper = McpJsonDefaults.getMapper();

      try (var reader =
          new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
        assertEquals(200, response.statusCode());
        String line;
        do {
          line = reader.readLine();
          assertNotNull(line, "The native ping must arrive before the response ends");
        } while (!line.startsWith("data:"));
        var ping =
            assertInstanceOf(
                McpSchema.JSONRPCRequest.class,
                McpSchema.deserializeJsonRpcMessage(mapper, line.substring(5).strip()));
        assertEquals("ping", ping.method());
        failResponse.set(true);
        assertEquals(Sinks.EmitResult.OK, breakResponse.tryEmitEmpty());
        assertTrue(httpEnded.await(5, TimeUnit.SECONDS));
        var replyBody =
            mapper.writeValueAsBytes(McpSchema.JSONRPCResponse.result(ping.id(), Map.of()));
        var reply =
            test.send(
                request ->
                    request
                        .path("/mcp")
                        .method("POST")
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .header("Mcp-Session-Id", sessionId)
                        .header("MCP-Protocol-Version", "2025-11-25")
                        .body(replyBody));
        assertEquals(202, reply.statusCode());
        assertTrue(nativeCompleted.await(5, TimeUnit.SECONDS));
        assertEquals(0, cancelled.get());
      }
    }
  }
}
