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
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class McpExecutorExpiryTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @Timeout(20)
  void expiresAStreamWithoutInterruptingTheReusedCallerOwnedWorker(boolean forkJoin)
      throws Exception {
    ExecutorService selected =
        forkJoin
            ? new ForkJoinPool(1)
            : Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("mcp-http-worker").factory());
    var owner = new AtomicReference<Thread>();
    var expiry = new AtomicReference<Instant>();
    var mcp =
        createMcpForExpiresAStreamWithoutInterruptingTheReusedCallerOwnedWorker(owner, expiry);
    var app = new Shoostr();
    app.routes(
        routes -> {
          routes.path("/mcp", group -> mcp.routes(group, selected));
          routes.get(
              "/probe",
              selected,
              (ignoredRequest, response) ->
                  response.text(
                      (Thread.currentThread() == owner.get())
                          + ":"
                          + Thread.currentThread().isInterrupted()));
        });

    try (selected;
        var readers = Executors.newVirtualThreadPerTaskExecutor();
        var test = TestServer.start(app)) {
      var initialized =
          sendInitializedRequestForExpiresAStreamWithoutInterruptingTheReusedCallerOwnedWorker(
              test);

      assertEquals(200, initialized.statusCode());

      var listening =
          sendListeningRequestForExpiresAStreamWithoutInterruptingTheReusedCallerOwnedWorker(
              test, initialized);

      try (var input = listening.body()) {
        assertEquals(200, listening.statusCode());

        var ended =
            submitEndedForExpiresAStreamWithoutInterruptingTheReusedCallerOwnedWorker(
                readers, input);
        var verifiedExpiry = expiry.get();
        awaitTermination(verifiedExpiry, ended::isDone);

        assertNotBeforeExpiry(verifiedExpiry, ended.get());
      }

      var probe = test.send(request -> request.path("/probe"));

      assertEquals(200, probe.statusCode());
      assertEquals("true:false", new String(probe.body(), StandardCharsets.UTF_8));
    }
  }

  private Mcp<McpSyncServer>
      createMcpForExpiresAStreamWithoutInterruptingTheReusedCallerOwnedWorker(
          AtomicReference<Thread> owner, AtomicReference<Instant> expiry) {
    return Mcp.sync(
        new McpHttpSecurity(
            Set.of(),
            request -> {
              if ("GET".equals(request.method())) {
                owner.set(Thread.currentThread());
                var verifiedExpiry = Instant.now().plusSeconds(1);
                expiry.set(verifiedExpiry);

                return new McpIdentity("alice:client", McpTransportContext.EMPTY, verifiedExpiry);
              }

              return new McpIdentity("alice:client", McpTransportContext.EMPTY, null);
            },
            (ignored, ignoredJSONRPCMessage) -> {}),
        transport -> McpServer.sync(transport).build());
  }

  private HttpResponse<byte[]>
      sendInitializedRequestForExpiresAStreamWithoutInterruptingTheReusedCallerOwnedWorker(
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
                "clientInfo":{"name":"executor-expiry","version":"1.0"}}}
              """
                        .getBytes(StandardCharsets.UTF_8)));
  }

  private HttpResponse<InputStream>
      sendListeningRequestForExpiresAStreamWithoutInterruptingTheReusedCallerOwnedWorker(
          TestServer test, HttpResponse<byte[]> initialized)
          throws IOException, InterruptedException {
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

  private Future<Instant> submitEndedForExpiresAStreamWithoutInterruptingTheReusedCallerOwnedWorker(
      ExecutorService readers, InputStream input) {
    return readers.submit(
        () -> {
          try {
            input.readAllBytes();
          } catch (IOException ignoredFailure) {
            // A committed stream may end with transport failure rather than clean EOF.
          }

          return Instant.now();
        });
  }
}
