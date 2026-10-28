package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.suppierk.shoostr.RequestOutcome;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.SseEvent;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import nl.jqno.equalsverifier.api.ConfiguredEqualsVerifier;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.publisher.Sinks;

class McpRuntimeEqualityTest {
  private static final String INITIALIZE =
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
        "protocolVersion":"2025-11-25","capabilities":{},
        "clientInfo":{"name":"equality-fixture","version":"1"}}}
      """;

  @Test
  void preservesRuntimeIdentityAndSessionHashKeysThroughProtocolAndCleanup() throws Exception {
    var completed = new LinkedBlockingQueue<RequestOutcome>();
    var failures = new LinkedBlockingQueue<Throwable>();
    var subscriptions = new ArrayList<Disposable>();
    var acknowledgments = new ArrayList<MonoSink<Void>>();
    var flushed = new AtomicInteger();
    var hashes = new HashMap<Object, Integer>();
    var firstMapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());
    var secondMapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());
    var streams = List.of(new McpStream(firstMapper, null), new McpStream(secondMapper, null));

    try (var firstApp = new Shoostr().afterRequest(completed::add);
        var secondApp = new Shoostr().afterRequest(completed::add)) {
      var first =
          Mcp.sync(
              McpValueEqualityTest.security("first"),
              transport -> McpServer.sync(transport).serverInfo("first", "1").build());
      firstApp.extensions(first).routes(routes -> routes.path("/mcp", first::routes));
      var second =
          Mcp.sync(
              McpValueEqualityTest.security("second"),
              transport -> McpServer.sync(transport).serverInfo("second", "2").build());
      secondApp.extensions(second).routes(routes -> routes.path("/mcp", second::routes));
      var capabilities = List.of(first, second);
      var apps = List.of(firstApp, secondApp);
      for (int index = 0; index < apps.size(); index++) {
        var stream = streams.get(index);
        hashes.put(capabilities.get(index), capabilities.get(index).hashCode());
        hashes.put(stream, stream.hashCode());
        apps.get(index)
            .routes()
            .get(
                "/stream",
                (request, response) -> {
                  subscriptions.add(
                      stream
                          .sendMessage(new McpSchema.JSONRPCNotification("notice"))
                          .subscribe(value -> {}, failures::add, flushed::incrementAndGet));
                  stream.finish();
                  stream.pump(response);
                });
      }

      try (var firstServer = TestServer.start(firstApp);
          var secondServer = TestServer.start(secondApp)) {
        var servers = List.of(firstServer, secondServer);
        var providers = List.of(field(first, "provider"), field(second, "provider"));
        var sessions = new ArrayList<>();
        var identifiers = new ArrayList<String>();
        for (int index = 0; index < servers.size(); index++) {
          var initialized = post(servers.get(index), INITIALIZE, null);
          assertEquals(200, initialized.statusCode());
          assertNotNull(completed.poll(3, TimeUnit.SECONDS));
          var identifier = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
          identifiers.add(identifier);
          var session = ((Map<?, ?>) field(providers.get(index), "sessions")).get(identifier);
          assertNotNull(session);
          sessions.add(session);
          hashes.put(session, session.hashCode());
          hashes.put(providers.get(index), providers.get(index).hashCode());
        }
        var firstDeadlines = (ScheduledThreadPoolExecutor) field(first, "deadlines");
        var secondDeadlines = (ScheduledThreadPoolExecutor) field(second, "deadlines");
        var lifetimes =
            List.of(
                new McpRequestLifetime(firstDeadlines, "Bearer"),
                new McpRequestLifetime(secondDeadlines, "Bearer"));

        try {
          for (int index = 0; index < lifetimes.size(); index++) {
            var lifetime = lifetimes.get(index);
            hashes.put(lifetime, lifetime.hashCode());
            lifetime.arm(Instant.now().plusSeconds(3600));
            lifetime.stream(streams.get(index));
            subscriptions.add(
                Mono.<Void>create(acknowledgments::add).subscribe(value -> {}, failures::add));
            exerciseProtocol(servers.get(index), identifiers.get(index), completed);
          }
          assertEquals(2, flushed.get());
          var frames =
              List.of(
                  frame(new SseEvent("first", "message", "first", null), acknowledgments.get(0)),
                  frame(new SseEvent("second", "message", "second", null), acknowledgments.get(1)));
          var configuration =
              EqualsVerifier.configure()
                  .withPrefabValues(Mcp.class, first, second)
                  .withPrefabValues(
                      McpSessionLimits.class,
                      new McpSessionLimits(32, 8, Duration.ofMinutes(1)),
                      new McpSessionLimits(64, 16, Duration.ofMinutes(2)))
                  .withPrefabValues(
                      SseEvent.class,
                      new SseEvent("first", "message", "first", null),
                      new SseEvent("second", "message", "second", null))
                  .withPrefabValues(
                      McpHttpSecurity.class,
                      McpValueEqualityTest.security("first"),
                      McpValueEqualityTest.security("second"))
                  .withPrefabValues(McpJsonMapper.class, firstMapper, secondMapper)
                  .withPrefabValues(McpStream.class, streams.get(0), streams.get(1))
                  .withPrefabValues(McpRequestLifetime.class, lifetimes.get(0), lifetimes.get(1))
                  .withPrefabValues(
                      ScheduledThreadPoolExecutor.class, firstDeadlines, secondDeadlines)
                  .withPrefabValues(
                      ScheduledFuture.class,
                      (ScheduledFuture<?>) field(providers.get(0), "idleExpiry"),
                      (ScheduledFuture<?>) field(providers.get(1), "idleExpiry"))
                  .withPrefabValues(
                      McpStreamableServerSession.class,
                      (McpStreamableServerSession) field(sessions.get(0), "sdk"),
                      (McpStreamableServerSession) field(sessions.get(1), "sdk"))
                  .withPrefabValues(
                      Disposable.Composite.class,
                      (Disposable.Composite) field(sessions.get(0), "dispatches"),
                      (Disposable.Composite) field(sessions.get(1), "dispatches"))
                  .withPrefabValues(MonoSink.class, acknowledgments.get(0), acknowledgments.get(1))
                  .withPrefabValues(Sinks.Empty.class, Sinks.empty(), Sinks.empty())
                  .withPrefabValues(Mono.class, Mono.empty(), Mono.never())
                  .withPrefabValues(
                      Thread.class, new Thread(() -> {}, "first"), new Thread(() -> {}, "second"))
                  .withPrefabValues(
                      Throwable.class,
                      new IllegalStateException("first"),
                      new IllegalStateException("second"))
                  .withGenericPrefabValues(
                      ArrayDeque.class, value -> new ArrayDeque<>(List.of(value)));
          configuration =
              prefab(
                  configuration,
                  providers.getFirst().getClass(),
                  providers.get(0),
                  providers.get(1));
          configuration =
              prefab(
                  configuration, sessions.getFirst().getClass(), sessions.get(0), sessions.get(1));
          configuration =
              prefab(configuration, frames.getFirst().getClass(), frames.get(0), frames.get(1));
          configuration
              .forClass(Mcp.class)
              .withPrefabValuesForField("server", first.server(), second.server())
              .suppress(Warning.INHERITED_DIRECTLY_FROM_OBJECT, Warning.ALL_FIELDS_SHOULD_BE_USED)
              .verify();
          configuration
              .forClass(providers.getFirst().getClass())
              .suppress(Warning.INHERITED_DIRECTLY_FROM_OBJECT, Warning.ALL_FIELDS_SHOULD_BE_USED)
              .verify();
          configuration
              .forClass(McpRequestLifetime.class)
              .suppress(Warning.INHERITED_DIRECTLY_FROM_OBJECT, Warning.ALL_FIELDS_SHOULD_BE_USED)
              .verify();
          configuration
              .forClass(McpStream.class)
              .suppress(Warning.INHERITED_DIRECTLY_FROM_OBJECT, Warning.ALL_FIELDS_SHOULD_BE_USED)
              .verify();
          configuration
              .forClass(sessions.getFirst().getClass())
              .withNonnullFields(
                  "sdk", "owner", "dispatches", "terminating", "activity", "notifications")
              .verify();
          configuration
              .forClass(frames.getFirst().getClass())
              .withNonnullFields("event", "acknowledgment")
              .verify();
          assertNotEquals(first, second);
          assertNotEquals(streams.get(0), streams.get(1));
          assertNotEquals(lifetimes.get(0), lifetimes.get(1));
          for (int index = 0; index < servers.size(); index++) {
            var identifier = identifiers.get(index);
            var deleted =
                servers
                    .get(index)
                    .send(
                        request ->
                            request
                                .path("/mcp")
                                .method("DELETE")
                                .header("Mcp-Session-Id", identifier)
                                .header("MCP-Protocol-Version", "2025-11-25"));
            assertEquals(200, deleted.statusCode());
            assertNotNull(completed.poll(3, TimeUnit.SECONDS));
          }
        } finally {
          // Completion cancels deadlines before the capability shuts its scheduler down.
          for (var lifetime : lifetimes) {
            lifetime.close();
            lifetime.complete(new RequestOutcome("GET", "/mcp", 200, 0, null, null));
          }
          acknowledgments.forEach(MonoSink::success);
          subscriptions.forEach(Disposable::dispose);
        }
      }
    }

    assertTrue(failures.isEmpty());
    var retained = new HashSet<>(hashes.keySet());
    for (var entry : hashes.entrySet()) {
      assertEquals(entry.getKey(), entry.getKey());
      assertEquals(entry.getValue().intValue(), entry.getKey().hashCode());
      assertTrue(retained.remove(entry.getKey()));
    }
    assertTrue(retained.isEmpty());
  }

  private static void exerciseProtocol(
      TestServer server, String identifier, LinkedBlockingQueue<RequestOutcome> completed)
      throws Exception {
    assertEquals(
        202,
        post(
                server,
                """
        {"jsonrpc":"2.0","method":"notifications/initialized"}
        """,
                identifier)
            .statusCode());
    assertNotNull(completed.poll(3, TimeUnit.SECONDS));
    var ping =
        post(
            server,
            """
        {"jsonrpc":"2.0","id":2,"method":"ping"}
        """,
            identifier);
    assertEquals(200, ping.statusCode());
    assertTrue(ping.body().contains("\"id\":2"));
    assertNotNull(completed.poll(3, TimeUnit.SECONDS));
    var output =
        server.send(request -> request.path("/stream"), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, output.statusCode());
    assertTrue(output.body().contains("notice"));
    assertNotNull(completed.poll(3, TimeUnit.SECONDS));
  }

  private static HttpResponse<String> post(TestServer server, String body, String session)
      throws Exception {
    return server.send(
        request -> {
          request
              .path("/mcp")
              .method("POST")
              .header("Content-Type", "application/json")
              .header("Accept", "application/json, text/event-stream")
              .body(body.getBytes(StandardCharsets.UTF_8));
          if (session != null) {
            request.header("Mcp-Session-Id", session).header("MCP-Protocol-Version", "2025-11-25");
          }
        },
        HttpResponse.BodyHandlers.ofString());
  }

  private static Object field(Object owner, String name) throws ReflectiveOperationException {
    var field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  private static Object frame(SseEvent event, MonoSink<Void> acknowledgment)
      throws ReflectiveOperationException {
    var type = Class.forName(McpStream.class.getName() + "$Frame");
    var constructor = type.getDeclaredConstructor(SseEvent.class, MonoSink.class);
    constructor.setAccessible(true);
    return constructor.newInstance(event, acknowledgment);
  }

  private static <T> ConfiguredEqualsVerifier prefab(
      ConfiguredEqualsVerifier configuration, Class<T> type, Object first, Object second) {
    return configuration.withPrefabValues(type, type.cast(first), type.cast(second));
  }
}
