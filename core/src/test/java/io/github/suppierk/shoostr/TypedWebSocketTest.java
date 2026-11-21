package io.github.suppierk.shoostr;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.suppierk.shoostr.WebSocket.CloseHandler;
import io.github.suppierk.shoostr.WebSocket.ConnectionHandler;
import io.github.suppierk.shoostr.WebSocket.MessageHandler;
import io.github.suppierk.shoostr.WebSocket.Typed;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class TypedWebSocketTest {
  private static WebSocket connect(
      HttpClient client, TestServer server, String path, Messages messages) throws Exception {
    return client
        .newWebSocketBuilder()
        .buildAsync(new URI("ws", server.baseUri().getRawAuthority(), path, null, null), messages)
        .get(3, TimeUnit.SECONDS);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void suppliesTheSameTypedConnectionToOpeningTextObjectAndCloseCallbacks(boolean virtual)
      throws Exception {
    var opened = new AtomicReference<WebSocketConnection.Typed>();
    var closed = new CompletableFuture<WebSocketConnection.Typed>();
    var admitted = new CompletableFuture<String>();
    var configuredOptions = new Options();
    configuredOptions.setVirtualThreads(virtual);

    var base = new Shoostr(configuredOptions);

    var app =
        createAppForSuppliesTheSameTypedConnectionToOpeningTextObjectAndCloseCallbacks(
            base, opened, closed, admitted);

    try (var server = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var messages = new Messages();
      var socket = connect(client, server, "/callbacks", messages);

      try {
        assertEquals("/callbacks", admitted.get(3, TimeUnit.SECONDS));
        assertArrayEquals(
            new byte[] {111, 112, 101, 110}, messages.binary.poll(3, TimeUnit.SECONDS));

        socket.sendText("text", true).join();

        assertArrayEquals(
            new byte[] {116, 101, 120, 116}, messages.binary.poll(3, TimeUnit.SECONDS));

        socket.sendBinary(ByteBuffer.wrap(new byte[] {98}), true).join();

        assertArrayEquals(new byte[] {98}, messages.binary.poll(3, TimeUnit.SECONDS));

        socket.sendClose(1000, "done").join();

        assertSame(opened.get(), closed.get(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  private Shoostr createAppForSuppliesTheSameTypedConnectionToOpeningTextObjectAndCloseCallbacks(
      Shoostr base,
      AtomicReference<WebSocketConnection.Typed> opened,
      CompletableFuture<WebSocketConnection.Typed> closed,
      CompletableFuture<String> admitted) {
    return base.codec(new TextCodec())
        .routes(routes ->
            routes.websocket(
                "/callbacks",
                socket ->
                    socket
                        .beforeUpgrade((request, response) -> {
                          Request.Typed typedRequest = request;
                          Response.Typed typedResponse = response;
                          typedResponse.setHeader("X-Path", typedRequest.path());
                          admitted.complete(typedRequest.path());
                        })
                        .onOpen(connection -> {
                          opened.set(connection);
                          connection.sendObject("open");
                        })
                        .onText((connection, text) -> {
                          assertSame(opened.get(), connection);

                          connection.sendObject(text);
                        })
                        .onObject(
                            String.class,
                            (connection, value) -> {
                              assertSame(opened.get(), connection);

                              connection.sendObject(value);
                            })
                        .onClose(
                            (connection, ignored, ignoredString) -> closed.complete(connection))));
  }

  @Test
  void decodesFragmentedBinaryObjectsAndEncodesBinaryReplies() throws Exception {
    var app = createAppForDecodesFragmentedBinaryObjectsAndEncodesBinaryReplies();

    try (var server = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var messages = new Messages();
      var socket = connect(client, server, "/objects", messages);

      try {
        socket.sendBinary(ByteBuffer.wrap(new byte[] {(byte) 0xc3}), false).join();
        socket.sendBinary(ByteBuffer.wrap(new byte[] {(byte) 0xa9}), true).join();

        assertArrayEquals(
            "reply:é".getBytes(StandardCharsets.UTF_8), messages.binary.poll(3, TimeUnit.SECONDS));
        assertEquals(0, messages.text.size());
      } finally {
        socket.abort();
      }
    }
  }

  private Shoostr createAppForDecodesFragmentedBinaryObjectsAndEncodesBinaryReplies() {
    return new Shoostr()
        .codec(new TextCodec())
        .routes(routes ->
            routes.websocket(
                "/objects",
                socket ->
                    socket.onObject(
                        String.class,
                        (connection, value) -> connection.sendObject("reply:" + value))));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void usesTheLastObjectOrRawBinaryRegistration(boolean objectLast) throws Exception {
    var reads = new AtomicInteger();
    var codec = createCodecForUsesTheLastObjectOrRawBinaryRegistration(reads);
    MessageHandler<byte[]> raw = WebSocketConnection::send;
    var app = createAppForUsesTheLastObjectOrRawBinaryRegistration(codec, objectLast, raw);

    try (var server = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var messages = new Messages();
      var socket = connect(client, server, "/replace", messages);

      try {
        socket.sendBinary(ByteBuffer.wrap(new byte[] {97}), true).join();

        assertArrayEquals(new byte[] {97}, messages.binary.poll(3, TimeUnit.SECONDS));
        assertEquals(objectLast ? 1 : 0, reads.get());
      } finally {
        socket.abort();
      }
    }
  }

  private TextCodec createCodecForUsesTheLastObjectOrRawBinaryRegistration(AtomicInteger reads) {
    return new TextCodec() {
      @Override
      public <T> T read(byte[] source, Class<T> type) throws Exception {
        reads.incrementAndGet();

        return super.read(source, type);
      }
    };
  }

  private Shoostr createAppForUsesTheLastObjectOrRawBinaryRegistration(
      TextCodec codec, boolean objectLast, MessageHandler<byte[]> raw) {
    return new Shoostr()
        .codec(codec)
        .routes(routes ->
            routes.websocket(
                "/replace",
                socket -> {
                  if (objectLast) {
                    socket
                        .onBinary(raw)
                        .onObject(String.class, WebSocketConnection.Typed::sendObject);
                  } else {
                    socket
                        .onObject(
                            String.class,
                            (ignoredTypedWebSocketConnection, ignoredString) ->
                                fail("Replaced object callback"))
                        .onBinary(raw);
                  }
                }));
  }

  @Test
  void preservesNonUtf8CodecBytesAndBypassesConversionForRawSends() throws Exception {
    var writes = new AtomicInteger();
    var codec = createCodecForPreservesNonUtf8CodecBytesAndBypassesConversionForRawSends(writes);
    var app = createAppForPreservesNonUtf8CodecBytesAndBypassesConversionForRawSends(codec);

    try (var server = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var messages = new Messages();
      var socket = connect(client, server, "/bytes", messages);

      try {
        assertEquals("raw", messages.text.poll(3, TimeUnit.SECONDS));
        assertArrayEquals(new byte[] {1, 2}, messages.binary.poll(3, TimeUnit.SECONDS));
        assertArrayEquals(new byte[] {(byte) 0xff, 0}, messages.binary.poll(3, TimeUnit.SECONDS));
        assertEquals(1, writes.get());
      } finally {
        socket.abort();
      }
    }
  }

  private TextCodec createCodecForPreservesNonUtf8CodecBytesAndBypassesConversionForRawSends(
      AtomicInteger writes) {
    return new TextCodec() {
      @Override
      public byte[] write(Object value) {
        writes.incrementAndGet();

        return new byte[] {(byte) 0xff, 0};
      }
    };
  }

  private Shoostr createAppForPreservesNonUtf8CodecBytesAndBypassesConversionForRawSends(
      TextCodec codec) {
    return new Shoostr()
        .codec(codec)
        .routes(routes ->
            routes.websocket(
                "/bytes",
                socket ->
                    socket.onOpen(connection -> {
                      connection.send("raw");
                      connection.send(new byte[] {1, 2});
                      connection.sendObject("encoded even though this is a String");
                    })));
  }

  @Test
  void retainsDecodeFailureIdentityAndClosesBeforeTheObjectCallback() throws Exception {
    var failure = new IOException("private decoder details");
    var observed = new CompletableFuture<Throwable>();
    var callbacks = new AtomicInteger();
    var order = new LinkedBlockingQueue<String>();
    var codec = createCodecForRetainsDecodeFailureIdentityAndClosesBeforeTheObjectCallback(failure);
    var app =
        createAppForRetainsDecodeFailureIdentityAndClosesBeforeTheObjectCallback(
            codec, callbacks, order, observed);

    try (var server = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var messages = new Messages();
      var socket = connect(client, server, "/failure", messages);

      try {
        socket.sendBinary(ByteBuffer.wrap(new byte[] {1}), true).join();

        assertSame(failure, observed.get(3, TimeUnit.SECONDS));
        assertEquals(1011, messages.closed.get(3, TimeUnit.SECONDS));
        assertEquals("error", order.poll(3, TimeUnit.SECONDS));
        assertEquals("close", order.poll(3, TimeUnit.SECONDS));
        assertEquals(0, callbacks.get());
      } finally {
        socket.abort();
      }
    }
  }

  private TextCodec createCodecForRetainsDecodeFailureIdentityAndClosesBeforeTheObjectCallback(
      IOException failure) {
    return new TextCodec() {
      @Override
      public <T> T read(byte[] source, Class<T> type) throws IOException {
        throw failure;
      }
    };
  }

  private Shoostr createAppForRetainsDecodeFailureIdentityAndClosesBeforeTheObjectCallback(
      TextCodec codec,
      AtomicInteger callbacks,
      LinkedBlockingQueue<String> order,
      CompletableFuture<Throwable> observed) {
    return new Shoostr()
        .codec(codec)
        .routes(routes ->
            routes.websocket(
                "/failure",
                socket ->
                    socket
                        .onObject(
                            String.class,
                            (ignoredTypedWebSocketConnection, ignoredString) ->
                                callbacks.incrementAndGet())
                        .onError((ignoredTypedWebSocketConnection, error) -> {
                          order.add("error");
                          observed.complete(error);
                        })
                        .onClose((ignoredTypedWebSocketConnection, ignoredIndex, ignoredString) ->
                            order.add("close"))));
  }

  @Test
  void allowsRecoveryFromCaughtEncodeFailureWithoutSubmittingBinaryOutput() throws Exception {
    var failure = new IOException("encoding failed");
    var codec =
        createCodecForAllowsRecoveryFromCaughtEncodeFailureWithoutSubmittingBinaryOutput(failure);
    var app =
        createAppForAllowsRecoveryFromCaughtEncodeFailureWithoutSubmittingBinaryOutput(
            codec, failure);

    try (var server = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var messages = new Messages();
      var socket = connect(client, server, "/recover", messages);

      try {
        socket.sendText("attempt", true).join();

        assertEquals("recovered", messages.text.poll(3, TimeUnit.SECONDS));
        assertTrue(messages.binary.isEmpty());
        assertFalse(socket.isInputClosed());
      } finally {
        socket.abort();
      }
    }
  }

  private TextCodec
      createCodecForAllowsRecoveryFromCaughtEncodeFailureWithoutSubmittingBinaryOutput(
          IOException failure) {
    return new TextCodec() {
      @Override
      public byte[] write(Object value) throws IOException {
        throw failure;
      }
    };
  }

  private Shoostr createAppForAllowsRecoveryFromCaughtEncodeFailureWithoutSubmittingBinaryOutput(
      TextCodec codec, IOException failure) {
    return new Shoostr()
        .codec(codec)
        .routes(routes ->
            routes.websocket(
                "/recover",
                socket ->
                    socket.onText((connection, text) -> {
                      assertSame(
                          failure,
                          assertThrows(IOException.class, () -> connection.sendObject(text)));

                      connection.send("recovered");
                    })));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectsInterruptedObjectSendsBeforeEncoding(boolean closed) throws Exception {
    var writes = new AtomicInteger();
    var checked = new CompletableFuture<Exception>();
    var interrupted = new CompletableFuture<Boolean>();
    var codec = createCodecForRejectsInterruptedObjectSendsBeforeEncoding(writes);
    var app =
        createAppForRejectsInterruptedObjectSendsBeforeEncoding(
            codec, closed, checked, interrupted);

    try (var server = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var socket = connect(client, server, "/closed", new Messages());

      try {
        var failure = checked.get(3, TimeUnit.SECONDS);
        if (closed) {
          assertInstanceOf(IOException.class, failure);
        } else {
          assertInstanceOf(InterruptedException.class, failure);
        }

        assertEquals(closed, interrupted.get(3, TimeUnit.SECONDS));
        assertEquals(0, writes.get());
      } finally {
        socket.abort();
      }
    }
  }

  private TextCodec createCodecForRejectsInterruptedObjectSendsBeforeEncoding(
      AtomicInteger writes) {
    return new TextCodec() {
      @Override
      public byte[] write(Object value) {
        writes.incrementAndGet();

        return new byte[] {1};
      }
    };
  }

  private Shoostr createAppForRejectsInterruptedObjectSendsBeforeEncoding(
      TextCodec codec,
      boolean closed,
      CompletableFuture<Exception> checked,
      CompletableFuture<Boolean> interrupted) {
    return new Shoostr()
        .codec(codec)
        .routes(routes ->
            routes.websocket(
                "/closed",
                socket ->
                    socket.onOpen(connection -> {
                      if (closed) {
                        connection.close();
                      }

                      // Native close timing must not decide the admission result.
                      Thread.currentThread().interrupt();

                      try {
                        connection.sendObject("late");
                        checked.complete(null);
                      } catch (Exception failure) {
                        checked.complete(failure);
                      } finally {
                        interrupted.complete(Thread.interrupted());
                      }
                    })));
  }

  @Test
  void allowsCloseDuringEncodingAndRejectsTheEncodedBytesBeforeSubmission() throws Exception {
    var encoding = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var opened = new CompletableFuture<WebSocketConnection.Typed>();
    var calls = new AtomicInteger();
    var codec =
        createCodecForAllowsCloseDuringEncodingAndRejectsTheEncodedBytesBeforeSubmission(
            calls, encoding, release);
    var app =
        new Shoostr()
            .codec(codec)
            .routes(
                routes -> routes.websocket("/closing", socket -> socket.onOpen(opened::complete)));

    try (var server = TestServer.start(app);
        var client = HttpClient.newHttpClient();
        var producers = Executors.newVirtualThreadPerTaskExecutor()) {
      var messages = new Messages();
      var socket = connect(client, server, "/closing", messages);

      try {
        var connection = opened.get(3, TimeUnit.SECONDS);
        final var send =
            producers.submit(
                () -> assertThrows(IOException.class, () -> connection.sendObject("blocked")));

        assertTrue(encoding.await(3, TimeUnit.SECONDS));

        var queuedSend = new CompletableFuture<Void>();
        var queued =
            createThirdForSerializesObjectAndRawSendsAndAllowsInterruptionOfAnEstablishedWaiter(
                connection, queuedSend);
        await()
            .atMost(Duration.ofSeconds(3))
            .until(() -> queued.getState() == Thread.State.WAITING);

        connection.close();

        assertFalse(connection.isOpen());
        assertEquals(1000, messages.closed.get(3, TimeUnit.SECONDS));

        release.countDown();
        send.get(3, TimeUnit.SECONDS);

        var queuedFailure =
            assertThrows(ExecutionException.class, () -> queuedSend.get(3, TimeUnit.SECONDS));
        assertInstanceOf(IOException.class, queuedFailure.getCause());
        assertEquals(1, calls.get());
        assertTrue(messages.binary.isEmpty());
      } finally {
        release.countDown();
        socket.abort();
      }
    }
  }

  private TextCodec
      createCodecForAllowsCloseDuringEncodingAndRejectsTheEncodedBytesBeforeSubmission(
          AtomicInteger calls, CountDownLatch encoding, CountDownLatch release) {
    return new TextCodec() {
      @Override
      public byte[] write(Object value) throws Exception {
        calls.incrementAndGet();
        encoding.countDown();

        assertTrue(release.await(5, TimeUnit.SECONDS));

        return new byte[] {1};
      }
    };
  }

  @Test
  void rejectsDuplicateConfigurationAndFreezesEveryCallbackAfterRegistration() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close) {
        var routes = app.codec(new TextCodec()).routes();
        var saved = new AtomicReference<Typed>();
        routes.websocket(
            "/frozen",
            socket -> {
              assertFluentCallbackRegistration(socket);
              saved.set(socket);
            });
        var socket = saved.get();

        assertThrows(
            IllegalStateException.class,
            () ->
                socket.onObject(
                    String.class, (ignoredTypedWebSocketConnection, ignoredString) -> {}));
        assertThrows(
            IllegalStateException.class,
            () -> socket.onText((ignoredTypedWebSocketConnection, ignoredString) -> {}));
        assertThrows(
            IllegalStateException.class,
            () -> socket.onBinary((ignoredTypedWebSocketConnection, ignoredByteValues) -> {}));
        assertThrows(
            IllegalStateException.class,
            () -> socket.onOpen(ignoredTypedWebSocketConnection -> {}));
        assertThrows(
            IllegalStateException.class,
            () -> socket.onError((ignoredTypedWebSocketConnection, ignoredFailure) -> {}));
        assertThrows(
            IllegalStateException.class,
            () ->
                socket.onClose(
                    (ignoredTypedWebSocketConnection, ignoredIndex, ignoredString) -> {}));
        assertThrows(
            IllegalStateException.class,
            () -> socket.beforeUpgrade((ignoredTypedRequest, ignoredTypedResponse) -> {}));
        assertThrows(
            IllegalArgumentException.class,
            () ->
                routes.websocket(
                    "/frozen", ignoredTypedWebSocket -> fail("Duplicate configuration")));
        assertThrows(
            IllegalArgumentException.class,
            () ->
                routes.websocket(
                    "/retry",
                    ignoredTypedWebSocket -> {
                      throw new IllegalArgumentException("retry");
                    }));

        routes.websocket("/retry", events -> events.onText(WebSocketConnection::send));
      }
    }
  }

  private void assertFluentCallbackRegistration(Typed socket) {
    Handler admission = (request, response) -> {};
    assertSame(socket, socket.beforeUpgrade(admission));

    ConnectionHandler opening = connection -> {};
    assertSame(socket, socket.onOpen(opening));

    MessageHandler<String> text = (connection, message) -> {};
    assertSame(socket, socket.onText(text));

    MessageHandler<byte[]> binary = (connection, bytes) -> {};
    assertSame(socket, socket.onBinary(binary));
    assertSame(socket, socket.onBinary((connection, bytes) -> {}));

    MessageHandler<Throwable> error = (connection, failure) -> {};
    assertSame(socket, socket.onError(error));

    CloseHandler close = (connection, code, reason) -> {};
    assertSame(socket, socket.onClose(close));
    assertSame(socket, socket.onClose((connection, code, reason) -> {}));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectsNullDecodedObjectsAndEncodedBytesThroughTerminalHandling(boolean decoding)
      throws Exception {
    var observed = new CompletableFuture<Throwable>();
    var codec = createCodecForRejectsNullDecodedObjectsAndEncodedBytesThroughTerminalHandling();
    var app = nullCodecApplication(codec, decoding, observed);

    try (var server = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var messages = new Messages();
      var socket = connect(client, server, "/null-result", messages);

      try {
        if (decoding) {
          socket.sendBinary(ByteBuffer.wrap(new byte[] {1}), true).join();
        }

        assertInstanceOf(NullPointerException.class, observed.get(3, TimeUnit.SECONDS));
        assertEquals(1011, messages.closed.get(3, TimeUnit.SECONDS));
        assertTrue(messages.binary.isEmpty());
      } finally {
        socket.abort();
      }
    }
  }

  private TextCodec
      createCodecForRejectsNullDecodedObjectsAndEncodedBytesThroughTerminalHandling() {
    return new TextCodec() {
      @Override
      @SuppressWarnings("NullAway")
      public <T> T read(byte[] source, Class<T> type) {
        return null;
      }

      @Override
      @SuppressWarnings("NullAway")
      public byte[] write(Object value) {
        return null;
      }
    };
  }

  private Shoostr nullCodecApplication(
      TextCodec codec, boolean decoding, CompletableFuture<Throwable> observed) {
    return new Shoostr()
        .codec(codec)
        .routes(routes ->
            routes.websocket(
                "/null-result",
                socket ->
                    socket
                        .onOpen(connection -> {
                          if (!decoding) {
                            connection.sendObject("start");
                          }
                        })
                        .onObject(
                            String.class,
                            (ignoredTypedWebSocketConnection, ignoredString) ->
                                fail("Null result must not reach the callback"))
                        .onError((ignoredTypedWebSocketConnection, failure) ->
                            observed.complete(failure))));
  }

  @Test
  void serializesObjectAndRawSendsAndAllowsInterruptionOfAnEstablishedWaiter() throws Exception {
    var encoding = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var opened = new CompletableFuture<WebSocketConnection.Typed>();
    var calls = new AtomicInteger();
    var codec =
        createCodecForSerializesObjectAndRawSendsAndAllowsInterruptionOfAnEstablishedWaiter(
            calls, encoding, release);
    var app =
        createAppForSerializesObjectAndRawSendsAndAllowsInterruptionOfAnEstablishedWaiter(
            codec, opened);

    try (var server = TestServer.start(app);
        var client = HttpClient.newHttpClient();
        var producers = Executors.newVirtualThreadPerTaskExecutor()) {
      var messages = new Messages();
      var socket = connect(client, server, "/concurrent", messages);

      try {
        var connection = opened.get(3, TimeUnit.SECONDS);
        final var first =
            submitFirstForSerializesObjectAndRawSendsAndAllowsInterruptionOfAnEstablishedWaiter(
                producers, connection);

        assertTrue(encoding.await(3, TimeUnit.SECONDS));

        var rawSent = new CompletableFuture<Void>();
        var rawSender =
            createRawSenderForSerializesObjectAndRawSendsAndAllowsInterruptionOfAnEstablishedWaiter(
                connection, rawSent);
        await()
            .atMost(Duration.ofSeconds(3))
            .until(() -> rawSender.getState() == Thread.State.WAITING);
        var thirdSent = new CompletableFuture<Void>();
        var third =
            createThirdForSerializesObjectAndRawSendsAndAllowsInterruptionOfAnEstablishedWaiter(
                connection, thirdSent);
        await().atMost(Duration.ofSeconds(3)).until(() -> third.getState() == Thread.State.WAITING);
        var interrupted = new CompletableFuture<Void>();
        var second =
            createSecondForSerializesObjectAndRawSendsAndAllowsInterruptionOfAnEstablishedWaiter(
                connection, interrupted);
        await()
            .atMost(Duration.ofSeconds(3))
            .until(() -> second.getState() == Thread.State.WAITING);
        second.interrupt();
        interrupted.get(3, TimeUnit.SECONDS);

        assertEquals(1, calls.get());
        assertTrue(messages.text.isEmpty());
        assertTrue(messages.binary.isEmpty());

        release.countDown();

        assertTrue(first.get(3, TimeUnit.SECONDS));

        rawSent.get(3, TimeUnit.SECONDS);
        thirdSent.get(3, TimeUnit.SECONDS);

        assertEquals("raw", messages.text.poll(3, TimeUnit.SECONDS));
        assertArrayEquals(
            new byte[] {102, 105, 114, 115, 116}, messages.binary.poll(3, TimeUnit.SECONDS));
        assertArrayEquals(
            new byte[] {116, 104, 105, 114, 100}, messages.binary.poll(3, TimeUnit.SECONDS));
        assertEquals(2, calls.get());
        assertTrue(connection.isOpen());
      } finally {
        release.countDown();
        socket.abort();
      }
    }
  }

  private TextCodec
      createCodecForSerializesObjectAndRawSendsAndAllowsInterruptionOfAnEstablishedWaiter(
          AtomicInteger calls, CountDownLatch encoding, CountDownLatch release) {
    return new TextCodec() {
      @Override
      public byte[] write(Object value) throws Exception {
        calls.incrementAndGet();
        if ("first".equals(value)) {
          encoding.countDown();

          assertTrue(release.await(10, TimeUnit.SECONDS));
        }

        return super.write(value);
      }
    };
  }

  private Shoostr createAppForSerializesObjectAndRawSendsAndAllowsInterruptionOfAnEstablishedWaiter(
      TextCodec codec, CompletableFuture<WebSocketConnection.Typed> opened) {
    return new Shoostr()
        .codec(codec)
        .routes(
            routes -> routes.websocket("/concurrent", socket -> socket.onOpen(opened::complete)));
  }

  private Future<Boolean>
      submitFirstForSerializesObjectAndRawSendsAndAllowsInterruptionOfAnEstablishedWaiter(
          ExecutorService producers, WebSocketConnection.Typed connection) {
    return producers.submit(() -> {
      connection.sendObject("first");

      return true;
    });
  }

  private Thread
      createRawSenderForSerializesObjectAndRawSendsAndAllowsInterruptionOfAnEstablishedWaiter(
          WebSocketConnection.Typed connection, CompletableFuture<Void> rawSent) {
    return Thread.ofVirtual()
        .start(() -> {
          try {
            connection.send("raw");
            rawSent.complete(null);
          } catch (Exception failure) {
            rawSent.completeExceptionally(failure);
          }
        });
  }

  private Thread
      createThirdForSerializesObjectAndRawSendsAndAllowsInterruptionOfAnEstablishedWaiter(
          WebSocketConnection.Typed connection, CompletableFuture<Void> thirdSent) {
    return Thread.ofVirtual()
        .start(() -> {
          try {
            connection.sendObject("third");
            thirdSent.complete(null);
          } catch (Exception failure) {
            thirdSent.completeExceptionally(failure);
          }
        });
  }

  private Thread
      createSecondForSerializesObjectAndRawSendsAndAllowsInterruptionOfAnEstablishedWaiter(
          WebSocketConnection.Typed connection, CompletableFuture<Void> interrupted) {
    return Thread.ofVirtual()
        .start(() -> {
          try {
            assertThrows(InterruptedException.class, () -> connection.sendObject("second"));

            interrupted.complete(null);
          } catch (Throwable failure) {
            interrupted.completeExceptionally(failure);
          }
        });
  }

  private static class TextCodec implements Codec {
    @Override
    public <T> T read(byte[] source, Class<T> type) throws Exception {
      return type.cast(new String(source, StandardCharsets.UTF_8));
    }

    @Override
    public byte[] write(Object value) throws Exception {
      return value.toString().getBytes(StandardCharsets.UTF_8);
    }
  }

  private static final class Messages implements WebSocket.Listener {
    private final LinkedBlockingQueue<byte[]> binary;
    private final LinkedBlockingQueue<String> text;
    private final ByteArrayOutputStream bytes;
    private final StringBuilder characters;
    private final CompletableFuture<Integer> closed;

    private Messages() {
      this.binary = new LinkedBlockingQueue<>();
      this.text = new LinkedBlockingQueue<>();
      this.bytes = new ByteArrayOutputStream();
      this.characters = new StringBuilder();
      this.closed = new CompletableFuture<>();
    }

    @Override
    public CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
      closed.complete(code);

      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
      var part = new byte[data.remaining()];
      data.get(part);
      bytes.writeBytes(part);
      if (last) {
        binary.add(bytes.toByteArray());
        bytes.reset();
      }

      socket.request(1);

      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
      characters.append(data);
      if (last) {
        text.add(characters.toString());
        characters.setLength(0);
      }

      socket.request(1);

      return CompletableFuture.completedFuture(null);
    }
  }
}
