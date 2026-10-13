package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.exceptions.ForbiddenException;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class McpNativeErrorTest {
  @Test
  void preservesANativeToolFailureAsAProtocolErrorWithoutAnOAuthChallenge() throws Exception {
    var security =
        new McpHttpSecurity(
            Set.of(),
            _ -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (_, _) -> {});
    var mcp =
        Mcp.async(
            security,
            transport ->
                McpServer.async(transport)
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .tools(
                        McpServerFeatures.AsyncToolSpecification.builder()
                            .tool(
                                McpSchema.Tool.builder("refuse", Map.of("type", "object")).build())
                            .callHandler(
                                (_, _) -> Mono.error(new ForbiddenException("Business refusal")))
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
                            "clientInfo":{"name":"native-error-fixture","version":"1.0"}}}
                          """
                              .getBytes(StandardCharsets.UTF_8)));
      var sessionId = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var result =
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
                          {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"refuse","arguments":{}}}
                          """
                              .getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertTrue(result.headers().firstValue("WWW-Authenticate").isEmpty());
      var payload =
          result
              .body()
              .lines()
              .filter(line -> line.startsWith("data:"))
              .findFirst()
              .orElseThrow()
              .substring(5)
              .strip();
      var reply =
          assertInstanceOf(
              McpSchema.JSONRPCResponse.class,
              McpSchema.deserializeJsonRpcMessage(McpJsonDefaults.getMapper(), payload));
      assertEquals(2, reply.id());
      assertNotNull(reply.error());
    }
  }
}
