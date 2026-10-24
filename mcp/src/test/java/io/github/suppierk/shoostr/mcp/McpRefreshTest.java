package io.github.suppierk.shoostr.mcp;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class McpRefreshTest {
  @Test
  @Timeout(20)
  void replacingAnOldGetClosesItsConnectionWithoutDetachingTheRefreshedListener() throws Exception {
    var oldExpiry = new AtomicReference<Instant>();
    var security =
        new McpHttpSecurity(
            Set.of(),
            request -> {
              var expiry =
                  Instant.now().plusSeconds(request.header("X-Old-Token").isPresent() ? 2 : 10);
              if (request.header("X-Old-Token").isPresent()) {
                oldExpiry.set(expiry);
              }

              return new McpIdentity("alice:client", McpTransportContext.EMPTY, expiry);
            },
            (ignored, ignored2) -> {});
    var mcp =
        Mcp.sync(
            security,
            transport ->
                McpServer.sync(transport)
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                    .build());
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
                "clientInfo":{"name":"refresh-fixture","version":"1.0"}}}
              """
                              .getBytes(StandardCharsets.UTF_8)));
      var sessionId = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var oldGet =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .header("Accept", "text/event-stream")
                      .header("Mcp-Session-Id", sessionId)
                      .header("MCP-Protocol-Version", "2025-11-25")
                      .header("X-Old-Token", "true"),
              HttpResponse.BodyHandlers.ofInputStream());

      try (var oldInput = oldGet.body()) {
        var refreshedGet =
            test.send(
                request ->
                    request
                        .path("/mcp")
                        .header("Accept", "text/event-stream")
                        .header("Mcp-Session-Id", sessionId)
                        .header("MCP-Protocol-Version", "2025-11-25"),
                HttpResponse.BodyHandlers.ofInputStream());

        try (var refreshedInput =
            new BufferedReader(
                new InputStreamReader(refreshedGet.body(), StandardCharsets.UTF_8))) {
          assertEquals(200, oldGet.statusCode());
          assertEquals(200, refreshedGet.statusCode());
          var oldEnded =
              readers.submit(
                  () -> {
                    try {
                      oldInput.readAllBytes();
                    } catch (IOException ignored3) {
                      // Either EOF or a transport failure ends the committed old stream.
                    }

                    return null;
                  });
          oldEnded.get(1, TimeUnit.SECONDS);
          var target = oldExpiry.get().plusMillis(100);
          var started = System.nanoTime();
          var initial = Duration.between(Instant.now(), target);
          await("the refreshed listener outlives the old token deadline")
              .pollInSameThread()
              .pollDelay(Duration.ZERO)
              .pollInterval(Duration.ofMillis(10))
              .atMost(Duration.ofSeconds(5))
              .until(
                  () -> {
                    if (Thread.interrupted()) {
                      throw new InterruptedException("The old expiry wait was interrupted");
                    }

                    return Duration.ofNanos(System.nanoTime() - started).compareTo(initial) >= 0
                        && !Instant.now().isBefore(target);
                  });
          mcp.server().notifyToolsListChanged();
          var received =
              readers.submit(
                  () -> {
                    String line;
                    do {
                      line = refreshedInput.readLine();
                      assertNotNull(line, "The refreshed stream must remain attached");
                    } while (!line.startsWith("data:"));
                    return McpSchema.deserializeJsonRpcMessage(
                        McpJsonDefaults.getMapper(), line.substring(5).strip());
                  });
          var notification =
              assertInstanceOf(
                  McpSchema.JSONRPCNotification.class, received.get(3, TimeUnit.SECONDS));
          assertEquals("notifications/tools/list_changed", notification.method());
        }
      }
    }
  }
}
