package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class McpMediaTypeTest {
  @ParameterizedTest
  @ValueSource(strings = {"", "application/json", "text/event-stream;q=0", "*/*;q=0"})
  void rejectsGetRequestsThatDoNotAcceptEventStreams(String accept) throws Exception {
    var mcp = createMcp();
    var app = new Shoostr();
    app.routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var response = sendResponseForRejectsGetRequestsThatDoNotAcceptEventStreams(test, accept);

      assertEquals(406, response.statusCode());
      assertTrue(response.headers().firstValue("Mcp-Session-Id").isEmpty());
    }
  }

  private HttpResponse<byte[]> sendResponseForRejectsGetRequestsThatDoNotAcceptEventStreams(
      TestServer test, String accept) throws IOException, InterruptedException {
    return test.send(
        request -> {
          request.path("/mcp");
          if (!accept.isEmpty()) {
            request.header("Accept", accept);
          }
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "application/json; charset=utf-8", "APPLICATION/JSON"})
  void acceptsAbsentOrExplicitJsonRequestRepresentations(String contentType) throws Exception {
    var mcp = createMcp();
    var app = new Shoostr();
    app.routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var response =
          sendResponseForAcceptsAbsentOrExplicitJsonRequestRepresentations(test, contentType);

      assertEquals(200, response.statusCode());
      assertTrue(response.headers().firstValue("Mcp-Session-Id").isPresent());
    }
  }

  private HttpResponse<byte[]> sendResponseForAcceptsAbsentOrExplicitJsonRequestRepresentations(
      TestServer test, String contentType) throws IOException, InterruptedException {
    return test.send(
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
    var mcp = createMcp();
    var app = new Shoostr();
    app.routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var response =
          sendResponseForRejectsAnExplicitNonJsonRequestRepresentation(test, contentType);

      assertEquals(415, response.statusCode());
      assertTrue(response.headers().firstValue("Mcp-Session-Id").isEmpty());
    }
  }

  private HttpResponse<byte[]> sendResponseForRejectsAnExplicitNonJsonRequestRepresentation(
      TestServer test, String contentType) throws IOException, InterruptedException {
    return test.send(
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
    var mcp = createMcp();
    var app = new Shoostr();
    app.routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var response =
          sendResponseForRejectsPostsThatDoNotAcceptBothJsonAndEventStreams(test, accept);

      assertEquals(406, response.statusCode());
      assertTrue(response.headers().firstValue("Mcp-Session-Id").isEmpty());
    }
  }

  private Mcp<McpSyncServer> createMcp() {
    return Mcp.sync(
        new McpHttpSecurity(
            Set.of(),
            ignored -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (ignoredMcpIdentity, ignoredJSONRPCMessage) -> {}),
        transport -> McpServer.sync(transport).build());
  }

  private HttpResponse<byte[]> sendResponseForRejectsPostsThatDoNotAcceptBothJsonAndEventStreams(
      TestServer test, String accept) throws IOException, InterruptedException {
    return test.send(
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
  }
}
