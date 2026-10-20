package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class McpCompletedDeadlineTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @Timeout(20)
  void aCompletedStreamCannotInterruptItsReusedWorkerAtTheOldDeadline(boolean forkJoin)
      throws Exception {
    ExecutorService selected =
        forkJoin
            ? new ForkJoinPool(1)
            : Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("mcp-completed-worker").factory());
    var owner = new AtomicReference<Thread>();
    var expiry = new AtomicReference<Instant>();
    var mcp =
        Mcp.sync(
            new McpHttpSecurity(
                Set.of(),
                request -> {
                  if (request.header("X-Expiring").isPresent()) {
                    var deadline = Instant.now().plusSeconds(2);
                    owner.set(Thread.currentThread());
                    expiry.set(deadline);
                    return new McpIdentity("public", McpTransportContext.EMPTY, deadline);
                  }

                  return new McpIdentity("public", McpTransportContext.EMPTY, null);
                },
                (_, _) -> {}),
            transport -> McpServer.sync(transport).build());
    var app = new Shoostr();
    app.extensions(mcp)
        .routes(
            routes -> {
              routes.path("/mcp", group -> mcp.routes(group, selected));
              routes.get(
                  "/occupy",
                  selected,
                  (_, response) -> {
                    var target = Objects.requireNonNull(expiry.get()).plusMillis(100);
                    var started = System.nanoTime();
                    var remaining = Duration.between(Instant.now(), target);
                    var waiting = new CountDownLatch(1);
                    do {
                      var budget = TimeUnit.SECONDS.toNanos(5) - (System.nanoTime() - started);
                      assertTrue(
                          budget > 0,
                          "Wall clock did not pass the verified expiry within five seconds");
                      var delay =
                          remaining.compareTo(Duration.ofNanos(budget)) > 0
                              ? budget
                              : Math.max(0, remaining.toNanos());
                      assertFalse(
                          waiting.await(delay, TimeUnit.NANOSECONDS),
                          "The worker should remain occupied until the old deadline has passed");
                      remaining = Duration.between(Instant.now(), target);
                    } while (remaining.isPositive());
                    response.text(
                        (Thread.currentThread() == owner.get())
                            + ":"
                            + Thread.currentThread().isInterrupted());
                  });
            });

    try (selected;
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
                "clientInfo":{"name":"completed-deadline","version":"1.0"}}}
              """
                              .getBytes(StandardCharsets.UTF_8)));
      var completed =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .method("POST")
                      .header("Content-Type", "application/json")
                      .header("Accept", "application/json, text/event-stream")
                      .header(
                          "Mcp-Session-Id",
                          initialized.headers().firstValue("Mcp-Session-Id").orElseThrow())
                      .header("X-Expiring", "true")
                      .body(
                          "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}"
                              .getBytes(StandardCharsets.UTF_8)));
      assertEquals(200, completed.statusCode());
      assertTrue(
          Instant.now().isBefore(Objects.requireNonNull(expiry.get())),
          "Complete the control stream before its original deadline");
      var occupied = test.send(request -> request.path("/occupy"));
      assertEquals(200, occupied.statusCode());
      assertEquals("true:false", new String(occupied.body(), StandardCharsets.UTF_8));
      assertTrue(
          Instant.now().isAfter(Objects.requireNonNull(expiry.get())),
          "Keep the same worker busy past the original deadline");
    }
  }
}
