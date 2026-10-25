package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import nl.jqno.equalsverifier.api.SingleTypeEqualsVerifierApi;
import org.eclipse.jetty.websocket.api.Session;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WebSocketEqualityTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void identifiesSeparateConnectionsAndRetainsHashesThroughMessagesAndClose(boolean typed)
      throws Exception {
    var opened = new LinkedBlockingQueue<WebSocketConnection>();
    var closed = new LinkedBlockingQueue<WebSocketConnection>();
    var hashes = new ConcurrentHashMap<Object, Integer>();
    var connections = new ArrayList<WebSocketConnection>();
    var clients = new ArrayList<WebSocket>();

    try (var app = new Shoostr();
        var client = HttpClient.newHttpClient();
        var firstExecutor = Executors.newSingleThreadExecutor();
        var secondExecutor = Executors.newSingleThreadExecutor()) {
      if (typed) {
        app.codec(codec("configured"));
      }

      app.routes()
          .websocket(
              "/socket",
              socket ->
                  socket
                      .onOpen(
                          connection -> {
                            hashes.put(connection, connection.hashCode());
                            var listener = listener(connection);
                            hashes.put(listener, listener.hashCode());
                            opened.add(connection);
                          })
                      .onText((connection, text) -> connection.send("echo:" + text))
                      .onClose((connection, code, reason) -> closed.add(connection)));

      try (var server = TestServer.start(app)) {
        try {
          for (int index = 0; index < 2; index++) {
            var messages = new LinkedBlockingQueue<String>();
            var nativeClient =
                client
                    .newWebSocketBuilder()
                    .buildAsync(
                        new URI("ws", server.baseUri().getRawAuthority(), "/socket", null, null),
                        new WebSocket.Listener() {
                          @Override
                          public CompletionStage<?> onText(
                              WebSocket socket, CharSequence text, boolean last) {
                            messages.add(text.toString());
                            return WebSocket.Listener.super.onText(socket, text, last);
                          }
                        })
                    .get(3, TimeUnit.SECONDS);
            clients.add(nativeClient);
            var connection = opened.poll(3, TimeUnit.SECONDS);
            assertNotNull(connection);
            connections.add(connection);
            nativeClient.sendText("same", true).get(3, TimeUnit.SECONDS);
            assertEquals("echo:same", messages.poll(3, TimeUnit.SECONDS));
          }
          assertNotEquals(connections.get(0), connections.get(1));
          if (typed) {
            verifier(TypedWebSocketConnection.class, connections, firstExecutor, secondExecutor)
                .verify();
          } else {
            verifier(WebSocketConnection.class, connections, firstExecutor, secondExecutor)
                .withRedefinedSubclass(TypedWebSocketConnection.class)
                .verify();
          }

          verifier(ManagedWebSocket.class, connections, firstExecutor, secondExecutor).verify();
          verifier(WebSocketRuntime.class, connections, firstExecutor, secondExecutor).verify();
          for (var nativeClient : clients) {
            nativeClient.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(3, TimeUnit.SECONDS);
            var connection = closed.poll(3, TimeUnit.SECONDS);
            assertNotNull(connection);
          }
        } finally {
          clients.forEach(WebSocket::abort);
        }
      }
    }

    var retained = new HashSet<>(hashes.keySet());
    for (var entry : hashes.entrySet()) {
      assertEquals(entry.getKey(), entry.getKey());
      assertEquals(entry.getValue().intValue(), entry.getKey().hashCode());
      assertTrue(retained.remove(entry.getKey()));
    }
    assertTrue(retained.isEmpty());
    assertTrue(closed.isEmpty());
  }

  private static <T> SingleTypeEqualsVerifierApi<T> verifier(
      Class<T> type,
      List<WebSocketConnection> connections,
      Executor firstExecutor,
      Executor secondExecutor)
      throws ReflectiveOperationException {
    return EqualsVerifier.forClass(type)
        .withPrefabValues(WebSocketConnection.class, connections.get(0), connections.get(1))
        .withPrefabValues(
            ManagedWebSocket.class, listener(connections.get(0)), listener(connections.get(1)))
        .withPrefabValues(Session.class, session(connections.get(0)), session(connections.get(1)))
        .withPrefabValues(
            WebSocketRuntime.class,
            new WebSocketRuntime(firstExecutor),
            new WebSocketRuntime(secondExecutor))
        .withPrefabValues(Executor.class, firstExecutor, secondExecutor)
        .withPrefabValues(
            io.github.suppierk.shoostr.WebSocket.class,
            new io.github.suppierk.shoostr.WebSocket()
                .onText((connection, message) -> connection.send("first")),
            new io.github.suppierk.shoostr.WebSocket()
                .onText((connection, message) -> connection.send("second")))
        .withPrefabValues(Codec.class, codec("first"), codec("second"))
        .withPrefabValues(ReentrantLock.class, new ReentrantLock(), new ReentrantLock())
        .withPrefabValues(
            CompletableFuture.class, new CompletableFuture<Void>(), new CompletableFuture<Void>())
        .withPrefabValues(
            FutureTask.class, new FutureTask<Void>(() -> null), new FutureTask<Void>(() -> null))
        .withPrefabValues(
            Thread.class, new Thread(() -> {}, "first"), new Thread(() -> {}, "second"))
        .withPrefabValues(
            Throwable.class,
            new IllegalStateException("first"),
            new IllegalStateException("second"))
        .suppress(Warning.INHERITED_DIRECTLY_FROM_OBJECT, Warning.ALL_FIELDS_SHOULD_BE_USED);
  }

  private static ManagedWebSocket listener(WebSocketConnection connection)
      throws ReflectiveOperationException {
    var field = WebSocketConnection.class.getDeclaredField("listener");
    field.setAccessible(true);
    return ManagedWebSocket.class.cast(field.get(connection));
  }

  private static Session session(WebSocketConnection connection)
      throws ReflectiveOperationException {
    var field = WebSocketConnection.class.getDeclaredField("session");
    field.setAccessible(true);
    return Session.class.cast(field.get(connection));
  }

  private static Codec codec(String marker) {
    return new Codec() {
      @Override
      public <T> T read(byte[] source, Class<T> type) {
        return type.cast(new String(source, StandardCharsets.UTF_8));
      }

      @Override
      public byte[] write(Object value) {
        return (marker + value).getBytes(StandardCharsets.UTF_8);
      }
    };
  }
}
