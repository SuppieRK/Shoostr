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
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class McpDetachedOperationCleanupTest {
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"session deletion", "application shutdown"})
  @Timeout(20)
  void cancelsDetachedNativeWorkWhenItsOwnerTerminates(String termination) throws Exception {
    var breakResponse = Sinks.<Void>empty();
    var httpEnded = new CountDownLatch(1);
    var nativePending = new CountDownLatch(1);
    var nativeCancelled = new CountDownLatch(1);
    var cancellations = new AtomicInteger();
    var failResponse = new AtomicBoolean();
    var security =
        new McpHttpSecurity(
            Set.of(),
            ignored -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (ignored2, ignored3) -> {});
    var tool = McpSchema.Tool.builder("wait", Map.of("type", "object")).build();
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
                                (exchange, ignored4) ->
                                    breakResponse
                                        .asMono()
                                        .then(
                                            exchange.loggingNotification(
                                                McpSchema.LoggingMessageNotification.builder(
                                                        McpSchema.LoggingLevel.INFO,
                                                        "end original HTTP exchange")
                                                    .build()))
                                        .onErrorResume(ignored5 -> Mono.empty())
                                        .then(
                                            Mono.<McpSchema.CallToolResult>never()
                                                .doOnSubscribe(
                                                    ignored6 -> nativePending.countDown())
                                                .doOnCancel(
                                                    () -> {
                                                      cancellations.incrementAndGet();
                                                      nativeCancelled.countDown();
                                                    })))
                            .build())
                    .build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));
    app.beforeResponseFlush(
        (request, ignored7) -> {
          if (request.header("X-Fail-Response").isPresent() && failResponse.get()) {
            throw new IOException("fixture HTTP failure while native work remains pending");
          }
        });
    app.afterRequest(
        outcome -> {
          if ("POST".equals(outcome.method()) && outcome.applicationFailure() != null) {
            httpEnded.countDown();
          }
        });

    try (var test = TestServer.start(app)) {
      var initialization =
          """
          {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
            "protocolVersion":"2025-11-25","capabilities":{},
            "clientInfo":{"name":"detached-cleanup-fixture","version":"1.0"}}}
          """
              .getBytes(StandardCharsets.UTF_8);
      var initialized =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .method("POST")
                      .header("Content-Type", "application/json")
                      .header("Accept", "application/json, text/event-stream")
                      .body(initialization));
      var sessionId = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var unrelated =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .method("POST")
                      .header("Content-Type", "application/json")
                      .header("Accept", "application/json, text/event-stream")
                      .body(initialization));
      var unrelatedId = unrelated.headers().firstValue("Mcp-Session-Id").orElseThrow();
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
                "name":"wait","arguments":{}}}
              """
                              .getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofInputStream());

      try (var body = response.body()) {
        assertEquals(200, response.statusCode());
        assertTrue(body.read() >= 0);
        failResponse.set(true);
        assertEquals(Sinks.EmitResult.OK, breakResponse.tryEmitEmpty());
        assertTrue(httpEnded.await(5, TimeUnit.SECONDS));
        assertTrue(nativePending.await(5, TimeUnit.SECONDS));
        assertEquals(0, cancellations.get(), "Ending HTTP must not cancel native work");

        if ("session deletion".equals(termination)) {
          var deleted =
              test.send(
                  request ->
                      request
                          .path("/mcp")
                          .method("DELETE")
                          .header("Mcp-Session-Id", sessionId)
                          .header("MCP-Protocol-Version", "2025-11-25"));
          assertEquals(200, deleted.statusCode());
          var stale =
              test.send(
                  request ->
                      request
                          .path("/mcp")
                          .method("DELETE")
                          .header("Mcp-Session-Id", sessionId)
                          .header("MCP-Protocol-Version", "2025-11-25"));
          assertEquals(404, stale.statusCode());
          var usable =
              test.send(
                  request ->
                      request
                          .path("/mcp")
                          .method("POST")
                          .header("Content-Type", "application/json")
                          .header("Accept", "application/json, text/event-stream")
                          .header("Mcp-Session-Id", unrelatedId)
                          .header("MCP-Protocol-Version", "2025-11-25")
                          .body(
                              """
                  {"jsonrpc":"2.0","id":3,"method":"ping"}
                  """
                                  .getBytes(StandardCharsets.UTF_8)),
                  HttpResponse.BodyHandlers.ofString());
          assertEquals(200, usable.statusCode());
          assertTrue(usable.body().contains("\"result\":{}"));
        } else {
          app.close();
          app.close();
        }

        assertTrue(nativeCancelled.await(5, TimeUnit.SECONDS));
        assertEquals(1, cancellations.get());
      }
    }
  }
}
