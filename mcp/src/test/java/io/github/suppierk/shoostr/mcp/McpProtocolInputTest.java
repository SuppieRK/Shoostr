package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class McpProtocolInputTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"protocolVersion\":{},\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"input\",\"version\":\"1\"}}",
        "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":[],"
            + "\"clientInfo\":{\"name\":\"input\",\"version\":\"1\"}}",
        "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":[]}",
        "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":1,\"version\":\"1\"}}",
        "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"input\",\"version\":\"1\"},\"_meta\":[1]}"
      })
  void rejectsInvalidInitializationParametersWithoutCreatingSession(String parameters)
      throws Exception {
    var mcp = createMcpForRejectsInvalidInitializationParametersWithoutCreatingSession();
    var app = new Shoostr();
    app.routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var response =
          sendResponseForRejectsInvalidInitializationParametersWithoutCreatingSession(
              test, parameters);

      assertEquals(400, response.statusCode());
      assertTrue(response.headers().firstValue("Mcp-Session-Id").isEmpty());

      var result =
          McpJsonDefaults.getMapper()
              .readValue(response.body(), new TypeRef<Map<String, Object>>() {});

      assertEquals(1, result.get("id"));
      assertEquals(Map.of("code", -32602, "message", "Invalid params"), result.get("error"));
    }
  }

  private Mcp<McpSyncServer>
      createMcpForRejectsInvalidInitializationParametersWithoutCreatingSession() {
    return Mcp.sync(
        new McpHttpSecurity(
            Set.of(),
            ignored -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (ignoredMcpIdentity, ignoredJsonRpcMessage) -> {}),
        transport -> McpServer.sync(transport).build());
  }

  private HttpResponse<String>
      sendResponseForRejectsInvalidInitializationParametersWithoutCreatingSession(
          TestServer test, String parameters) throws IOException, InterruptedException {
    return test.send(
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
  }

  @Test
  void rejectsInvalidUtf8InsteadOfReplacingBytesAndStartingSession() throws Exception {
    var mcp = createMcpForRejectsInvalidUtf8InsteadOfReplacingBytesAndStartingSession();
    var app = new Shoostr();
    app.routes(routes -> routes.path("/mcp", mcp::routes));
    var json = createJsonForRejectsInvalidUtf8InsteadOfReplacingBytesAndStartingSession();
    var body = json.getBytes(StandardCharsets.UTF_8);
    body[json.indexOf("broken")] = (byte) 0xff;

    try (var test = TestServer.start(app)) {
      var response =
          sendResponseForRejectsInvalidUtf8InsteadOfReplacingBytesAndStartingSession(test, body);

      assertEquals(400, response.statusCode());
      assertTrue(response.headers().firstValue("Mcp-Session-Id").isEmpty());

      var result =
          McpJsonDefaults.getMapper()
              .readValue(response.body(), new TypeRef<Map<String, Object>>() {});

      assertEquals(Map.of("code", -32700, "message", "Parse error"), result.get("error"));
      assertFalse(result.containsKey("id"));
    }
  }

  private Mcp<McpSyncServer>
      createMcpForRejectsInvalidUtf8InsteadOfReplacingBytesAndStartingSession() {
    return Mcp.sync(
        new McpHttpSecurity(
            Set.of(),
            ignoredRequest -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (ignoredMcpIdentity, ignoredJsonRpcMessage) -> {}),
        transport -> McpServer.sync(transport).build());
  }

  private String createJsonForRejectsInvalidUtf8InsteadOfReplacingBytesAndStartingSession() {
    return
        """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
          "protocolVersion":"2025-11-25","capabilities":{},
          "clientInfo":{"name":"broken","version":"1.0"}}}
        """;
  }

  private HttpResponse<String>
      sendResponseForRejectsInvalidUtf8InsteadOfReplacingBytesAndStartingSession(
          TestServer test, byte[] body) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/mcp")
                .method("POST")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .body(body),
        HttpResponse.BodyHandlers.ofString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"GET", "POST", "DELETE"})
  @Timeout(15)
  void rejectsAnUnsupportedHttpProtocolVersionBeforeNativeDispatch(String method) throws Exception {
    var mcp = createMcpForRejectsAnUnsupportedHttpProtocolVersionBeforeNativeDispatch();
    var app = new Shoostr();
    app.routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var initialized =
          sendInitializedRequestForRejectsAnUnsupportedHttpProtocolVersionBeforeNativeDispatch(
              test);

      assertEquals(200, initialized.statusCode());

      var session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var response =
          sendResponseForRejectsAnUnsupportedHttpProtocolVersionBeforeNativeDispatch(
              test, method, session);

      try {
        assertEquals(400, response.statusCode());
      } finally {
        response.body().close();
      }
    }
  }

  private Mcp<McpSyncServer>
      createMcpForRejectsAnUnsupportedHttpProtocolVersionBeforeNativeDispatch() {
    return Mcp.sync(
        new McpHttpSecurity(
            Set.of(),
            ignoredRequest -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (ignoredMcpIdentity, ignoredJsonRpcMessage) -> {}),
        transport -> McpServer.sync(transport).serverInfo("input", "1.0").build());
  }

  private HttpResponse<byte[]>
      sendInitializedRequestForRejectsAnUnsupportedHttpProtocolVersionBeforeNativeDispatch(
          TestServer test) throws IOException, InterruptedException {
    return test.send(request ->
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
  }

  private HttpResponse<InputStream>
      sendResponseForRejectsAnUnsupportedHttpProtocolVersionBeforeNativeDispatch(
          TestServer test, String method, String session) throws IOException, InterruptedException {
    return test.send(
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
  }

  @ParameterizedTest
  @MethodSource("invalidEnvelopes")
  void rejectsInvalidEnvelopesAsRequestsRatherThanParseOrServerErrors(
      String body, Object expectedId) throws Exception {
    var mcp = createMcpForRejectsInvalidEnvelopesAsRequestsRatherThanParseOrServerErrors();
    var app = new Shoostr();
    app.routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var response =
          sendResponseForRejectsInvalidEnvelopesAsRequestsRatherThanParseOrServerErrors(test, body);

      assertEquals(400, response.statusCode());
      assertTrue(response.headers().firstValue("Mcp-Session-Id").isEmpty());

      var result =
          McpJsonDefaults.getMapper()
              .readValue(response.body(), new TypeRef<Map<String, Object>>() {});

      assertEquals("2.0", result.get("jsonrpc"));
      assertEquals(Map.of("code", -32600, "message", "Invalid Request"), result.get("error"));
      if (expectedId == null) {
        assertFalse(result.containsKey("id"));
      } else {
        assertEquals(expectedId, result.get("id"));
      }
    }
  }

  private static Stream<Arguments> invalidEnvelopes() {
    return Stream.of(
        arguments("[]", null),
        arguments("{}", null),
        arguments("null", null),
        arguments("1", null),
        arguments("{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"ping\"}", 1),
        arguments("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"ping\"}", null),
        arguments("{\"jsonrpc\":\"2.0\",\"id\":1.5,\"method\":\"ping\"}", null),
        arguments("{\"jsonrpc\":\"2.0\",\"id\":\"request-1\",\"method\":1}", "request-1"),
        arguments("{\"jsonrpc\":\"2.0\",\"id\":2147483648,\"method\":1}", 2_147_483_648L),
        arguments("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":[]}", 1),
        arguments("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"result\":{}}", 1),
        arguments("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{},\"error\":{}}", 1),
        arguments(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":\"-32603\",\"message\":\"failure\"}}",
            1));
  }

  private Mcp<McpSyncServer>
      createMcpForRejectsInvalidEnvelopesAsRequestsRatherThanParseOrServerErrors() {
    return Mcp.sync(
        new McpHttpSecurity(
            Set.of(),
            ignoredRequest -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (ignoredMcpIdentity, ignoredJsonRpcMessage) -> {}),
        transport -> McpServer.sync(transport).serverInfo("input", "1.0").build());
  }

  private HttpResponse<String>
      sendResponseForRejectsInvalidEnvelopesAsRequestsRatherThanParseOrServerErrors(
          TestServer test, String body) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/mcp")
                .method("POST")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .body(body.getBytes(StandardCharsets.UTF_8)),
        HttpResponse.BodyHandlers.ofString());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{",
        "{} {}",
        "{\"jsonrpc\":\"2.0\",\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"
      })
  void rejectsMalformedOrAmbiguousJsonBeforeStartingSessionOrEventStream(String body)
      throws Exception {
    var mcp = createMcpForRejectsMalformedOrAmbiguousJsonBeforeStartingSessionOrEventStream();
    var app = new Shoostr();
    app.routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var response =
          sendResponseForRejectsMalformedOrAmbiguousJsonBeforeStartingSessionOrEventStream(
              test, body);

      assertEquals(400, response.statusCode());
      assertTrue(response.headers().firstValue("Mcp-Session-Id").isEmpty());
      assertEquals("application/json", response.headers().firstValue("Content-Type").orElseThrow());

      var result =
          McpJsonDefaults.getMapper()
              .readValue(response.body(), new TypeRef<Map<String, Object>>() {});

      assertEquals("2.0", result.get("jsonrpc"));
      assertEquals(Map.of("code", -32700, "message", "Parse error"), result.get("error"));
      assertFalse(result.containsKey("id"));
    }
  }

  private Mcp<McpSyncServer>
      createMcpForRejectsMalformedOrAmbiguousJsonBeforeStartingSessionOrEventStream() {
    return Mcp.sync(
        new McpHttpSecurity(
            Set.of(),
            ignoredRequest -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (ignoredMcpIdentity, ignoredJsonRpcMessage) -> {}),
        transport -> McpServer.sync(transport).serverInfo("input", "1.0").build());
  }

  private HttpResponse<String>
      sendResponseForRejectsMalformedOrAmbiguousJsonBeforeStartingSessionOrEventStream(
          TestServer test, String body) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/mcp")
                .method("POST")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .body(body.getBytes(StandardCharsets.UTF_8)),
        HttpResponse.BodyHandlers.ofString());
  }
}
