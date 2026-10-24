package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class McpReplyAuthorizationTest {
  @Test
  @Timeout(20)
  void anUnauthorizedReplyCannotCompleteANativeRequestButAGrantedRetryCan() throws Exception {
    var completed = new CountDownLatch(1);
    var security =
        new McpHttpSecurity(
            Set.of(),
            request ->
                new McpIdentity(
                    "alice:client",
                    McpTransportContext.create(
                        Map.of("replyAllowed", request.header("X-Verified-Grants").isPresent())),
                    null),
            (identity, message) -> {
              if (message instanceof McpSchema.JSONRPCResponse
                  && !Boolean.TRUE.equals(identity.context().get("replyAllowed"))) {
                throw McpHttpSecurity.scopeDenied(
                    Set.of("client:reply"),
                    URI.create("https://example.com/.well-known/oauth-protected-resource/mcp"));
              }
            });
    var mcp =
        Mcp.async(
            security,
            transport ->
                McpServer.async(transport)
                    .requestTimeout(Duration.ofSeconds(10))
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .tools(
                        McpServerFeatures.AsyncToolSpecification.builder()
                            .tool(
                                McpSchema.Tool.builder("ping-client", Map.of("type", "object"))
                                    .build())
                            .callHandler(
                                (exchange, ignored) ->
                                    exchange
                                        .ping()
                                        .doOnSuccess(ignored2 -> completed.countDown())
                                        .thenReturn(
                                            McpSchema.CallToolResult.builder()
                                                .addTextContent("reply accepted")
                                                .build()))
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
                            "clientInfo":{"name":"reply-authorization-fixture","version":"1.0"}}}
                          """
                              .getBytes(StandardCharsets.UTF_8)));
      var sessionId = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var response =
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
                          {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                            "name":"ping-client","arguments":{}}}
                          """
                              .getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofInputStream());
      var mapper = McpJsonDefaults.getMapper();

      try (var reader =
          new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
        assertEquals(200, response.statusCode());
        String line;
        do {
          line = reader.readLine();
          assertNotNull(line, "The native request must arrive before its reply is sent");
        } while (!line.startsWith("data:"));
        var ping =
            assertInstanceOf(
                McpSchema.JSONRPCRequest.class,
                McpSchema.deserializeJsonRpcMessage(mapper, line.substring(5).strip()));
        assertEquals("ping", ping.method());
        var replyBody =
            mapper.writeValueAsBytes(McpSchema.JSONRPCResponse.result(ping.id(), Map.of()));
        var denied =
            test.send(
                request ->
                    request
                        .path("/mcp")
                        .method("POST")
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .header("Mcp-Session-Id", sessionId)
                        .header("MCP-Protocol-Version", "2025-11-25")
                        .body(replyBody));
        assertEquals(403, denied.statusCode());
        assertEquals(
            "Bearer error=\"insufficient_scope\", scope=\"client:reply\", "
                + "resource_metadata=\"https://example.com/.well-known/oauth-protected-resource/mcp\"",
            denied.headers().firstValue("WWW-Authenticate").orElseThrow());
        assertEquals(1L, completed.getCount());

        var granted =
            test.send(
                request ->
                    request
                        .path("/mcp")
                        .method("POST")
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .header("Mcp-Session-Id", sessionId)
                        .header("MCP-Protocol-Version", "2025-11-25")
                        .header("X-Verified-Grants", "client:reply")
                        .body(replyBody));
        assertEquals(202, granted.statusCode());
        assertTrue(completed.await(3, TimeUnit.SECONDS));
      }
    }
  }
}
