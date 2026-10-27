package io.github.suppierk.shoostr.mcp;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

@Timeout(20)
class McpCancellationPolicyTest {
  @ParameterizedTest
  @MethodSource("ignoredTargets")
  void ignoresUnmatchedOrMalformedCancellationWithoutAffectingAnActiveRequest(
      String parameters, boolean anotherSession) throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var interrupted = new AtomicBoolean();
    var mcp =
        Mcp.async(
            security(),
            McpSessionLimits.defaults(),
            new McpOperationLimits(4, 4, 2, Duration.ofSeconds(10)),
            new McpOperationLimits(1, 1, 1, Duration.ofSeconds(5)),
            (transport, work) ->
                McpServer.async(transport)
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .tools(
                        McpServerFeatures.AsyncToolSpecification.builder()
                            .tool(McpSchema.Tool.builder("wait", Map.of("type", "object")).build())
                            .callHandler(
                                (exchange, request) ->
                                    work.call(
                                        () -> {
                                          entered.countDown();

                                          try {
                                            if (!release.await(8, TimeUnit.SECONDS)) {
                                              throw new IllegalStateException(
                                                  "Release the fixture");
                                            }
                                          } catch (InterruptedException failure) {
                                            interrupted.set(true);
                                            throw failure;
                                          }

                                          return result("kept");
                                        }))
                            .build())
                    .build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var session = initialize(test);
      var source = anotherSession ? initialize(test) : session;
      assertEquals(
          200,
          post(test, session, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}").statusCode());

      try (var response = open(test, session).body()) {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        assertEquals(202, post(test, source, cancellation(parameters)).statusCode());
        // Admission to this one-slot lane proves the preceding cancellation has finished.
        await()
            .atMost(Duration.ofSeconds(3))
            .untilAsserted(
                () ->
                    assertEquals(
                        202,
                        post(test, source, cancellation("{\"requestId\":\"barrier\"}"))
                            .statusCode()));
        assertFalse(interrupted.get(), "Unmatched cancellation must not interrupt the active call");
        release.countDown();
        var body = new String(response.readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"text\":\"kept\""), body);
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void cancelsBeforeADeferredManagedCallableStartsAndRecoversCapacity() throws Exception {
    var entered = new CountDownLatch(1);
    var cancelled = new CountDownLatch(1);
    var gate = Sinks.<Void>empty();
    var invocations = new AtomicInteger();
    var mcp =
        Mcp.async(
            security(),
            McpSessionLimits.defaults(),
            new McpOperationLimits(1, 1, 1, Duration.ofSeconds(5)),
            McpOperationLimits.defaults(),
            (transport, work) ->
                McpServer.async(transport)
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .tools(
                        McpServerFeatures.AsyncToolSpecification.builder()
                            .tool(McpSchema.Tool.builder("wait", Map.of("type", "object")).build())
                            .callHandler(
                                (exchange, request) -> {
                                  var action =
                                      work.call(
                                          () -> {
                                            invocations.incrementAndGet();
                                            return result("fresh");
                                          });
                                  if (Boolean.TRUE.equals(request.arguments().get("defer"))) {
                                    entered.countDown();
                                    return gate.asMono()
                                        .doOnCancel(cancelled::countDown)
                                        .then(action);
                                  }

                                  return action;
                                })
                            .build())
                    .build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var session = initialize(test);

      try (var response = open(test, session).body()) {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        assertEquals(202, post(test, session, cancellation("{\"requestId\":2}")).statusCode());
        assertTrue(cancelled.await(3, TimeUnit.SECONDS));
        assertFalse(
            new String(response.readAllBytes(), StandardCharsets.UTF_8).contains("\"result\""));
        gate.tryEmitEmpty();
        await()
            .atMost(Duration.ofSeconds(3))
            .untilAsserted(
                () -> {
                  var replacement =
                      post(
                          test,
                          session,
                          """
                        {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"wait","arguments":{}}}
                        """);
                  assertEquals(200, replacement.statusCode());
                  assertTrue(
                      new String(replacement.body(), StandardCharsets.UTF_8)
                          .contains("\"text\":\"fresh\""));
                });
        assertEquals(1, invocations.get(), "Only the replacement callable may execute");
      }
    } finally {
      gate.tryEmitEmpty();
    }
  }

  @Test
  void expiresAtTheAdmissionDeadlineWhileProgressContinues() throws Exception {
    var mcp =
        Mcp.async(
            security(),
            McpSessionLimits.defaults(),
            new McpOperationLimits(1, 1, 1, Duration.ofSeconds(1)),
            McpOperationLimits.defaults(),
            (transport, work) ->
                McpServer.async(transport)
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .tools(
                        McpServerFeatures.AsyncToolSpecification.builder()
                            .tool(McpSchema.Tool.builder("wait", Map.of("type", "object")).build())
                            .callHandler(
                                (exchange, request) ->
                                    Flux.interval(Duration.ofMillis(100))
                                        .concatMap(
                                            tick ->
                                                exchange.progressNotification(
                                                    McpSchema.ProgressNotification.builder(
                                                            "ongoing", tick.doubleValue())
                                                        .build()))
                                        .then(Mono.never()))
                            .build())
                    .build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var workers = Executors.newVirtualThreadPerTaskExecutor();
        var test = TestServer.start(app)) {
      var session = initialize(test);
      var pending =
          workers.submit(
              () ->
                  post(
                      test,
                      session,
                      """
                      {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                      "name":"wait","arguments":{},"_meta":{"progressToken":"ongoing"}}}
                      """));
      var response = pending.get(5, TimeUnit.SECONDS);
      assertEquals(200, response.statusCode());
      var body = new String(response.body(), StandardCharsets.UTF_8);
      assertTrue(
          body.lines().filter(line -> line.contains("notifications/progress")).count() >= 2, body);
      assertTrue(body.contains("\"id\":2,\"error\":{\"code\":-32001"), body);
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(
              () ->
                  assertEquals(
                      200,
                      post(test, session, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\"}")
                          .statusCode()));
    }
  }

  private static Stream<Arguments> ignoredTargets() {
    return Stream.of(
        Arguments.of("{}", false),
        Arguments.of("{\"requestId\":null}", false),
        Arguments.of("{\"requestId\":true}", false),
        Arguments.of("{\"requestId\":\"unknown\"}", false),
        Arguments.of("{\"requestId\":3}", false),
        Arguments.of("{\"requestId\":2}", true));
  }

  private static McpSchema.CallToolResult result(String text) {
    return McpSchema.CallToolResult.builder().addTextContent(text).build();
  }

  private static McpHttpSecurity security() {
    return new McpHttpSecurity(
        Set.of(),
        ignored -> new McpIdentity("alice", McpTransportContext.EMPTY, null),
        (identity, message) -> {});
  }

  private static String cancellation(String parameters) {
    return "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":"
        + parameters
        + "}";
  }

  private static HttpResponse<InputStream> open(TestServer test, String session) throws Exception {
    return test.send(
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
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"wait","arguments":{"defer":true}}}
                    """
                        .getBytes(StandardCharsets.UTF_8)),
        HttpResponse.BodyHandlers.ofInputStream());
  }

  private static String initialize(TestServer test) throws Exception {
    var response =
        post(
            test,
            null,
            """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
            "protocolVersion":"2025-11-25","capabilities":{},
            "clientInfo":{"name":"cancel-policy","version":"1.0"}}}
            """);
    assertEquals(200, response.statusCode());
    return response.headers().firstValue("Mcp-Session-Id").orElseThrow();
  }

  private static HttpResponse<byte[]> post(TestServer test, String session, String json)
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
}
