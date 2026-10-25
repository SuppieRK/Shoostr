package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Sinks;

class McpNotificationCleanupTest {
  @ParameterizedTest
  @ValueSource(strings = {"session deletion", "application shutdown"})
  @Timeout(20)
  void cancelsAPendingNativeNotificationWhenItsOwnerTerminates(String termination)
      throws Exception {
    var release = Sinks.<Void>empty();
    var pending = new CountDownLatch(1);
    var cancelled = new CountDownLatch(1);
    var cancellations = new AtomicInteger();
    var mcp =
        Mcp.async(
            new McpHttpSecurity(
                Set.of(),
                ignored -> new McpIdentity("public", McpTransportContext.EMPTY, null),
                (ignored2, ignored3) -> {}),
            transport ->
                McpServer.async(transport)
                    .requestTimeout(Duration.ofSeconds(5))
                    .rootsChangeHandler(
                        (ignored4, ignored5) ->
                            release
                                .asMono()
                                .doOnSubscribe(ignored6 -> pending.countDown())
                                .doOnCancel(
                                    () -> {
                                      cancellations.incrementAndGet();
                                      cancelled.countDown();
                                    }))
                    .build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var callers = Executors.newVirtualThreadPerTaskExecutor();
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
                  "protocolVersion":"2025-11-25","capabilities":{"roots":{"listChanged":true}},
                  "clientInfo":{"name":"notification-cleanup","version":"1.0"}}}
                """
                                .getBytes(StandardCharsets.UTF_8)));
        var session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
        var listening =
            test.send(
                request ->
                    request
                        .path("/mcp")
                        .header("Accept", "text/event-stream")
                        .header("Mcp-Session-Id", session),
                HttpResponse.BodyHandlers.ofInputStream());

        try (var reader =
            new BufferedReader(new InputStreamReader(listening.body(), StandardCharsets.UTF_8))) {
          var notification =
              callers.submit(
                  () ->
                      test.send(
                          request ->
                              request
                                  .path("/mcp")
                                  .method("POST")
                                  .header("Content-Type", "application/json")
                                  .header("Accept", "application/json, text/event-stream")
                                  .header("Mcp-Session-Id", session)
                                  .body(
                                      "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/roots/list_changed\"}"
                                          .getBytes(StandardCharsets.UTF_8))));
          String line;
          do {
            line = reader.readLine();
            assertNotNull(line, "The SDK must request current roots");
          } while (!line.startsWith("data:"));
          var roots =
              assertInstanceOf(
                  McpSchema.JSONRPCRequest.class,
                  McpSchema.deserializeJsonRpcMessage(
                      McpJsonDefaults.getMapper(), line.substring(5).strip()));
          assertEquals("roots/list", roots.method());
          var reply =
              McpJsonDefaults.getMapper()
                  .writeValueAsBytes(
                      McpSchema.JSONRPCResponse.result(roots.id(), Map.of("roots", List.of())));
          var replied =
              test.send(
                  request ->
                      request
                          .path("/mcp")
                          .method("POST")
                          .header("Content-Type", "application/json")
                          .header("Accept", "application/json, text/event-stream")
                          .header("Mcp-Session-Id", session)
                          .body(reply));
          assertEquals(202, replied.statusCode());
          assertTrue(pending.await(3, TimeUnit.SECONDS));
          assertEquals(
              202,
              notification.get(3, TimeUnit.SECONDS).statusCode(),
              "HTTP acceptance must not await the pending native callback");
          if ("session deletion".equals(termination)) {
            assertEquals(
                200,
                test.send(
                        request ->
                            request.path("/mcp").method("DELETE").header("Mcp-Session-Id", session))
                    .statusCode());
          } else {
            app.close();
          }

          assertTrue(
              cancelled.await(1, TimeUnit.SECONDS),
              "The session owner must cancel its pending native notification");
          assertEquals(1, cancellations.get());
        }
      } finally {
        release.tryEmitEmpty();
      }
    }
  }
}
