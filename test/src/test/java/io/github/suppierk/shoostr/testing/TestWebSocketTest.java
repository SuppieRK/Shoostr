package io.github.suppierk.shoostr.testing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.WebSocketConnection;
import io.github.suppierk.shoostr.http.exceptions.ForbiddenException;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class TestWebSocketTest {
  private static final Duration MESSAGE_TIMEOUT = Duration.ofSeconds(3);

  @Test
  void assemblesFragmentedRepliesAndStartsEachMessageWithEmptyBuffers() throws Exception {
    var written = new CompletableFuture<Callback.Completable>();
    var app = new Shoostr();
    app.routes().websocket("/fragmented", (request, upgrade) -> new FragmentedReplies(written));

    try (var test = TestServer.start(app);
        var socket = test.openWebSocket("/fragmented")) {
      assertEquals("hello", socket.receiveText(MESSAGE_TIMEOUT));
      assertEquals("next", socket.receiveText(MESSAGE_TIMEOUT));
      assertArrayEquals(new byte[] {0, (byte) 255}, socket.receiveBinary(MESSAGE_TIMEOUT));
      assertArrayEquals(new byte[] {42}, socket.receiveBinary(MESSAGE_TIMEOUT));
      written.get(3, TimeUnit.SECONDS).get(3, TimeUnit.SECONDS);
    }
  }

  private static Callback.Completable sendFragmentedReplies(Session connection) {
    return Callback.Completable.with(write -> connection.sendPartialText("hel", false, write))
        .compose(write -> connection.sendPartialText("lo", true, write))
        .compose(write -> connection.sendText("next", write))
        .compose(
            write -> connection.sendPartialBinary(ByteBuffer.wrap(new byte[] {0}), false, write))
        .compose(write ->
            connection.sendPartialBinary(ByteBuffer.wrap(new byte[] {(byte) 255}), true, write))
        .compose(write -> connection.sendBinary(ByteBuffer.wrap(new byte[] {42}), write));
  }

  public static final class FragmentedReplies implements Session.Listener {
    private final CompletableFuture<Callback.Completable> written;

    private FragmentedReplies(CompletableFuture<Callback.Completable> written) {
      this.written = written;
    }

    @Override
    public void onWebSocketOpen(Session connection) {
      written.complete(sendFragmentedReplies(connection));
      connection.demand();
    }
  }

  @Test
  void preservesMessageOrderAndLeavesAnUnexpectedMessageAvailable() throws Exception {
    var app = new Shoostr();
    app.routes()
        .websocket(
            "/ordered",
            events ->
                events.onOpen(connection -> {
                  connection.send("first");
                  connection.send(new byte[] {42});
                  connection.close(4001, "finished");
                }));

    try (var test = TestServer.start(app);
        var socket = test.openWebSocket("/ordered")) {
      assertThrows(IOException.class, () -> socket.receiveClose(MESSAGE_TIMEOUT));
      assertEquals("first", socket.receiveText(MESSAGE_TIMEOUT));
      assertThrows(IOException.class, () -> socket.receiveText(MESSAGE_TIMEOUT));
      assertArrayEquals(new byte[] {42}, socket.receiveBinary(MESSAGE_TIMEOUT));

      var close = socket.receiveClose(MESSAGE_TIMEOUT);
      assertEquals(4001, close.code());
      assertEquals("finished", close.reason());
      assertThrows(IOException.class, () -> socket.receiveText(MESSAGE_TIMEOUT));
    }
  }

  @Test
  void sharesFixtureCookiesAndConfiguredHeadersWithTheUpgrade() throws Exception {
    var seen = new AtomicReference<String>();
    var app = new Shoostr();
    app.routes()
        .get("/login", (request, response) -> response.cookie("token", "fixture"))
        .websocket(
            "/room/{name}",
            events ->
                events
                    .beforeUpgrade((request, response) -> seen.set(handshakeInput(request)))
                    .onOpen(connection -> connection.send(connection.path())));

    try (var test = TestServer.start(app)) {
      test.send(request -> request.path("/login"));

      try (var socket =
          test.openWebSocket(
              "/room/hello%20world", builder -> builder.header("X-Test", "configured"))) {
        assertEquals("/room/hello%20world", socket.receiveText(MESSAGE_TIMEOUT));
        assertEquals("fixture|configured", seen.get());
      }
    }
  }

  private static String handshakeInput(Request request) {
    return request.cookie("token").orElseThrow() + "|" + request.header("X-Test").orElseThrow();
  }

  @Test
  void preservesRawPathAndQueryOnTheOutgoingHandshake() throws Exception {
    var app = new Shoostr();
    app.routes()
        .websocket(
            "/room/{name}",
            events ->
                events.beforeUpgrade((request, response) -> {
                  throw new ForbiddenException();
                }));

    try (var test = TestServer.start(app)) {
      var failure =
          assertThrows(
              WebSocketHandshakeException.class,
              () -> test.openWebSocket("/room/hello%20world?q=a%2Bb&x=a+b"));

      assertEquals(403, failure.getResponse().statusCode());
      var uri = failure.getResponse().request().uri();
      assertEquals("/room/hello%20world", uri.getRawPath());
      assertEquals("q=a%2Bb&x=a+b", uri.getRawQuery());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "//localhost/elsewhere", "ws://localhost/elsewhere", "/value#fragment"})
  void rejectsTargetsOutsideTheFixtureBeforeConfiguringTheUpgrade(String target) throws Exception {
    try (var test = TestServer.start(new Shoostr())) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              test.openWebSocket(
                  target, builder -> fail("Invalid target reached the builder callback")));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              test.openWebSocket(
                  target,
                  path -> fail("Invalid target reached the path callback"),
                  builder -> fail("Invalid target reached the handshake callback")));
    }
  }

  @Test
  void closesThePathBuilderAndSkipsHandshakeConfigurationWhenThePathFails() throws Exception {
    var retained = new AtomicReference<TestRequest.PathBuilder>();

    try (var test = TestServer.start(new Shoostr())) {
      assertThrows(
          IllegalStateException.class,
          () ->
              test.openWebSocket(
                  "/rooms/{room}",
                  retained::set,
                  builder -> fail("Incomplete path reached the handshake callback")));
      var closedPath = retained.get();
      assertThrows(IllegalStateException.class, () -> closedPath.pathParam("room", "lobby"));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              test.openWebSocket(
                  "/rooms/{room}",
                  path -> path.pathParam("typo", "lobby"),
                  builder -> fail("Invalid parameter reached the handshake callback")));
    }
  }

  @Test
  void keepsTheSocketUsableAfterReceiveTimeoutAndClosesItWithTheFixture() throws Exception {
    var app = new Shoostr();
    app.routes().websocket("/echo", events -> events.onText(WebSocketConnection::send));

    try (var test = TestServer.start(app)) {
      var socket = test.openWebSocket("/echo");
      assertThrows(IllegalArgumentException.class, () -> socket.receiveText(Duration.ZERO));
      assertThrows(SocketTimeoutException.class, () -> socket.receiveText(Duration.ofMillis(50)));

      socket.sendText("still open");
      assertEquals("still open", socket.receiveText(MESSAGE_TIMEOUT));

      try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
        var receiving = workers.submit(() -> socket.receiveText(Duration.ofSeconds(30)));
        var closingFixture =
            workers.submit(() -> {
              test.close();
              return null;
            });
        var closingSocket = workers.submit(socket::close);

        closingSocket.get(3, TimeUnit.SECONDS);
        closingFixture.get(5, TimeUnit.SECONDS);
        var failure =
            assertThrows(ExecutionException.class, () -> receiving.get(3, TimeUnit.SECONDS));
        assertInstanceOf(IOException.class, failure.getCause());
      }

      assertThrows(IOException.class, () -> socket.receiveText(MESSAGE_TIMEOUT));
      assertThrows(IOException.class, () -> socket.sendText("closed"));
      socket.close();
      test.close();
    }
  }

  @Test
  void closesAnOutstandingUpgradeWithoutWaitingForItsConfiguredDeadline() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);

    try (var test = TestServer.start(waitingApplication(entered, release, () -> {}));
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var opening =
          workers.submit(() ->
              test.openWebSocket(
                  "/waiting", builder -> builder.connectTimeout(Duration.ofSeconds(30))));

      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        var closing =
            workers.submit(() -> {
              test.close();
              return null;
            });

        try {
          var failure =
              assertThrows(ExecutionException.class, () -> opening.get(3, TimeUnit.SECONDS));
          assertInstanceOf(IOException.class, failure.getCause());
        } finally {
          release.countDown();
        }

        closing.get(5, TimeUnit.SECONDS);
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void abortsLateSocketAfterInterruptedHandshakeWait() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var lateClose = new CountDownLatch(1);

    try (var test = TestServer.start(waitingApplication(entered, release, lateClose::countDown))) {
      var opening =
          new FutureTask<>(
              () -> assertThrows(InterruptedException.class, () -> test.openWebSocket("/waiting")));
      var caller = Thread.ofVirtual().start(opening);

      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        caller.interrupt();
        opening.get(3, TimeUnit.SECONDS);
      } finally {
        release.countDown();
      }

      assertTrue(
          lateClose.await(3, TimeUnit.SECONDS), "the server must observe the late socket close");
      assertEquals(204, test.send(request -> request.path("/ready")).statusCode());
    }
  }

  @Test
  void surfacesTheConfiguredHandshakeTimeout() throws Exception {
    var release = new CountDownLatch(1);

    try (var test =
        TestServer.start(waitingApplication(new CountDownLatch(1), release, () -> {}))) {
      try {
        assertThrows(
            HttpTimeoutException.class,
            () ->
                test.openWebSocket(
                    "/waiting", builder -> builder.connectTimeout(Duration.ofMillis(200))));
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void abortsAndUnregistersWhenTheWriteDeadlineExpires() throws Exception {
    var aborted = new AtomicBoolean();
    var unregistered = new AtomicBoolean();
    var cleanup = new IllegalStateException("abort failed");

    Runnable abort =
        () -> {
          aborted.set(true);
          throw cleanup;
        };

    try (var socket = stalledWriter(new CountDownLatch(1), abort, unregistered)) {
      var failure = assertThrows(SocketTimeoutException.class, () -> socket.sendText("pending"));

      assertArrayEquals(new Throwable[] {cleanup}, failure.getSuppressed());
      assertTrue(aborted.get());
      assertTrue(unregistered.get());
      assertThrows(IOException.class, () -> socket.sendText("closed"));
    }
  }

  @Test
  void abortsAndUnregistersWhenTheWriterIsInterrupted() throws Exception {
    var writing = new CountDownLatch(1);
    var aborted = new AtomicBoolean();
    var unregistered = new AtomicBoolean();
    var cleanup = new LinkageError("abort failed");

    Runnable abort =
        () -> {
          aborted.set(true);
          throw cleanup;
        };

    try (var socket = stalledWriter(writing, abort, unregistered)) {
      var sending =
          new FutureTask<>(
              () -> assertThrows(InterruptedException.class, () -> socket.sendText("pending")));
      var caller = Thread.ofVirtual().start(sending);

      try {
        assertTrue(writing.await(3, TimeUnit.SECONDS));
        caller.interrupt();
        var failure = sending.get(3, TimeUnit.SECONDS);
        assertArrayEquals(new Throwable[] {cleanup}, failure.getSuppressed());
      } finally {
        caller.interrupt();
      }

      assertTrue(aborted.get());
      assertTrue(unregistered.get());
      assertThrows(IOException.class, () -> socket.sendText("closed"));
    }
  }

  private static TestWebSocket stalledWriter(
      CountDownLatch writing, Runnable abort, AtomicBoolean unregistered)
      throws IOException, InterruptedException {
    var socket = new TestWebSocket(closed -> unregistered.set(true));
    socket.awaitOpen(CompletableFuture.completedFuture(stalledTransport(writing, abort)));
    return socket;
  }

  private static WebSocket stalledTransport(CountDownLatch writing, Runnable abort) {
    var pendingWrite = new CompletableFuture<WebSocket>();
    return (WebSocket)
        Proxy.newProxyInstance(
            WebSocket.class.getClassLoader(),
            new Class<?>[] {WebSocket.class},
            (socket, method, arguments) ->
                switch (method.getName()) {
                  case "sendText" -> {
                    writing.countDown();
                    yield pendingWrite;
                  }
                  case "abort" -> {
                    abort.run();
                    yield null;
                  }
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  private static Shoostr waitingApplication(
      CountDownLatch entered, CountDownLatch release, Runnable onClose) {
    return new Shoostr()
        .routes(routes ->
            routes
                .get("/ready", (request, response) -> response.status(204))
                .websocket(
                    "/waiting",
                    events ->
                        events
                            .beforeUpgrade((request, response) -> {
                              entered.countDown();
                              release.await(5, TimeUnit.SECONDS);
                            })
                            .onClose((connection, code, reason) -> onClose.run())));
  }
}
