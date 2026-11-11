package io.github.suppierk.shoostr.mcp;

import static io.github.suppierk.shoostr.mcp.McpExpiryAssertions.assertNotBeforeExpiry;
import static io.github.suppierk.shoostr.mcp.McpExpiryAssertions.awaitTermination;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Mono;

class McpPostExpiryTest {
  private static McpStreamableServerTransportProvider captureSession(
      McpStreamableServerTransportProvider transport,
      AtomicReference<McpStreamableServerSession> session) {
    return new McpStreamableServerTransportProvider() {
      @Override
      public void setSessionFactory(McpStreamableServerSession.Factory factory) {
        transport.setSessionFactory(
            parameters -> {
              var initialized = factory.startSession(parameters);
              session.set(initialized.session());

              return initialized;
            });
      }

      @Override
      public Mono<Void> notifyClients(String method, Object parameters) {
        return transport.notifyClients(method, parameters);
      }

      @Override
      public Mono<Void> closeGracefully() {
        return transport.closeGracefully();
      }

      @Override
      public List<String> protocolVersions() {
        return transport.protocolVersions();
      }
    };
  }

  @Test
  @Timeout(20)
  void freshReplyCompletesAnIssuedNativeRequestAfterThePostTokenExpires() throws Exception {
    var expiry = new AtomicReference<Instant>();
    var httpEnded = new CountDownLatch(1);
    var nativeCompleted = new CountDownLatch(1);
    var cancellations = new AtomicInteger();
    var nativeSession = new AtomicReference<McpStreamableServerSession>();
    var security =
        createSecurityForFreshReplyCompletesAnIssuedNativeRequestAfterThePostTokenExpires(expiry);
    var tool = McpSchema.Tool.builder("ping-client", Map.of("type", "object")).build();
    var mcp =
        createMcpForFreshReplyCompletesAnIssuedNativeRequestAfterThePostTokenExpires(
            security, nativeSession, tool, nativeCompleted, cancellations);
    var app = new Shoostr();
    app.routes(routes -> routes.path("/mcp", mcp::routes));
    app.afterRequest(
        outcome -> {
          if (outcome.applicationFailure() != null && "POST".equals(outcome.method())) {
            httpEnded.countDown();
          }
        });

    try (var readers = Executors.newVirtualThreadPerTaskExecutor();
        var test = TestServer.start(app)) {
      var initialized =
          sendInitializedRequestForFreshReplyCompletesAnIssuedNativeRequestAfterThePostTokenExpires(
              test);
      var sessionId = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var response =
          sendResponseForFreshReplyCompletesAnIssuedNativeRequestAfterThePostTokenExpires(
              test, sessionId);
      var mapper = McpJsonDefaults.getMapper();

      var body = response.body();
      // Close native input first: a blocked readLine holds the BufferedReader's monitor.

      try (var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
          body) {
        assertEquals(200, response.statusCode());

        String line;
        do {
          line = reader.readLine();

          assertNotNull(line, "The native ping must arrive before token expiry");
        } while (!line.startsWith("data:"));

        var ping =
            assertInstanceOf(
                McpSchema.JSONRPCRequest.class,
                McpSchema.deserializeJsonRpcMessage(mapper, line.substring(5).strip()));
        assertEquals("ping", ping.method());

        var ended =
            submitEndedForFreshReplyCompletesAnIssuedNativeRequestAfterThePostTokenExpires(
                readers, reader);
        var verifiedExpiry = expiry.get();
        awaitTermination(verifiedExpiry, () -> ended.isDone() && httpEnded.getCount() == 0);

        assertNotBeforeExpiry(verifiedExpiry, ended.get());
        assertEquals(0, cancellations.get());
        assertFalse(
            Objects.requireNonNull(nativeSession.get()).hasOpenStream(),
            "Ended HTTP must release its SDK stream while the native request stays pending");

        var replyBody =
            mapper.writeValueAsBytes(McpSchema.JSONRPCResponse.result(ping.id(), Map.of()));
        var reply =
            sendReplyRequestForFreshReplyCompletesAnIssuedNativeRequestAfterThePostTokenExpires(
                test, sessionId, replyBody);

        assertEquals(202, reply.statusCode());

        await("fresh reply completes the outstanding native request")
            .atMost(Duration.ofSeconds(5))
            .until(() -> nativeCompleted.getCount() == 0);

        assertEquals(0, cancellations.get());
      }
    }
  }

  private McpHttpSecurity
      createSecurityForFreshReplyCompletesAnIssuedNativeRequestAfterThePostTokenExpires(
          AtomicReference<Instant> expiry) {
    return new McpHttpSecurity(
        Set.of(),
        request -> {
          var verifiedExpiry =
              Instant.now().plusSeconds(request.header("X-Old-Token").isPresent() ? 2 : 10);
          if (request.header("X-Old-Token").isPresent()) {
            expiry.set(verifiedExpiry);
          }

          return new McpIdentity("alice:client", McpTransportContext.EMPTY, verifiedExpiry);
        },
        (ignored, ignoredJsonRpcMessage) -> {});
  }

  private Mcp<McpAsyncServer>
      createMcpForFreshReplyCompletesAnIssuedNativeRequestAfterThePostTokenExpires(
          McpHttpSecurity security,
          AtomicReference<McpStreamableServerSession> nativeSession,
          Tool tool,
          CountDownLatch nativeCompleted,
          AtomicInteger cancellations) {
    return Mcp.async(
        security,
        transport ->
            McpServer.async(captureSession(transport, nativeSession))
                .requestTimeout(Duration.ofSeconds(10))
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(
                    McpServerFeatures.AsyncToolSpecification.builder()
                        .tool(tool)
                        .callHandler(
                            (exchange, ignoredCallToolRequest) ->
                                exchange
                                    .ping()
                                    .doOnSuccess(ignoredObject -> nativeCompleted.countDown())
                                    .doOnCancel(cancellations::incrementAndGet)
                                    .thenReturn(
                                        McpSchema.CallToolResult.builder()
                                            .addTextContent("native ping completed")
                                            .build()))
                        .build())
                .build());
  }

  private HttpResponse<byte[]>
      sendInitializedRequestForFreshReplyCompletesAnIssuedNativeRequestAfterThePostTokenExpires(
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
                      "protocolVersion":"2025-11-25","capabilities":{},
                      "clientInfo":{"name":"post-expiry-fixture","version":"1.0"}}}
                    """
                        .getBytes(StandardCharsets.UTF_8)));
  }

  private HttpResponse<InputStream>
      sendResponseForFreshReplyCompletesAnIssuedNativeRequestAfterThePostTokenExpires(
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
                .header("X-Old-Token", "true")
                .body(
                    """
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                      "name":"ping-client","arguments":{}}}
                    """
                        .getBytes(StandardCharsets.UTF_8)),
        HttpResponse.BodyHandlers.ofInputStream());
  }

  private Future<Instant>
      submitEndedForFreshReplyCompletesAnIssuedNativeRequestAfterThePostTokenExpires(
          ExecutorService readers, BufferedReader reader) {
    return readers.submit(
        () -> {
          try {
            while (reader.readLine() != null) {
              // Read until the committed response ends or its transport fails.
            }
          } catch (IOException ignoredFailure) {
            // A token deadline cannot replace already committed200 with a401.
          }

          return Instant.now();
        });
  }

  private HttpResponse<byte[]>
      sendReplyRequestForFreshReplyCompletesAnIssuedNativeRequestAfterThePostTokenExpires(
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
