package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpServer;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class McpProtocolInputTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"protocolVersion\":{},\"capabilities\":{},\"clientInfo\":{\"name\":\"input\",\"version\":\"1\"}}",
        "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":[],\"clientInfo\":{\"name\":\"input\",\"version\":\"1\"}}",
        "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":[]}",
        "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":1,\"version\":\"1\"}}"
      })
  void rejectsInvalidInitializationParametersWithoutCreatingASession(String parameters)
      throws Exception {
    var mcp =
        Mcp.sync(
            new McpHttpSecurity(
                Set.of(),
                _ -> new McpIdentity("public", McpTransportContext.EMPTY, null),
                (_, _) -> {}),
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
                      .header("Content-Type", "application/json")
                      .header("Accept", "application/json, text/event-stream")
                      .body(
                          ("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                                  + parameters
                                  + "}")
                              .getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(400, response.statusCode());
      assertTrue(response.headers().firstValue("Mcp-Session-Id").isEmpty());
      var result =
          McpJsonDefaults.getMapper()
              .readValue(response.body(), new TypeRef<Map<String, Object>>() {});
      assertEquals(1, result.get("id"));
      assertEquals(Map.of("code", -32602, "message", "Invalid params"), result.get("error"));
    }
  }

  @Test
  void rejectsInvalidUtf8InsteadOfReplacingBytesAndStartingASession() throws Exception {
    var mcp =
        Mcp.sync(
            new McpHttpSecurity(
                Set.of(),
                _ -> new McpIdentity("public", McpTransportContext.EMPTY, null),
                (_, _) -> {}),
            transport -> McpServer.sync(transport).build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));
    var json =
        """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
          "protocolVersion":"2025-11-25","capabilities":{},
          "clientInfo":{"name":"broken","version":"1.0"}}}
        """;
    var body = json.getBytes(StandardCharsets.UTF_8);
    body[json.indexOf("broken")] = (byte) 0xff;

    try (var test = TestServer.start(app)) {
      var response =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .method("POST")
                      .header("Content-Type", "application/json")
                      .header("Accept", "application/json, text/event-stream")
                      .body(body),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(400, response.statusCode());
      assertTrue(response.headers().firstValue("Mcp-Session-Id").isEmpty());
      var result =
          McpJsonDefaults.getMapper()
              .readValue(response.body(), new TypeRef<Map<String, Object>>() {});
      assertEquals(Map.of("code", -32700, "message", "Parse error"), result.get("error"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"GET", "POST", "DELETE"})
  @Timeout(15)
  void rejectsAnUnsupportedHttpProtocolVersionBeforeNativeDispatch(String method) throws Exception {
    var mcp =
        Mcp.sync(
            new McpHttpSecurity(
                Set.of(),
                _ -> new McpIdentity("public", McpTransportContext.EMPTY, null),
                (_, _) -> {}),
            transport -> McpServer.sync(transport).serverInfo("input", "1.0").build());
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
                "clientInfo":{"name":"input","version":"1.0"}}}
              """
                              .getBytes(StandardCharsets.UTF_8)));
      assertEquals(200, initialized.statusCode());
      var session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var response =
          test.send(
              request -> {
                request
                    .path("/mcp")
                    .method(method)
                    .header("Mcp-Session-Id", session)
                    .header("MCP-Protocol-Version", "2099-01-01")
                    .header("Accept", "application/json, text/event-stream");
                if ("POST".equals(method)) {
                  request
                      .header("Content-Type", "application/json")
                      .body(
                          "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}"
                              .getBytes(StandardCharsets.UTF_8));
                }
              },
              HttpResponse.BodyHandlers.ofInputStream());

      try {
        assertEquals(400, response.statusCode());
      } finally {
        response.body().close();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "[]",
        "{}",
        "null",
        "1",
        "{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"ping\"}",
        "{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"ping\"}",
        "{\"jsonrpc\":\"2.0\",\"id\":1.5,\"method\":\"ping\"}",
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":1}",
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":[]}",
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"result\":{}}",
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{},\"error\":{}}",
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":\"-32603\",\"message\":\"failure\"}}"
      })
  void rejectsInvalidEnvelopesAsRequestsRatherThanParseOrServerErrors(String body)
      throws Exception {
    var mcp =
        Mcp.sync(
            new McpHttpSecurity(
                Set.of(),
                _ -> new McpIdentity("public", McpTransportContext.EMPTY, null),
                (_, _) -> {}),
            transport -> McpServer.sync(transport).serverInfo("input", "1.0").build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var response =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .method("POST")
                      .header("Content-Type", "application/json")
                      .header("Accept", "application/json, text/event-stream")
                      .body(body.getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(400, response.statusCode());
      assertTrue(response.headers().firstValue("Mcp-Session-Id").isEmpty());
      var result =
          McpJsonDefaults.getMapper()
              .readValue(response.body(), new TypeRef<Map<String, Object>>() {});
      assertEquals("2.0", result.get("jsonrpc"));
      assertEquals(Map.of("code", -32600, "message", "Invalid Request"), result.get("error"));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{",
        "{} {}",
        "{\"jsonrpc\":\"2.0\",\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"
      })
  void rejectsMalformedOrAmbiguousJsonBeforeStartingASessionOrEventStream(String body)
      throws Exception {
    var mcp =
        Mcp.sync(
            new McpHttpSecurity(
                Set.of(),
                _ -> new McpIdentity("public", McpTransportContext.EMPTY, null),
                (_, _) -> {}),
            transport -> McpServer.sync(transport).serverInfo("input", "1.0").build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var response =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .method("POST")
                      .header("Content-Type", "application/json")
                      .header("Accept", "application/json, text/event-stream")
                      .body(body.getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(400, response.statusCode());
      assertTrue(response.headers().firstValue("Mcp-Session-Id").isEmpty());
      assertEquals("application/json", response.headers().firstValue("Content-Type").orElseThrow());
      var result =
          McpJsonDefaults.getMapper()
              .readValue(response.body(), new TypeRef<Map<String, Object>>() {});
      assertEquals("2.0", result.get("jsonrpc"));
      assertEquals(Map.of("code", -32700, "message", "Parse error"), result.get("error"));
    }
  }
}
