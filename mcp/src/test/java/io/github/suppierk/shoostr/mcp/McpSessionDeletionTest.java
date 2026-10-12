package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class McpSessionDeletionTest {
  @Test
  @Timeout(20)
  void deletionEndsListeningRequestsAdmittedBeforeTheirFirstFlush() throws Exception {
    var opening = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    var security =
        new McpHttpSecurity(
            Set.of(),
            _ -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (_, _) -> {});
    var mcp = Mcp.sync(security, transport -> McpServer.sync(transport).build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));
    app.beforeResponseFlush(
        (request, _) -> {
          if (request.header("X-Hold-Flush").isPresent()) {
            opening.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
              throw new IllegalStateException("test flush was not released");
            }
          }
        });

    try (var workers = Executors.newVirtualThreadPerTaskExecutor();
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
                "clientInfo":{"name":"delete-race-fixture","version":"1.0"}}}
              """
                              .getBytes(StandardCharsets.UTF_8)));
      var sessionId = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var responses = new ArrayList<Future<HttpResponse<InputStream>>>();
      for (int stream = 0; stream < 2; stream++) {
        responses.add(
            workers.submit(
                () ->
                    test.send(
                        request ->
                            request
                                .path("/mcp")
                                .header("Accept", "text/event-stream")
                                .header("Mcp-Session-Id", sessionId)
                                .header("MCP-Protocol-Version", "2025-11-25")
                                .header("X-Hold-Flush", "true"),
                        HttpResponse.BodyHandlers.ofInputStream())));
      }

      try {
        assertTrue(opening.await(5, TimeUnit.SECONDS));
        var deleted =
            test.send(
                request ->
                    request
                        .path("/mcp")
                        .method("DELETE")
                        .header("Mcp-Session-Id", sessionId)
                        .header("MCP-Protocol-Version", "2025-11-25"));
        assertEquals(200, deleted.statusCode());
        release.countDown();
        for (var response : responses) {
          var listening = response.get(5, TimeUnit.SECONDS);

          try (var body = listening.body()) {
            assertEquals(200, listening.statusCode());
            workers.submit(body::readAllBytes).get(3, TimeUnit.SECONDS);
          }
        }
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  @Timeout(15)
  void deletionEndsEveryListeningStreamInTheSession() throws Exception {
    var security =
        new McpHttpSecurity(
            Set.of(),
            _ -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (_, _) -> {});
    var mcp = Mcp.sync(security, transport -> McpServer.sync(transport).build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var workers = Executors.newVirtualThreadPerTaskExecutor();
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
                "clientInfo":{"name":"delete-fixture","version":"1.0"}}}
              """
                              .getBytes(StandardCharsets.UTF_8)));
      var sessionId = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var first =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .header("Accept", "text/event-stream")
                      .header("Mcp-Session-Id", sessionId)
                      .header("MCP-Protocol-Version", "2025-11-25"),
              HttpResponse.BodyHandlers.ofInputStream());

      try (var firstBody = first.body()) {
        var second =
            test.send(
                request ->
                    request
                        .path("/mcp")
                        .header("Accept", "text/event-stream")
                        .header("Mcp-Session-Id", sessionId)
                        .header("MCP-Protocol-Version", "2025-11-25"),
                HttpResponse.BodyHandlers.ofInputStream());

        try (var secondBody = second.body()) {
          assertEquals(200, first.statusCode());
          assertEquals(200, second.statusCode());
          var firstEnded = workers.submit(firstBody::readAllBytes);
          var secondEnded = workers.submit(secondBody::readAllBytes);
          var deleted =
              test.send(
                  request ->
                      request
                          .path("/mcp")
                          .method("DELETE")
                          .header("Mcp-Session-Id", sessionId)
                          .header("MCP-Protocol-Version", "2025-11-25"));
          assertEquals(200, deleted.statusCode());
          firstEnded.get(3, TimeUnit.SECONDS);
          secondEnded.get(3, TimeUnit.SECONDS);
        }
      }
    }
  }
}
