package io.github.suppierk.shoostr.mcp;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class McpExpiryTest {
  @Test
  @Timeout(15)
  void opensAStreamWithAFarFutureVerifiedExpiryWithoutOverflowingItsDeadline() throws Exception {
    var mcp =
        Mcp.sync(
            new McpHttpSecurity(
                Set.of(),
                ignored -> new McpIdentity("alice:client", McpTransportContext.EMPTY, Instant.MAX),
                (ignored2, ignored3) -> {}),
            transport -> McpServer.sync(transport).build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

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
                "clientInfo":{"name":"expiry-fixture","version":"1.0"}}}
              """
                              .getBytes(StandardCharsets.UTF_8)));
      assertEquals(200, initialized.statusCode());
      var response =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .header("Accept", "text/event-stream")
                      .header(
                          "Mcp-Session-Id",
                          initialized.headers().firstValue("Mcp-Session-Id").orElseThrow()),
              HttpResponse.BodyHandlers.ofInputStream());

      try {
        assertEquals(200, response.statusCode());
      } finally {
        response.body().close();
      }
    }
  }

  @Test
  @Timeout(15)
  void endsAnIdleListeningStreamAtItsAuthenticatedTokenExpiry() throws Exception {
    var security =
        new McpHttpSecurity(
            Set.of(),
            request ->
                new McpIdentity(
                    "alice:client",
                    McpTransportContext.EMPTY,
                    request.header("X-Expiring-Identity").isPresent()
                        ? Instant.now().plusSeconds(1)
                        : null),
            (ignored4, ignored5) -> {});
    var mcp = Mcp.sync(security, transport -> McpServer.sync(transport).build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var readers = Executors.newVirtualThreadPerTaskExecutor();
        var test = TestServer.start(app)) {
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
                "clientInfo":{"name":"expiry-fixture","version":"1.0"}}}
              """
                              .getBytes(StandardCharsets.UTF_8)));
      var sessionId = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var listening =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .header("Accept", "text/event-stream")
                      .header("Mcp-Session-Id", sessionId)
                      .header("MCP-Protocol-Version", "2025-11-25")
                      .header("X-Expiring-Identity", "true"),
              HttpResponse.BodyHandlers.ofInputStream());

      try (var input = listening.body()) {
        assertEquals(200, listening.statusCode());
        var ended =
            readers.submit(
                () -> {
                  try {
                    input.readAllBytes();
                  } catch (IOException ignored6) {
                    // A committed stream may end by transport failure instead of clean EOF.
                  }

                  return null;
                });
        await("the listening stream stays open before its authenticated expiry")
            .pollDelay(Duration.ZERO)
            .pollInterval(Duration.ofMillis(10))
            .during(Duration.ofMillis(100))
            .atMost(Duration.ofMillis(1100))
            .failFast("the listening stream ended before expiry", ended::isDone)
            .until(() -> !ended.isDone());
        ended.get(4, TimeUnit.SECONDS);
      }
    }
  }
}
