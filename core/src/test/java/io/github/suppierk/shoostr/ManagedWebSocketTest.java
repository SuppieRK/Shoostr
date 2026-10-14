package io.github.suppierk.shoostr;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.suppierk.shoostr.http.exceptions.AuthenticationRequiredException;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.websocket.server.ServerWebSocketContainer;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ManagedWebSocketTest {
  @Test
  void rejectsDuplicateRoutesBeforeInvokingTheirConfiguration() throws Exception {
    try (var app = new Shoostr()) {
      var routes = app.routes();
      routes.websocket("/echo", events -> events.onText(WebSocketConnection::send));
      assertThrows(
          IllegalArgumentException.class,
          () -> routes.websocket("/echo", _ -> fail("Duplicate configuration must not run")));
    }
  }

  @Test
  @Timeout(10)
  void echoesTextThroughAnOrdinaryCallback() throws Exception {
    var app =
        new Shoostr()
            .routes(
                routes ->
                    routes.websocket("/echo", events -> events.onText(WebSocketConnection::send)));

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var received = new CompletableFuture<String>();
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/echo", null, null),
                  new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(
                        WebSocket webSocket, CharSequence data, boolean last) {
                      received.complete(data.toString());
                      return CompletableFuture.completedFuture(null);
                    }
                  })
              .get(3, TimeUnit.SECONDS);

      try {
        socket.sendText("hello", true).get(3, TimeUnit.SECONDS);
        assertEquals("hello", received.get(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(10)
  void rejectsInvalidInputBeforeTheWebSocketUpgrade() throws Exception {
    var app =
        new Shoostr()
            .routes(
                routes ->
                    routes.websocket(
                        "/rooms/{room}",
                        events -> {
                          events.beforeUpgrade(
                              (request, _) -> {
                                if (request.pathParam("room").orElseThrow().length() > 32) {
                                  throw new BadRequestException("Invalid room");
                                }
                              });
                          events.onText(WebSocketConnection::send);
                        }));

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var failure =
          assertThrows(
              ExecutionException.class,
              () ->
                  client
                      .newWebSocketBuilder()
                      .buildAsync(
                          new URI(
                              "ws",
                              test.baseUri().getRawAuthority(),
                              "/rooms/" + "x".repeat(33),
                              null,
                              null),
                          new WebSocket.Listener() {})
                      .get(3, TimeUnit.SECONDS));
      var handshake = assertInstanceOf(WebSocketHandshakeException.class, failure.getCause());
      assertEquals(400, handshake.getResponse().statusCode());
    }
  }

  @Test
  @Timeout(10)
  void suppliesDecodedPathParametersAfterUpgrade() throws Exception {
    var app =
        new Shoostr()
            .routes(
                routes ->
                    routes.websocket(
                        "/rooms/{room}",
                        events ->
                            events.onText(
                                (connection, text) ->
                                    connection.send(
                                        connection.pathParam("room").orElseThrow()
                                            + ": "
                                            + text))));

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var received = new CompletableFuture<String>();
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  URI.create("ws://" + test.baseUri().getRawAuthority() + "/rooms/team%20one"),
                  new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(
                        WebSocket webSocket, CharSequence data, boolean last) {
                      received.complete(data.toString());
                      return CompletableFuture.completedFuture(null);
                    }
                  })
              .get(3, TimeUnit.SECONDS);

      try {
        socket.sendText("hello", true).get(3, TimeUnit.SECONDS);
        assertEquals("team one: hello", received.get(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(10)
  void runsTheOpeningCallbackOnAVirtualThread() throws Exception {
    var app =
        new Shoostr()
            .routes(
                routes ->
                    routes.websocket(
                        "/open",
                        events ->
                            events.onOpen(
                                connection ->
                                    connection.send(
                                        Thread.currentThread().isVirtual()
                                            ? "virtual"
                                            : "platform"))));

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var received = new Messages();
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/open", null, null), received)
              .get(3, TimeUnit.SECONDS);

      try {
        assertEquals("virtual", received.text.poll(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(10)
  void echoesOwnedBytesFromACompleteFragmentedBinaryMessage() throws Exception {
    var app =
        new Shoostr()
            .routes(
                routes ->
                    routes.websocket(
                        "/binary", events -> events.onBinary(WebSocketConnection::send)));

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var received = new Messages();
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/binary", null, null), received)
              .get(3, TimeUnit.SECONDS);

      try {
        socket.sendBinary(ByteBuffer.wrap(new byte[] {0, 1}), false).get(3, TimeUnit.SECONDS);
        socket.sendBinary(ByteBuffer.wrap(new byte[] {-1, 127}), true).get(3, TimeUnit.SECONDS);
        assertArrayEquals(new byte[] {0, 1, -1, 127}, received.binary.poll(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(10)
  void completesANormalServerInitiatedClose() throws Exception {
    var app =
        new Shoostr()
            .routes(
                routes ->
                    routes.websocket(
                        "/close", events -> events.onText((connection, _) -> connection.close())));

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var received = new Messages();
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/close", null, null), received)
              .get(3, TimeUnit.SECONDS);

      try {
        socket.sendText("finish", true).get(3, TimeUnit.SECONDS);
        assertEquals(1000, received.closeCode.get(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(10)
  void reportsThePeersCloseCodeAndReasonToTheCloseCallback() throws Exception {
    var observed = new CompletableFuture<String>();
    var app =
        new Shoostr()
            .routes(
                routes ->
                    routes.websocket(
                        "/close",
                        events ->
                            events.onClose(
                                (_, code, reason) -> observed.complete(code + ":" + reason))));

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var received = new Messages();
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/close", null, null), received)
              .get(3, TimeUnit.SECONDS);

      try {
        socket.sendClose(1000, "Finished").get(3, TimeUnit.SECONDS);
        assertEquals("1000:Finished", observed.get(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  @RepeatedTest(20)
  @Timeout(10)
  void acknowledgesANormalPeerClose() throws Exception {
    var ready = new CompletableFuture<Void>();
    var app =
        new Shoostr()
            .routes(
                routes ->
                    routes.websocket("/close", events -> events.onOpen(_ -> ready.complete(null))));

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var received = new Messages();
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/close", null, null), received)
              .get(3, TimeUnit.SECONDS);

      try {
        ready.get(3, TimeUnit.SECONDS);
        socket.sendClose(1000, "Finished").get(3, TimeUnit.SECONDS);
        assertEquals(1000, received.closeCode.get(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(10)
  void reportsAnEmptyNonNullReasonWhenThePeerClosesWithoutAReason() throws Exception {
    var observed = new CompletableFuture<String>();
    var app =
        new Shoostr()
            .routes(
                routes ->
                    routes.websocket(
                        "/close",
                        events ->
                            events.onClose(
                                (_, code, reason) -> observed.complete(code + ":" + reason))));

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/close", null, null),
                  new Messages())
              .get(3, TimeUnit.SECONDS);

      try {
        socket.sendClose(1000, "").get(3, TimeUnit.SECONDS);
        assertEquals("1000:", observed.get(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(10)
  void reportsAnUnexpectedCheckedFailureToTheErrorCallback() throws Exception {
    var observed = new CompletableFuture<String>();
    var app =
        new Shoostr()
            .routes(
                routes ->
                    routes.websocket(
                        "/failure",
                        events -> {
                          events.onText(
                              (_, _) -> {
                                throw new IOException("Fixture failure");
                              });
                          events.onError((_, failure) -> observed.complete(failure.getMessage()));
                        }));

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var received = new Messages();
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/failure", null, null), received)
              .get(3, TimeUnit.SECONDS);

      try {
        socket.sendText("fail", true).get(3, TimeUnit.SECONDS);
        assertEquals("Fixture failure", observed.get(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(10)
  void runsHandshakeAdmissionOnTheSuppliedExecutor() throws Exception {
    try (var executor =
        Executors.newSingleThreadExecutor(
            task -> Thread.ofPlatform().name("ws-handshake").unstarted(task))) {
      var app =
          new Shoostr()
              .routes(
                  routes ->
                      routes.websocket(
                          "/executor",
                          executor,
                          events -> {
                            events.beforeUpgrade(
                                (request, _) -> {
                                  var name = Thread.currentThread().getName();
                                  request.principal(() -> name);
                                });
                            events.onText(
                                (connection, _) ->
                                    connection.send(
                                        connection.principal().orElseThrow().getName()));
                          }));

      try (var test = TestServer.start(app);
          var client = HttpClient.newHttpClient()) {
        var received = new Messages();
        var socket =
            client
                .newWebSocketBuilder()
                .buildAsync(
                    new URI("ws", test.baseUri().getRawAuthority(), "/executor", null, null),
                    received)
                .get(3, TimeUnit.SECONDS);

        try {
          socket.sendText("who", true).get(3, TimeUnit.SECONDS);
          assertEquals("ws-handshake", received.text.poll(3, TimeUnit.SECONDS));
        } finally {
          socket.abort();
        }
      }
    }
  }

  @Test
  void freezesConfigurationAfterRegistration() throws Exception {
    var captured = new AtomicReference<io.github.suppierk.shoostr.WebSocket>();

    try (var app = new Shoostr()) {
      app.routes().websocket("/echo", captured::set);
      var configuration = captured.get();
      assertNotNull(configuration);
      assertThrows(
          IllegalStateException.class, () -> configuration.onText(WebSocketConnection::send));
    }
  }

  @Test
  void releasesAReservedRouteWhenConfigurationFails() throws Exception {
    try (var app = new Shoostr()) {
      var routes = app.routes();
      assertThrows(
          IllegalArgumentException.class,
          () ->
              routes.websocket(
                  "/retry",
                  _ -> {
                    throw new IllegalArgumentException("Fixture failure");
                  }));
      app.routes().websocket("/retry", events -> events.onText(WebSocketConnection::send));
    }
  }

  @Test
  void preventsStartupWhileWebSocketConfigurationIsRunning() throws Exception {
    try (var app = new Shoostr()) {
      app.routes().websocket("/echo", _ -> assertThrows(IllegalStateException.class, app::start));
    }
  }

  @Test
  @Timeout(10)
  void retainsVirtualMessageCallbacksWithAForkJoinHandshakeExecutor() throws Exception {
    try (var executor = new ForkJoinPool(1)) {
      var app =
          new Shoostr()
              .routes(
                  routes ->
                      routes.path(
                          "/executor",
                          group ->
                              group.websocket(
                                  executor,
                                  events ->
                                      events.onText(
                                          (connection, _) ->
                                              connection.send(
                                                  Thread.currentThread().isVirtual()
                                                      ? "virtual"
                                                      : "platform")))));

      try (var test = TestServer.start(app);
          var client = HttpClient.newHttpClient()) {
        var received = new Messages();
        var socket =
            client
                .newWebSocketBuilder()
                .buildAsync(
                    new URI("ws", test.baseUri().getRawAuthority(), "/executor", null, null),
                    received)
                .get(3, TimeUnit.SECONDS);

        try {
          socket.sendText("thread", true).get(3, TimeUnit.SECONDS);
          assertEquals("virtual", received.text.poll(3, TimeUnit.SECONDS));
        } finally {
          socket.abort();
        }
      }

      assertFalse(executor.isShutdown());
    }
  }

  @Test
  @Timeout(10)
  void rejectsAnUnavailableManagedEndpointWith404() throws Exception {
    var app =
        new Shoostr()
            .routes(
                routes ->
                    routes.when(
                        () -> false, unavailable -> unavailable.websocket("/hidden", _ -> {})));

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var failure =
          assertThrows(
              ExecutionException.class,
              () ->
                  client
                      .newWebSocketBuilder()
                      .buildAsync(
                          new URI("ws", test.baseUri().getRawAuthority(), "/hidden", null, null),
                          new Messages())
                      .get(3, TimeUnit.SECONDS));
      assertEquals(
          404,
          assertInstanceOf(WebSocketHandshakeException.class, failure.getCause())
              .getResponse()
              .statusCode());
    }
  }

  @Test
  @Timeout(10)
  void requiresTheSelectedAuthenticatorBeforeUpgrade() throws Exception {
    var authentication = new FixtureAuthentication();
    var app =
        new Shoostr()
            .authentication(authentication)
            .routes(
                routes ->
                    routes.websocket(
                        "/secure",
                        _ -> {},
                        extensions -> extensions.get(authentication).required()));

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var failure =
          assertThrows(
              ExecutionException.class,
              () ->
                  client
                      .newWebSocketBuilder()
                      .buildAsync(
                          new URI("ws", test.baseUri().getRawAuthority(), "/secure", null, null),
                          new Messages())
                      .get(3, TimeUnit.SECONDS));
      assertEquals(
          401,
          assertInstanceOf(WebSocketHandshakeException.class, failure.getCause())
              .getResponse()
              .statusCode());
    }
  }

  @Test
  @Timeout(10)
  void retainsTheAuthenticatedIdentityAfterTheHandshake() throws Exception {
    var authentication = new FixtureAuthentication();
    var app =
        new Shoostr()
            .authentication(authentication)
            .routes(
                routes ->
                    routes.path(
                        "/secure",
                        group ->
                            group.websocket(
                                events ->
                                    events.onText(
                                        (connection, _) ->
                                            connection.send(
                                                connection.principal().orElseThrow().getName())),
                                extensions -> extensions.get(authentication).required())));

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var received = new Messages();
      var socket =
          client
              .newWebSocketBuilder()
              .header("X-Identity", "alice")
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/secure", null, null), received)
              .get(3, TimeUnit.SECONDS);

      try {
        socket.sendText("who", true).get(3, TimeUnit.SECONDS);
        assertEquals("alice", received.text.poll(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(10)
  void rejectsStagedOutputFromBeforeUpgradeAsAServerError() throws Exception {
    var app =
        new Shoostr()
            .routes(
                routes ->
                    routes.websocket(
                        "/invalid",
                        events ->
                            events.beforeUpgrade(
                                (_, response) -> response.status(403).text("Not a rejection"))));

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var failure =
          assertThrows(
              ExecutionException.class,
              () ->
                  client
                      .newWebSocketBuilder()
                      .buildAsync(
                          new URI("ws", test.baseUri().getRawAuthority(), "/invalid", null, null),
                          new Messages())
                      .get(3, TimeUnit.SECONDS));
      assertEquals(
          500,
          assertInstanceOf(WebSocketHandshakeException.class, failure.getCause())
              .getResponse()
              .statusCode());
    }
  }

  @Test
  @Timeout(10)
  void serializesRapidMessagesUntilThePreviousCallbackReturns() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var app =
        new Shoostr()
            .routes(
                routes ->
                    routes.websocket(
                        "/ordered",
                        events ->
                            events.onText(
                                (connection, text) -> {
                                  if ("first".equals(text)) {
                                    entered.countDown();
                                    release.await();
                                  }

                                  connection.send(text);
                                })));

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var received = new Messages();
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/ordered", null, null), received)
              .get(3, TimeUnit.SECONDS);

      try {
        socket.sendText("first", true).get(3, TimeUnit.SECONDS);
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        socket.sendText("second", true).get(3, TimeUnit.SECONDS);
        release.countDown();
        assertEquals("first", received.text.poll(3, TimeUnit.SECONDS));
        assertEquals("second", received.text.poll(3, TimeUnit.SECONDS));
      } finally {
        release.countDown();
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(10)
  void allowsConcurrentProducersToRetainAndSendThroughTheConnection() throws Exception {
    var opened = new CompletableFuture<WebSocketConnection>();
    var app =
        new Shoostr()
            .routes(
                routes -> routes.websocket("/producer", events -> events.onOpen(opened::complete)));

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient();
        var producers = Executors.newFixedThreadPool(2)) {
      var received = new Messages();
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/producer", null, null),
                  received)
              .get(3, TimeUnit.SECONDS);

      try {
        var connection = opened.get(3, TimeUnit.SECONDS);
        var first =
            producers.submit(
                () -> {
                  connection.send("alpha");
                  return null;
                });
        var second =
            producers.submit(
                () -> {
                  connection.send("beta");
                  return null;
                });
        first.get(3, TimeUnit.SECONDS);
        second.get(3, TimeUnit.SECONDS);
        var firstMessage = received.text.poll(3, TimeUnit.SECONDS);
        var secondMessage = received.text.poll(3, TimeUnit.SECONDS);
        assertNotNull(firstMessage);
        assertNotNull(secondMessage);
        assertEquals(Set.of("alpha", "beta"), Set.of(firstMessage, secondMessage));
      } finally {
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(10)
  void rejectsWritesAfterPublicClose() throws Exception {
    var opened = new CompletableFuture<WebSocketConnection>();
    var app =
        new Shoostr()
            .routes(
                routes -> routes.websocket("/close", events -> events.onOpen(opened::complete)));

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/close", null, null),
                  new Messages())
              .get(3, TimeUnit.SECONDS);

      try {
        var connection = opened.get(3, TimeUnit.SECONDS);
        connection.close();
        assertThrows(IOException.class, () -> connection.send("late"));
      } finally {
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(10)
  void preservesTheNativeMessageTooLargeCloseCode() throws Exception {
    var server = new AtomicReference<Server>();
    var app =
        new Shoostr()
            .routes(routes -> routes.websocket("/limit", _ -> {}))
            .modifyServer(server::set);

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      server
          .get()
          .getContainedBeans(ServerWebSocketContainer.class)
          .iterator()
          .next()
          .setMaxTextMessageSize(16);
      var received = new Messages();
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/limit", null, null), received)
              .get(3, TimeUnit.SECONDS);

      try {
        socket.sendText("x".repeat(17), true).get(3, TimeUnit.SECONDS);
        assertEquals(1009, received.closeCode.get(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(10)
  void interruptsActiveManagedWorkWhenTheAppCloses() throws Exception {
    var entered = new CountDownLatch(1);
    var canceled = new CompletableFuture<String>();
    var app =
        new Shoostr()
            .routes(
                routes ->
                    routes.websocket(
                        "/work",
                        events ->
                            events.onText(
                                (_, _) -> {
                                  entered.countDown();

                                  try {
                                    new CountDownLatch(1).await();
                                  } catch (InterruptedException failure) {
                                    canceled.complete("interrupted");
                                    throw failure;
                                  }
                                })));

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/work", null, null),
                  new Messages())
              .get(3, TimeUnit.SECONDS);

      try {
        socket.sendText("work", true).get(3, TimeUnit.SECONDS);
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        test.close();
        assertEquals("interrupted", canceled.get(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(10)
  void doesNotWaitForInterruptionIgnoringWorkDuringAppClose() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var exited = new CountDownLatch(1);
    var app =
        new Shoostr()
            .routes(
                routes ->
                    routes.websocket(
                        "/work",
                        events ->
                            events.onText(
                                (_, _) -> {
                                  entered.countDown();
                                  ignoreInterruptsUntilReleased(release);
                                  exited.countDown();
                                })));

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/work", null, null),
                  new Messages())
              .get(3, TimeUnit.SECONDS);

      try {
        socket.sendText("work", true).get(3, TimeUnit.SECONDS);
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        test.close();
        assertEquals(1, exited.getCount());
      } finally {
        release.countDown();
        assertTrue(exited.await(3, TimeUnit.SECONDS));
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(10)
  void defersTheCloseObserverUntilActiveApplicationWorkActuallyUnwinds() throws Exception {
    var opened = new CompletableFuture<WebSocketConnection>();
    var observedOverlap = new CompletableFuture<Boolean>();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var active = new AtomicBoolean();
    var app =
        new Shoostr()
            .routes(
                routes ->
                    routes.websocket(
                        "/work",
                        events -> {
                          events.onOpen(opened::complete);
                          events.onText(
                              (_, _) -> {
                                active.set(true);
                                entered.countDown();
                                ignoreInterruptsUntilReleased(release);
                                active.set(false);
                              });
                          events.onClose((_, _, _) -> observedOverlap.complete(active.get()));
                        }));

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var received = new Messages();
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/work", null, null), received)
              .get(3, TimeUnit.SECONDS);

      try {
        socket.sendText("work", true).get(3, TimeUnit.SECONDS);
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        opened.get(3, TimeUnit.SECONDS).close(1011, "Stopped");
        assertEquals(1011, received.closeCode.get(3, TimeUnit.SECONDS));
        await()
            .during(Duration.ofMillis(100))
            .atMost(Duration.ofSeconds(1))
            .untilAsserted(() -> assertFalse(observedOverlap.isDone()));
        release.countDown();
        assertFalse(observedOverlap.get(3, TimeUnit.SECONDS));
      } finally {
        release.countDown();
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(10)
  void stillNotifiesCloseWhenTheErrorObserverThrows() throws Exception {
    var closed = new CompletableFuture<Integer>();
    var app =
        new Shoostr()
            .routes(
                routes ->
                    routes.websocket(
                        "/failure",
                        events -> {
                          events.onText(
                              (_, _) -> {
                                throw new IOException("Fixture failure");
                              });
                          events.onError(
                              (_, _) -> {
                                throw new IllegalStateException("Observer failure");
                              });
                          events.onClose((_, code, _) -> closed.complete(code));
                        }));

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/failure", null, null),
                  new Messages())
              .get(3, TimeUnit.SECONDS);

      try {
        socket.sendText("fail", true).get(3, TimeUnit.SECONDS);
        assertEquals(1011, closed.get(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(10)
  void preservesInterruptionWhenAnErrorObserverThrowsAnError() throws Exception {
    var observed = new CompletableFuture<Boolean>();
    var fatal = new LinkageError("Observer failure");
    var app =
        new Shoostr()
            .routes(
                routes ->
                    routes.websocket(
                        "/failure",
                        events -> {
                          events.onOpen(
                              _ ->
                                  Thread.currentThread()
                                      .setUncaughtExceptionHandler(
                                          (thread, failure) -> {
                                            if (failure == fatal) {
                                              observed.complete(thread.isInterrupted());
                                            } else {
                                              observed.completeExceptionally(failure);
                                            }
                                          }));
                          events.onText(
                              (_, _) -> {
                                Thread.currentThread().interrupt();
                                throw new IOException("Fixture failure");
                              });
                          events.onError(
                              (_, _) -> {
                                throw fatal;
                              });
                        }));

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/failure", null, null),
                  new Messages())
              .get(3, TimeUnit.SECONDS);

      try {
        socket.sendText("fail", true).get(3, TimeUnit.SECONDS);
        assertTrue(observed.get(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(10)
  void completesInterruptibleCloseWorkAfterAnErrorObserverIsInterrupted() throws Exception {
    var observed = new CompletableFuture<Boolean>();
    var ready = new CountDownLatch(0);
    var app =
        new Shoostr()
            .routes(
                routes ->
                    routes.websocket(
                        "/failure",
                        events -> {
                          events.onText(
                              (_, _) -> {
                                throw new IOException("Fixture failure");
                              });
                          events.onError(
                              (_, _) -> {
                                throw new InterruptedException("Observer interrupted");
                              });
                          events.onClose(
                              (_, _, _) -> {
                                ready.await();
                                observed.complete(true);
                              });
                        }));

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/failure", null, null),
                  new Messages())
              .get(3, TimeUnit.SECONDS);

      try {
        socket.sendText("fail", true).get(3, TimeUnit.SECONDS);
        assertTrue(observed.get(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(10)
  void validatesCloseArgumentsBeforeChangingConnectionState() throws Exception {
    var opened = new CompletableFuture<WebSocketConnection>();
    var app =
        new Shoostr()
            .routes(
                routes ->
                    routes.websocket("/validation", events -> events.onOpen(opened::complete)));

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var received = new Messages();
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/validation", null, null),
                  received)
              .get(3, TimeUnit.SECONDS);

      try {
        var connection = opened.get(3, TimeUnit.SECONDS);
        assertThrows(IllegalArgumentException.class, () -> connection.close(1006, "Invalid"));
        var oversizedReason = "x".repeat(124);
        assertThrows(IllegalArgumentException.class, () -> connection.close(1000, oversizedReason));
        connection.send("still open");
        assertEquals("still open", received.text.poll(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(20)
  void snapshotsMutableBinaryOutputBeforeWaitingForTransportCompletion() throws Exception {
    var bytes = new byte[16 * 1024 * 1024];
    Arrays.fill(bytes, (byte) 1);
    var sent = new CompletableFuture<Void>();
    var app =
        new Shoostr()
            .routes(
                routes ->
                    routes.websocket(
                        "/bytes",
                        events ->
                            events.onText(
                                (connection, _) -> {
                                  connection.send(bytes);
                                  sent.complete(null);
                                })));

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var received = new PausedBinaryMessages();
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/bytes", null, null), received)
              .get(3, TimeUnit.SECONDS);

      try {
        socket.sendText("bytes", true).get(3, TimeUnit.SECONDS);
        received.first.get(3, TimeUnit.SECONDS);
        assertFalse(sent.isDone());
        Arrays.fill(bytes, (byte) 2);
        received.release.complete(null);
        var expected = new byte[16 * 1024 * 1024];
        Arrays.fill(expected, (byte) 1);
        assertArrayEquals(expected, received.payload.get(10, TimeUnit.SECONDS));
        sent.get(3, TimeUnit.SECONDS);
      } finally {
        received.release.complete(null);
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(15)
  void settlesABlockedSendWhenTheAppCloses() throws Exception {
    var settled = new CompletableFuture<String>();
    var app =
        new Shoostr()
            .routes(
                routes ->
                    routes.websocket(
                        "/bytes",
                        events ->
                            events.onText(
                                (connection, _) -> {
                                  try {
                                    connection.send(new byte[16 * 1024 * 1024]);
                                    settled.complete("sent");
                                  } catch (IOException | InterruptedException failure) {
                                    settled.complete("settled");
                                    if (failure instanceof InterruptedException) {
                                      Thread.currentThread().interrupt();
                                    }
                                  }
                                })));

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var received = new PausedBinaryMessages();
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/bytes", null, null), received)
              .get(3, TimeUnit.SECONDS);

      try {
        socket.sendText("bytes", true).get(3, TimeUnit.SECONDS);
        received.first.get(3, TimeUnit.SECONDS);
        assertFalse(settled.isDone());
        test.close();
        assertEquals("settled", settled.get(3, TimeUnit.SECONDS));
      } finally {
        received.release.complete(null);
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(10)
  void completesTheNormalServerCloseHandshakeBeforeNotifyingItsObserver() throws Exception {
    var closed = new CompletableFuture<Integer>();
    var app =
        new Shoostr()
            .routes(
                routes ->
                    routes.websocket(
                        "/close",
                        events -> {
                          events.onText((connection, _) -> connection.close());
                          events.onClose((_, code, _) -> closed.complete(code));
                        }));

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/close", null, null),
                  new Messages())
              .get(3, TimeUnit.SECONDS);

      try {
        socket.sendText("finish", true).get(3, TimeUnit.SECONDS);
        assertEquals(1000, closed.get(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(10)
  void reportsNativeSendRejectionWithoutLeavingTheCallerWaiting() throws Exception {
    var server = new AtomicReference<Server>();
    var failure = new CompletableFuture<Throwable>();
    var app =
        new Shoostr()
            .modifyServer(server::set)
            .routes(
                routes ->
                    routes.websocket(
                        "/reject",
                        events -> {
                          events.onText((connection, _) -> connection.send("x".repeat(64)));
                          events.onError((_, cause) -> failure.complete(cause));
                        }));

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var container =
          server.get().getContainedBeans(ServerWebSocketContainer.class).iterator().next();
      container.setMaxFrameSize(32);
      container.setAutoFragment(false);
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/reject", null, null),
                  new Messages())
              .get(3, TimeUnit.SECONDS);

      try {
        socket.sendText("rejected", true).get(3, TimeUnit.SECONDS);
        assertInstanceOf(IOException.class, failure.get(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(10)
  void closesSafelyWhenAnApplicationErrorEscapes() throws Exception {
    var app =
        new Shoostr()
            .routes(
                routes ->
                    routes.websocket(
                        "/failure",
                        events ->
                            events.onText(
                                (_, _) -> {
                                  throw new LinkageError("Private fixture failure");
                                })));

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var received = new Messages();
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/failure", null, null), received)
              .get(3, TimeUnit.SECONDS);

      try {
        socket.sendText("fail", true).get(3, TimeUnit.SECONDS);
        assertEquals(1011, received.closeCode.get(3, TimeUnit.SECONDS));
        assertEquals("Processing failed", received.closeReason.get(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  private static final class PausedBinaryMessages implements WebSocket.Listener {
    private final ByteArrayOutputStream bytes;
    private final CompletableFuture<Void> first;
    private final CompletableFuture<Void> release;
    private final CompletableFuture<byte[]> payload;
    private boolean paused;

    private PausedBinaryMessages() {
      bytes = new ByteArrayOutputStream();
      first = new CompletableFuture<>();
      release = new CompletableFuture<>();
      payload = new CompletableFuture<>();
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
      var owned = new byte[data.remaining()];
      data.get(owned);
      bytes.writeBytes(owned);
      if (last) {
        payload.complete(bytes.toByteArray());
      }

      if (!paused) {
        paused = true;
        first.complete(null);
        return release.thenRun(() -> socket.request(1));
      }

      socket.request(1);
      return CompletableFuture.completedFuture(null);
    }
  }

  private static void ignoreInterruptsUntilReleased(CountDownLatch release) {
    boolean interrupted = false;
    while (true) {
      try {
        release.await();
        break;
      } catch (InterruptedException _) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private static final class FixtureAuthentication extends AuthenticationExtension {
    private FixtureAuthentication() {}

    @Override
    public void handle(Request request, Response response) {
      var name =
          request
              .header("X-Identity")
              .orElseThrow(() -> new AuthenticationRequiredException("Bearer"));
      request.principal(() -> name);
    }
  }

  private static final class Messages implements WebSocket.Listener {
    private final LinkedBlockingQueue<String> text;
    private final LinkedBlockingQueue<byte[]> binary;
    private final CompletableFuture<Integer> closeCode;
    private final CompletableFuture<String> closeReason;
    private final StringBuilder textParts;
    private final ByteArrayOutputStream binaryParts;

    private Messages() {
      text = new LinkedBlockingQueue<>();
      binary = new LinkedBlockingQueue<>();
      closeCode = new CompletableFuture<>();
      closeReason = new CompletableFuture<>();
      textParts = new StringBuilder();
      binaryParts = new ByteArrayOutputStream();
    }

    @Override
    public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
      textParts.append(data);
      if (last) {
        text.add(textParts.toString());
        textParts.setLength(0);
      }

      socket.request(1);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
      var bytes = new byte[data.remaining()];
      data.get(bytes);
      binaryParts.writeBytes(bytes);
      if (last) {
        binary.add(binaryParts.toByteArray());
        binaryParts.reset();
      }

      socket.request(1);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
      closeCode.complete(code);
      closeReason.complete(reason);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket socket, Throwable failure) {
      closeCode.completeExceptionally(failure);
      closeReason.completeExceptionally(failure);
    }
  }
}
