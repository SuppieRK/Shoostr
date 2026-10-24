package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Extension;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class McpShutdownTest {
  @Test
  void closesAnIdleListeningStreamWhenTheApplicationStops() throws Exception {
    var security =
        new McpHttpSecurity(
            Set.of(),
            ignored -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (ignored2, ignored3) -> {});
    var mcp = Mcp.sync(security, transport -> McpServer.sync(transport).build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
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
                "clientInfo":{"name":"shutdown-fixture","version":"1.0"}}}
              """
                              .getBytes(StandardCharsets.UTF_8)));
      var sessionId = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var listening =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .header("Accept", "text/event-stream")
                      .header("Mcp-Session-Id", sessionId)
                      .header("MCP-Protocol-Version", "2025-11-25"),
              HttpResponse.BodyHandlers.ofInputStream());

      try (var input = listening.body()) {
        assertEquals(200, listening.statusCode());
        assertDoesNotThrow(app::close);
        assertDoesNotThrow(input::readAllBytes);
      }
    }
  }

  @Test
  void closesAPendingPostStreamWhenTheApplicationStops() throws Exception {
    var security =
        new McpHttpSecurity(
            Set.of(),
            ignored4 -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (ignored5, ignored6) -> {});
    var tool =
        McpSchema.Tool.builder("wait", Map.of("type", "object", "properties", Map.of())).build();
    var mcp =
        Mcp.async(
            security,
            transport ->
                McpServer.async(transport)
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .tools(
                        McpServerFeatures.AsyncToolSpecification.builder()
                            .tool(tool)
                            .callHandler((ignored7, ignored8) -> Mono.never())
                            .build())
                    .build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
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
                "clientInfo":{"name":"shutdown-fixture","version":"1.0"}}}
              """
                              .getBytes(StandardCharsets.UTF_8)));
      var sessionId = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var pending =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .method("POST")
                      .header("Content-Type", "application/json")
                      .header("Accept", "application/json, text/event-stream")
                      .header("Mcp-Session-Id", sessionId)
                      .header("MCP-Protocol-Version", "2025-11-25")
                      .body(
                          """
              {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"wait","arguments":{}}}
              """
                              .getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofInputStream());

      try (var input = pending.body()) {
        assertEquals(200, pending.statusCode());
        assertDoesNotThrow(app::close);
        assertDoesNotThrow(input::readAllBytes);
      }
    }
  }

  @Test
  void rejectsStreamRegistrationThatLosesTheRaceWithShutdown() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var stopped = new CountDownLatch(1);
    var security =
        new McpHttpSecurity(
            Set.of(),
            request -> {
              if ("GET".equals(request.method())) {
                entered.countDown();

                try {
                  if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test timed out");
                  }
                } catch (InterruptedException error) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException(error);
                }
              }

              return new McpIdentity("public", McpTransportContext.EMPTY, null);
            },
            (ignored9, ignored10) -> {});
    var mcp = Mcp.sync(security, transport -> McpServer.sync(transport).build());
    class ShutdownWitness implements Extension<Void> {
      @Override
      public void beforeStop() {
        stopped.countDown();
      }
    }
    Extension<Void> witness = new ShutdownWitness();
    var app = new Shoostr();
    app.extensions(witness, mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app);
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
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
                "clientInfo":{"name":"shutdown-fixture","version":"1.0"}}}
              """
                              .getBytes(StandardCharsets.UTF_8)));
      var sessionId = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var opening =
          workers.submit(
              () ->
                  test.send(
                      request ->
                          request
                              .path("/mcp")
                              .header("Accept", "text/event-stream")
                              .header("Mcp-Session-Id", sessionId)
                              .header("MCP-Protocol-Version", "2025-11-25")));

      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var closing =
            workers.submit(
                () -> {
                  app.close();
                  return null;
                });
        assertTrue(stopped.await(5, TimeUnit.SECONDS));
        release.countDown();
        assertEquals(503, opening.get(5, TimeUnit.SECONDS).statusCode());
        closing.get(5, TimeUnit.SECONDS);
      } finally {
        release.countDown();
      }
    }
  }
}
