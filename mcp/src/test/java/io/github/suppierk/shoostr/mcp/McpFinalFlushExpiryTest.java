package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class McpFinalFlushExpiryTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @Timeout(20)
  void interruptsTheFrameworkFinalFlushAfterTheHandlerReturns(boolean forkJoin) throws Exception {
    ExecutorService selected =
        forkJoin
            ? new ForkJoinPool(1)
            : Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("mcp-final-flush").factory());
    var finalFlush = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var completed = new CountDownLatch(1);
    var hold = new CountDownLatch(1);
    var mcp =
        Mcp.sync(
            new McpHttpSecurity(
                Set.of(),
                request ->
                    new McpIdentity(
                        "public",
                        McpTransportContext.EMPTY,
                        request.header("X-Expiring").isPresent()
                            ? Instant.now().plusSeconds(2)
                            : null),
                (ignored, ignored2) -> {}),
            transport -> McpServer.sync(transport).build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", group -> mcp.routes(group, selected)));
    app.afterRouteHandler(
        (request, ignored3) -> {
          if (request.header("X-Expiring").isPresent()) {
            request.attribute("handler-returned", true);
          }
        });
    app.beforeResponseFlush(
        (request, ignored4) -> {
          if (request.attribute("handler-returned").filter(Boolean.TRUE::equals).isPresent()) {
            finalFlush.countDown();

            try {
              if (!hold.await(5, TimeUnit.SECONDS)) {
                throw new IOException("The verified deadline did not interrupt the final flush");
              }
            } catch (InterruptedException failure) {
              interrupted.countDown();
              throw failure;
            }
          }
        });
    app.afterRequest(
        outcome -> {
          if ("POST".equals(outcome.method()) && outcome.applicationFailure() != null) {
            completed.countDown();
          }
        });

    try (selected;
        var readers = Executors.newVirtualThreadPerTaskExecutor();
        var test = TestServer.start(app)) {
      try {
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
                  "clientInfo":{"name":"final-flush","version":"1.0"}}}
                """
                                .getBytes(StandardCharsets.UTF_8)));
        var session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
        var response =
            test.send(
                request ->
                    request
                        .path("/mcp")
                        .method("POST")
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .header("Mcp-Session-Id", session)
                        .header("X-Expiring", "true")
                        .body(
                            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}"
                                .getBytes(StandardCharsets.UTF_8)),
                HttpResponse.BodyHandlers.ofInputStream());

        try (var body = response.body()) {
          assertEquals(200, response.statusCode());
          var ended =
              readers.submit(
                  () -> {
                    try {
                      body.readAllBytes();
                    } catch (IOException ignored5) {
                      // Expiry aborts an already committed stream rather than replacing its status.
                    }

                    return null;
                  });
          assertTrue(
              finalFlush.await(1, TimeUnit.SECONDS), "Hold only the post-handler final flush");
          assertTrue(
              interrupted.await(4, TimeUnit.SECONDS), "Expiry must interrupt final ownership");
          assertTrue(completed.await(2, TimeUnit.SECONDS), "The exchange must terminate as failed");
          ended.get(2, TimeUnit.SECONDS);
        }
      } finally {
        hold.countDown();
      }
    }
  }
}
