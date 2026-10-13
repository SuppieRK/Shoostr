package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    var mcp =
        Mcp.sync(
            new McpHttpSecurity(
                Set.of(),
                request -> {
                  if ("GET".equals(request.method())) {
                    owner.set(Thread.currentThread());
                    return new McpIdentity(
                        "alice:client", McpTransportContext.EMPTY, Instant.now().plusSeconds(1));
                  }

                  return new McpIdentity("alice:client", McpTransportContext.EMPTY, null);
                },
                (_, _) -> {}),
            transport -> McpServer.sync(transport).build());
    var app = new Shoostr();
    app.extensions(mcp)
        .routes(
            routes -> {
              routes.path("/mcp", group -> mcp.routes(group, selected));
              routes.get(
                  "/probe",
                  selected,
                  (_, response) ->
                      response.text(
                          (Thread.currentThread() == owner.get())
                              + ":"
                              + Thread.currentThread().isInterrupted()));
            });

    try (selected;
        var readers = Executors.newVirtualThreadPerTaskExecutor();
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
                "clientInfo":{"name":"executor-expiry","version":"1.0"}}}
              """
                              .getBytes(StandardCharsets.UTF_8)));
      assertEquals(200, initialized.statusCode());
      var listening =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .header("Accept", "text/event-stream")
                      .header(
                          "Mcp-Session-Id",
                          initialized.headers().firstValue("Mcp-Session-Id").orElseThrow()),
              HttpResponse.BodyHandlers.ofInputStream());

      try (var input = listening.body()) {
        assertEquals(200, listening.statusCode());
        var ended =
            readers.submit(
                () -> {
                  try {
                    input.readAllBytes();
                  } catch (IOException _) {
                    // A committed stream may end with transport failure rather than clean EOF.
                  }

                  return null;
                });
        assertThrows(TimeoutException.class, () -> ended.get(100, TimeUnit.MILLISECONDS));
        ended.get(4, TimeUnit.SECONDS);
      }

      var probe = test.send(request -> request.path("/probe"));
      assertEquals(200, probe.statusCode());
      assertEquals("true:false", new String(probe.body(), StandardCharsets.UTF_8));
    }
  }
}
