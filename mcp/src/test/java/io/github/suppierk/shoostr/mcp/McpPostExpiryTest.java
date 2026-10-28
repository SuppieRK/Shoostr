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
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import java.io.BufferedReader;
import java.io.IOException;
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
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Mono;

class McpPostExpiryTest {
  @Test
  @Timeout(20)
  void aFreshReplyCompletesAnIssuedNativeRequestAfterThePostTokenExpires() throws Exception {
    var expiry = new AtomicReference<Instant>();
    var httpEnded = new CountDownLatch(1);
    var nativeCompleted = new CountDownLatch(1);
    var cancellations = new AtomicInteger();
    var nativeSession = new AtomicReference<McpStreamableServerSession>();
    var security =
        new McpHttpSecurity(
            Set.of(),
            request -> {
              var verifiedExpiry =
                  Instant.now().plusSeconds(request.header("X-Old-Token").isPresent() ? 2 : 10);
              if (request.header("X-Old-Token").isPresent()) {
                expiry.set(verifiedExpiry);
              }

              return new McpIdentity("alice:client", McpTransportContext.EMPTY, verifiedExpiry);
            },
            (ignored, ignored2) -> {});
    var tool = McpSchema.Tool.builder("ping-client", Map.of("type", "object")).build();
    var mcp =
        Mcp.async(
            security,
            transport ->
                McpServer.async(captureSession(transport, nativeSession))
                    .requestTimeout(Duration.ofSeconds(10))
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .tools(
                        McpServerFeatures.AsyncToolSpecification.builder()
                            .tool(tool)
                            .callHandler(
                                (exchange, ignored3) ->
                                    exchange
                                        .ping()
                                        .doOnSuccess(ignored4 -> nativeCompleted.countDown())
                                        .doOnCancel(cancellations::incrementAndGet)
                                        .thenReturn(
                                            McpSchema.CallToolResult.builder()
                                                .addTextContent("native ping completed")
                                                .build()))
                            .build())
                    .build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));
    app.afterRequest(
        outcome -> {
          if (outcome.applicationFailure() != null && "POST".equals(outcome.method())) {
            httpEnded.countDown();
          }
        });

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
                "clientInfo":{"name":"post-expiry-fixture","version":"1.0"}}}
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
                      .header("X-Old-Token", "true")
                      .body(
                          """
              {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                "name":"ping-client","arguments":{}}}
              """
                              .getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofInputStream());
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
            readers.submit(
                () -> {
                  try {
                    while (reader.readLine() != null) {
                      // Read until the committed response ends or its transport fails.
                    }
                  } catch (IOException ignored5) {
                    // A token deadline cannot replace already committed200 with a401.
                  }

                  return Instant.now();
                });
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
        await("fresh reply completes the outstanding native request")
            .atMost(Duration.ofSeconds(5))
            .until(() -> nativeCompleted.getCount() == 0);
        assertEquals(0, cancellations.get());
      }
    }
  }

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
}
