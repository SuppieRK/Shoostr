package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class McpSessionLimitsTest {
  private static final String INITIALIZE =
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
        "protocolVersion":"2025-11-25","capabilities":{},
        "clientInfo":{"name":"session-limits","version":"1.0"}}}
      """;

  @Test
  @Timeout(20)
  void boundsSessionsSharingAVerifiedOwnerWithTheDefaultPolicy() throws Exception {
    var mcp = Mcp.sync(security(), transport -> McpServer.sync(transport).build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      for (int index = 0; index < 32; index++) {
        assertEquals(200, initialize(test, "alice:client").statusCode());
      }
      assertEquals(429, initialize(test, "alice:client").statusCode());
      assertEquals(200, initialize(test, "bob:client").statusCode());
    }
  }

  @Test
  @Timeout(15)
  void boundsAllOwnersTogetherAndMakesDeletedCapacityAvailable() throws Exception {
    var mcp =
        Mcp.sync(
            security(),
            new McpSessionLimits(2, 2, Duration.ofMinutes(30)),
            transport -> McpServer.sync(transport).build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var first = initialize(test, "alice:client");
      assertEquals(200, first.statusCode());
      assertEquals(200, initialize(test, "bob:client").statusCode());
      assertEquals(503, initialize(test, "carol:client").statusCode());
      var deleted =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .method("DELETE")
                      .header("X-Verified-Owner", "alice:client")
                      .header(
                          "Mcp-Session-Id",
                          first.headers().firstValue("Mcp-Session-Id").orElseThrow()));
      assertEquals(200, deleted.statusCode());
      assertEquals(200, initialize(test, "carol:client").statusCode());
    }
  }

  @Test
  @Timeout(15)
  void expiresAQuiescentSessionEvenWhenItsListeningConnectionRemainsOpen() throws Exception {
    var mcp =
        Mcp.sync(
            security(),
            new McpSessionLimits(1, 1, Duration.ofSeconds(1)),
            transport -> McpServer.sync(transport).build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app);
        var readers = Executors.newVirtualThreadPerTaskExecutor()) {
      var initialized = initialize(test, "alice:client");
      assertEquals(200, initialized.statusCode());
      var session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var listening =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .header("Accept", "text/event-stream")
                      .header("Mcp-Session-Id", session),
              HttpResponse.BodyHandlers.ofInputStream());

      try (var input = listening.body()) {
        assertEquals(200, listening.statusCode());
        readers.submit(input::readAllBytes).get(4, TimeUnit.SECONDS);
      }

      assertEquals(
          404,
          test.send(
                  request ->
                      request.path("/mcp").method("DELETE").header("Mcp-Session-Id", session))
              .statusCode());
      assertEquals(200, initialize(test, "alice:client").statusCode());
    }
  }

  @Test
  @Timeout(15)
  void preservesPendingNativeWorkBeyondTheIdleInterval() throws Exception {
    var release = Sinks.<Void>empty();
    var started = new CountDownLatch(1);
    var mcp =
        Mcp.async(
            security(),
            new McpSessionLimits(2, 2, Duration.ofMillis(300)),
            transport ->
                McpServer.async(transport)
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .tools(
                        McpServerFeatures.AsyncToolSpecification.builder()
                            .tool(McpSchema.Tool.builder("wait", Map.of("type", "object")).build())
                            .callHandler(
                                (_, _) ->
                                    release
                                        .asMono()
                                        .doOnSubscribe(_ -> started.countDown())
                                        .thenReturn(
                                            McpSchema.CallToolResult.builder()
                                                .addTextContent("finished")
                                                .build()))
                            .build())
                    .build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var initialized = initialize(test, "alice:client");
      var session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var response =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .method("POST")
                      .header("Content-Type", "application/json")
                      .header("Accept", "application/json, text/event-stream")
                      .header("Mcp-Session-Id", session)
                      .body(
                          "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"wait\",\"arguments\":{}}}"
                              .getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofInputStream());

      try (var input = response.body()) {
        assertEquals(200, response.statusCode());
        assertTrue(started.await(3, TimeUnit.SECONDS));
        Thread.sleep(900);
        var ping =
            test.send(
                request ->
                    request
                        .path("/mcp")
                        .method("POST")
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .header("Mcp-Session-Id", session)
                        .body(
                            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}"
                                .getBytes(StandardCharsets.UTF_8)));
        assertEquals(200, ping.statusCode(), "Pending native work must keep session ownership");
        release.tryEmitEmpty();
        assertTrue(new String(input.readAllBytes(), StandardCharsets.UTF_8).contains("finished"));
      }
    } finally {
      release.tryEmitEmpty();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @Timeout(15)
  void countsInitializationBeforeItsNativeResultIsReady(boolean global) throws Exception {
    var started = new CountDownLatch(1);
    var release = Sinks.<Void>empty();
    var allocations = new AtomicInteger();
    var mcp =
        Mcp.sync(
            security(),
            new McpSessionLimits(global ? 1 : 3, 1, Duration.ofMinutes(30)),
            transport ->
                McpServer.sync(delayedInitialization(transport, started, release, allocations))
                    .build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app);
        var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      try {
        var first = callers.submit(() -> initialize(test, "alice:client"));
        assertTrue(started.await(3, TimeUnit.SECONDS));
        assertEquals(
            global ? 503 : 429,
            initialize(test, global ? "bob:client" : "alice:client").statusCode());
        assertEquals(
            1, allocations.get(), "Rejected initialization must not allocate native state");
        if (!global) {
          assertEquals(200, initialize(test, "bob:client").statusCode());
        }

        release.tryEmitEmpty();
        assertEquals(200, first.get(3, TimeUnit.SECONDS).statusCode());
      } finally {
        release.tryEmitEmpty();
      }
    }
  }

  @Test
  void rejectsNonPositiveSessionPolicyInputs() {
    assertThrows(
        IllegalArgumentException.class, () -> new McpSessionLimits(0, 1, Duration.ofMinutes(1)));
    assertThrows(
        IllegalArgumentException.class, () -> new McpSessionLimits(1, 0, Duration.ofMinutes(1)));
    assertThrows(IllegalArgumentException.class, () -> new McpSessionLimits(1, 1, Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class, () -> new McpSessionLimits(1, 1, Duration.ofSeconds(-1)));
    assertThrows(NullPointerException.class, () -> new McpSessionLimits(1, 1, null));
  }

  private static McpStreamableServerTransportProvider delayedInitialization(
      McpStreamableServerTransportProvider transport,
      CountDownLatch started,
      Sinks.Empty<Void> release,
      AtomicInteger allocations) {
    var first = new AtomicBoolean(true);
    return new McpStreamableServerTransportProvider() {
      @Override
      public void setSessionFactory(McpStreamableServerSession.Factory factory) {
        transport.setSessionFactory(
            parameters -> {
              allocations.incrementAndGet();
              var initialized = factory.startSession(parameters);
              if (!first.getAndSet(false)) {
                return initialized;
              }

              return new McpStreamableServerSession.McpStreamableServerSessionInit(
                  initialized.session(),
                  initialized
                      .initResult()
                      .delayUntil(
                          _ -> {
                            started.countDown();
                            return release.asMono();
                          }));
            });
      }

      @Override
      public Mono<Void> notifyClients(String method, Object parameters) {
        return transport.notifyClients(method, parameters);
      }

      @Override
      public Mono<Void> closeGracefully() {
        return transport.closeGracefully();
      }

      @Override
      public List<String> protocolVersions() {
        return transport.protocolVersions();
      }
    };
  }

  private static McpHttpSecurity security() {
    return new McpHttpSecurity(
        Set.of(),
        request ->
            new McpIdentity(
                request.header("X-Verified-Owner").orElse("alice:client"),
                McpTransportContext.EMPTY,
                null),
        (_, _) -> {});
  }

  private static HttpResponse<String> initialize(TestServer test, String owner) throws Exception {
    return test.send(
        request ->
            request
                .path("/mcp")
                .method("POST")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("X-Verified-Owner", owner)
                .body(INITIALIZE.getBytes(StandardCharsets.UTF_8)),
        HttpResponse.BodyHandlers.ofString());
  }
}
