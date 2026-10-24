package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class McpMediaTypeTest {
  @ParameterizedTest
  @ValueSource(strings = {"", "application/json", "text/event-stream;q=0", "*/*;q=0"})
  void rejectsGetRequestsThatDoNotAcceptEventStreams(String accept) throws Exception {
    var mcp =
        Mcp.sync(
            new McpHttpSecurity(
                Set.of(),
                ignored -> new McpIdentity("public", McpTransportContext.EMPTY, null),
                (ignored2, ignored3) -> {}),
            transport -> McpServer.sync(transport).build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var response =
          test.send(
              request -> {
                request.path("/mcp");
                if (!accept.isEmpty()) {
                  request.header("Accept", accept);
                }
              });
      assertEquals(406, response.statusCode());
      assertTrue(response.headers().firstValue("Mcp-Session-Id").isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "application/json; charset=utf-8", "APPLICATION/JSON"})
  void acceptsAbsentOrExplicitJsonRequestRepresentations(String contentType) throws Exception {
    var mcp =
        Mcp.sync(
            new McpHttpSecurity(
                Set.of(),
                ignored4 -> new McpIdentity("public", McpTransportContext.EMPTY, null),
                (ignored5, ignored6) -> {}),
            transport -> McpServer.sync(transport).build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var response =
          test.send(
              request -> {
                request
                    .path("/mcp")
                    .method("POST")
                    .header("Accept", "application/json, text/event-stream")
                    .body(
                        """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-11-25","capabilities":{},
                  "clientInfo":{"name":"media-fixture","version":"1.0"}}}
                """
                            .getBytes(StandardCharsets.UTF_8));
                if (!contentType.isEmpty()) {
                  request.header("Content-Type", contentType);
                }
              });
      assertEquals(200, response.statusCode());
      assertTrue(response.headers().firstValue("Mcp-Session-Id").isPresent());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "text/plain",
        "application/octet-stream",
        "application/problem+json",
        "application/json, application/json",
        ""
      })
  void rejectsAnExplicitNonJsonRequestRepresentation(String contentType) throws Exception {
    var mcp =
        Mcp.sync(
            new McpHttpSecurity(
                Set.of(),
                ignored7 -> new McpIdentity("public", McpTransportContext.EMPTY, null),
                (ignored8, ignored9) -> {}),
            transport -> McpServer.sync(transport).build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var response =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .method("POST")
                      .header("Content-Type", contentType)
                      .header("Accept", "application/json, text/event-stream")
                      .body(
                          """
              {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                "protocolVersion":"2025-11-25","capabilities":{},
                "clientInfo":{"name":"media-fixture","version":"1.0"}}}
              """
                              .getBytes(StandardCharsets.UTF_8)));
      assertEquals(415, response.statusCode());
      assertTrue(response.headers().firstValue("Mcp-Session-Id").isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "application/json",
        "text/event-stream",
        "text/plain",
        "application/json, text/event-stream;q=0",
        "*/*, application/json;q=0"
      })
  void rejectsPostsThatDoNotAcceptBothJsonAndEventStreams(String accept) throws Exception {
    var mcp =
        Mcp.sync(
            new McpHttpSecurity(
                Set.of(),
                ignored10 -> new McpIdentity("public", McpTransportContext.EMPTY, null),
                (ignored11, ignored12) -> {}),
            transport -> McpServer.sync(transport).build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var response =
          test.send(
              request -> {
                request
                    .path("/mcp")
                    .method("POST")
                    .header("Content-Type", "application/json")
                    .body(
                        """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-11-25","capabilities":{},
                  "clientInfo":{"name":"media-fixture","version":"1.0"}}}
                """
                            .getBytes(StandardCharsets.UTF_8));
                if (!accept.isEmpty()) {
                  request.header("Accept", accept);
                }
              });
      assertEquals(406, response.statusCode());
      assertTrue(response.headers().firstValue("Mcp-Session-Id").isEmpty());
    }
  }
}
