package io.github.suppierk.shoostr;

import static io.github.suppierk.shoostr.Locks.getUnderLock;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.suppierk.shoostr.http.exceptions.AuthenticationRequiredException;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandler.ContextScopeListener;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.component.LifeCycle.Listener;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketSessionListener;
import org.eclipse.jetty.websocket.api.exceptions.MessageTooLargeException;
import org.eclipse.jetty.websocket.server.ServerWebSocketContainer;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ManagedWebSocketTest {
  private static void ignoreInterruptsUntilReleased(CountDownLatch release) {
    boolean interrupted = false;
    while (true) {
      try {
        release.await();
        break;
      } catch (InterruptedException ignoredFailure) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  void rejectsDuplicateRoutesBeforeInvokingTheirConfiguration() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close) {
        var routes = app.routes();
        routes.websocket("/echo", events -> events.onText(WebSocketConnection::send));

        assertThrows(
            IllegalArgumentException.class,
            () ->
                routes.websocket("/echo", ignored -> fail("Duplicate configuration must not run")));
      }
    }
  }

  @Test
  @Timeout(10)
  void echoesTextThroughAnOrdinaryCallback() throws Exception {
    var app =
        new Shoostr()
            .routes(routes ->
                routes.websocket("/echo", events -> events.onText(WebSocketConnection::send)));

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var received = new CompletableFuture<String>();
      var socket = createSocketForEchoesTextThroughAnOrdinaryCallback(client, test, received);

      try {
        socket.sendText("hello", true).get(3, TimeUnit.SECONDS);

        assertEquals("hello", received.get(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  private WebSocket createSocketForEchoesTextThroughAnOrdinaryCallback(
      HttpClient client, TestServer test, CompletableFuture<String> received)
      throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
    return client
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
  }

  @Test
  @Timeout(20)
  void servesHttpAndMessagesWithMoreIdleWebSocketsThanPlatformWorkers() throws Exception {
    var app = createAppForServesHttpAndMessagesWithMoreIdleWebSocketsThanPlatformWorkers();

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var sockets = new ArrayList<WebSocket>();
      var messages = new ArrayList<Messages>();

      try {
        for (int index = 0; index < 64; index++) {
          var received = new Messages();
          var socket =
              createSocketForServesHttpAndMessagesWithMoreIdleWebSocketsThanPlatformWorkers(
                  client, test, received);
          sockets.add(socket);
          messages.add(received);

          assertEquals("platform", received.text.poll(3, TimeUnit.SECONDS));
        }

        var response = test.send(request -> request.path("/probe").timeout(Duration.ofSeconds(3)));

        assertEquals(200, response.statusCode());

        sockets.get(63).sendText("still available", true).get(3, TimeUnit.SECONDS);

        assertEquals("still available", messages.get(63).text.poll(3, TimeUnit.SECONDS));
      } finally {
        sockets.forEach(WebSocket::abort);
      }
    }
  }

  private Shoostr createAppForServesHttpAndMessagesWithMoreIdleWebSocketsThanPlatformWorkers() {
    return new Shoostr()
        .modifyServer(server ->
            assertInstanceOf(QueuedThreadPool.class, server.getThreadPool()).setMaxThreads(32))
        .routes(routes -> {
          routes.get("/probe", (request, response) -> response.text("available"));
          routes.websocket(
              "/idle",
              events -> {
                events.onOpen(connection ->
                    connection.send(Thread.currentThread().isVirtual() ? "virtual" : "platform"));
                events.onText(WebSocketConnection::send);
              });
        });
  }

  private WebSocket createSocketForServesHttpAndMessagesWithMoreIdleWebSocketsThanPlatformWorkers(
      HttpClient client, TestServer test, Messages received)
      throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
    return client
        .newWebSocketBuilder()
        .buildAsync(new URI("ws", test.baseUri().getRawAuthority(), "/idle", null, null), received)
        .get(3, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(10)
  void rejectsInvalidInputBeforeTheWebSocketUpgrade() throws Exception {
    var app = createAppForRejectsInvalidInputBeforeTheWebSocketUpgrade();

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var failure = assertFailureForRejectsInvalidInputBeforeTheWebSocketUpgrade(client, test);

      var handshake = assertInstanceOf(WebSocketHandshakeException.class, failure.getCause());
      assertEquals(400, handshake.getResponse().statusCode());
    }
  }

  private Shoostr createAppForRejectsInvalidInputBeforeTheWebSocketUpgrade() {
    return new Shoostr()
        .routes(routes ->
            routes.websocket(
                "/rooms/{room}",
                events -> {
                  events.beforeUpgrade((request, ignoredResponse) -> {
                    if (request.pathParam("room").orElseThrow().length() > 32) {
                      throw new BadRequestException("Invalid room");
                    }
                  });
                  events.onText(WebSocketConnection::send);
                }));
  }

  private ExecutionException assertFailureForRejectsInvalidInputBeforeTheWebSocketUpgrade(
      HttpClient client, TestServer test) {
    return assertThrows(
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
  }

  @Test
  @Timeout(10)
  void suppliesDecodedPathParametersAfterUpgrade() throws Exception {
    var app = createAppForSuppliesDecodedPathParametersAfterUpgrade();

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var received = new CompletableFuture<String>();
      var socket = createSocketForSuppliesDecodedPathParametersAfterUpgrade(client, test, received);

      try {
        socket.sendText("hello", true).get(3, TimeUnit.SECONDS);

        assertEquals("team one: hello", received.get(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  private Shoostr createAppForSuppliesDecodedPathParametersAfterUpgrade() {
    return new Shoostr()
        .routes(routes ->
            routes.websocket(
                "/rooms/{room}",
                events ->
                    events.onText((connection, text) ->
                        connection.send(
                            connection.pathParam("room").orElseThrow() + ": " + text))));
  }

  private WebSocket createSocketForSuppliesDecodedPathParametersAfterUpgrade(
      HttpClient client, TestServer test, CompletableFuture<String> received)
      throws InterruptedException, ExecutionException, TimeoutException {
    return client
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
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @Timeout(10)
  void runsTheOpeningCallbackOnTheConfiguredThreadKind(boolean virtual) throws Exception {
    var configuredOptions = new Options();
    configuredOptions.setVirtualThreads(virtual);

    var app = new Shoostr(configuredOptions);

    app.routes(routes ->
        routes.websocket(
            "/open",
            events ->
                events.onOpen(connection ->
                    connection.send(Thread.currentThread().isVirtual() ? "virtual" : "platform"))));

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var received = new Messages();
      var socket =
          createSocketForRunsTheOpeningCallbackOnTheConfiguredThreadKind(client, test, received);

      try {
        assertEquals(virtual ? "virtual" : "platform", received.text.poll(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  private WebSocket createSocketForRunsTheOpeningCallbackOnTheConfiguredThreadKind(
      HttpClient client, TestServer test, Messages received)
      throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
    return client
        .newWebSocketBuilder()
        .buildAsync(new URI("ws", test.baseUri().getRawAuthority(), "/open", null, null), received)
        .get(3, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(10)
  void echoesOwnedBytesFromCompleteFragmentedBinaryMessage() throws Exception {
    var server = new AtomicReference<Server>();
    var app = createAppForEchoesOwnedBytesFromCompleteFragmentedBinaryMessage(server);

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var received = new Messages();
      var socket =
          createSocketForEchoesOwnedBytesFromCompleteFragmentedBinaryMessage(
              client, test, received);

      try {
        socket.sendBinary(ByteBuffer.wrap(new byte[] {0, 1}), false).get(3, TimeUnit.SECONDS);
        socket.sendBinary(ByteBuffer.wrap(new byte[] {-1, 127}), true).get(3, TimeUnit.SECONDS);

        assertArrayEquals(new byte[] {0, 1, -1, 127}, received.binary.poll(3, TimeUnit.SECONDS));

        socket.sendClose(1000, "done").get(3, TimeUnit.SECONDS);

        assertEquals(1000, received.closeCode.get(3, TimeUnit.SECONDS));
        var container =
            server.get().getContainedBeans(ServerWebSocketContainer.class).iterator().next();
        await().atMost(Duration.ofSeconds(3)).until(() -> container.getOpenSessions().isEmpty());
        var buffers = assertInstanceOf(ArrayByteBufferPool.class, server.get().getByteBufferPool());

        assertTrue(buffers.getHeapMemory() > 0);
        await()
            .atMost(Duration.ofSeconds(3))
            .untilAsserted(() -> {
              assertEquals(0, buffers.getReserved());
              assertEquals(buffers.getHeapMemory(), buffers.getAvailableHeapMemory());
              assertEquals(buffers.getDirectMemory(), buffers.getAvailableDirectMemory());
            });
      } finally {
        socket.abort();
      }
    }
  }

  private Shoostr createAppForEchoesOwnedBytesFromCompleteFragmentedBinaryMessage(
      AtomicReference<Server> server) {
    return new Shoostr()
        .modifyServer(server::set)
        .routes(routes ->
            routes.websocket("/binary", events -> events.onBinary(WebSocketConnection::send)));
  }

  private WebSocket createSocketForEchoesOwnedBytesFromCompleteFragmentedBinaryMessage(
      HttpClient client, TestServer test, Messages received)
      throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
    return client
        .newWebSocketBuilder()
        .buildAsync(
            new URI("ws", test.baseUri().getRawAuthority(), "/binary", null, null), received)
        .get(3, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(10)
  void completesNormalServerInitiatedClose() throws Exception {
    var app = createAppForCompletesNormalServerInitiatedClose();

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var received = new Messages();
      var socket = createSocketForCompletesNormalServerInitiatedClose(client, test, received);

      try {
        socket.sendText("finish", true).get(3, TimeUnit.SECONDS);

        assertEquals(1000, received.closeCode.get(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  private Shoostr createAppForCompletesNormalServerInitiatedClose() {
    return new Shoostr()
        .routes(routes ->
            routes.websocket(
                "/close",
                events -> events.onText((connection, ignoredString) -> connection.close())));
  }

  private WebSocket createSocketForCompletesNormalServerInitiatedClose(
      HttpClient client, TestServer test, Messages received)
      throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
    return client
        .newWebSocketBuilder()
        .buildAsync(new URI("ws", test.baseUri().getRawAuthority(), "/close", null, null), received)
        .get(3, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(10)
  void reportsThePeersCloseCodeAndReasonToTheCloseCallback() throws Exception {
    var observed = new CompletableFuture<String>();
    var app = createAppForReportsThePeersCloseCodeAndReasonToTheCloseCallback(observed);

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var received = new Messages();
      var socket =
          createSocketForReportsThePeersCloseCodeAndReasonToTheCloseCallback(
              client, test, received);

      try {
        socket.sendClose(1000, "Finished").get(3, TimeUnit.SECONDS);

        assertEquals("1000:Finished", observed.get(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  private Shoostr createAppForReportsThePeersCloseCodeAndReasonToTheCloseCallback(
      CompletableFuture<String> observed) {
    return new Shoostr()
        .routes(routes ->
            routes.websocket(
                "/close",
                events ->
                    events.onClose((ignoredWebSocketConnection, code, reason) ->
                        observed.complete(code + ":" + reason))));
  }

  private WebSocket createSocketForReportsThePeersCloseCodeAndReasonToTheCloseCallback(
      HttpClient client, TestServer test, Messages received)
      throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
    return client
        .newWebSocketBuilder()
        .buildAsync(new URI("ws", test.baseUri().getRawAuthority(), "/close", null, null), received)
        .get(3, TimeUnit.SECONDS);
  }

  @RepeatedTest(20)
  @Timeout(10)
  void acknowledgesNormalPeerClose() throws Exception {
    var ready = new CompletableFuture<Void>();
    var app = createAppForAcknowledgesNormalPeerClose(ready);

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var received = new Messages();
      var socket = createSocketForAcknowledgesNormalPeerClose(client, test, received);

      try {
        ready.get(3, TimeUnit.SECONDS);
        socket.sendClose(1000, "Finished").get(3, TimeUnit.SECONDS);

        assertEquals(1000, received.closeCode.get(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  private Shoostr createAppForAcknowledgesNormalPeerClose(CompletableFuture<Void> ready) {
    return new Shoostr()
        .routes(routes ->
            routes.websocket(
                "/close",
                events -> events.onOpen(ignoredWebSocketConnection -> ready.complete(null))));
  }

  private WebSocket createSocketForAcknowledgesNormalPeerClose(
      HttpClient client, TestServer test, Messages received)
      throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
    return client
        .newWebSocketBuilder()
        .buildAsync(new URI("ws", test.baseUri().getRawAuthority(), "/close", null, null), received)
        .get(3, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(10)
  void reportsAnEmptyNonNullReasonWhenThePeerClosesWithoutReason() throws Exception {
    var observed = new CompletableFuture<String>();
    var app = createAppForReportsAnEmptyNonNullReasonWhenThePeerClosesWithoutReason(observed);

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var socket =
          createSocketForReportsAnEmptyNonNullReasonWhenThePeerClosesWithoutReason(client, test);

      try {
        socket.sendClose(1000, "").get(3, TimeUnit.SECONDS);

        assertEquals("1000:", observed.get(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  private Shoostr createAppForReportsAnEmptyNonNullReasonWhenThePeerClosesWithoutReason(
      CompletableFuture<String> observed) {
    return new Shoostr()
        .routes(routes ->
            routes.websocket(
                "/close",
                events ->
                    events.onClose((ignoredWebSocketConnection, code, reason) ->
                        observed.complete(code + ":" + reason))));
  }

  private WebSocket createSocketForReportsAnEmptyNonNullReasonWhenThePeerClosesWithoutReason(
      HttpClient client, TestServer test)
      throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
    return client
        .newWebSocketBuilder()
        .buildAsync(
            new URI("ws", test.baseUri().getRawAuthority(), "/close", null, null), new Messages())
        .get(3, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(10)
  void reportsAnUnexpectedCheckedFailureToTheErrorCallback() throws Exception {
    var observed = new CompletableFuture<String>();
    var app = createAppForReportsAnUnexpectedCheckedFailureToTheErrorCallback(observed);

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var received = new Messages();
      var socket =
          createSocketForReportsAnUnexpectedCheckedFailureToTheErrorCallback(
              client, test, received);

      try {
        socket.sendText("fail", true).get(3, TimeUnit.SECONDS);

        assertEquals("Fixture failure", observed.get(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  private Shoostr createAppForReportsAnUnexpectedCheckedFailureToTheErrorCallback(
      CompletableFuture<String> observed) {
    return new Shoostr()
        .routes(routes ->
            routes.websocket(
                "/failure",
                events -> {
                  events.onText((ignoredWebSocketConnection, ignoredString) -> {
                    throw new IOException("Fixture failure");
                  });
                  events.onError((ignoredWebSocketConnection, failure) ->
                      observed.complete(failure.getMessage()));
                }));
  }

  private WebSocket createSocketForReportsAnUnexpectedCheckedFailureToTheErrorCallback(
      HttpClient client, TestServer test, Messages received)
      throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
    return client
        .newWebSocketBuilder()
        .buildAsync(
            new URI("ws", test.baseUri().getRawAuthority(), "/failure", null, null), received)
        .get(3, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(10)
  void runsHandshakeAdmissionOnTheSuppliedExecutor() throws Exception {
    try (var executor =
        Executors.newSingleThreadExecutor(
            task -> Thread.ofPlatform().name("ws-handshake").unstarted(task))) {
      var app = createAppForRunsHandshakeAdmissionOnTheSuppliedExecutor(executor);

      try (var test = TestServer.start(app);
          var client = HttpClient.newHttpClient()) {
        var received = new Messages();
        var socket =
            createSocketForRunsHandshakeAdmissionOnTheSuppliedExecutor(client, test, received);

        try {
          socket.sendText("who", true).get(3, TimeUnit.SECONDS);

          assertEquals("ws-handshake", received.text.poll(3, TimeUnit.SECONDS));
        } finally {
          socket.abort();
        }
      }
    }
  }

  private Shoostr createAppForRunsHandshakeAdmissionOnTheSuppliedExecutor(
      ExecutorService executor) {
    return new Shoostr()
        .routes(routes ->
            routes.websocket(
                "/executor",
                executor,
                events -> {
                  events.beforeUpgrade((request, ignoredResponse) -> {
                    var name = Thread.currentThread().getName();
                    request.principal(() -> name);
                  });
                  events.onText((connection, ignoredString) ->
                      connection.send(connection.principal().orElseThrow().getName()));
                }));
  }

  private WebSocket createSocketForRunsHandshakeAdmissionOnTheSuppliedExecutor(
      HttpClient client, TestServer test, Messages received)
      throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
    return client
        .newWebSocketBuilder()
        .buildAsync(
            new URI("ws", test.baseUri().getRawAuthority(), "/executor", null, null), received)
        .get(3, TimeUnit.SECONDS);
  }

  @Test
  void freezesConfigurationAfterRegistration() throws Exception {
    var captured = new AtomicReference<io.github.suppierk.shoostr.WebSocket>();

    {
      var app = new Shoostr();

      try (Closeable appCleanup1 = app::close) {
        app.routes()
            .websocket(
                "/echo",
                events -> {
                  assertSame(events, events.onBinary(WebSocketConnection::send));
                  assertSame(events, events.onError((connection, failure) -> {}));
                  assertSame(events, events.onClose((connection, code, reason) -> {}));
                  captured.set(events);
                });
        var configuration = captured.get();

        assertNotNull(configuration);
        assertThrows(
            IllegalStateException.class, () -> configuration.onText(WebSocketConnection::send));
      }
    }
  }

  @Test
  void releasesReservedRouteWhenConfigurationFails() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup2 = app::close) {
        var routes = app.routes();

        assertThrows(
            IllegalArgumentException.class,
            () ->
                routes.websocket(
                    "/retry",
                    ignoredWebSocket -> {
                      throw new IllegalArgumentException("Fixture failure");
                    }));

        app.routes().websocket("/retry", events -> events.onText(WebSocketConnection::send));
      }
    }
  }

  @Test
  void preventsStartupWhileWebSocketConfigurationIsRunning() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup3 = app::close) {
        app.routes()
            .websocket(
                "/echo", ignoredWebSocket -> assertThrows(IllegalStateException.class, app::start));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @Timeout(10)
  void separatesTheHandshakeExecutorFromConfiguredMessageThreads(boolean virtual) throws Exception {
    try (var executor = new ForkJoinPool(1)) {
      var configuredOptions = new Options();
      configuredOptions.setVirtualThreads(virtual);

      var app = new Shoostr(configuredOptions);

      app.routes(routes ->
          routes.path(
              "/executor",
              group ->
                  group.websocket(
                      executor,
                      events ->
                          events.onText((connection, ignoredString) ->
                              connection.send(
                                  Thread.currentThread().isVirtual() ? "virtual" : "platform")))));

      try (var test = TestServer.start(app);
          var client = HttpClient.newHttpClient()) {
        var received = new Messages();
        var socket =
            createSocketForSeparatesTheHandshakeExecutorFromConfiguredMessageThreads(
                client, test, received);

        try {
          socket.sendText("thread", true).get(3, TimeUnit.SECONDS);

          assertEquals(virtual ? "virtual" : "platform", received.text.poll(3, TimeUnit.SECONDS));
        } finally {
          socket.abort();
        }
      }

      assertFalse(executor.isShutdown());
    }
  }

  private WebSocket createSocketForSeparatesTheHandshakeExecutorFromConfiguredMessageThreads(
      HttpClient client, TestServer test, Messages received)
      throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
    return client
        .newWebSocketBuilder()
        .buildAsync(
            new URI("ws", test.baseUri().getRawAuthority(), "/executor", null, null), received)
        .get(3, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(10)
  void rejectsAnUnavailableManagedEndpointWith404() throws Exception {
    var app = createAppForRejectsAnUnavailableManagedEndpointWith404();

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var failure = assertFailureForRejectsAnUnavailableManagedEndpointWith404(client, test);

      assertEquals(
          404,
          assertInstanceOf(WebSocketHandshakeException.class, failure.getCause())
              .getResponse()
              .statusCode());
    }
  }

  private Shoostr createAppForRejectsAnUnavailableManagedEndpointWith404() {
    return new Shoostr()
        .routes(routes ->
            routes.when(
                () -> false,
                unavailable -> unavailable.websocket("/hidden", ignoredWebSocket -> {})));
  }

  private ExecutionException assertFailureForRejectsAnUnavailableManagedEndpointWith404(
      HttpClient client, TestServer test) {
    return assertThrows(
        ExecutionException.class,
        () ->
            client
                .newWebSocketBuilder()
                .buildAsync(
                    new URI("ws", test.baseUri().getRawAuthority(), "/hidden", null, null),
                    new Messages())
                .get(3, TimeUnit.SECONDS));
  }

  @Test
  @Timeout(10)
  void requiresTheSelectedAuthenticatorBeforeUpgrade() throws Exception {
    var authentication = new FixtureAuthentication();
    var app = createAppForRequiresTheSelectedAuthenticatorBeforeUpgrade(authentication);

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var failure = assertFailureForRequiresTheSelectedAuthenticatorBeforeUpgrade(client, test);

      assertEquals(
          401,
          assertInstanceOf(WebSocketHandshakeException.class, failure.getCause())
              .getResponse()
              .statusCode());
    }
  }

  private Shoostr createAppForRequiresTheSelectedAuthenticatorBeforeUpgrade(
      FixtureAuthentication authentication) {
    return new Shoostr()
        .routes(routes ->
            routes.websocket(
                "/secure",
                ignoredWebSocket -> {},
                extensions -> extensions.withExtension(authentication)));
  }

  private ExecutionException assertFailureForRequiresTheSelectedAuthenticatorBeforeUpgrade(
      HttpClient client, TestServer test) {
    return assertThrows(
        ExecutionException.class,
        () ->
            client
                .newWebSocketBuilder()
                .buildAsync(
                    new URI("ws", test.baseUri().getRawAuthority(), "/secure", null, null),
                    new Messages())
                .get(3, TimeUnit.SECONDS));
  }

  @Test
  @Timeout(10)
  void retainsTheAuthenticatedIdentityAfterTheHandshake() throws Exception {
    var authentication = new FixtureAuthentication();
    var app = createAppForRetainsTheAuthenticatedIdentityAfterTheHandshake(authentication);

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var received = new Messages();
      var socket =
          createSocketForRetainsTheAuthenticatedIdentityAfterTheHandshake(client, test, received);

      try {
        socket.sendText("who", true).get(3, TimeUnit.SECONDS);

        assertEquals("alice", received.text.poll(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  private Shoostr createAppForRetainsTheAuthenticatedIdentityAfterTheHandshake(
      FixtureAuthentication authentication) {
    return new Shoostr()
        .routes(routes ->
            routes.path(
                "/secure",
                group ->
                    group.websocket(
                        events ->
                            events.onText((connection, ignoredString) ->
                                connection.send(connection.principal().orElseThrow().getName())),
                        extensions -> extensions.withExtension(authentication))));
  }

  private WebSocket createSocketForRetainsTheAuthenticatedIdentityAfterTheHandshake(
      HttpClient client, TestServer test, Messages received)
      throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
    return client
        .newWebSocketBuilder()
        .header("X-Identity", "alice")
        .buildAsync(
            new URI("ws", test.baseUri().getRawAuthority(), "/secure", null, null), received)
        .get(3, TimeUnit.SECONDS);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @Timeout(10)
  void rejectsInvalidAdmissionOutputBeforeRunningAfterHooks(boolean stagedBody) throws Exception {
    var afterCalls = new AtomicInteger();
    var app = invalidAdmissionOutputApplication(stagedBody, afterCalls);

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var failure = assertFailureForRejectsStagedOutputFromBeforeUpgradeAsServerError(client, test);

      assertEquals(
          500,
          assertInstanceOf(WebSocketHandshakeException.class, failure.getCause())
              .getResponse()
              .statusCode());
      assertEquals(0, afterCalls.get());
    }
  }

  private Shoostr invalidAdmissionOutputApplication(boolean stagedBody, AtomicInteger afterCalls) {
    return new Shoostr()
        .afterRouteHandler((request, response) -> {
          afterCalls.incrementAndGet();
          response.status(200);
        })
        .routes(routes ->
            routes.websocket(
                "/invalid",
                events ->
                    events.beforeUpgrade((ignoredRequest, response) -> {
                      response.status(403);
                      if (stagedBody) {
                        response.text("Not a rejection");
                      }
                    })));
  }

  private ExecutionException assertFailureForRejectsStagedOutputFromBeforeUpgradeAsServerError(
      HttpClient client, TestServer test) {
    return assertThrows(
        ExecutionException.class,
        () ->
            client
                .newWebSocketBuilder()
                .buildAsync(
                    new URI("ws", test.baseUri().getRawAuthority(), "/invalid", null, null),
                    new Messages())
                .get(3, TimeUnit.SECONDS));
  }

  @Test
  @Timeout(10)
  void rejectsStreamingDuringAdmissionAndAllowsValidUpgrade() throws Exception {
    var gated = new CompletableFuture<Boolean>();
    var app = streamingAdmissionApplication(gated);

    try (Closeable appCleanup = app::close;
        var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var received = new Messages();
      var socket =
          createSocketForValidatesCloseArgumentsBeforeChangingConnectionState(
              client, test, received);

      try {
        assertTrue(gated.get(3, TimeUnit.SECONDS));

        socket.sendText("usable", true).get(3, TimeUnit.SECONDS);

        assertEquals("usable", received.text.poll(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  private Shoostr streamingAdmissionApplication(CompletableFuture<Boolean> gated) {
    return new Shoostr()
        .routes(routes ->
            routes.websocket(
                "/validation",
                events -> {
                  events.beforeUpgrade((request, response) -> {
                    try {
                      response.startStream("text/plain");
                      gated.complete(false);
                    } catch (IllegalStateException expected) {
                      gated.complete(true);
                    }
                  });
                  events.onText(WebSocketConnection::send);
                }));
  }

  @Test
  @Timeout(10)
  void releasesTheAdmissionGateBeforeStreamingTheMappedFailure() throws Exception {
    var rendered = new CompletableFuture<Boolean>();
    var app = streamingAdmissionFailureApplication(rendered);

    try (Closeable appCleanup = app::close;
        var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var failure = assertFailureForRejectsStagedOutputFromBeforeUpgradeAsServerError(client, test);

      assertEquals(
          422,
          assertInstanceOf(WebSocketHandshakeException.class, failure.getCause())
              .getResponse()
              .statusCode());
      assertTrue(rendered.get(3, TimeUnit.SECONDS));
    }
  }

  private Shoostr streamingAdmissionFailureApplication(CompletableFuture<Boolean> rendered) {
    return new Shoostr()
        .exception(
            IOException.class,
            (failure, request, response) -> {
              response.status(422);

              response.startStream("text/plain").write("Admission rejected").flush();

              rendered.complete(true);
            })
        .routes(routes ->
            routes.websocket(
                "/invalid",
                events ->
                    events.beforeUpgrade((request, response) -> {
                      throw new IOException("Admission rejected");
                    })));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @Timeout(10)
  void serializesRapidMessagesUntilThePreviousCallbackReturns(boolean virtual) throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var configuredOptions = new Options();
    configuredOptions.setVirtualThreads(virtual);

    var app = new Shoostr(configuredOptions);

    app.routes(routes ->
        routes.websocket(
            "/ordered",
            events ->
                events.onText((connection, text) -> {
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
          createSocketForSerializesRapidMessagesUntilThePreviousCallbackReturns(
              client, test, received);

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

  private WebSocket createSocketForSerializesRapidMessagesUntilThePreviousCallbackReturns(
      HttpClient client, TestServer test, Messages received)
      throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
    return client
        .newWebSocketBuilder()
        .buildAsync(
            new URI("ws", test.baseUri().getRawAuthority(), "/ordered", null, null), received)
        .get(3, TimeUnit.SECONDS);
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
          createSocketForAllowsConcurrentProducersToRetainAndSendThroughTheConnection(
              client, test, received);

      try {
        var connection = opened.get(3, TimeUnit.SECONDS);
        var first =
            submitFirstForAllowsConcurrentProducersToRetainAndSendThroughTheConnection(
                producers, connection);
        var second =
            submitSecondForAllowsConcurrentProducersToRetainAndSendThroughTheConnection(
                producers, connection);
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

  private WebSocket createSocketForAllowsConcurrentProducersToRetainAndSendThroughTheConnection(
      HttpClient client, TestServer test, Messages received)
      throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
    return client
        .newWebSocketBuilder()
        .buildAsync(
            new URI("ws", test.baseUri().getRawAuthority(), "/producer", null, null), received)
        .get(3, TimeUnit.SECONDS);
  }

  private Future<Object> submitFirstForAllowsConcurrentProducersToRetainAndSendThroughTheConnection(
      ExecutorService producers, WebSocketConnection connection) {
    return producers.submit(() -> {
      connection.send("alpha");

      return null;
    });
  }

  private Future<Object>
      submitSecondForAllowsConcurrentProducersToRetainAndSendThroughTheConnection(
          ExecutorService producers, WebSocketConnection connection) {
    return producers.submit(() -> {
      connection.send("beta");

      return null;
    });
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
      var socket = createSocketForRejectsWritesAfterPublicClose(client, test);

      try {
        var connection = opened.get(3, TimeUnit.SECONDS);
        connection.close();

        assertThrows(IOException.class, () -> connection.send("late"));
      } finally {
        socket.abort();
      }
    }
  }

  private WebSocket createSocketForRejectsWritesAfterPublicClose(HttpClient client, TestServer test)
      throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
    return client
        .newWebSocketBuilder()
        .buildAsync(
            new URI("ws", test.baseUri().getRawAuthority(), "/close", null, null), new Messages())
        .get(3, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(10)
  void preservesTheNativeMessageTooLargeCloseCode() throws Exception {
    var server = new AtomicReference<Server>();
    var observed = new LinkedBlockingQueue<Object>();
    var messages = new AtomicInteger();
    var app = messageLimitApplication(server, observed, messages);

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
          createSocketForPreservesTheNativeMessageTooLargeCloseCode(client, test, received);

      try {
        socket.sendText("x".repeat(17), true).get(3, TimeUnit.SECONDS);

        assertEquals(1009, received.closeCode.get(3, TimeUnit.SECONDS));
        assertInstanceOf(MessageTooLargeException.class, observed.poll(3, TimeUnit.SECONDS));
        assertEquals(1009, observed.poll(3, TimeUnit.SECONDS));
        assertTrue(observed.isEmpty());
        assertEquals(0, messages.get());
      } finally {
        socket.abort();
      }
    }
  }

  private Shoostr messageLimitApplication(
      AtomicReference<Server> server,
      LinkedBlockingQueue<Object> observed,
      AtomicInteger messages) {
    return new Shoostr()
        .routes(routes ->
            routes.websocket(
                "/limit",
                events -> {
                  events.onText((connection, text) -> messages.incrementAndGet());
                  events.onError((connection, failure) -> observed.add(failure));
                  events.onClose((connection, code, reason) -> observed.add(code));
                }))
        .modifyServer(server::set);
  }

  private WebSocket createSocketForPreservesTheNativeMessageTooLargeCloseCode(
      HttpClient client, TestServer test, Messages received)
      throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
    return client
        .newWebSocketBuilder()
        .buildAsync(new URI("ws", test.baseUri().getRawAuthority(), "/limit", null, null), received)
        .get(3, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(10)
  void interruptsActiveManagedWorkWhenTheAppCloses() throws Exception {
    var entered = new CountDownLatch(1);
    var canceled = new CompletableFuture<String>();
    var app = createAppForInterruptsActiveManagedWorkWhenTheAppCloses(entered, canceled);

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var socket = createSocketForInterruptsActiveManagedWorkWhenTheAppCloses(client, test);

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

  private Shoostr createAppForInterruptsActiveManagedWorkWhenTheAppCloses(
      CountDownLatch entered, CompletableFuture<String> canceled) {
    return new Shoostr()
        .routes(routes ->
            routes.websocket(
                "/work",
                events ->
                    events.onText((ignoredWebSocketConnection, ignoredString) -> {
                      entered.countDown();

                      try {
                        new CountDownLatch(1).await();
                      } catch (InterruptedException failure) {
                        canceled.complete("interrupted");
                        throw failure;
                      }
                    })));
  }

  private WebSocket createSocketForInterruptsActiveManagedWorkWhenTheAppCloses(
      HttpClient client, TestServer test)
      throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
    return client
        .newWebSocketBuilder()
        .buildAsync(
            new URI("ws", test.baseUri().getRawAuthority(), "/work", null, null), new Messages())
        .get(3, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(20)
  void observesAnIdleConnectionsCloseWhileNativeShutdownStillAllowsDispatch() throws Exception {
    var closed = new CompletableFuture<Integer>();
    var observedDuringNativeStop = new CompletableFuture<Void>();
    var app =
        createAppForObservesAnIdleConnectionsCloseWhileNativeShutdownStillAllowsDispatch(
            closed, observedDuringNativeStop);

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var received = new Messages();
      var socket =
          createSocketForObservesAnIdleConnectionsCloseWhileNativeShutdownStillAllowsDispatch(
              client, test, received);

      try {
        assertEquals("ready", received.text.poll(3, TimeUnit.SECONDS));

        test.close();
        observedDuringNativeStop.get(1, TimeUnit.SECONDS);

        assertEquals(1001, closed.get(1, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  private Shoostr createAppForObservesAnIdleConnectionsCloseWhileNativeShutdownStillAllowsDispatch(
      CompletableFuture<Integer> closed, CompletableFuture<Void> observedDuringNativeStop) {
    return new Shoostr()
        .modifyServer(server ->
            server.addEventListener(
                new LifeCycle.Listener() {
                  @Override
                  public void lifeCycleStopping(LifeCycle event) {
                    try {
                      closed.get(5, TimeUnit.SECONDS);
                      observedDuringNativeStop.complete(null);
                    } catch (InterruptedException failure) {
                      Thread.currentThread().interrupt();
                      observedDuringNativeStop.completeExceptionally(failure);
                    } catch (ExecutionException | TimeoutException failure) {
                      observedDuringNativeStop.completeExceptionally(failure);
                    }
                  }
                }))
        .routes(routes ->
            routes.websocket(
                "/idle-close",
                events -> {
                  events.onOpen(connection -> connection.send("ready"));
                  events.onClose((connection, code, reason) -> closed.complete(code));
                }));
  }

  private WebSocket
      createSocketForObservesAnIdleConnectionsCloseWhileNativeShutdownStillAllowsDispatch(
          HttpClient client, TestServer test, Messages received)
          throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
    return client
        .newWebSocketBuilder()
        .buildAsync(
            new URI("ws", test.baseUri().getRawAuthority(), "/idle-close", null, null), received)
        .get(3, TimeUnit.SECONDS);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @Timeout(15)
  void doesNotQueueEmptyDispatchForIdleOrClosedConnections(boolean closeBeforeBarrier)
      throws Exception {
    var release = new CountDownLatch(1);

    try (var borrowed = singleWorkerExecutor()) {
      var server = new AtomicReference<Server>();
      var closed = new CompletableFuture<Integer>();
      var app =
          createAppForDoesNotRunQueuedCloseObserverAfterApplicationShutdown(
              server, borrowed, new CompletableFuture<>(), closed);

      try (Closeable appCleanup = app::close;
          var test = TestServer.start(app);
          var client = HttpClient.newHttpClient()) {
        var received = new Messages();
        var socket =
            createSocketForDoesNotRunQueuedCloseObserverAfterApplicationShutdown(
                client, test, received);

        try {
          assertEquals("ready", received.text.poll(3, TimeUnit.SECONDS));
          if (closeBeforeBarrier) {
            socket.sendClose(1000, "done").get(3, TimeUnit.SECONDS);

            assertEquals(1000, closed.get(3, TimeUnit.SECONDS));
          }

          var occupied = new CountDownLatch(1);
          final var blocker =
              submitBlockerForDoesNotRunQueuedCloseObserverAfterApplicationShutdown(
                  borrowed, occupied, release);

          assertTrue(occupied.await(3, TimeUnit.SECONDS));
          assertTrue(borrowed.getQueue().isEmpty());

          release.countDown();

          assertEquals("released", blocker.get(3, TimeUnit.SECONDS));
        } finally {
          release.countDown();
          socket.abort();
        }
      }
    } finally {
      release.countDown();
    }
  }

  private ThreadPoolExecutor singleWorkerExecutor() {
    return new ThreadPoolExecutor(
        1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), Thread.ofVirtual().factory());
  }

  @Test
  @Timeout(20)
  void cancelsQueuedMessageImmediatelyWhenPublicCloseStarts() throws Exception {
    var release = new CountDownLatch(1);

    try (var borrowed = singleWorkerExecutor()) {
      var opened = new CompletableFuture<WebSocketConnection>();
      var messages = new AtomicInteger();
      var closed = new CompletableFuture<Integer>();
      var app = queuedMessageApplication(borrowed, opened, messages, closed);

      try (var test = TestServer.start(app);
          var peer = new Socket()) {
        upgradeQueuedPeer(peer, app.port());
        var connection = opened.get(3, TimeUnit.SECONDS);
        assertEquals("idle", borrowed.submit(() -> "idle").get(3, TimeUnit.SECONDS));

        var occupied = new CountDownLatch(1);
        final var blocker =
            submitBlockerForDoesNotRunQueuedCloseObserverAfterApplicationShutdown(
                borrowed, occupied, release);

        try {
          assertTrue(occupied.await(3, TimeUnit.SECONDS));
          peer.getOutputStream()
              .write(
                  new byte[] {(byte) 0x81, (byte) 0x86, 0, 0, 0, 0, 'q', 'u', 'e', 'u', 'e', 'd'});
          await().atMost(Duration.ofSeconds(3)).until(() -> dispatchFuture(connection).isPresent());
          var queued = dispatchFuture(connection).orElseThrow();
          assertFalse(queued.isDone());

          connection.close(1000, "done");

          assertTrue(queued.isCancelled());
          assertTrue(dispatchFuture(connection).isEmpty());
          assertFalse(closed.isDone());
          release.countDown();
          assertEquals("released", blocker.get(3, TimeUnit.SECONDS));
          assertEquals("settled", borrowed.submit(() -> "settled").get(3, TimeUnit.SECONDS));
          assertEquals(0, messages.get());
        } finally {
          release.countDown();
        }
      }
    } finally {
      release.countDown();
    }
  }

  private Shoostr queuedMessageApplication(
      ThreadPoolExecutor borrowed,
      CompletableFuture<WebSocketConnection> opened,
      AtomicInteger messages,
      CompletableFuture<Integer> closed) {
    return new Shoostr()
        .modifyServer(server -> {
          var pool = assertInstanceOf(QueuedThreadPool.class, server.getThreadPool());
          pool.setReservedThreads(1);
          pool.setVirtualThreadsExecutor(borrowed);
        })
        .routes(routes ->
            routes.websocket(
                "/queued-message",
                events -> {
                  events.onOpen(opened::complete);
                  events.onText((connection, text) -> messages.incrementAndGet());
                  events.onClose((connection, code, reason) -> closed.complete(code));
                }));
  }

  private void upgradeQueuedPeer(Socket peer, int port) throws Exception {
    peer.connect(new InetSocketAddress(InetAddress.getAllByName("127.0.0.1")[0], port));
    peer.setSoTimeout(3000);
    peer.getOutputStream().write(queuedPeerHandshake().getBytes(StandardCharsets.US_ASCII));
    var reader =
        new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.US_ASCII));
    var status = reader.readLine();
    assertNotNull(status);
    assertTrue(status.startsWith("HTTP/1.1 101"), status);
    String header;
    boolean accepted = false;
    while ((header = reader.readLine()) != null && !header.isEmpty()) {
      accepted |= header.regionMatches(true, 0, "Sec-WebSocket-Accept:", 0, 21);
    }
    assertTrue(accepted);
  }

  private String queuedPeerHandshake() {
    return String.join(
        "\r\n",
        "GET /queued-message HTTP/1.1",
        "Host: localhost",
        "Upgrade: websocket",
        "Connection: Upgrade",
        "Sec-WebSocket-Version: 13",
        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==",
        "",
        "");
  }

  @Test
  @Timeout(25)
  void rejectsAnInFlightUpgradeAfterManagedShutdownStarts() throws Exception {
    var admissionEntered = new CountDownLatch(1);
    var releaseAdmission = new CountDownLatch(1);
    var nativeStopping = new CompletableFuture<Server>();
    var releaseNativeStop = new CountDownLatch(1);
    var callbacks = new AtomicInteger();

    try (var borrowed = singleWorkerExecutor()) {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close) {
        configureShutdownAdmission(
            app,
            borrowed,
            admissionEntered,
            releaseAdmission,
            nativeStopping,
            releaseNativeStop,
            callbacks);

        try (var test = TestServer.start(app);
            var client = HttpClient.newHttpClient()) {
          var stopped = new CompletableFuture<Void>();
          var closing = applicationCloseThread(app, stopped);

          try (AutoCloseable shutdownCleanup =
              () -> finishAdmissionShutdown(closing, releaseAdmission, releaseNativeStop)) {
            var received = new Messages();
            var handshake = beginShutdownAdmissionHandshake(client, app.port(), received);

            try (AutoCloseable socketCleanup = () -> abortHandshake(handshake)) {
              assertTrue(admissionEntered.await(3, TimeUnit.SECONDS));
              closing.start();
              final var server = nativeStopping.get(3, TimeUnit.SECONDS);
              releaseAdmission.countDown();
              handshake.get(3, TimeUnit.SECONDS);

              assertEquals(1001, received.closeCode.get(3, TimeUnit.SECONDS));
              var container =
                  server.getContainedBeans(ServerWebSocketContainer.class).iterator().next();
              await()
                  .atMost(Duration.ofSeconds(3))
                  .until(() -> container.getOpenSessions().isEmpty());
              assertEquals("settled", borrowed.submit(() -> "settled").get(3, TimeUnit.SECONDS));
              assertEquals(0, callbacks.get());
              assertFalse(stopped.isDone());
            }
          }

          stopped.get(3, TimeUnit.SECONDS);
          assertEquals(0, callbacks.get());
        }
      }
    }
  }

  private void configureShutdownAdmission(
      Shoostr app,
      ThreadPoolExecutor borrowed,
      CountDownLatch admissionEntered,
      CountDownLatch releaseAdmission,
      CompletableFuture<Server> nativeStopping,
      CountDownLatch releaseNativeStop,
      AtomicInteger callbacks) {
    app.modifyServer(
            server -> {
              var pool = assertInstanceOf(QueuedThreadPool.class, server.getThreadPool());
              pool.setReservedThreads(1);
              pool.setVirtualThreadsExecutor(borrowed);
              server.addEventListener(admissionShutdownGate(nativeStopping, releaseNativeStop));
            })
        .routes(routes ->
            routes.websocket(
                "/shutdown-admission",
                events -> {
                  events.beforeUpgrade((request, response) -> {
                    admissionEntered.countDown();
                    assertTrue(releaseAdmission.await(10, TimeUnit.SECONDS));
                  });
                  events.onOpen(connection -> callbacks.incrementAndGet());
                  events.onText((connection, text) -> callbacks.incrementAndGet());
                  events.onError((connection, failure) -> callbacks.incrementAndGet());
                  events.onClose((connection, code, reason) -> callbacks.incrementAndGet());
                }));
  }

  private LifeCycle.Listener admissionShutdownGate(
      CompletableFuture<Server> nativeStopping, CountDownLatch releaseNativeStop) {
    return new LifeCycle.Listener() {
      @Override
      public void lifeCycleStopping(LifeCycle event) {
        nativeStopping.complete(assertInstanceOf(Server.class, event));

        try {
          assertTrue(releaseNativeStop.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
          Thread.currentThread().interrupt();
          fail("Interrupted while waiting to release native stop", failure);
        }
      }
    };
  }

  private Thread applicationCloseThread(Shoostr app, CompletableFuture<Void> stopped) {
    return Thread.ofVirtual()
        .unstarted(() -> {
          try {
            app.close();
            stopped.complete(null);
          } catch (Exception | Error failure) {
            stopped.completeExceptionally(failure);
          }
        });
  }

  private void finishAdmissionShutdown(
      Thread closing, CountDownLatch releaseAdmission, CountDownLatch releaseNativeStop)
      throws InterruptedException {
    releaseAdmission.countDown();
    releaseNativeStop.countDown();
    closing.join(5000);
    assertFalse(closing.isAlive());
  }

  private void abortHandshake(CompletableFuture<WebSocket> handshake) {
    if (!handshake.cancel(true) && !handshake.isCompletedExceptionally()) {
      handshake.join().abort();
    }
  }

  private CompletableFuture<WebSocket> beginShutdownAdmissionHandshake(
      HttpClient client, int port, Messages received) {
    return client
        .newWebSocketBuilder()
        .buildAsync(URI.create("ws://127.0.0.1:" + port + "/shutdown-admission"), received);
  }

  @Test
  @Timeout(15)
  void closesRejectedManagedDispatchWithoutInvokingApplicationCallbacks() throws Exception {
    var rejectNext = new AtomicBoolean();
    var calls = new AtomicInteger();

    try (var borrowed = singleRejectionExecutor(rejectNext)) {
      var nativeServer = new AtomicReference<Server>();
      var app = rejectingDispatchApplication(nativeServer, borrowed, calls);

      try (Closeable appCleanup = app::close;
          var test = TestServer.start(app);
          var client = HttpClient.newHttpClient()) {
        var received = new Messages();
        var socket =
            createSocketForValidatesCloseArgumentsBeforeChangingConnectionState(
                client, test, received);

        try {
          assertEquals("ready", received.text.poll(3, TimeUnit.SECONDS));
          assertEquals("idle", borrowed.submit(() -> "idle").get(3, TimeUnit.SECONDS));

          rejectNext.set(true);
          socket.sendText("rejected", true).get(3, TimeUnit.SECONDS);

          assertEquals(1001, received.closeCode.get(3, TimeUnit.SECONDS));
          assertFalse(rejectNext.get());
          var container =
              createContainerForDoesNotRunQueuedCloseObserverAfterApplicationShutdown(nativeServer);
          await().atMost(Duration.ofSeconds(3)).until(() -> container.getOpenSessions().isEmpty());

          assertEquals("settled", borrowed.submit(() -> "settled").get(3, TimeUnit.SECONDS));
          assertEquals(0, calls.get());
          assertEquals(200, test.send(request -> request.path("/probe")).statusCode());
        } finally {
          rejectNext.set(false);
          socket.abort();
        }
      }
    }
  }

  private ThreadPoolExecutor singleRejectionExecutor(AtomicBoolean rejectNext) {
    return new ThreadPoolExecutor(
        1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), Thread.ofVirtual().factory()) {
      @Override
      public void execute(Runnable task) {
        if (rejectNext.compareAndSet(true, false)) {
          throw new RejectedExecutionException("Fixture rejected one dispatch");
        }

        super.execute(task);
      }
    };
  }

  private Shoostr rejectingDispatchApplication(
      AtomicReference<Server> nativeServer, ThreadPoolExecutor borrowed, AtomicInteger calls) {
    return new Shoostr()
        .modifyServer(server -> {
          nativeServer.set(server);
          var pool = assertInstanceOf(QueuedThreadPool.class, server.getThreadPool());
          pool.setReservedThreads(1);
          pool.setVirtualThreadsExecutor(borrowed);
        })
        .routes(routes -> {
          routes.get("/probe", (request, response) -> response.text("usable"));
          routes.websocket(
              "/validation",
              events -> {
                events.onOpen(connection -> connection.send("ready"));
                events.onText((connection, text) -> calls.incrementAndGet());
                events.onError((connection, failure) -> calls.incrementAndGet());
                events.onClose((connection, code, reason) -> calls.incrementAndGet());
              });
        });
  }

  @Test
  @Timeout(30)
  void doesNotRunQueuedCloseObserverAfterApplicationShutdown() throws Exception {
    var release = new CountDownLatch(1);

    try (var borrowed =
        new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), Thread.ofVirtual().factory())) {
      var nativeServer = new AtomicReference<Server>();
      var opened = new CompletableFuture<WebSocketConnection>();
      var closed = new CompletableFuture<Integer>();
      var app =
          createAppForDoesNotRunQueuedCloseObserverAfterApplicationShutdown(
              nativeServer, borrowed, opened, closed);

      try (var test = TestServer.start(app);
          var client = HttpClient.newHttpClient()) {
        var received = new Messages();
        var socket =
            createSocketForDoesNotRunQueuedCloseObserverAfterApplicationShutdown(
                client, test, received);

        try {
          assertEquals("ready", received.text.poll(5, TimeUnit.SECONDS));

          var occupied = new CountDownLatch(1);
          final var blocker =
              submitBlockerForDoesNotRunQueuedCloseObserverAfterApplicationShutdown(
                  borrowed, occupied, release);

          assertTrue(occupied.await(5, TimeUnit.SECONDS));

          socket.sendClose(1000, "done").get(5, TimeUnit.SECONDS);

          assertEquals(1000, received.closeCode.get(5, TimeUnit.SECONDS));

          var container =
              createContainerForDoesNotRunQueuedCloseObserverAfterApplicationShutdown(nativeServer);
          await().atMost(Duration.ofSeconds(5)).until(() -> container.getOpenSessions().isEmpty());
          await().atMost(Duration.ofSeconds(5)).until(() -> !borrowed.getQueue().isEmpty());

          var connection = opened.get(5, TimeUnit.SECONDS);
          await().atMost(Duration.ofSeconds(5)).until(() -> dispatchFuture(connection).isPresent());
          var queued = dispatchFuture(connection).orElseThrow();

          assertFalse(queued.isDone());
          assertFalse(closed.isDone());

          test.close();

          assertTrue(queued.isCancelled());
          assertTrue(dispatchFuture(connection).isEmpty());
          assertFalse(runtimeRetainsConnection(connection));
          assertFalse(borrowed.isShutdown());

          release.countDown();

          assertEquals("released", blocker.get(5, TimeUnit.SECONDS));
          assertEquals("usable", borrowed.submit(() -> "usable").get(5, TimeUnit.SECONDS));
          assertFalse(closed.isDone());
        } finally {
          release.countDown();
          socket.abort();
        }
      }
    } finally {
      release.countDown();
    }
  }

  private Shoostr createAppForDoesNotRunQueuedCloseObserverAfterApplicationShutdown(
      AtomicReference<Server> nativeServer,
      ThreadPoolExecutor borrowed,
      CompletableFuture<WebSocketConnection> opened,
      CompletableFuture<Integer> closed) {
    return new Shoostr()
        .modifyServer(server -> {
          nativeServer.set(server);

          var pool = assertInstanceOf(QueuedThreadPool.class, server.getThreadPool());

          pool.setReservedThreads(1);
          pool.setVirtualThreadsExecutor(borrowed);
        })
        .routes(routes ->
            routes.websocket(
                "/queued-close",
                events -> {
                  events.onOpen(connection -> {
                    opened.complete(connection);
                    connection.send("ready");
                  });
                  events.onClose((connection, code, reason) -> closed.complete(code));
                }));
  }

  private WebSocket createSocketForDoesNotRunQueuedCloseObserverAfterApplicationShutdown(
      HttpClient client, TestServer test, Messages received)
      throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
    return client
        .newWebSocketBuilder()
        .buildAsync(
            new URI("ws", test.baseUri().getRawAuthority(), "/queued-close", null, null), received)
        .get(5, TimeUnit.SECONDS);
  }

  private Future<String> submitBlockerForDoesNotRunQueuedCloseObserverAfterApplicationShutdown(
      ThreadPoolExecutor borrowed, CountDownLatch occupied, CountDownLatch release) {
    return borrowed.submit(() -> {
      occupied.countDown();

      assertTrue(release.await(15, TimeUnit.SECONDS));

      return "released";
    });
  }

  private ServerWebSocketContainer
      createContainerForDoesNotRunQueuedCloseObserverAfterApplicationShutdown(
          AtomicReference<Server> nativeServer) {
    return nativeServer.get().getContainedBeans(ServerWebSocketContainer.class).iterator().next();
  }

  @Test
  @Timeout(25)
  void doesNotDispatchLateNativeCloseAfterNativeStopFails() throws Exception {
    var nativeServer = new AtomicReference<Server>();
    var opened = new CompletableFuture<WebSocketConnection>();
    var nativeOpened = new CompletableFuture<Session>();
    var nativeClosed = new CountDownLatch(1);
    var closed = new AtomicInteger();
    var holdNativeCallback = new AtomicBoolean();
    var nativeCallbackEntered = new CountDownLatch(1);
    var releaseNativeCallback = new CountDownLatch(1);
    var nativeGateCompleted = new CompletableFuture<Void>();
    var stopFailure = createStopFailureForDoesNotDispatchLateNativeCloseAfterNativeStopFails();
    var app = new Shoostr();

    configureLateNativeClose(
        app,
        nativeServer,
        stopFailure,
        nativeOpened,
        nativeClosed,
        holdNativeCallback,
        nativeCallbackEntered,
        releaseNativeCallback,
        nativeGateCompleted,
        opened,
        closed);

    try {
      try (var test = TestServer.start(app);
          var client = HttpClient.newHttpClient()) {
        var socket =
            createSocketForDoesNotDispatchLateNativeCloseAfterNativeStopFails(client, test);
        CompletableFuture<Void> closing = null;

        try {
          var connection = opened.get(5, TimeUnit.SECONDS);
          nativeOpened.get(5, TimeUnit.SECONDS);
          holdNativeCallback.set(true);
          closing =
              CompletableFuture.runAsync(() -> connection.close(1001, "Delay native callback"));

          assertTrue(nativeCallbackEntered.await(5, TimeUnit.SECONDS));
          assertEquals(0, closed.get());

          assertThrows(IOException.class, test::close);
          assertEquals(0, closed.get());

          releaseNativeCallback.countDown();
          closing.get(5, TimeUnit.SECONDS);
          nativeGateCompleted.get(5, TimeUnit.SECONDS);

          assertTrue(nativeClosed.await(5, TimeUnit.SECONDS));

          await()
              .during(Duration.ofMillis(100))
              .atMost(Duration.ofSeconds(1))
              .untilAsserted(() -> assertEquals(0, closed.get()));
        } finally {
          releaseNativeCallback.countDown();
          socket.abort();
          if (closing != null) {
            closing.get(5, TimeUnit.SECONDS);
          }
        }
      }
    } finally {
      releaseNativeCallback.countDown();
      var server = nativeServer.get();
      if (server != null) {
        server.removeEventListener(stopFailure);
        server.stop();
      }
    }
  }

  private Listener createStopFailureForDoesNotDispatchLateNativeCloseAfterNativeStopFails() {
    return new LifeCycle.Listener() {
      @Override
      public void lifeCycleStopping(LifeCycle event) {
        throw new IllegalStateException("Fixture native stop failure");
      }
    };
  }

  private void configureLateNativeClose(
      Shoostr app,
      AtomicReference<Server> nativeServer,
      Listener stopFailure,
      CompletableFuture<Session> nativeOpened,
      CountDownLatch nativeClosed,
      AtomicBoolean holdNativeCallback,
      CountDownLatch nativeCallbackEntered,
      CountDownLatch releaseNativeCallback,
      CompletableFuture<Void> nativeGateCompleted,
      CompletableFuture<WebSocketConnection> opened,
      AtomicInteger closed) {
    app.modifyServer(
            server -> {
              nativeServer.set(server);
              server.addEventListener(stopFailure);
              var container =
                  server.getContainedBeans(ServerWebSocketContainer.class).iterator().next();
              container.addSessionListener(
                  new WebSocketSessionListener() {
                    @Override
                    public void onWebSocketSessionOpened(Session session) {
                      nativeOpened.complete(session);
                    }

                    @Override
                    public void onWebSocketSessionClosed(Session session) {
                      nativeClosed.countDown();
                    }
                  });
              var context = server.getContainedBeans(ContextHandler.class).iterator().next();
              context.addEventListener(
                  new ContextScopeListener() {
                    @Override
                    public void enterScope(
                        Context scope, org.eclipse.jetty.server.Request request) {
                      var session = nativeOpened.getNow(null);
                      if (request != null
                          || session == null
                          || session.isOpen()
                          || !holdNativeCallback.compareAndSet(true, false)) {
                        return;
                      }

                      nativeCallbackEntered.countDown();

                      try {
                        if (releaseNativeCallback.await(10, TimeUnit.SECONDS)) {
                          nativeGateCompleted.complete(null);
                        } else {
                          nativeGateCompleted.completeExceptionally(
                              new IOException("Native callback release timed out"));
                        }
                      } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        nativeGateCompleted.completeExceptionally(failure);
                      }
                    }
                  });
            })
        .routes(routes ->
            routes.websocket(
                "/late-close",
                events -> {
                  events.onOpen(opened::complete);
                  events.onClose((connection, code, reason) -> closed.incrementAndGet());
                }));
  }

  private WebSocket createSocketForDoesNotDispatchLateNativeCloseAfterNativeStopFails(
      HttpClient client, TestServer test)
      throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
    return client
        .newWebSocketBuilder()
        .buildAsync(
            new URI("ws", test.baseUri().getRawAuthority(), "/late-close", null, null),
            new Messages())
        .get(5, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(25)
  void interruptsAnActiveCloseObserverDuringBothShutdownPhases() throws Exception {
    var releaseObserver = new CountDownLatch(1);

    try (var borrowed = Executors.newVirtualThreadPerTaskExecutor()) {
      var opened = new CompletableFuture<WebSocketConnection>();
      var observerEntered = new CountDownLatch(1);
      var firstInterrupt = new CompletableFuture<Void>();
      var secondInterrupt = new CompletableFuture<Void>();
      var firstInterruptBeforeNativeStop = new CompletableFuture<Void>();
      var app = new Shoostr();

      configureBlockedCloseObserver(
          app,
          borrowed,
          firstInterrupt,
          firstInterruptBeforeNativeStop,
          opened,
          observerEntered,
          releaseObserver,
          secondInterrupt);

      try (var test = TestServer.start(app);
          var client = HttpClient.newHttpClient()) {
        var received = new Messages();
        var socket =
            createSocketForInterruptsAnActiveCloseObserverDuringBothShutdownPhases(
                client, test, received);

        try {
          opened.get(5, TimeUnit.SECONDS);
          socket.sendClose(1000, "Observe close").get(5, TimeUnit.SECONDS);

          assertTrue(observerEntered.await(5, TimeUnit.SECONDS));

          test.close();
          firstInterruptBeforeNativeStop.get(1, TimeUnit.SECONDS);
          secondInterrupt.get(5, TimeUnit.SECONDS);

          assertFalse(borrowed.isShutdown());
        } finally {
          releaseObserver.countDown();
          socket.abort();
        }
      }
    } finally {
      releaseObserver.countDown();
    }
  }

  private void configureBlockedCloseObserver(
      Shoostr app,
      ExecutorService borrowed,
      CompletableFuture<Void> firstInterrupt,
      CompletableFuture<Void> firstInterruptBeforeNativeStop,
      CompletableFuture<WebSocketConnection> opened,
      CountDownLatch observerEntered,
      CountDownLatch releaseObserver,
      CompletableFuture<Void> secondInterrupt) {
    app.modifyServer(
            server -> {
              assertInstanceOf(QueuedThreadPool.class, server.getThreadPool())
                  .setVirtualThreadsExecutor(borrowed);
              server.addEventListener(
                  new LifeCycle.Listener() {
                    @Override
                    public void lifeCycleStopping(LifeCycle event) {
                      try {
                        firstInterrupt.get(5, TimeUnit.SECONDS);
                        firstInterruptBeforeNativeStop.complete(null);
                      } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        firstInterruptBeforeNativeStop.completeExceptionally(failure);
                      } catch (ExecutionException | TimeoutException failure) {
                        firstInterruptBeforeNativeStop.completeExceptionally(failure);
                      }
                    }
                  });
            })
        .routes(routes ->
            routes.websocket(
                "/cancel-observer",
                events -> {
                  events.onOpen(opened::complete);
                  events.onClose((connection, code, reason) -> {
                    observerEntered.countDown();

                    try {
                      releaseObserver.await();
                    } catch (InterruptedException first) {
                      firstInterrupt.complete(null);

                      try {
                        releaseObserver.await();
                      } catch (InterruptedException second) {
                        secondInterrupt.complete(null);
                        throw second;
                      }
                    }
                  });
                }));
  }

  private WebSocket createSocketForInterruptsAnActiveCloseObserverDuringBothShutdownPhases(
      HttpClient client, TestServer test, Messages received)
      throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
    return client
        .newWebSocketBuilder()
        .buildAsync(
            new URI("ws", test.baseUri().getRawAuthority(), "/cancel-observer", null, null),
            received)
        .get(5, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(20)
  void reusesCancelledCallbacksNativeWorkerWithoutInterruptingItsNextTask() throws Exception {
    var pool = new AtomicReference<QueuedThreadPool>();
    var opened = new CompletableFuture<WebSocketConnection>();
    var worker = new CompletableFuture<Thread>();
    var cancelled = new CompletableFuture<Void>();
    var app =
        createAppForReusesCancelledCallbacksNativeWorkerWithoutInterruptingItsNextTask(
            pool, opened, worker, cancelled);

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var received = new Messages();
      var socket =
          createSocketForReusesCancelledCallbacksNativeWorkerWithoutInterruptingItsNextTask(
              client, test, received);
      var release = new CountDownLatch(1);
      var sentinels = new ArrayList<CompletableFuture<Void>>();

      try {
        socket.sendText("work", true).get(3, TimeUnit.SECONDS);
        var previousWorker = worker.get(3, TimeUnit.SECONDS);

        assertFalse(previousWorker.isVirtual());

        opened.get(3, TimeUnit.SECONDS).close(1001, "Cancel work");

        assertEquals(1001, received.closeCode.get(3, TimeUnit.SECONDS));

        cancelled.get(3, TimeUnit.SECONDS);

        var reused = new CompletableFuture<Boolean>();
        var interruptedAfterReuse = new AtomicBoolean();
        for (int index = 0; index < 32; index++) {
          var completed = new CompletableFuture<Void>();
          sentinels.add(completed);
          pool.get()
              .execute(() -> {
                boolean sameWorker = Thread.currentThread() == previousWorker;
                if (sameWorker) {
                  reused.complete(Thread.currentThread().isInterrupted());
                }

                try {
                  if (!release.await(5, TimeUnit.SECONDS)) {
                    completed.completeExceptionally(new IOException("Sentinel release timed out"));
                  }
                } catch (InterruptedException failure) {
                  if (sameWorker) {
                    interruptedAfterReuse.set(true);
                  }

                  Thread.currentThread().interrupt();
                  completed.completeExceptionally(failure);
                } finally {
                  completed.complete(null);
                }
              });
        }

        assertFalse(reused.get(3, TimeUnit.SECONDS));

        await()
            .during(Duration.ofMillis(100))
            .atMost(Duration.ofSeconds(1))
            .untilAsserted(() -> assertFalse(interruptedAfterReuse.get()));
      } finally {
        release.countDown();

        try {
          for (var sentinel : sentinels) {
            sentinel.get(3, TimeUnit.SECONDS);
          }
        } finally {
          socket.abort();
        }
      }
    }
  }

  private Shoostr createAppForReusesCancelledCallbacksNativeWorkerWithoutInterruptingItsNextTask(
      AtomicReference<QueuedThreadPool> pool,
      CompletableFuture<WebSocketConnection> opened,
      CompletableFuture<Thread> worker,
      CompletableFuture<Void> cancelled) {
    return new Shoostr()
        .modifyServer(server -> {
          var nativePool = assertInstanceOf(QueuedThreadPool.class, server.getThreadPool());

          nativePool.setMaxThreads(32);
          pool.set(nativePool);
        })
        .routes(routes ->
            routes.websocket(
                "/cancel",
                events -> {
                  events.onOpen(opened::complete);
                  events.onText((connection, text) -> {
                    worker.complete(Thread.currentThread());

                    try {
                      new CountDownLatch(1).await();
                    } catch (InterruptedException failure) {
                      cancelled.complete(null);
                      throw failure;
                    }
                  });
                }));
  }

  private WebSocket
      createSocketForReusesCancelledCallbacksNativeWorkerWithoutInterruptingItsNextTask(
          HttpClient client, TestServer test, Messages received)
          throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
    return client
        .newWebSocketBuilder()
        .buildAsync(
            new URI("ws", test.baseUri().getRawAuthority(), "/cancel", null, null), received)
        .get(3, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(10)
  void doesNotWaitForInterruptionIgnoringWorkDuringAppClose() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var exited = new CountDownLatch(1);
    var app =
        createAppForDoesNotWaitForInterruptionIgnoringWorkDuringAppClose(entered, release, exited);

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var socket =
          createSocketForDoesNotWaitForInterruptionIgnoringWorkDuringAppClose(client, test);

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

  private Shoostr createAppForDoesNotWaitForInterruptionIgnoringWorkDuringAppClose(
      CountDownLatch entered, CountDownLatch release, CountDownLatch exited) {
    return new Shoostr()
        .routes(routes ->
            routes.websocket(
                "/work",
                events ->
                    events.onText((ignoredWebSocketConnection, ignoredString) -> {
                      entered.countDown();
                      ignoreInterruptsUntilReleased(release);
                      exited.countDown();
                    })));
  }

  private WebSocket createSocketForDoesNotWaitForInterruptionIgnoringWorkDuringAppClose(
      HttpClient client, TestServer test)
      throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
    return client
        .newWebSocketBuilder()
        .buildAsync(
            new URI("ws", test.baseUri().getRawAuthority(), "/work", null, null), new Messages())
        .get(3, TimeUnit.SECONDS);
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
        createAppForDefersTheCloseObserverUntilActiveApplicationWorkActuallyUnwinds(
            opened, active, entered, release, observedOverlap);

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var received = new Messages();
      var socket =
          createSocketForDefersTheCloseObserverUntilActiveApplicationWorkActuallyUnwinds(
              client, test, received);

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

  private Shoostr createAppForDefersTheCloseObserverUntilActiveApplicationWorkActuallyUnwinds(
      CompletableFuture<WebSocketConnection> opened,
      AtomicBoolean active,
      CountDownLatch entered,
      CountDownLatch release,
      CompletableFuture<Boolean> observedOverlap) {
    return new Shoostr()
        .routes(routes ->
            routes.websocket(
                "/work",
                events -> {
                  events.onOpen(opened::complete);
                  events.onText((ignoredWebSocketConnection, ignoredString) -> {
                    active.set(true);
                    entered.countDown();
                    ignoreInterruptsUntilReleased(release);
                    active.set(false);
                  });
                  events.onClose((ignoredWebSocketConnection, ignoredIndex, ignoredString) ->
                      observedOverlap.complete(active.get()));
                }));
  }

  private WebSocket createSocketForDefersTheCloseObserverUntilActiveApplicationWorkActuallyUnwinds(
      HttpClient client, TestServer test, Messages received)
      throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
    return client
        .newWebSocketBuilder()
        .buildAsync(new URI("ws", test.baseUri().getRawAuthority(), "/work", null, null), received)
        .get(3, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(10)
  void stillNotifiesCloseWhenTheErrorObserverThrows() throws Exception {
    var closed = new CompletableFuture<Integer>();
    var app = createAppForStillNotifiesCloseWhenTheErrorObserverThrows(closed);

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var socket = createSocketForStillNotifiesCloseWhenTheErrorObserverThrows(client, test);

      try {
        socket.sendText("fail", true).get(3, TimeUnit.SECONDS);

        assertEquals(1011, closed.get(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  private Shoostr createAppForStillNotifiesCloseWhenTheErrorObserverThrows(
      CompletableFuture<Integer> closed) {
    return new Shoostr()
        .routes(routes ->
            routes.websocket(
                "/failure",
                events -> {
                  events.onText((ignoredWebSocketConnection, ignoredString) -> {
                    throw new IOException("Fixture failure");
                  });
                  events.onError((ignoredWebSocketConnection, ignoredFailure) -> {
                    throw new IllegalStateException("Observer failure");
                  });
                  events.onClose(
                      (ignoredWebSocketConnection, code, ignoredString) -> closed.complete(code));
                }));
  }

  private WebSocket createSocketForStillNotifiesCloseWhenTheErrorObserverThrows(
      HttpClient client, TestServer test)
      throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
    return client
        .newWebSocketBuilder()
        .buildAsync(
            new URI("ws", test.baseUri().getRawAuthority(), "/failure", null, null), new Messages())
        .get(3, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(10)
  void keepsServingRequestsAfterAnInterruptedCallbackAndFatalErrorObserver() throws Exception {
    var observed = new CompletableFuture<Boolean>();
    var fatal = new LinkageError("Observer failure");
    var app =
        createAppForKeepsServingRequestsAfterAnInterruptedCallbackAndFatalErrorObserver(
            observed, fatal);

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var socket =
          createSocketForKeepsServingRequestsAfterAnInterruptedCallbackAndFatalErrorObserver(
              client, test);

      try {
        socket.sendText("fail", true).get(3, TimeUnit.SECONDS);

        assertFalse(observed.get(3, TimeUnit.SECONDS));
        assertEquals(
            200,
            test.send(request -> request.path("/probe").timeout(Duration.ofSeconds(3)))
                .statusCode());
      } finally {
        socket.abort();
      }
    }
  }

  private Shoostr createAppForKeepsServingRequestsAfterAnInterruptedCallbackAndFatalErrorObserver(
      CompletableFuture<Boolean> observed, LinkageError fatal) {
    return new Shoostr()
        .routes(routes -> {
          routes.get("/probe", (request, response) -> response.text("available"));
          routes.websocket(
              "/failure",
              events -> {
                events.onText((ignoredWebSocketConnection, ignoredString) -> {
                  Thread.currentThread().interrupt();
                  throw new IOException("Fixture failure");
                });
                events.onError((ignoredWebSocketConnection, ignoredFailure) -> {
                  observed.complete(Thread.currentThread().isInterrupted());
                  throw fatal;
                });
              });
        });
  }

  private WebSocket
      createSocketForKeepsServingRequestsAfterAnInterruptedCallbackAndFatalErrorObserver(
          HttpClient client, TestServer test)
          throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
    return client
        .newWebSocketBuilder()
        .buildAsync(
            new URI("ws", test.baseUri().getRawAuthority(), "/failure", null, null), new Messages())
        .get(3, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(10)
  void completesInterruptibleCloseWorkAfterAnErrorObserverIsInterrupted() throws Exception {
    var observed = new CompletableFuture<Boolean>();
    var ready = new CountDownLatch(0);
    var app =
        createAppForCompletesInterruptibleCloseWorkAfterAnErrorObserverIsInterrupted(
            ready, observed);

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var socket =
          createSocketForCompletesInterruptibleCloseWorkAfterAnErrorObserverIsInterrupted(
              client, test);

      try {
        socket.sendText("fail", true).get(3, TimeUnit.SECONDS);

        assertTrue(observed.get(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  private Shoostr createAppForCompletesInterruptibleCloseWorkAfterAnErrorObserverIsInterrupted(
      CountDownLatch ready, CompletableFuture<Boolean> observed) {
    return new Shoostr()
        .routes(routes ->
            routes.websocket(
                "/failure",
                events -> {
                  events.onText((ignoredWebSocketConnection, ignoredString) -> {
                    throw new IOException("Fixture failure");
                  });
                  events.onError((ignoredWebSocketConnection, ignoredFailure) -> {
                    throw new InterruptedException("Observer interrupted");
                  });
                  events.onClose((ignoredWebSocketConnection, ignoredIndex, ignoredString) -> {
                    ready.await();
                    observed.complete(true);
                  });
                }));
  }

  private WebSocket createSocketForCompletesInterruptibleCloseWorkAfterAnErrorObserverIsInterrupted(
      HttpClient client, TestServer test)
      throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
    return client
        .newWebSocketBuilder()
        .buildAsync(
            new URI("ws", test.baseUri().getRawAuthority(), "/failure", null, null), new Messages())
        .get(3, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(10)
  void validatesCloseArgumentsBeforeChangingConnectionState() throws Exception {
    var opened = new CompletableFuture<WebSocketConnection>();
    var app =
        new Shoostr()
            .routes(routes ->
                routes.websocket("/validation", events -> events.onOpen(opened::complete)));

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var received = new Messages();
      var socket =
          createSocketForValidatesCloseArgumentsBeforeChangingConnectionState(
              client, test, received);

      try {
        var connection = opened.get(3, TimeUnit.SECONDS);

        assertThrows(IllegalArgumentException.class, () -> connection.close(1006, "Invalid"));

        for (var invalidReason :
            List.of("x".repeat(124), "€".repeat(42), Character.toString((char) 0xD800))) {
          assertThrows(IllegalArgumentException.class, () -> connection.close(1000, invalidReason));
        }

        connection.send("still open");

        assertEquals("still open", received.text.poll(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  private WebSocket createSocketForValidatesCloseArgumentsBeforeChangingConnectionState(
      HttpClient client, TestServer test, WebSocket.Listener received)
      throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
    return client
        .newWebSocketBuilder()
        .buildAsync(
            new URI("ws", test.baseUri().getRawAuthority(), "/validation", null, null), received)
        .get(3, TimeUnit.SECONDS);
  }

  @ParameterizedTest
  @CsvSource({"x,123", "€,41"})
  @Timeout(10)
  void transmitsCloseReasonsAtTheMaximumEncodedByteLength(String character, int repetitions)
      throws Exception {
    var reason = character.repeat(repetitions);
    var opened = new CompletableFuture<WebSocketConnection>();
    var app = closeValidationApplication(opened);

    try (Closeable appCleanup = app::close;
        var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var received = new Messages();
      var socket =
          createSocketForValidatesCloseArgumentsBeforeChangingConnectionState(
              client, test, received);

      try {
        var connection = opened.get(3, TimeUnit.SECONDS);
        connection.close(1000, reason);

        assertEquals(1000, received.closeCode.get(3, TimeUnit.SECONDS));
        assertEquals(reason, received.closeReason.get(3, TimeUnit.SECONDS));
        assertFalse(connection.isOpen());
      } finally {
        socket.abort();
      }
    }
  }

  private Shoostr closeValidationApplication(CompletableFuture<WebSocketConnection> opened) {
    return new Shoostr()
        .routes(
            routes -> routes.websocket("/validation", events -> events.onOpen(opened::complete)));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @Timeout(15)
  void drainsQueuedMessagesWithoutDeliveryToCompleteNormalCloseDuringOpening(boolean binary)
      throws Exception {
    var opened = new CompletableFuture<WebSocketConnection>();
    var interrupted = new CompletableFuture<Boolean>();
    var closed = new CompletableFuture<Integer>();
    var release = new CountDownLatch(1);
    var messages = new AtomicInteger();
    var app = closingDuringOpeningApplication(opened, interrupted, closed, release, messages);

    try (Closeable appCleanup = app::close;
        var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var socket =
          createSocketForValidatesCloseArgumentsBeforeChangingConnectionState(
              client, test, new Messages());

      try {
        var connection = opened.get(3, TimeUnit.SECONDS);
        if (binary) {
          socket.sendBinary(ByteBuffer.wrap(new byte[] {1}), true).get(3, TimeUnit.SECONDS);
        } else {
          socket.sendText("queued", true).get(3, TimeUnit.SECONDS);
        }

        socket.sendClose(1000, "done").get(3, TimeUnit.SECONDS);

        connection.close(1000, "done");

        assertTrue(interrupted.get(3, TimeUnit.SECONDS));
        assertEquals(1000, closed.get(3, TimeUnit.SECONDS));
        assertEquals(0, messages.get());
      } finally {
        release.countDown();
        socket.abort();
      }
    }
  }

  private Shoostr closingDuringOpeningApplication(
      CompletableFuture<WebSocketConnection> opened,
      CompletableFuture<Boolean> interrupted,
      CompletableFuture<Integer> closed,
      CountDownLatch release,
      AtomicInteger messages) {
    return new Shoostr()
        .routes(routes ->
            routes.websocket(
                "/validation",
                events -> {
                  events.onOpen(connection -> {
                    opened.complete(connection);

                    try {
                      release.await();
                    } catch (InterruptedException expected) {
                      interrupted.complete(true);
                    }
                  });
                  events.onText((connection, text) -> messages.incrementAndGet());
                  events.onBinary((connection, bytes) -> messages.incrementAndGet());
                  events.onClose((connection, code, reason) -> closed.complete(code));
                }));
  }

  @Test
  @Timeout(20)
  void snapshotsMutableBinaryOutputBeforeWaitingForTransportCompletion() throws Exception {
    var bytes = new byte[16 * 1024 * 1024];
    Arrays.fill(bytes, (byte) 1);
    var sent = new CompletableFuture<Void>();
    var app =
        createAppForSnapshotsMutableBinaryOutputBeforeWaitingForTransportCompletion(bytes, sent);

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var received = new PausedBinaryMessages();
      var socket =
          createSocketForSnapshotsMutableBinaryOutputBeforeWaitingForTransportCompletion(
              client, test, received);

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

  private Shoostr createAppForSnapshotsMutableBinaryOutputBeforeWaitingForTransportCompletion(
      byte[] bytes, CompletableFuture<Void> sent) {
    return new Shoostr()
        .routes(routes ->
            routes.websocket(
                "/bytes",
                events ->
                    events.onText((connection, ignoredString) -> {
                      connection.send(bytes);
                      sent.complete(null);
                    })));
  }

  private WebSocket createSocketForSnapshotsMutableBinaryOutputBeforeWaitingForTransportCompletion(
      HttpClient client, TestServer test, PausedBinaryMessages received)
      throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
    return client
        .newWebSocketBuilder()
        .buildAsync(new URI("ws", test.baseUri().getRawAuthority(), "/bytes", null, null), received)
        .get(3, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(15)
  void closesTheConnectionWhenAnAlreadySubmittedSendIsInterrupted() throws Exception {
    var opened = new CompletableFuture<WebSocketConnection>();
    var app = closeValidationApplication(opened);

    try (Closeable appCleanup = app::close;
        var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var received = new PausedBinaryMessages();
      var socket =
          createSocketForValidatesCloseArgumentsBeforeChangingConnectionState(
              client, test, received);

      try {
        var connection = opened.get(3, TimeUnit.SECONDS);
        var openAfterInterruption = new CompletableFuture<Boolean>();
        var sender = startInterruptibleSend(connection, openAfterInterruption);

        try {
          received.first.get(3, TimeUnit.SECONDS);
          await()
              .atMost(Duration.ofSeconds(3))
              .until(() -> sender.getState() == Thread.State.WAITING);
          assertFalse(openAfterInterruption.isDone());

          sender.interrupt();

          assertFalse(openAfterInterruption.get(3, TimeUnit.SECONDS));
          assertThrows(IOException.class, () -> connection.send("late"));
        } finally {
          sender.interrupt();
          received.release.complete(null);
          sender.join(3000);
        }
      } finally {
        received.release.complete(null);
        socket.abort();
      }
    }
  }

  private Thread startInterruptibleSend(
      WebSocketConnection connection, CompletableFuture<Boolean> openAfterInterruption) {
    return Thread.ofVirtual()
        .start(() -> {
          try {
            connection.send(new byte[16 * 1024 * 1024]);
            fail("Send completed before interruption");
          } catch (InterruptedException expected) {
            openAfterInterruption.complete(connection.isOpen());
          } catch (Throwable failure) {
            openAfterInterruption.completeExceptionally(failure);
          }
        });
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @Timeout(20)
  void publicCloseSettlesPendingSendBeforeThePeerCanFinishTheHandshake(boolean repeated)
      throws Exception {
    var opened = new CompletableFuture<WebSocketConnection>();
    var closed = new CompletableFuture<String>();
    var errors = new AtomicInteger();
    var nativeServer = new AtomicReference<Server>();

    try (var borrowed = singleWorkerExecutor()) {
      var app = pendingCloseApplication(nativeServer, borrowed, opened, closed, errors);

      try (Closeable appCleanup = app::close;
          var test = TestServer.start(app);
          var client = HttpClient.newHttpClient();
          var producers = Executors.newVirtualThreadPerTaskExecutor()) {
        var received = new PausedBinaryMessages();
        var socket =
            createSocketForValidatesCloseArgumentsBeforeChangingConnectionState(
                client, test, received);

        try {
          var connection = opened.get(3, TimeUnit.SECONDS);
          borrowed.submit(() -> {}).get(3, TimeUnit.SECONDS);
          assertTrue(runtimeRetainsConnection(connection));
          final var sent = submitPendingSend(producers, connection);

          received.first.get(3, TimeUnit.SECONDS);

          assertFalse(sent.isDone());
          assertTrue(pendingWrite(connection).isPresent());

          connection.close(1000, "first");
          if (repeated) {
            connection.close(1001, "second");
          }

          sent.get(3, TimeUnit.SECONDS);
          assertTrue(pendingWrite(connection).isEmpty());
          assertFalse(connection.isOpen());

          received.release.complete(null);

          assertEquals("1000:first", received.closed.get(3, TimeUnit.SECONDS));
          assertEquals("1000:", closed.get(3, TimeUnit.SECONDS));
          var container =
              createContainerForDoesNotRunQueuedCloseObserverAfterApplicationShutdown(nativeServer);
          await().atMost(Duration.ofSeconds(3)).until(() -> container.getOpenSessions().isEmpty());

          assertEquals("settled", borrowed.submit(() -> "settled").get(3, TimeUnit.SECONDS));
          assertFalse(runtimeRetainsConnection(connection));
          assertEquals(0, errors.get());
          assertArrayEquals(new byte[16 * 1024 * 1024], received.payload.get(3, TimeUnit.SECONDS));
        } finally {
          received.release.complete(null);
          socket.abort();
        }
      }
    }
  }

  private Optional<Future<?>> pendingWrite(WebSocketConnection connection)
      throws ReflectiveOperationException {
    var guardField = privateField(WebSocketConnection.class, "lifecycleLock");
    var guard = (ReentrantLock) Objects.requireNonNull(guardField.get(connection));
    var pending = privateField(WebSocketConnection.class, "pending");

    return Objects.requireNonNull(
        getUnderLock(guard, () -> Optional.ofNullable((Future<?>) pending.get(connection))));
  }

  private boolean runtimeRetainsConnection(WebSocketConnection connection)
      throws ReflectiveOperationException {
    var listener = connectionListener(connection);
    var runtimeField = privateField(ManagedWebSocket.class, "runtime");
    var runtime = (WebSocketRuntime) Objects.requireNonNull(runtimeField.get(listener));
    var guardField = privateField(WebSocketRuntime.class, "lifecycleLock");
    var guard = (ReentrantLock) Objects.requireNonNull(guardField.get(runtime));
    var connectionsField = privateField(WebSocketRuntime.class, "connections");
    var connections = (Set<?>) Objects.requireNonNull(connectionsField.get(runtime));

    return Boolean.TRUE.equals(getUnderLock(guard, () -> connections.contains(listener)));
  }

  private Optional<Future<?>> dispatchFuture(WebSocketConnection connection)
      throws ReflectiveOperationException {
    var listener = connectionListener(connection);
    var guardField = privateField(ManagedWebSocket.class, "events");
    var guard = (ReentrantLock) Objects.requireNonNull(guardField.get(listener));
    var taskField = privateField(ManagedWebSocket.class, "task");

    return Objects.requireNonNull(
        getUnderLock(
            guard,
            () -> {
              var task = taskField.get(listener);
              if (task == null) {
                return Optional.empty();
              }

              var futureField = privateField(task.getClass(), "future");
              return Optional.of((Future<?>) Objects.requireNonNull(futureField.get(task)));
            }));
  }

  private ManagedWebSocket connectionListener(WebSocketConnection connection)
      throws ReflectiveOperationException {
    var listenerField = privateField(WebSocketConnection.class, "listener");

    return (ManagedWebSocket) Objects.requireNonNull(listenerField.get(connection));
  }

  private void awaitConnectionRetirement(WebSocketConnection connection) {
    await()
        .atMost(Duration.ofSeconds(3))
        .until(() -> dispatchFuture(connection).isEmpty() && !runtimeRetainsConnection(connection));
  }

  private Field privateField(Class<?> type, String name) throws NoSuchFieldException {
    var field = type.getDeclaredField(name);
    field.setAccessible(true);

    return field;
  }

  private Shoostr pendingCloseApplication(
      AtomicReference<Server> nativeServer,
      ThreadPoolExecutor borrowed,
      CompletableFuture<WebSocketConnection> opened,
      CompletableFuture<String> closed,
      AtomicInteger errors) {
    return new Shoostr()
        .modifyServer(server -> {
          nativeServer.set(server);
          var pool = assertInstanceOf(QueuedThreadPool.class, server.getThreadPool());
          pool.setReservedThreads(1);
          pool.setVirtualThreadsExecutor(borrowed);
        })
        .routes(routes ->
            routes.websocket(
                "/validation",
                events -> {
                  events.onOpen(opened::complete);
                  events.onError((connection, failure) -> errors.incrementAndGet());
                  events.onClose(
                      (connection, code, reason) -> closed.complete(code + ":" + reason));
                }));
  }

  private Future<?> submitPendingSend(ExecutorService producers, WebSocketConnection connection) {
    return producers.submit(
        () -> assertThrows(IOException.class, () -> connection.send(new byte[16 * 1024 * 1024])));
  }

  @Test
  @Timeout(15)
  void settlesBlockedSendWhenTheAppCloses() throws Exception {
    var settled = new CompletableFuture<String>();
    var app = createAppForSettlesBlockedSendWhenTheAppCloses(settled);

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var received = new PausedBinaryMessages();
      var socket = createSocketForSettlesBlockedSendWhenTheAppCloses(client, test, received);

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

  private Shoostr createAppForSettlesBlockedSendWhenTheAppCloses(
      CompletableFuture<String> settled) {
    return new Shoostr()
        .routes(routes ->
            routes.websocket(
                "/bytes",
                events ->
                    events.onText((connection, ignoredString) -> {
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
  }

  private WebSocket createSocketForSettlesBlockedSendWhenTheAppCloses(
      HttpClient client, TestServer test, PausedBinaryMessages received)
      throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
    return client
        .newWebSocketBuilder()
        .buildAsync(new URI("ws", test.baseUri().getRawAuthority(), "/bytes", null, null), received)
        .get(3, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(10)
  void completesTheNormalServerCloseHandshakeBeforeNotifyingItsObserver() throws Exception {
    var closed = new CompletableFuture<Integer>();
    var app = createAppForCompletesTheNormalServerCloseHandshakeBeforeNotifyingItsObserver(closed);

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var socket =
          createSocketForCompletesTheNormalServerCloseHandshakeBeforeNotifyingItsObserver(
              client, test);

      try {
        socket.sendText("finish", true).get(3, TimeUnit.SECONDS);

        assertEquals(1000, closed.get(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  private Shoostr createAppForCompletesTheNormalServerCloseHandshakeBeforeNotifyingItsObserver(
      CompletableFuture<Integer> closed) {
    return new Shoostr()
        .routes(routes ->
            routes.websocket(
                "/close",
                events -> {
                  events.onText((connection, ignoredString) -> connection.close());
                  events.onClose(
                      (ignoredWebSocketConnection, code, ignoredString) -> closed.complete(code));
                }));
  }

  private WebSocket createSocketForCompletesTheNormalServerCloseHandshakeBeforeNotifyingItsObserver(
      HttpClient client, TestServer test)
      throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
    return client
        .newWebSocketBuilder()
        .buildAsync(
            new URI("ws", test.baseUri().getRawAuthority(), "/close", null, null), new Messages())
        .get(3, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(10)
  void reportsNativeSendRejectionWithoutLeavingTheCallerWaiting() throws Exception {
    var server = new AtomicReference<Server>();
    var failure = new CompletableFuture<Throwable>();
    var app = createAppForReportsNativeSendRejectionWithoutLeavingTheCallerWaiting(server, failure);

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var container =
          server.get().getContainedBeans(ServerWebSocketContainer.class).iterator().next();
      container.setMaxFrameSize(32);
      container.setAutoFragment(false);
      var socket = openRejectionSocket(client, test);

      try {
        socket.sendText("rejected", true).get(3, TimeUnit.SECONDS);

        assertInstanceOf(IOException.class, failure.get(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  private Shoostr createAppForReportsNativeSendRejectionWithoutLeavingTheCallerWaiting(
      AtomicReference<Server> server, CompletableFuture<Throwable> failure) {
    return new Shoostr()
        .modifyServer(server::set)
        .routes(routes ->
            routes.websocket(
                "/reject",
                events -> {
                  events.onText((connection, ignoredString) -> connection.send("x".repeat(64)));
                  events.onError((ignoredWebSocketConnection, cause) -> failure.complete(cause));
                }));
  }

  private WebSocket openRejectionSocket(HttpClient client, TestServer test)
      throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
    return client
        .newWebSocketBuilder()
        .buildAsync(
            new URI("ws", test.baseUri().getRawAuthority(), "/reject", null, null), new Messages())
        .get(3, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(10)
  void reportsNativeCloseFrameRejectionAndRetiresConnectionOwnership() throws Exception {
    var server = new AtomicReference<Server>();
    var opened = new CompletableFuture<WebSocketConnection>();
    var closed = new CompletableFuture<Integer>();
    var errors = new LinkedBlockingQueue<Throwable>();
    var app = closeFrameRejectionApplication(server, opened, closed, errors);

    try (Closeable appCleanup = app::close;
        var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var container =
          createContainerForDoesNotRunQueuedCloseObserverAfterApplicationShutdown(server);
      container.setMaxFrameSize(32);
      container.setAutoFragment(false);
      var socket = openRejectionSocket(client, test);

      try {
        var connection = opened.get(3, TimeUnit.SECONDS);
        connection.close(1000, "x".repeat(64));

        var failure = errors.poll(3, TimeUnit.SECONDS);

        assertNotNull(failure);
        assertInstanceOf(
            org.eclipse.jetty.websocket.core.exception.MessageTooLargeException.class, failure);
        closed.get(3, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(3)).until(() -> container.getOpenSessions().isEmpty());
        awaitConnectionRetirement(connection);

        assertFalse(connection.isOpen());
        assertThrows(IOException.class, () -> connection.send("late"));
        assertTrue(errors.isEmpty());
      } finally {
        socket.abort();
      }
    }
  }

  private Shoostr closeFrameRejectionApplication(
      AtomicReference<Server> server,
      CompletableFuture<WebSocketConnection> opened,
      CompletableFuture<Integer> closed,
      LinkedBlockingQueue<Throwable> errors) {
    return new Shoostr()
        .modifyServer(server::set)
        .routes(routes ->
            routes.websocket(
                "/reject",
                events -> {
                  events.onOpen(opened::complete);
                  events.onClose((connection, code, reason) -> closed.complete(code));
                  events.onError((connection, cause) -> errors.add(cause));
                }));
  }

  @Test
  @Timeout(10)
  void closesWithShutdownStatusWhenCallbackThrowsInterruptedException() throws Exception {
    var opened = new CompletableFuture<WebSocketConnection>();
    var closed = new CompletableFuture<Integer>();
    var app =
        createAppForClosesWithShutdownStatusWhenCallbackThrowsInterruptedException(opened, closed);

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var received = new Messages();
      var socket =
          createSocketForClosesWithShutdownStatusWhenCallbackThrowsInterruptedException(
              client, test, received);

      try {
        socket.sendText("fail", true).get(3, TimeUnit.SECONDS);

        assertEquals(1001, received.closeCode.get(3, TimeUnit.SECONDS));
        assertEquals("Processing interrupted", received.closeReason.get(3, TimeUnit.SECONDS));
        assertEquals(1001, closed.get(1, TimeUnit.SECONDS));

        var connection = opened.get(3, TimeUnit.SECONDS);

        awaitConnectionRetirement(connection);
      } finally {
        socket.abort();
      }
    }
  }

  private Shoostr createAppForClosesWithShutdownStatusWhenCallbackThrowsInterruptedException(
      CompletableFuture<WebSocketConnection> opened, CompletableFuture<Integer> closed) {
    return new Shoostr()
        .routes(routes ->
            routes.websocket(
                "/interrupted",
                events -> {
                  events.onOpen(opened::complete);
                  events.onClose((connection, code, reason) -> closed.complete(code));
                  events.onText((ignoredWebSocketConnection, ignoredString) -> {
                    throw new InterruptedException("Fixture interruption");
                  });
                }));
  }

  private WebSocket createSocketForClosesWithShutdownStatusWhenCallbackThrowsInterruptedException(
      HttpClient client, TestServer test, Messages received)
      throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
    return client
        .newWebSocketBuilder()
        .buildAsync(
            new URI("ws", test.baseUri().getRawAuthority(), "/interrupted", null, null), received)
        .get(3, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(10)
  void closesSafelyWhenAnApplicationErrorEscapes() throws Exception {
    var opened = new CompletableFuture<WebSocketConnection>();
    var closed = new CompletableFuture<Integer>();
    var app = createAppForClosesSafelyWhenAnApplicationErrorEscapes(opened, closed);

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var received = new Messages();
      var socket = createSocketForClosesSafelyWhenAnApplicationErrorEscapes(client, test, received);

      try {
        socket.sendText("fail", true).get(3, TimeUnit.SECONDS);

        assertEquals(1011, received.closeCode.get(3, TimeUnit.SECONDS));
        assertEquals("Processing failed", received.closeReason.get(3, TimeUnit.SECONDS));
        assertEquals(1011, closed.get(1, TimeUnit.SECONDS));

        var connection = opened.get(3, TimeUnit.SECONDS);

        awaitConnectionRetirement(connection);
      } finally {
        socket.abort();
      }
    }
  }

  private Shoostr createAppForClosesSafelyWhenAnApplicationErrorEscapes(
      CompletableFuture<WebSocketConnection> opened, CompletableFuture<Integer> closed) {
    return new Shoostr()
        .routes(routes ->
            routes.websocket(
                "/failure",
                events -> {
                  events.onOpen(opened::complete);
                  events.onClose((connection, code, reason) -> closed.complete(code));
                  events.onText((ignoredWebSocketConnection, ignoredString) -> {
                    throw new LinkageError("Private fixture failure");
                  });
                }));
  }

  private WebSocket createSocketForClosesSafelyWhenAnApplicationErrorEscapes(
      HttpClient client, TestServer test, Messages received)
      throws InterruptedException, ExecutionException, TimeoutException, URISyntaxException {
    return client
        .newWebSocketBuilder()
        .buildAsync(
            new URI("ws", test.baseUri().getRawAuthority(), "/failure", null, null), received)
        .get(3, TimeUnit.SECONDS);
  }

  private static final class PausedBinaryMessages implements WebSocket.Listener {
    private final ByteArrayOutputStream bytes;
    private final CompletableFuture<Void> first;
    private final CompletableFuture<Void> release;
    private final CompletableFuture<byte[]> payload;
    private final CompletableFuture<String> closed;
    private boolean paused;

    private PausedBinaryMessages() {
      this.bytes = new ByteArrayOutputStream();
      this.first = new CompletableFuture<>();
      this.release = new CompletableFuture<>();
      this.payload = new CompletableFuture<>();
      this.closed = new CompletableFuture<>();
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

    @Override
    public CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
      if (!payload.isDone()) {
        payload.completeExceptionally(
            new IOException("Connection closed before the binary payload"));
      }

      closed.complete(code + ":" + reason);

      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket socket, Throwable failure) {
      first.completeExceptionally(failure);
      payload.completeExceptionally(failure);
      closed.completeExceptionally(failure);
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
      this.text = new LinkedBlockingQueue<>();
      this.binary = new LinkedBlockingQueue<>();
      this.closeCode = new CompletableFuture<>();
      this.closeReason = new CompletableFuture<>();
      this.textParts = new StringBuilder();
      this.binaryParts = new ByteArrayOutputStream();
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
