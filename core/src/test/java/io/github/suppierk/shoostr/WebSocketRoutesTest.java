package io.github.suppierk.shoostr;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.exceptions.AuthenticationRequiredException;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import io.github.suppierk.shoostr.http.exceptions.ForbiddenException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.channels.WritePendingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.Principal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.server.ServerUpgradeResponse;
import org.eclipse.jetty.websocket.server.ServerWebSocketContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class WebSocketRoutesTest {
  @TempDir Path temporaryDirectory;

  @Test
  @Timeout(10)
  void echoesTextThroughAWebSocketRoute() throws Exception {
    var received = new LinkedBlockingQueue<String>();
    var factoryCalls = new AtomicInteger();
    var completedSends = new CountDownLatch(3);
    var sendFailure = new AtomicReference<Throwable>();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().get("/echo", (request, response) -> response.text("ordinary"));
      app.routes()
          .websocket(
              "/echo",
              (request, upgrade) -> {
                factoryCalls.incrementAndGet();
                return new EchoListener("", completedSends, sendFailure);
              });
      app.start();
      var ordinary =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/echo"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals("ordinary", ordinary.body());

      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  URI.create("ws://127.0.0.1:" + app.port() + "/echo"),
                  new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(
                        WebSocket webSocket, CharSequence data, boolean last) {
                      received.add(data.toString());
                      webSocket.request(1);
                      return CompletableFuture.completedFuture(null);
                    }
                  })
              .get(3, TimeUnit.SECONDS);

      socket.sendText("hel", false).get(3, TimeUnit.SECONDS);
      socket.sendText("lo", true).get(3, TimeUnit.SECONDS);
      socket.sendText("second", true).get(3, TimeUnit.SECONDS);
      socket.sendText("third", true).get(3, TimeUnit.SECONDS);
      assertEquals("hello", received.poll(3, TimeUnit.SECONDS));
      assertEquals("second", received.poll(3, TimeUnit.SECONDS));
      assertEquals("third", received.poll(3, TimeUnit.SECONDS));
      assertTrue(completedSends.await(3, TimeUnit.SECONDS));
      assertNull(sendFailure.get());
      assertEquals(1, factoryCalls.get());
      socket.abort();
    }
  }

  @Test
  @Timeout(10)
  void upgradesAndExchangesWebSocketMessagesOverTls() throws Exception {
    var keyStore = KeyStore.getInstance("PKCS12");

    try (var input =
        Objects.requireNonNull(getClass().getResourceAsStream("/localhost-test.p12"))) {
      keyStore.load(input, "changeit".toCharArray());
    }

    var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trust.init(keyStore);
    var context = SSLContext.getInstance("TLS");
    context.init(null, trust.getTrustManagers(), null);
    var keyStorePath = temporaryDirectory.resolve("localhost-test.p12");

    try (var input =
        Objects.requireNonNull(getClass().getResourceAsStream("/localhost-test.p12"))) {
      Files.copy(input, keyStorePath, StandardCopyOption.REPLACE_EXISTING);
    }

    var received = new LinkedBlockingQueue<String>();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newBuilder().sslContext(context).build()) {
      app.tls(
          tls -> {
            tls.setKeyStorePath(keyStorePath.toString());
            tls.setKeyStorePassword("changeit");
          });
      app.routes()
          .websocket(
              "/secure",
              (request, upgrade) -> {
                assertTrue(request.isSecure());
                assertEquals("https", request.scheme());
                return new EchoListener("secure:");
              });
      app.start();

      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  URI.create("wss://localhost:" + app.port() + "/secure"),
                  new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(
                        WebSocket webSocket, CharSequence data, boolean last) {
                      received.add(data.toString());
                      webSocket.request(1);
                      return CompletableFuture.completedFuture(null);
                    }
                  })
              .get(3, TimeUnit.SECONDS);

      socket.sendText("hello", true).get(3, TimeUnit.SECONDS);
      assertEquals("secure:hello", received.poll(3, TimeUnit.SECONDS));
      socket.abort();
    }
  }

  @Test
  @Timeout(10)
  void passesParsedProtocolsAndPathParametersToAListenerFactoryForEachConnection()
      throws Exception {
    var factoryCalls = new AtomicInteger();
    var firstMessage = new CompletableFuture<String>();
    var secondMessage = new CompletableFuture<String>();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .path(
              "/rooms",
              routes ->
                  routes.websocket(
                      "/{id}",
                      (request, upgrade) -> {
                        assertEquals(List.of("chat.v1", "chat.v2"), request.webSocketProtocols());
                        upgrade.setAcceptedSubProtocol("chat.v2");
                        factoryCalls.incrementAndGet();
                        return new EchoListener(request.pathParam("id") + ":");
                      }));
      app.start();

      var first =
          client
              .newWebSocketBuilder()
              .subprotocols("chat.v1", "chat.v2")
              .buildAsync(
                  URI.create("ws://127.0.0.1:" + app.port() + "/rooms/first"),
                  receiving(firstMessage))
              .get(3, TimeUnit.SECONDS);
      var second =
          client
              .newWebSocketBuilder()
              .subprotocols("chat.v1", "chat.v2")
              .buildAsync(
                  URI.create("ws://127.0.0.1:" + app.port() + "/rooms/second"),
                  receiving(secondMessage))
              .get(3, TimeUnit.SECONDS);

      assertEquals("chat.v2", first.getSubprotocol());
      assertEquals("chat.v2", second.getSubprotocol());
      first.sendText("hello", true).get(3, TimeUnit.SECONDS);
      second.sendText("hello", true).get(3, TimeUnit.SECONDS);
      assertEquals("first:hello", firstMessage.get(3, TimeUnit.SECONDS));
      assertEquals("second:hello", secondMessage.get(3, TimeUnit.SECONDS));
      assertEquals(2, factoryCalls.get());
      first.abort();
      second.abort();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @Timeout(10)
  void prefersLiteralWebSocketRoutesAndFallsBackAfterSuffixMismatch(boolean literalFirst)
      throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      BiFunction<Request, ServerUpgradeResponse, Session.Listener> literal =
          (request, upgrade) -> new EchoListener("literal:");
      BiFunction<Request, ServerUpgradeResponse, Session.Listener> parameter =
          (request, upgrade) -> new EchoListener("parameter:" + request.pathParam("id") + ":");
      if (literalFirst) {
        app.routes().websocket("/rooms/latest", literal);
        app.routes().websocket("/rooms/{id}", parameter);
      } else {
        app.routes().websocket("/rooms/{id}", parameter);
        app.routes().websocket("/rooms/latest", literal);
      }

      app.routes().websocket("/fallback/fixed/x", (request, upgrade) -> new EchoListener("x:"));
      app.routes()
          .websocket(
              "/fallback/{name}/y",
              (request, upgrade) -> new EchoListener(request.pathParam("name") + ":"));
      app.start();

      assertEquals("literal:ping", sendAndReceive(client, app, "/rooms/latest"));
      assertEquals("parameter:42:ping", sendAndReceive(client, app, "/rooms/42"));
      assertEquals("fixed:ping", sendAndReceive(client, app, "/fallback/fixed/y"));
    }
  }

  @Test
  @Timeout(10)
  void rejectsAnOriginInRouteAdmissionBeforeCallingTheListenerFactory() throws Exception {
    var factoryCalls = new AtomicInteger();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .protect(
              (request, response) -> {
                if (!"https://allowed.example".equals(request.header("Origin"))) {
                  throw new ForbiddenException();
                }
              },
              routes ->
                  routes.websocket(
                      "/private",
                      (request, upgrade) -> {
                        factoryCalls.incrementAndGet();
                        return new EchoListener();
                      }));
      app.start();

      var failure =
          assertThrows(
              ExecutionException.class,
              () ->
                  client
                      .newWebSocketBuilder()
                      .header("Origin", "https://denied.example")
                      .buildAsync(
                          URI.create("ws://127.0.0.1:" + app.port() + "/private"),
                          new WebSocket.Listener() {})
                      .get(3, TimeUnit.SECONDS));
      assertEquals(
          403,
          assertInstanceOf(WebSocketHandshakeException.class, failure.getCause())
              .getResponse()
              .statusCode());
      assertEquals(0, factoryCalls.get());
    }
  }

  @Test
  @Timeout(10)
  void authenticatesBeforeUpgradeAndPassesThePrincipalToTheNewListener() throws Exception {
    var accepted = new CompletableFuture<String>();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .protect(
              (request, response) -> {
                if (!"Bearer secret".equals(request.header("Authorization"))) {
                  throw new AuthenticationRequiredException("Bearer realm=\"chat\"");
                }

                request.principal((Principal) () -> "alice");
              },
              routes ->
                  routes.websocket(
                      "/authenticated",
                      (request, upgrade) ->
                          new EchoListener(
                              Objects.requireNonNull(request.principal()).getName() + ":")));
      app.start();

      var rejected =
          assertThrows(
              ExecutionException.class,
              () ->
                  client
                      .newWebSocketBuilder()
                      .buildAsync(
                          URI.create("ws://127.0.0.1:" + app.port() + "/authenticated"),
                          new WebSocket.Listener() {})
                      .get(3, TimeUnit.SECONDS));
      var challenge =
          assertInstanceOf(WebSocketHandshakeException.class, rejected.getCause()).getResponse();
      assertEquals(401, challenge.statusCode());
      assertEquals(
          "Bearer realm=\"chat\"",
          challenge.headers().firstValue("WWW-Authenticate").orElseThrow());

      var socket =
          client
              .newWebSocketBuilder()
              .header("Authorization", "Bearer secret")
              .buildAsync(
                  URI.create("ws://127.0.0.1:" + app.port() + "/authenticated"),
                  receiving(accepted))
              .get(3, TimeUnit.SECONDS);
      socket.sendText("hello", true).get(3, TimeUnit.SECONDS);
      assertEquals("alice:hello", accepted.get(3, TimeUnit.SECONDS));
      socket.abort();
    }
  }

  @Test
  @Timeout(10)
  void completesInvalidUpgradeWith426AndAllowsAFactoryHandshakeHeader() throws Exception {
    var factoryCalls = new AtomicInteger();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes()
          .websocket(
              "/wire",
              (request, upgrade) -> {
                factoryCalls.incrementAndGet();
                upgrade.getHeaders().put("X-Handshake", "accepted");
                return new EchoListener();
              });
      app.start();

      var invalid = handshake(app, 12);
      assertTrue(invalid.getFirst().contains(" 426 "), invalid.toString());
      assertEquals(0, factoryCalls.get());
      assertTrue(invalid.stream().anyMatch("Upgrade: websocket"::equalsIgnoreCase));
      assertTrue(invalid.stream().anyMatch("Sec-WebSocket-Version: 13"::equalsIgnoreCase));

      var valid = handshake(app, 13);
      assertTrue(valid.getFirst().contains(" 101 "));
      assertTrue(valid.stream().anyMatch("X-Handshake: accepted"::equalsIgnoreCase));
      assertEquals(1, factoryCalls.get());
    }
  }

  @Test
  @Timeout(10)
  void mapsFactoryRejectionAndReportsOneApplicationFailure() throws Exception {
    var outcome = new CompletableFuture<RequestOutcome>();
    var notifications = new AtomicInteger();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.exception(
          BadRequestException.class,
          (failure, request, response) ->
              response.status(422).header("X-Error-Mapped", "yes").text("mapped"));
      app.afterRequest(
          result -> {
            notifications.incrementAndGet();
            outcome.complete(result);
          });
      app.routes()
          .websocket(
              "/reject",
              (request, upgrade) -> {
                throw new BadRequestException();
              });
      app.start();

      var failure =
          assertThrows(
              ExecutionException.class,
              () ->
                  client
                      .newWebSocketBuilder()
                      .buildAsync(
                          URI.create("ws://127.0.0.1:" + app.port() + "/reject"),
                          new WebSocket.Listener() {})
                      .get(3, TimeUnit.SECONDS));
      var handshake =
          assertInstanceOf(WebSocketHandshakeException.class, failure.getCause()).getResponse();
      assertEquals(422, handshake.statusCode());
      assertEquals("yes", handshake.headers().firstValue("X-Error-Mapped").orElseThrow());
      assertEquals(422, outcome.get(3, TimeUnit.SECONDS).statusCode());
      assertInstanceOf(BadRequestException.class, outcome.getNow(null).applicationFailure());
      assertEquals(1, notifications.get());
    }
  }

  @Test
  @Timeout(10)
  void retainsFactoryFailureWhenTheErrorResponseFlushHookAlsoFails() throws Exception {
    var original = new BadRequestException();
    var postFlush = new IOException("after flush");
    var outcome = new CompletableFuture<RequestOutcome>();
    var notifications = new AtomicInteger();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.afterRequest(
          result -> {
            notifications.incrementAndGet();
            outcome.complete(result);
          });
      app.afterResponseFlush(
          (request, response) -> {
            throw postFlush;
          });
      app.exception(
          BadRequestException.class, (failure, request, response) -> response.text("mapped"));
      app.routes()
          .websocket(
              "/flush-failure",
              (request, upgrade) -> {
                throw original;
              });
      app.start();

      var failure =
          assertThrows(
              ExecutionException.class,
              () ->
                  client
                      .newWebSocketBuilder()
                      .buildAsync(
                          URI.create("ws://127.0.0.1:" + app.port() + "/flush-failure"),
                          new WebSocket.Listener() {})
                      .get(3, TimeUnit.SECONDS));
      assertEquals(
          400,
          assertInstanceOf(WebSocketHandshakeException.class, failure.getCause())
              .getResponse()
              .statusCode());
      assertSame(original, outcome.get(3, TimeUnit.SECONDS).applicationFailure());
      assertArrayEquals(new Throwable[] {postFlush}, original.getSuppressed());
      assertEquals(1, notifications.get());
    }
  }

  @Test
  @Timeout(10)
  void doesNotAcceptUnexpectedFailuresNullListenersOrUnofferedProtocols() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .websocket(
              "/failure",
              (request, upgrade) -> {
                throw new IllegalStateException("broken factory");
              });
      app.routes().websocket("/null", (request, upgrade) -> null);
      app.routes()
          .websocket(
              "/protocol",
              (request, upgrade) -> {
                upgrade.setAcceptedSubProtocol("not-offered");
                return new EchoListener();
              });
      app.start();

      assertEquals(500, failedHandshakeStatus(client, app, "/failure"));
      assertEquals(500, failedHandshakeStatus(client, app, "/null"));
      assertNotEquals(101, failedHandshakeStatus(client, app, "/protocol"));
    }
  }

  @Test
  @Timeout(10)
  void exchangesBinaryPingPongAndNormalCloseThroughTheNativeSession() throws Exception {
    var binary = new CompletableFuture<byte[]>();
    var pong = new CompletableFuture<byte[]>();
    var closed = new CompletableFuture<Integer>();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().websocket("/binary", (request, upgrade) -> new BinaryEchoListener());
      app.start();
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  URI.create("ws://127.0.0.1:" + app.port() + "/binary"),
                  new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onBinary(
                        WebSocket webSocket, ByteBuffer data, boolean last) {
                      var payload = new byte[data.remaining()];
                      data.get(payload);
                      binary.complete(payload);
                      webSocket.request(1);
                      return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer data) {
                      var payload = new byte[data.remaining()];
                      data.get(payload);
                      pong.complete(payload);
                      webSocket.request(1);
                      return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public CompletionStage<?> onClose(
                        WebSocket webSocket, int statusCode, String reason) {
                      closed.complete(statusCode);
                      return CompletableFuture.completedFuture(null);
                    }
                  })
              .get(3, TimeUnit.SECONDS);

      var payload = new byte[] {1, 2, 3};
      socket.sendBinary(ByteBuffer.wrap(payload), true).get(3, TimeUnit.SECONDS);
      assertArrayEquals(payload, binary.get(3, TimeUnit.SECONDS));
      socket.sendPing(ByteBuffer.wrap(payload)).get(3, TimeUnit.SECONDS);
      assertArrayEquals(payload, pong.get(3, TimeUnit.SECONDS));
      socket.sendClose(1000, "done").get(3, TimeUnit.SECONDS);
      assertEquals(1000, closed.get(3, TimeUnit.SECONDS));
    }
  }

  @Test
  @Timeout(15)
  void closesAnActiveWebSocketWhenTheAppStops() throws Exception {
    var terminated = new CompletableFuture<Void>();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().websocket("/live", (request, upgrade) -> new EchoListener());
      app.start();
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  URI.create("ws://127.0.0.1:" + app.port() + "/live"),
                  new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onClose(
                        WebSocket webSocket, int statusCode, String reason) {
                      terminated.complete(null);
                      return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                      terminated.complete(null);
                    }
                  })
              .get(3, TimeUnit.SECONDS);

      app.close();
      terminated.get(3, TimeUnit.SECONDS);
      await().atMost(Duration.ofSeconds(3)).until(socket::isOutputClosed);
      socket.abort();
    }
  }

  @Test
  @Timeout(10)
  void boundsOutgoingFramesAndAllowsPerSessionMessageLimits() throws Exception {
    var defaultOutgoing = new CompletableFuture<Integer>();
    var configuredOutgoing = new CompletableFuture<Integer>();
    var accepted = new CompletableFuture<String>();
    var closed = new CompletableFuture<Integer>();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .websocket(
              "/bounded",
              (request, upgrade) ->
                  new LimitedListener(defaultOutgoing, configuredOutgoing, accepted));
      app.start();
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  URI.create("ws://127.0.0.1:" + app.port() + "/bounded"),
                  new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onClose(
                        WebSocket webSocket, int statusCode, String reason) {
                      closed.complete(statusCode);
                      return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                      closed.completeExceptionally(error);
                    }
                  })
              .get(3, TimeUnit.SECONDS);

      assertEquals(32, defaultOutgoing.get(3, TimeUnit.SECONDS));
      assertEquals(2, configuredOutgoing.get(3, TimeUnit.SECONDS));
      socket.sendText("four", true).get(3, TimeUnit.SECONDS);
      assertEquals("four", accepted.get(3, TimeUnit.SECONDS));
      socket.sendText("five!", true).get(3, TimeUnit.SECONDS);
      assertEquals(1009, closed.get(3, TimeUnit.SECONDS));
    }
  }

  @Test
  @Timeout(15)
  void failsQueuedSendsToAnUnreadPeerAndReleasesTheSessionOnDisconnect() throws Exception {
    var opened = new CompletableFuture<Session>();
    var terminal = new CompletableFuture<Void>();
    var sendFailure = new CompletableFuture<Throwable>();
    var server = new AtomicReference<Server>();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var peer = new Socket()) {
      app.modifyServer(server::set);
      app.routes().websocket("/slow", (request, upgrade) -> new SlowPeerListener(opened, terminal));
      app.start();
      var container =
          server.get().getContainedBeans(ServerWebSocketContainer.class).iterator().next();
      upgradeUnreadPeer(peer, app.port(), "/slow");

      var session = opened.get(3, TimeUnit.SECONDS);
      var completed = new CountDownLatch(4);
      var payload = new byte[4 * 1024 * 1024];
      for (int index = 0; index < 4; index++) {
        session.sendBinary(
            ByteBuffer.wrap(payload),
            Callback.from(
                completed::countDown,
                failure -> {
                  sendFailure.complete(failure);
                  completed.countDown();
                }));
      }

      assertInstanceOf(WritePendingException.class, sendFailure.get(3, TimeUnit.SECONDS));
      peer.close();
      assertTrue(completed.await(5, TimeUnit.SECONDS));
      terminal.get(5, TimeUnit.SECONDS);
      assertFalse(session.isOpen());
      await()
          .pollInterval(Duration.ofMillis(20))
          .atMost(Duration.ofSeconds(1))
          .until(() -> !container.getOpenSessions().contains(session));
    }
  }

  @Test
  @Timeout(15)
  void rejectsProducerOverflowAndClearsQueuedMessagesAfterPeerDisconnect() throws Exception {
    var opened = new CompletableFuture<SerializedProducerListener>();
    var completed = new CountDownLatch(1);
    var sendFailure = new AtomicReference<Throwable>();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var peer = new Socket()) {
      app.routes()
          .websocket(
              "/producer",
              (request, upgrade) ->
                  new SerializedProducerListener(opened, completed, sendFailure, 2));
      app.start();
      upgradeUnreadPeer(peer, app.port(), "/producer");

      var listener = opened.get(3, TimeUnit.SECONDS);
      listener.submit("x".repeat(4 * 1024 * 1024));
      listener.submit("queued-one");
      listener.submit("queued-two");
      assertThrows(IllegalStateException.class, () -> listener.submit("overflow"));
      assertEquals(2, listener.pendingCount());

      peer.close();
      assertTrue(completed.await(5, TimeUnit.SECONDS));
      assertNotNull(sendFailure.get());
      assertEquals(0, listener.pendingCount());
      assertThrows(IllegalStateException.class, () -> listener.submit("after-disconnect"));
    }
  }

  @Test
  @Timeout(15)
  void serializesConcurrentProducersUntilEachNativeSendCallbackCompletes() throws Exception {
    var opened = new CompletableFuture<SerializedProducerListener>();
    var completed = new CountDownLatch(20);
    var sendFailure = new AtomicReference<Throwable>();
    var received = new LinkedBlockingQueue<String>();
    var current = new StringBuilder();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient();
        var producers = Executors.newFixedThreadPool(4)) {
      app.routes()
          .websocket(
              "/ordered",
              (request, upgrade) -> new SerializedProducerListener(opened, completed, sendFailure));
      app.start();
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  URI.create("ws://127.0.0.1:" + app.port() + "/ordered"),
                  new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(
                        WebSocket webSocket, CharSequence data, boolean last) {
                      current.append(data);
                      if (last) {
                        received.add(current.toString());
                        current.setLength(0);
                      }

                      webSocket.request(1);
                      return CompletableFuture.completedFuture(null);
                    }
                  })
              .get(3, TimeUnit.SECONDS);
      var listener = opened.get(3, TimeUnit.SECONDS);
      var begin = new CountDownLatch(1);
      var tasks = new ArrayList<Future<?>>();
      for (int producer = 0; producer < 4; producer++) {
        final int id = producer;
        tasks.add(
            producers.submit(
                () -> {
                  begin.await();
                  for (int message = 0; message < 5; message++) {
                    listener.submit(id + ":" + message);
                  }

                  return null;
                }));
      }

      begin.countDown();
      for (var task : tasks) {
        task.get(5, TimeUnit.SECONDS);
      }

      assertTrue(completed.await(5, TimeUnit.SECONDS));
      assertNull(sendFailure.get());
      var delivered = new ArrayList<String>();
      for (int index = 0; index < 20; index++) {
        delivered.add(received.poll(3, TimeUnit.SECONDS));
      }

      assertEquals(listener.accepted(), delivered);
      socket.abort();
    }
  }

  private static String sendAndReceive(HttpClient client, Shoostr app, String path)
      throws Exception {
    var received = new CompletableFuture<String>();
    var socket =
        client
            .newWebSocketBuilder()
            .buildAsync(URI.create("ws://127.0.0.1:" + app.port() + path), receiving(received))
            .get(3, TimeUnit.SECONDS);
    socket.sendText("ping", true).get(3, TimeUnit.SECONDS);
    String message = received.get(3, TimeUnit.SECONDS);
    socket.abort();
    return message;
  }

  private static void upgradeUnreadPeer(Socket peer, int port, String path) throws Exception {
    peer.setReceiveBufferSize(1024);
    peer.connect(new InetSocketAddress(InetAddress.getAllByName("127.0.0.1")[0], port));
    peer.setSoTimeout(3000);
    var handshake =
        "GET "
            + path
            + " HTTP/1.1\r\n"
            + "Host: localhost\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Version: 13\r\n"
            + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n";
    peer.getOutputStream().write(handshake.getBytes(StandardCharsets.US_ASCII));
    var reader =
        new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.US_ASCII));
    var statusLine = reader.readLine();
    assertTrue(statusLine.startsWith("HTTP/1.1 101"), statusLine);
    String header;
    boolean accepted = false;
    while ((header = reader.readLine()) != null && !header.isEmpty()) {
      accepted |= header.regionMatches(true, 0, "Sec-WebSocket-Accept:", 0, 21);
    }

    assertTrue(accepted);
  }

  private static List<String> handshake(Shoostr app, int version) throws Exception {
    try (var socket = new Socket(InetAddress.getAllByName("127.0.0.1")[0], app.port())) {
      socket.setSoTimeout(3000);
      var request =
          "GET /wire HTTP/1.1\r\n"
              + "Host: localhost\r\n"
              + "Upgrade: websocket\r\n"
              + "Connection: Upgrade\r\n"
              + "Sec-WebSocket-Version: "
              + version
              + "\r\n"
              + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n";
      socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
      var reader =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
      var lines = new ArrayList<String>();
      String line;
      while ((line = reader.readLine()) != null && !line.isEmpty()) {
        lines.add(line);
      }

      return lines;
    }
  }

  private static int failedHandshakeStatus(HttpClient client, Shoostr app, String path) {
    var failure =
        assertThrows(
            ExecutionException.class,
            () ->
                client
                    .newWebSocketBuilder()
                    .buildAsync(
                        URI.create("ws://127.0.0.1:" + app.port() + path),
                        new WebSocket.Listener() {})
                    .get(3, TimeUnit.SECONDS));
    return assertInstanceOf(WebSocketHandshakeException.class, failure.getCause())
        .getResponse()
        .statusCode();
  }

  private static WebSocket.Listener receiving(CompletableFuture<String> message) {
    return new WebSocket.Listener() {
      @Override
      public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        message.complete(data.toString());
        webSocket.request(1);
        return CompletableFuture.completedFuture(null);
      }
    };
  }

  public static final class EchoListener implements Session.Listener {
    private final String prefix;
    private final CountDownLatch completedSends;
    private final AtomicReference<Throwable> sendFailure;
    private Optional<Session> session;

    public EchoListener() {
      this("");
    }

    public EchoListener(String prefix) {
      this(prefix, new CountDownLatch(0), new AtomicReference<>());
    }

    public EchoListener(
        String prefix, CountDownLatch completedSends, AtomicReference<Throwable> sendFailure) {
      this.prefix = prefix;
      this.completedSends = completedSends;
      this.sendFailure = sendFailure;
      this.session = Optional.empty();
    }

    @Override
    public void onWebSocketOpen(Session opened) {
      session = Optional.of(opened);
      opened.demand();
    }

    @Override
    public void onWebSocketText(String message) {
      session
          .orElseThrow()
          .sendText(
              prefix + message,
              Callback.from(
                  () -> {
                    completedSends.countDown();
                    session.orElseThrow().demand();
                  },
                  failure -> {
                    sendFailure.set(failure);
                    completedSends.countDown();
                  }));
    }
  }

  public static final class BinaryEchoListener implements Session.Listener.AutoDemanding {
    private Optional<Session> session;

    public BinaryEchoListener() {
      this.session = Optional.empty();
    }

    @Override
    public void onWebSocketOpen(Session opened) {
      session = Optional.of(opened);
    }

    @Override
    public void onWebSocketBinary(ByteBuffer data, Callback callback) {
      session.orElseThrow().sendBinary(data, Callback.from(callback::succeed, callback::fail));
    }
  }

  public static final class LimitedListener implements Session.Listener {
    private final CompletableFuture<Integer> defaultOutgoing;
    private final CompletableFuture<Integer> configuredOutgoing;
    private final CompletableFuture<String> accepted;
    private Optional<Session> session;

    public LimitedListener(
        CompletableFuture<Integer> defaultOutgoing,
        CompletableFuture<Integer> configuredOutgoing,
        CompletableFuture<String> accepted) {
      this.defaultOutgoing = defaultOutgoing;
      this.configuredOutgoing = configuredOutgoing;
      this.accepted = accepted;
      this.session = Optional.empty();
    }

    @Override
    public void onWebSocketOpen(Session opened) {
      session = Optional.of(opened);
      defaultOutgoing.complete(opened.getMaxOutgoingFrames());
      opened.setMaxOutgoingFrames(2);
      opened.setMaxTextMessageSize(4);
      configuredOutgoing.complete(opened.getMaxOutgoingFrames());
      opened.demand();
    }

    @Override
    public void onWebSocketText(String message) {
      accepted.complete(message);
      session.orElseThrow().demand();
    }
  }

  public static final class SlowPeerListener implements Session.Listener {
    private final CompletableFuture<Session> opened;
    private final CompletableFuture<Void> terminal;

    public SlowPeerListener(CompletableFuture<Session> opened, CompletableFuture<Void> terminal) {
      this.opened = opened;
      this.terminal = terminal;
    }

    @Override
    public void onWebSocketOpen(Session session) {
      session.setMaxOutgoingFrames(1);
      opened.complete(session);
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason, Callback callback) {
      terminal.complete(null);
      callback.succeed();
    }
  }

  public static final class SerializedProducerListener implements Session.Listener {
    private final CompletableFuture<SerializedProducerListener> opened;
    private final CountDownLatch completed;
    private final AtomicReference<Throwable> sendFailure;
    private final ArrayDeque<String> pending;
    private final List<String> accepted;
    private final int maxPending;
    private Optional<Session> session;
    private boolean sending;
    private boolean terminal;

    public SerializedProducerListener(
        CompletableFuture<SerializedProducerListener> opened,
        CountDownLatch completed,
        AtomicReference<Throwable> sendFailure) {
      this(opened, completed, sendFailure, 32);
    }

    public SerializedProducerListener(
        CompletableFuture<SerializedProducerListener> opened,
        CountDownLatch completed,
        AtomicReference<Throwable> sendFailure,
        int maxPending) {
      this.opened = opened;
      this.completed = completed;
      this.sendFailure = sendFailure;
      this.pending = new ArrayDeque<>();
      this.accepted = new ArrayList<>();
      this.maxPending = maxPending;
      this.session = Optional.empty();
    }

    @Override
    public void onWebSocketOpen(Session connection) {
      session = Optional.of(connection);
      connection.demand();
      opened.complete(this);
    }

    public void submit(String message) {
      boolean start;
      synchronized (this) {
        if (terminal || pending.size() >= maxPending) {
          throw new IllegalStateException("WebSocket producer cannot accept more messages");
        }

        accepted.add(message);
        pending.addLast(message);
        start = !sending;
        sending = true;
      }

      if (start) {
        sendNext();
      }
    }

    public synchronized List<String> accepted() {
      return List.copyOf(accepted);
    }

    public synchronized int pendingCount() {
      return pending.size();
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason, Callback callback) {
      stop();
      callback.succeed();
    }

    @Override
    public void onWebSocketError(Throwable failure) {
      stop();
    }

    private synchronized void stop() {
      terminal = true;
      pending.clear();
      sending = false;
    }

    private void sendNext() {
      String next;
      synchronized (this) {
        next = pending.pollFirst();
        if (next == null) {
          sending = false;
          return;
        }
      }

      session
          .orElseThrow()
          .sendText(
              next,
              Callback.from(
                  () -> {
                    completed.countDown();
                    sendNext();
                  },
                  failure -> {
                    sendFailure.set(failure);
                    stop();
                    completed.countDown();
                    session.orElseThrow().disconnect();
                  }));
    }
  }
}
