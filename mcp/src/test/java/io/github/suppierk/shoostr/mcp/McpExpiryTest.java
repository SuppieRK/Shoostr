package io.github.suppierk.shoostr.mcp;

import static io.github.suppierk.shoostr.mcp.McpExpiryAssertions.assertNotBeforeExpiry;
import static io.github.suppierk.shoostr.mcp.McpExpiryAssertions.awaitTermination;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class McpExpiryTest {
  @Test
  @Timeout(15)
  void opensStreamWithFarFutureVerifiedExpiryWithoutOverflowingItsDeadline() throws Exception {
    var mcp = createMcpForFarFutureExpiry();
    var app = new Shoostr();
    app.routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var initialized = sendInitializedRequestForFarFutureExpiry(test);

      assertEquals(200, initialized.statusCode());

      var response = sendResponseForFarFutureExpiry(test, initialized);

      try {
        assertEquals(200, response.statusCode());
      } finally {
        response.body().close();
      }
    }
  }

  private Mcp<McpSyncServer> createMcpForFarFutureExpiry() {
    return Mcp.sync(
        new McpHttpSecurity(
            Set.of(),
            ignored -> new McpIdentity("alice:client", McpTransportContext.EMPTY, Instant.MAX),
            (ignoredMcpIdentity, ignoredJsonRpcMessage) -> {}),
        transport -> McpServer.sync(transport).build());
  }

  private HttpResponse<byte[]> sendInitializedRequestForFarFutureExpiry(TestServer test)
      throws IOException, InterruptedException {
    return test.send(request ->
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
  }

  private HttpResponse<InputStream> sendResponseForFarFutureExpiry(
      TestServer test, HttpResponse<byte[]> initialized) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/mcp")
                .header("Accept", "text/event-stream")
                .header(
                    "Mcp-Session-Id",
                    initialized.headers().firstValue("Mcp-Session-Id").orElseThrow()),
        HttpResponse.BodyHandlers.ofInputStream());
  }

  @Test
  @Timeout(15)
  void endsAnIdleListeningStreamAtItsAuthenticatedTokenExpiry() throws Exception {
    var expiry = new AtomicReference<Instant>();
    var security = createSecurityForEndsAnIdleListeningStreamAtItsAuthenticatedTokenExpiry(expiry);
    var mcp = Mcp.sync(security, transport -> McpServer.sync(transport).build());
    var app = new Shoostr();
    app.routes(routes -> routes.path("/mcp", mcp::routes));

    try (var readers = Executors.newVirtualThreadPerTaskExecutor();
        var test = TestServer.start(app)) {
      var initialized =
          sendInitializedRequestForEndsAnIdleListeningStreamAtItsAuthenticatedTokenExpiry(test);
      var sessionId = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var listening =
          sendListeningRequestForEndsAnIdleListeningStreamAtItsAuthenticatedTokenExpiry(
              test, sessionId);

      try (var input = listening.body()) {
        assertEquals(200, listening.statusCode());

        var ended =
            submitEndedForEndsAnIdleListeningStreamAtItsAuthenticatedTokenExpiry(readers, input);
        var verifiedExpiry = expiry.get();
        awaitTermination(verifiedExpiry, ended::isDone);

        assertNotBeforeExpiry(verifiedExpiry, ended.get());
      }
    }
  }

  private McpHttpSecurity createSecurityForEndsAnIdleListeningStreamAtItsAuthenticatedTokenExpiry(
      AtomicReference<Instant> expiry) {
    return new McpHttpSecurity(
        Set.of(),
        request -> {
          if (request.header("X-Expiring-Identity").isEmpty()) {
            return new McpIdentity("alice:client", McpTransportContext.EMPTY, null);
          }

          var verifiedExpiry = Instant.now().plusSeconds(1);
          expiry.set(verifiedExpiry);

          return new McpIdentity("alice:client", McpTransportContext.EMPTY, verifiedExpiry);
        },
        (ignoredMcpIdentity, ignoredJsonRpcMessage) -> {});
  }

  private HttpResponse<byte[]>
      sendInitializedRequestForEndsAnIdleListeningStreamAtItsAuthenticatedTokenExpiry(
          TestServer test) throws IOException, InterruptedException {
    return test.send(request ->
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
  }

  private HttpResponse<InputStream>
      sendListeningRequestForEndsAnIdleListeningStreamAtItsAuthenticatedTokenExpiry(
          TestServer test, String sessionId) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/mcp")
                .header("Accept", "text/event-stream")
                .header("Mcp-Session-Id", sessionId)
                .header("MCP-Protocol-Version", "2025-11-25")
                .header("X-Expiring-Identity", "true"),
        HttpResponse.BodyHandlers.ofInputStream());
  }

  private Future<Instant> submitEndedForEndsAnIdleListeningStreamAtItsAuthenticatedTokenExpiry(
      ExecutorService readers, InputStream input) {
    return readers.submit(() -> {
      try {
        input.readAllBytes();
      } catch (IOException ignoredFailure) {
        // A committed stream may end by transport failure instead of clean EOF.
      }

      return Instant.now();
    });
  }
}
