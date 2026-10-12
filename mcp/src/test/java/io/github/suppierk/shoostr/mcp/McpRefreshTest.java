package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class McpRefreshTest {
  @Test
  @Timeout(20)
  void expiringAnOldGetLeavesTheRefreshedSessionListeningStreamUsable() throws Exception {
    var security =
        new McpHttpSecurity(
            Set.of(),
            request ->
                new McpIdentity(
                    "alice:client",
                    McpTransportContext.EMPTY,
                    request.header("X-Old-Token").isPresent()
                        ? Instant.now().plusSeconds(2)
                        : Instant.now().plusSeconds(10)),
            (_, _) -> {});
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
                    } catch (IOException _) {
                      // Either EOF or a transport failure ends the committed old stream.
                    }

                    return null;
                  });
          assertThrows(TimeoutException.class, () -> oldEnded.get(100, TimeUnit.MILLISECONDS));
          oldEnded.get(5, TimeUnit.SECONDS);
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
