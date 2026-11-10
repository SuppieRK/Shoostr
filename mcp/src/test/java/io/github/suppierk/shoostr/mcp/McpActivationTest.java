package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import java.io.Closeable;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class McpActivationTest {
  @Test
  void inheritedSelectionAndHelperReselectionBindTheMountOnce() throws Exception {
    var mcp = transport();
    var app = new Shoostr();

    try (Closeable cleanup = app::close) {
      app.routes().path("/mcp", mcp::routes, lifecycle -> lifecycle.withExtension(mcp));

      try (var server = TestServer.start(app)) {
        var response =
            server.send(
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
                      "clientInfo":{"name":"fixture","version":"1"}}}
                    """
                                .getBytes(StandardCharsets.UTF_8)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertFalse(response.headers().firstValue("Mcp-Session-Id").orElseThrow().isBlank());
      }
    }
  }

  @Test
  void selectionAloneDoesNotCreateProtocolHandlers() throws Exception {
    var mcp = transport();
    var app = new Shoostr();

    try (Closeable cleanup = app::close) {
      app.routes()
          .get(
              "/ordinary",
              (request, response) -> response.text("ordinary"),
              lifecycle -> lifecycle.withExtension(mcp));

      try (var server = TestServer.start(app)) {
        assertEquals(
            "ordinary",
            server
                .send(request -> request.path("/ordinary"), HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            405, server.send(request -> request.path("/ordinary").method("POST")).statusCode());
        for (var method : new String[] {"GET", "POST", "DELETE"}) {
          assertEquals(
              404, server.send(request -> request.path("/mcp").method(method)).statusCode());
        }
      }
    }
  }

  @Test
  void duplicateMountsCloseTheApplicationBeforeItCanListen() throws Exception {
    var mcp = transport();
    var closed = new AtomicInteger();
    var app = new Shoostr().afterClose(closed::incrementAndGet);

    try (Closeable cleanup = app::close) {
      app.routes().path("/first", mcp::routes).path("/second", mcp::routes);
      assertEquals(
          "An MCP capability can be mounted only once",
          assertThrows(IllegalStateException.class, app::start).getMessage());
      assertEquals(1, closed.get());
      assertThrows(IllegalStateException.class, app::port);
      assertThrows(IllegalStateException.class, app::start);
    }

    assertEquals(1, closed.get());
  }

  @Test
  void nonliteralMountsCloseTheApplicationBeforeItCanListen() throws Exception {
    var mcp = transport();
    var closed = new AtomicInteger();
    var app = new Shoostr().afterClose(closed::incrementAndGet);

    try (Closeable cleanup = app::close) {
      app.routes().path("/{tenant}/mcp", mcp::routes);
      assertEquals(
          "An MCP mount must be a literal path",
          assertThrows(IllegalArgumentException.class, app::start).getMessage());
      assertEquals(1, closed.get());
      assertThrows(IllegalStateException.class, app::port);
      assertThrows(IllegalStateException.class, app::start);
    }

    assertEquals(1, closed.get());
  }

  private static Mcp<?> transport() {
    return Mcp.sync(
        new McpHttpSecurity(
            Set.of(),
            request -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (request, identity) -> {}),
        provider -> McpServer.sync(provider).serverInfo("activation-fixture", "1").build());
  }
}
