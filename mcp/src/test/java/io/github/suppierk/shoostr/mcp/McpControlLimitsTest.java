package io.github.suppierk.shoostr.mcp;

import static org.awaitility.Awaitility.await;
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
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Timeout(20)
class McpControlLimitsTest {
  @ParameterizedTest
  @MethodSource("controlPolicies")
  void retainsControlCapacityAfterItsDeadlineUntilReplyProcessingActuallyReturns(
      McpOperationLimits policy, int rejection) throws Exception {
    var processing = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var mcp =
        Mcp.async(
            new McpHttpSecurity(
                Set.of(),
                ignored -> new McpIdentity("alice", McpTransportContext.EMPTY, null),
                (identity, message) -> {}),
            McpSessionLimits.defaults(),
            new McpOperationLimits(2, 2, 2, Duration.ofMinutes(5)),
            policy,
            (transport, work) ->
                McpServer.async(transport)
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .tools(
                        McpServerFeatures.AsyncToolSpecification.builder()
                            .tool(McpSchema.Tool.builder("reply", Map.of("type", "object")).build())
                            .callHandler(
                                (exchange, request) ->
                                    exchange
                                        .ping()
                                        .map(
                                            ignored -> {
                                              processing.countDown();
                                              boolean done = false;
                                              while (!done) {
                                                try {
                                                  done = release.await(10, TimeUnit.SECONDS);
                                                  if (!done) {
                                                    throw new IllegalStateException(
                                                        "Fixture was not released");
                                                  }
                                                } catch (InterruptedException ignoredInterruption) {
                                                  interrupted.countDown();
                                                }
                                              }
                                              return McpSchema.CallToolResult.builder()
                                                  .addTextContent("reply accepted")
                                                  .build();
                                            }))
                            .build())
                    .build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var initialized =
          send(
              test,
              null,
              """
          {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
          "protocolVersion":"2025-11-25","capabilities":{},
          "clientInfo":{"name":"control-limits","version":"1.0"}}}
          """);
      var session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var response =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .method("POST")
                      .header("Content-Type", "application/json")
                      .header("Accept", "application/json, text/event-stream")
                      .header("MCP-Protocol-Version", "2025-11-25")
                      .header("Mcp-Session-Id", session)
                      .body(
                          """
              {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
              "name":"reply","arguments":{}}}
              """
                              .getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofInputStream());

      try (var reader =
          new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
        try {
          String line;
          do {
            line = reader.readLine();
            assertNotNull(line);
          } while (!line.startsWith("data:"));
          var mapper = McpJsonDefaults.getMapper();
          var ping =
              assertInstanceOf(
                  McpSchema.JSONRPCRequest.class,
                  McpSchema.deserializeJsonRpcMessage(mapper, line.substring(5).strip()));
          assertEquals(
              202,
              send(
                      test,
                      session,
                      mapper.writeValueAsString(
                          McpSchema.JSONRPCResponse.result(ping.id(), Map.of())))
                  .statusCode());
          assertTrue(processing.await(3, TimeUnit.SECONDS));
          var unknown = "{\"jsonrpc\":\"2.0\",\"id\":\"unknown\",\"result\":{}}";
          assertEquals(rejection, send(test, session, unknown).statusCode());
          assertEquals(
              200,
              send(test, session, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}")
                  .statusCode());
          assertTrue(interrupted.await(3, TimeUnit.SECONDS));
          assertEquals(rejection, send(test, session, unknown).statusCode());
          release.countDown();
          assertTrue(reader.lines().anyMatch(value -> value.contains("reply accepted")));
          await()
              .atMost(Duration.ofSeconds(3))
              .untilAsserted(() -> assertEquals(202, send(test, session, unknown).statusCode()));
        } finally {
          release.countDown();
        }
      }
    }
  }

  private static HttpResponse<byte[]> send(TestServer test, String session, String json)
      throws Exception {
    return test.send(
        request -> {
          request
              .path("/mcp")
              .method("POST")
              .header("Content-Type", "application/json")
              .header("Accept", "application/json, text/event-stream")
              .header("MCP-Protocol-Version", "2025-11-25")
              .body(json.getBytes(StandardCharsets.UTF_8));
          if (session != null) {
            request.header("Mcp-Session-Id", session);
          }
        });
  }

  private static Stream<Arguments> controlPolicies() {
    return Stream.of(
        Arguments.of(new McpOperationLimits(1, 4, 4, Duration.ofSeconds(1)), 503),
        Arguments.of(new McpOperationLimits(4, 1, 4, Duration.ofSeconds(1)), 429),
        Arguments.of(new McpOperationLimits(4, 4, 1, Duration.ofSeconds(1)), 429));
  }
}
