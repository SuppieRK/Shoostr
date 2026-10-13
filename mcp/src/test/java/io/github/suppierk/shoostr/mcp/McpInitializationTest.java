package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;

class McpInitializationTest {
  @Test
  void initializesTheConfiguredNativeServerAtTheExplicitMount() throws Exception {
    var security =
        new McpHttpSecurity(
            Set.of(),
            _ -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (_, _) -> {});
    var mcp =
        Mcp.sync(
            security,
            transport -> McpServer.sync(transport).serverInfo("shoostr-fixture", "1.0").build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var result =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .header("Content-Type", "application/json")
                      .header("Accept", "application/json, text/event-stream")
                      .method("POST")
                      .body(
                          """
                              {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                                "protocolVersion":"2025-11-25","capabilities":{},
                                "clientInfo":{"name":"fixture-client","version":"1.0"}}}
                              """
                              .getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(200, result.statusCode());
      assertFalse(result.headers().firstValue("Mcp-Session-Id").orElseThrow().isBlank());
      var mapper = McpJsonDefaults.getMapper();
      var reply =
          assertInstanceOf(
              McpSchema.JSONRPCResponse.class,
              McpSchema.deserializeJsonRpcMessage(mapper, result.body()));
      var initialized = mapper.convertValue(reply.result(), McpSchema.InitializeResult.class);
      assertEquals("2025-11-25", initialized.protocolVersion());
      assertEquals("shoostr-fixture", initialized.serverInfo().name());
    }
  }
}
