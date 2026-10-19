package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class McpGetLifetimeTest {
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"replacement", "expiry"})
  @Timeout(25)
  void aFreshReplyCompletesTheRootsCallbackAfterItsGetTransportEnds(String ending)
      throws Exception {
    var roots = new CompletableFuture<List<McpSchema.Root>>();
    var mcp =
        Mcp.sync(
            new McpHttpSecurity(
                Set.of(),
                request ->
                    new McpIdentity(
                        "alice:client",
                        McpTransportContext.EMPTY,
                        request.header("X-Old-Token").isPresent()
                            ? Instant.now().plusSeconds(2)
                            : null),
                (_, _) -> {}),
            new McpSessionLimits(1, 1, Duration.ofSeconds(1)),
            transport ->
                McpServer.sync(transport)
                    .requestTimeout(Duration.ofSeconds(15))
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .rootsChangeHandler((_, changed) -> roots.complete(changed))
                    .build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var readers = Executors.newVirtualThreadPerTaskExecutor();
        var test = TestServer.start(app)) {
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
                "protocolVersion":"2025-11-25","capabilities":{"roots":{"listChanged":true}},
                "clientInfo":{"name":"get-lifetime-fixture","version":"1.0"}}}
              """
                              .getBytes(StandardCharsets.UTF_8)));
      var sessionId = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var oldGet =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .header("Accept", "text/event-stream")
                      .header("Mcp-Session-Id", sessionId)
                      .header("X-Old-Token", "true")
                      .header("MCP-Protocol-Version", "2025-11-25"),
              HttpResponse.BodyHandlers.ofInputStream());

      try (var reader =
          new BufferedReader(new InputStreamReader(oldGet.body(), StandardCharsets.UTF_8))) {
        var notified =
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
                {"jsonrpc":"2.0","method":"notifications/roots/list_changed"}
                """
                                .getBytes(StandardCharsets.UTF_8)));
        assertEquals(202, notified.statusCode());
        String line;
        do {
          line = reader.readLine();
          assertNotNull(line, "The roots request must arrive on the original GET");
        } while (!line.startsWith("data:"));
        var mapper = McpJsonDefaults.getMapper();
        var nativeRequest =
            assertInstanceOf(
                McpSchema.JSONRPCRequest.class,
                McpSchema.deserializeJsonRpcMessage(mapper, line.substring(5).strip()));
        assertEquals("roots/list", nativeRequest.method());
        var oldEnded =
            readers.submit(
                () -> {
                  try {
                    while (reader.readLine() != null) {
                      // Drain the superseded connection before answering its native request.
                    }
                  } catch (IOException _) {
                    // EOF and a closed transport both end the original HTTP owner.
                  }

                  return null;
                });
        if ("expiry".equals(ending)) {
          oldEnded.get(5, TimeUnit.SECONDS);
        }

        var refreshed =
            test.send(
                request ->
                    request
                        .path("/mcp")
                        .header("Accept", "text/event-stream")
                        .header("Mcp-Session-Id", sessionId)
                        .header("MCP-Protocol-Version", "2025-11-25"),
                HttpResponse.BodyHandlers.ofInputStream());

        try (var _ = refreshed.body()) {
          assertEquals(200, refreshed.statusCode());
          oldEnded.get(5, TimeUnit.SECONDS);
          var expected = new McpSchema.Root("file:///workspace/refreshed", "refreshed");
          var replyBody =
              mapper.writeValueAsBytes(
                  McpSchema.JSONRPCResponse.result(
                      nativeRequest.id(),
                      Map.of(
                          "roots",
                          List.of(Map.of("uri", expected.uri(), "name", expected.name())))));
          var reply =
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
          assertEquals(202, reply.statusCode());
          assertEquals(List.of(expected), roots.get(5, TimeUnit.SECONDS));
        }
      }
    }
  }
}
