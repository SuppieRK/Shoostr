package io.github.suppierk.shoostr.mcp;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

@Timeout(20)
class McpManagedWorkTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void reusesCapacityForSequentialManagedCallsAfterReturnOrFailure(boolean recover)
      throws Exception {
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
                            .tool(
                                McpSchema.Tool.builder("sequential", Map.of("type", "object"))
                                    .build())
                            .callHandler(
                                (exchange, request) -> {
                                  var first =
                                      work.call(
                                          () -> {
                                            if (recover) {
                                              throw new IllegalStateException(
                                                  "Recover this failure");
                                            }

                                            return "first";
                                          });
                                  var last =
                                      recover
                                          ? first.onErrorResume(
                                              IllegalStateException.class,
                                              failure -> work.call(() -> "recovered"))
                                          : first.flatMap(
                                              value -> work.call(() -> value + ":second"));
                                  return last.map(
                                      value ->
                                          McpSchema.CallToolResult.builder()
                                              .addTextContent(value)
                                              .build());
                                })
                            .build())
                    .build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var session = initialize(test);
      var response =
          post(
              test,
              session,
              """
              {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"sequential"}}
              """);
      assertEquals(200, response.statusCode());
      var body = new String(response.body(), StandardCharsets.UTF_8);
      var expected = recover ? "recovered" : "first:second";
      assertTrue(body.contains("\"text\":\"" + expected + "\""), body);
    }
  }

  @Test
  void preservesManagedOwnershipForANativeHandlerRegisteredAfterInitialization() throws Exception {
    var mcp =
        Mcp.async(
            security(),
            transport ->
                McpServer.async(transport)
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app);
        var client =
            McpClient.sync(
                    HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                        .endpoint("/mcp")
                        .build())
                .requestTimeout(Duration.ofSeconds(3))
                .build()) {
      client.initialize();
      mcp.server()
          .addTool(
              McpServerFeatures.AsyncToolSpecification.builder()
                  .tool(McpSchema.Tool.builder("late", Map.of("type", "object")).build())
                  .callHandler(
                      (exchange, request) ->
                          mcp.work()
                              .call(
                                  () ->
                                      McpSchema.CallToolResult.builder()
                                          .addTextContent(
                                              "registered:" + Thread.currentThread().isVirtual())
                                          .build()))
                  .build())
          .block();
      var result = client.callTool(McpSchema.CallToolRequest.builder("late").build());
      assertEquals(
          "registered:true",
          assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst()).text());
    }
  }

  @Test
  void rejectsAnotherCapabilitysManagedWorkBeforeInvokingItsCallable() throws Exception {
    var invoked = new AtomicInteger();
    var other = Mcp.async(security(), transport -> McpServer.async(transport).build());
    var mcp =
        Mcp.async(
            security(),
            McpSessionLimits.defaults(),
            McpOperationLimits.defaults(),
            McpOperationLimits.defaults(),
            (transport, work) ->
                McpServer.async(transport)
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .tools(
                        McpServerFeatures.AsyncToolSpecification.builder()
                            .tool(
                                McpSchema.Tool.builder("foreign", Map.of("type", "object")).build())
                            .callHandler(
                                (exchange, request) ->
                                    other
                                        .work()
                                        .call(
                                            () -> {
                                              invoked.incrementAndGet();
                                              return McpSchema.CallToolResult.builder()
                                                  .addTextContent("wrong owner")
                                                  .build();
                                            }))
                            .build())
                    .build());
    var app = new Shoostr();
    app.extensions(other).extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app);
        var client =
            McpClient.sync(
                    HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                        .endpoint("/mcp")
                        .build())
                .requestTimeout(Duration.ofSeconds(3))
                .build()) {
      client.initialize();
      var failure =
          assertThrows(
              McpError.class,
              () -> client.callTool(McpSchema.CallToolRequest.builder("foreign").build()));
      assertTrue(failure.getMessage().contains("active operation"));
      assertEquals(0, invoked.get());
    }
  }

  @Test
  void retainsAnOwnersExecutionChargeAfterDeletingItsSessionUntilTheCallableExits()
      throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var exited = new CountDownLatch(1);
    var mcp =
        waiting(
            new McpOperationLimits(4, 1, 4, Duration.ofMinutes(5)),
            () -> {
              entered.countDown();

              try {
                awaitIgnoringInterrupts(release);
                return McpSchema.CallToolResult.builder().addTextContent("released").build();
              } finally {
                exited.countDown();
              }
            });
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var first = initialize(test);
      var second = initialize(test);

      try (var body = open(test, first, 2, "first").body()) {
        try {
          assertTrue(entered.await(3, TimeUnit.SECONDS));
          var deleted =
              test.send(
                  request ->
                      request
                          .path("/mcp")
                          .method("DELETE")
                          .header("Mcp-Session-Id", first)
                          .header("MCP-Protocol-Version", "2025-11-25"));
          assertEquals(200, deleted.statusCode());
          assertEquals(1, exited.getCount());
          assertEquals(
              429,
              post(test, second, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}")
                  .statusCode());
        } finally {
          release.countDown();
        }

        body.readAllBytes();
      }

      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(
              () ->
                  assertEquals(
                      200,
                      post(test, second, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}")
                          .statusCode()));
      assertTrue(exited.await(3, TimeUnit.SECONDS));
    }
  }

  @RepeatedTest(5)
  void arbitratesCompletionAndMatchingCancellationWithoutDuplicatingAResponse() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var mcp =
        waiting(
            new McpOperationLimits(4, 4, 1, Duration.ofMinutes(5)),
            () -> {
              entered.countDown();
              release.await();
              return McpSchema.CallToolResult.builder().addTextContent("completed").build();
            });
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app);
        var racers = Executors.newVirtualThreadPerTaskExecutor()) {
      var session = initialize(test);
      var ready = new CountDownLatch(2);
      var go = new CountDownLatch(1);

      try (var body = open(test, session, 2, "first").body()) {
        try {
          assertTrue(entered.await(3, TimeUnit.SECONDS));
          var cancellation =
              racers.submit(
                  () -> {
                    ready.countDown();
                    go.await();
                    return post(
                            test,
                            session,
                            """
                {"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":2}}
                """)
                        .statusCode();
                  });
          var completion =
              racers.submit(
                  () -> {
                    ready.countDown();
                    go.await();
                    release.countDown();
                    return true;
                  });
          assertTrue(ready.await(3, TimeUnit.SECONDS));
          go.countDown();
          assertEquals(202, cancellation.get(3, TimeUnit.SECONDS));
          assertTrue(completion.get(3, TimeUnit.SECONDS));
          var text =
              new String(
                  racers.submit(body::readAllBytes).get(3, TimeUnit.SECONDS),
                  StandardCharsets.UTF_8);
          var responses = text.lines().filter(line -> line.startsWith("data:")).toList();
          assertTrue(responses.size() <= 1, text);
          if (!responses.isEmpty()) {
            assertTrue(responses.getFirst().contains("\"id\":2"), text);
            assertTrue(responses.getFirst().contains("completed"), text);
          }
        } finally {
          go.countDown();
          release.countDown();
        }
      }

      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(
              () ->
                  assertEquals(
                      200,
                      post(test, session, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}")
                          .statusCode()));
    }
  }

  @Test
  void reportsAnErrorFromAManagedCallableAndReleasesItsCapacity() throws Exception {
    var invoked = new AtomicInteger();
    var mcp =
        Mcp.async(
            security(),
            McpSessionLimits.defaults(),
            new McpOperationLimits(4, 4, 1, Duration.ofMinutes(5)),
            McpOperationLimits.defaults(),
            (transport, work) ->
                McpServer.async(transport)
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .tools(
                        McpServerFeatures.AsyncToolSpecification.builder()
                            .tool(McpSchema.Tool.builder("error", Map.of("type", "object")).build())
                            .callHandler(
                                (exchange, request) ->
                                    work.call(
                                        () -> {
                                          if (invoked.getAndIncrement() == 0) {
                                            // Use JUnit's Error subtype to exercise publisher
                                            // failure propagation.
                                            return fail("Fixture managed callable failure");
                                          }

                                          return McpSchema.CallToolResult.builder()
                                              .addTextContent("recovered")
                                              .build();
                                        }))
                            .build())
                    .build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app);
        var client =
            McpClient.sync(
                    HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                        .endpoint("/mcp")
                        .build())
                .requestTimeout(Duration.ofSeconds(2))
                .build()) {
      client.initialize();
      var failure =
          assertThrows(
              RuntimeException.class,
              () -> client.callTool(McpSchema.CallToolRequest.builder("error").build()));
      assertTrue(
          failure.getMessage().contains("Fixture managed callable failure"), failure.toString());
      await()
          .atMost(Duration.ofSeconds(3))
          .ignoreExceptions()
          .untilAsserted(
              () -> {
                var result = client.callTool(McpSchema.CallToolRequest.builder("error").build());
                assertEquals(
                    "recovered",
                    assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst())
                        .text());
              });
    }
  }

  @Test
  void cancelsOnlyTheMatchingRequestWhileAnotherOperationRemainsActive() throws Exception {
    var started = new CountDownLatch(2);
    var releaseFirst = new CountDownLatch(1);
    var releaseSecond = new CountDownLatch(1);
    var mcp =
        Mcp.async(
            security(),
            McpSessionLimits.defaults(),
            new McpOperationLimits(4, 4, 2, Duration.ofMinutes(5)),
            McpOperationLimits.defaults(),
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
                                          var marker = (String) request.arguments().get("marker");
                                          started.countDown();
                                          if ("first".equals(marker)) {
                                            releaseFirst.await();
                                          } else {
                                            releaseSecond.await();
                                          }

                                          return McpSchema.CallToolResult.builder()
                                              .addTextContent(marker)
                                              .build();
                                        }))
                            .build())
                    .build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app);
        var readers = Executors.newVirtualThreadPerTaskExecutor()) {
      var session = initialize(test);

      try (var first = open(test, session, 2, "first").body();
          var second = open(test, session, 3, "second").body()) {
        try {
          assertTrue(started.await(3, TimeUnit.SECONDS));
          assertEquals(
              202,
              post(
                      test,
                      session,
                      """
              {"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":2}}
              """)
                  .statusCode());
          var text =
              new String(
                  readers.submit(first::readAllBytes).get(3, TimeUnit.SECONDS),
                  StandardCharsets.UTF_8);
          assertFalse(text.contains("data:"), text);
          assertEquals(1, releaseSecond.getCount());
          await()
              .atMost(Duration.ofSeconds(3))
              .untilAsserted(
                  () ->
                      assertEquals(
                          200,
                          post(test, session, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\"}")
                              .statusCode()));
          releaseSecond.countDown();
          assertTrue(new String(second.readAllBytes(), StandardCharsets.UTF_8).contains("second"));
        } finally {
          releaseFirst.countDown();
          releaseSecond.countDown();
        }
      }
    }
  }

  @Test
  void timesOutAClientButRetainsCapacityUntilAnIgnoringCallableExits() throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var mcp =
        Mcp.async(
            security(),
            McpSessionLimits.defaults(),
            new McpOperationLimits(4, 4, 1, Duration.ofSeconds(1)),
            McpOperationLimits.defaults(),
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
                                          started.countDown();
                                          boolean finished = false;
                                          while (!finished) {
                                            try {
                                              finished = release.await(10, TimeUnit.SECONDS);
                                              if (!finished) {
                                                throw new IllegalStateException(
                                                    "Fixture was not released");
                                              }
                                            } catch (InterruptedException ignored) {
                                              // Deliberately ignore cancellation until the test
                                              // releases this callback.
                                            }
                                          }
                                          return McpSchema.CallToolResult.builder()
                                              .addTextContent("late result")
                                              .build();
                                        }))
                            .build())
                    .build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app);
        var readers = Executors.newVirtualThreadPerTaskExecutor()) {
      var session = initialize(test);
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
              "name":"wait","arguments":{}}}
              """
                              .getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofInputStream());

      try (var body = response.body()) {
        try {
          assertEquals(200, response.statusCode());
          assertTrue(started.await(3, TimeUnit.SECONDS));
          var bytes = readers.submit(body::readAllBytes).get(3, TimeUnit.SECONDS);
          var text = new String(bytes, StandardCharsets.UTF_8);
          assertTrue(text.contains("\"id\":2"), text);
          assertTrue(text.contains("\"code\":-32001"), text);
          assertEquals(
              429,
              post(test, session, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}")
                  .statusCode());
        } finally {
          release.countDown();
        }
      }

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

  @ParameterizedTest
  @MethodSource("executionPolicies")
  void boundsParallelCallablesAndRetainsOperationCapacityUntilCancelledWorkExits(
      McpOperationLimits policy, int rejection) throws Exception {
    var started = Sinks.<Void>empty();
    var release = new CountDownLatch(1);
    var secondInvoked = new AtomicInteger();
    var mcp =
        Mcp.async(
            security(),
            McpSessionLimits.defaults(),
            policy,
            McpOperationLimits.defaults(),
            (transport, work) ->
                McpServer.async(transport)
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .tools(
                        McpServerFeatures.AsyncToolSpecification.builder()
                            .tool(
                                McpSchema.Tool.builder("parallel", Map.of("type", "object"))
                                    .build())
                            .callHandler(
                                (exchange, request) -> {
                                  var first =
                                      work.call(
                                          () -> {
                                            started.tryEmitEmpty();
                                            boolean finished = false;
                                            while (!finished) {
                                              try {
                                                finished = release.await(10, TimeUnit.SECONDS);
                                                if (!finished) {
                                                  throw new IllegalStateException(
                                                      "Fixture was not released");
                                                }
                                              } catch (InterruptedException ignored) {
                                                // This fixture deliberately keeps the managed
                                                // callable alive after cancel.
                                              }
                                            }
                                            return "first";
                                          });
                                  var second =
                                      started
                                          .asMono()
                                          .then(
                                              work.call(
                                                  () -> {
                                                    secondInvoked.incrementAndGet();
                                                    release.countDown();
                                                    return "second";
                                                  }));
                                  return Mono.zip(first, second)
                                      .map(
                                          result ->
                                              McpSchema.CallToolResult.builder()
                                                  .addTextContent(result.getT1() + result.getT2())
                                                  .build());
                                })
                            .build())
                    .build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var session = initialize(test);

      try {
        var response =
            post(
                test,
                session,
                """
            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
            "name":"parallel","arguments":{}}}
            """);
        var body = new String(response.body(), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"code\":-32000"), body);
        assertEquals(0, secondInvoked.get());
        assertEquals(
            rejection,
            post(test, session, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}").statusCode());
      } finally {
        release.countDown();
      }

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

  @Test
  void executesAComposedBlockingCallableOnAnOwnedVirtualThread() throws Exception {
    var mcp =
        Mcp.async(
            new McpHttpSecurity(
                Set.of(),
                ignored -> new McpIdentity("alice", McpTransportContext.EMPTY, null),
                (identity, message) -> {}),
            McpSessionLimits.defaults(),
            McpOperationLimits.defaults(),
            McpOperationLimits.defaults(),
            (transport, work) ->
                McpServer.async(transport)
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .tools(
                        McpServerFeatures.AsyncToolSpecification.builder()
                            .tool(
                                McpSchema.Tool.builder("managed", Map.of("type", "object")).build())
                            .callHandler(
                                (exchange, request) ->
                                    work.call(
                                        () ->
                                            McpSchema.CallToolResult.builder()
                                                .addTextContent(
                                                    "managed:" + Thread.currentThread().isVirtual())
                                                .build()))
                            .build())
                    .build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app);
        var client =
            McpClient.sync(
                    HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                        .endpoint("/mcp")
                        .build())
                .requestTimeout(Duration.ofSeconds(5))
                .build()) {
      client.initialize();
      var result = client.callTool(McpSchema.CallToolRequest.builder("managed").build());
      assertEquals(
          "managed:true",
          assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst()).text());
    }
  }

  private static McpHttpSecurity security() {
    return new McpHttpSecurity(
        Set.of(),
        ignored -> new McpIdentity("alice", McpTransportContext.EMPTY, null),
        (identity, message) -> {});
  }

  private static Mcp<McpAsyncServer> waiting(
      McpOperationLimits limits, Callable<McpSchema.CallToolResult> action) {
    return Mcp.async(
        security(),
        McpSessionLimits.defaults(),
        limits,
        McpOperationLimits.defaults(),
        (transport, work) ->
            McpServer.async(transport)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(
                    McpServerFeatures.AsyncToolSpecification.builder()
                        .tool(McpSchema.Tool.builder("wait", Map.of("type", "object")).build())
                        .callHandler((exchange, request) -> work.call(action))
                        .build())
                .build());
  }

  private static void awaitIgnoringInterrupts(CountDownLatch release) {
    boolean done = false;
    while (!done) {
      try {
        done = release.await(10, TimeUnit.SECONDS);
        if (!done) {
          throw new IllegalStateException("Fixture was not released");
        }
      } catch (InterruptedException ignored) {
        // Keep ownership alive until the test explicitly allows actual callback exit.
      }
    }
  }

  private static Stream<Arguments> executionPolicies() {
    return Stream.of(
        Arguments.of(new McpOperationLimits(1, 4, 4, Duration.ofMinutes(5)), 503),
        Arguments.of(new McpOperationLimits(4, 1, 4, Duration.ofMinutes(5)), 429),
        Arguments.of(new McpOperationLimits(4, 4, 1, Duration.ofMinutes(5)), 429));
  }

  private static HttpResponse<InputStream> open(
      TestServer test, String session, int id, String marker) throws Exception {
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
                    ("{\"jsonrpc\":\"2.0\",\"id\":"
                            + id
                            + ",\"method\":\"tools/call\",\"params\":{\"name\":\"wait\",\"arguments\":{\"marker\":\""
                            + marker
                            + "\"}}}")
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
        "clientInfo":{"name":"managed-work","version":"1.0"}}}
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
