package io.github.suppierk.shoostr.mcp;

import static io.github.suppierk.shoostr.mcp.McpExpiryAssertions.assertNotBeforeExpiry;
import static io.github.suppierk.shoostr.mcp.McpExpiryAssertions.awaitTermination;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.Root;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class McpGetLifetimeTest {
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"replacement", "expiry"})
  @Timeout(25)
  void freshReplyCompletesTheRootsCallbackAfterItsGetTransportEnds(String ending) throws Exception {
    var roots = new CompletableFuture<List<McpSchema.Root>>();
    var expiry = new AtomicReference<Instant>();
    var mcp =
        createMcpForFreshReplyCompletesTheRootsCallbackAfterItsGetTransportEnds(expiry, roots);
    var app = new Shoostr();
    app.routes(routes -> routes.path("/mcp", mcp::routes));

    try (var readers = Executors.newVirtualThreadPerTaskExecutor();
        var test = TestServer.start(app)) {
      var initialized =
          sendInitializedRequestForFreshReplyCompletesTheRootsCallbackAfterItsGetTransportEnds(
              test);
      var sessionId = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var oldGet =
          sendOldGetRequestForFreshReplyCompletesTheRootsCallbackAfterItsGetTransportEnds(
              test, sessionId);

      var body = oldGet.body();
      // Close native input first: a blocked readLine holds the BufferedReader's monitor.

      try (var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
          body) {
        var notified =
            sendNotifiedRequestForFreshReplyCompletesTheRootsCallbackAfterItsGetTransportEnds(
                test, sessionId);

        assertEquals(202, notified.statusCode());

        String line;
        do {
          line = reader.readLine();

          assertNotNull(line, "The roots request must arrive on the original GET");
        } while (!line.startsWith("data:"));
        var mapper = McpJsonDefaults.getMapper();

        var nativeRequest =
            assertInstanceOf(
                McpSchema.JSONRPCRequest.class,
                McpSchema.deserializeJsonRpcMessage(mapper, line.substring(5).strip()));
        assertEquals("roots/list", nativeRequest.method());

        var oldEnded =
            submitOldEndedForFreshReplyCompletesTheRootsCallbackAfterItsGetTransportEnds(
                readers, reader);
        if ("expiry".equals(ending)) {
          awaitTermination(expiry.get(), oldEnded::isDone);

          assertNotBeforeExpiry(expiry.get(), oldEnded.get());
        }

        var refreshed =
            sendRefreshedRequestForFreshReplyCompletesTheRootsCallbackAfterItsGetTransportEnds(
                test, sessionId);

        try (var ignoredInputStream = refreshed.body()) {
          assertEquals(200, refreshed.statusCode());

          await("superseded GET transport ends")
              .atMost(Duration.ofSeconds(5))
              .until(oldEnded::isDone);
          oldEnded.get();
          var expected = new McpSchema.Root("file:///workspace/refreshed", "refreshed");
          var replyBody =
              createReplyBodyForFreshReplyCompletesTheRootsCallbackAfterItsGetTransportEnds(
                  mapper, nativeRequest, expected);
          var reply =
              sendReplyRequestForFreshReplyCompletesTheRootsCallbackAfterItsGetTransportEnds(
                  test, sessionId, replyBody);

          assertEquals(202, reply.statusCode());

          await("fresh reply completes the roots callback")
              .atMost(Duration.ofSeconds(5))
              .until(roots::isDone);

          assertEquals(List.of(expected), roots.get());
        }
      }
    }
  }

  private Mcp<McpSyncServer>
      createMcpForFreshReplyCompletesTheRootsCallbackAfterItsGetTransportEnds(
          AtomicReference<Instant> expiry, CompletableFuture<List<Root>> roots) {
    return Mcp.sync(
        new McpHttpSecurity(
            Set.of(),
            request -> {
              if (request.header("X-Old-Token").isEmpty()) {
                return new McpIdentity("alice:client", McpTransportContext.EMPTY, null);
              }

              var verifiedExpiry = Instant.now().plusSeconds(2);
              expiry.set(verifiedExpiry);

              return new McpIdentity("alice:client", McpTransportContext.EMPTY, verifiedExpiry);
            },
            (ignored, ignoredJsonRpcMessage) -> {}),
        new McpSessionLimits(1, 1, Duration.ofSeconds(1)),
        transport ->
            McpServer.sync(transport)
                .requestTimeout(Duration.ofSeconds(15))
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .rootsChangeHandler(
                    (ignoredMcpSyncServerExchange, changed) -> roots.complete(changed))
                .build());
  }

  private HttpResponse<byte[]>
      sendInitializedRequestForFreshReplyCompletesTheRootsCallbackAfterItsGetTransportEnds(
          TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/mcp")
                .method("POST")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .body(
                    """
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                      "protocolVersion":"2025-11-25","capabilities":{"roots":{"listChanged":true}},
                      "clientInfo":{"name":"get-lifetime-fixture","version":"1.0"}}}
                    """
                        .getBytes(StandardCharsets.UTF_8)));
  }

  private HttpResponse<InputStream>
      sendOldGetRequestForFreshReplyCompletesTheRootsCallbackAfterItsGetTransportEnds(
          TestServer test, String sessionId) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/mcp")
                .header("Accept", "text/event-stream")
                .header("Mcp-Session-Id", sessionId)
                .header("X-Old-Token", "true")
                .header("MCP-Protocol-Version", "2025-11-25"),
        HttpResponse.BodyHandlers.ofInputStream());
  }

  private HttpResponse<byte[]>
      sendNotifiedRequestForFreshReplyCompletesTheRootsCallbackAfterItsGetTransportEnds(
          TestServer test, String sessionId) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/mcp")
                .method("POST")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Mcp-Session-Id", sessionId)
                .header("MCP-Protocol-Version", "2025-11-25")
                .body(
                    """
                    {"jsonrpc":"2.0","method":"notifications/roots/list_changed"}
                    """
                        .getBytes(StandardCharsets.UTF_8)));
  }

  private Future<Instant>
      submitOldEndedForFreshReplyCompletesTheRootsCallbackAfterItsGetTransportEnds(
          ExecutorService readers, BufferedReader reader) {
    return readers.submit(
        () -> {
          try {
            while (reader.readLine() != null) {
              // Drain the superseded connection before answering its native request.
            }
          } catch (IOException ignoredFailure) {
            // EOF and a closed transport both end the original HTTP owner.
          }

          return Instant.now();
        });
  }

  private HttpResponse<InputStream>
      sendRefreshedRequestForFreshReplyCompletesTheRootsCallbackAfterItsGetTransportEnds(
          TestServer test, String sessionId) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/mcp")
                .header("Accept", "text/event-stream")
                .header("Mcp-Session-Id", sessionId)
                .header("MCP-Protocol-Version", "2025-11-25"),
        HttpResponse.BodyHandlers.ofInputStream());
  }

  private byte[] createReplyBodyForFreshReplyCompletesTheRootsCallbackAfterItsGetTransportEnds(
      McpJsonMapper mapper, JSONRPCRequest nativeRequest, Root expected) throws IOException {
    return mapper.writeValueAsBytes(
        McpSchema.JSONRPCResponse.result(
            nativeRequest.id(),
            Map.of("roots", List.of(Map.of("uri", expected.uri(), "name", expected.name())))));
  }

  private HttpResponse<byte[]>
      sendReplyRequestForFreshReplyCompletesTheRootsCallbackAfterItsGetTransportEnds(
          TestServer test, String sessionId, byte[] replyBody)
          throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/mcp")
                .method("POST")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Mcp-Session-Id", sessionId)
                .header("MCP-Protocol-Version", "2025-11-25")
                .body(replyBody));
  }
}
