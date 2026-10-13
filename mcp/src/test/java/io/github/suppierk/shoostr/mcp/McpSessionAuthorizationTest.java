package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;

class McpSessionAuthorizationTest {
  @Test
  void rejectsAnotherOwnersSessionBeforeOperationPermissionChecks() throws Exception {
    var checkedOwners = new ConcurrentLinkedQueue<String>();
    var security =
        new McpHttpSecurity(
            Set.of(),
            request ->
                new McpIdentity(
                    request.header("X-Verified-Owner").orElseThrow(),
                    McpTransportContext.EMPTY,
                    null),
            (identity, message) -> {
              if (message instanceof McpSchema.JSONRPCRequest rpc && "ping".equals(rpc.method())) {
                checkedOwners.add(identity.sessionOwner());
              }
            });
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
                      .header("X-Verified-Owner", "alice:client")
                      .body(
                          """
              {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                "protocolVersion":"2025-11-25","capabilities":{},
                "clientInfo":{"name":"ownership-fixture","version":"1.0"}}}
              """
                              .getBytes(StandardCharsets.UTF_8)));
      var sessionId = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var ping =
          "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}".getBytes(StandardCharsets.UTF_8);
      var rejected =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .method("POST")
                      .header("Content-Type", "application/json")
                      .header("Accept", "application/json, text/event-stream")
                      .header("Mcp-Session-Id", sessionId)
                      .header("MCP-Protocol-Version", "2025-11-25")
                      .header("X-Verified-Owner", "bob:client")
                      .body(ping));
      assertEquals(404, rejected.statusCode());
      assertTrue(checkedOwners.isEmpty(), "Permission checks require an owned session first");
      var accepted =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .method("POST")
                      .header("Content-Type", "application/json")
                      .header("Accept", "application/json, text/event-stream")
                      .header("Mcp-Session-Id", sessionId)
                      .header("MCP-Protocol-Version", "2025-11-25")
                      .header("X-Verified-Owner", "alice:client")
                      .body(ping));
      assertEquals(200, accepted.statusCode());
      assertEquals(List.of("alice:client"), List.copyOf(checkedOwners));
    }
  }
}
