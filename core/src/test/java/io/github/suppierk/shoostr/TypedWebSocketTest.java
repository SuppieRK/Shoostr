package io.github.suppierk.shoostr;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.ByteArrayOutputStream;
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
import java.util.concurrent.Executors;
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
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void suppliesTheSameTypedConnectionToOpeningTextObjectAndCloseCallbacks(boolean virtual)
      throws Exception {
    var opened = new AtomicReference<TypedWebSocketConnection>();
    var closed = new CompletableFuture<TypedWebSocketConnection>();
    var base = new Shoostr();
    if (virtual) {
      base.virtualThreads();
    }

    var app =
        base.codec(new TextCodec())
            .routes(
                routes ->
                    routes.websocket(
                        "/callbacks",
                        socket ->
                            socket
                                .beforeUpgrade(
                                    (request, response) -> {
                                      TypedRequest typedRequest = request;
                                      TypedResponse typedResponse = response;
                                      typedResponse.setHeader("X-Path", typedRequest.path());
                                    })
                                .onOpen(
                                    connection -> {
                                      opened.set(connection);
                                      connection.sendObject("open");
                                    })
                                .onText(
                                    (connection, text) -> {
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
                                    (connection, ignored, ignored2) ->
                                        closed.complete(connection))));

    try (var server = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var messages = new Messages();
      var socket = connect(client, server, "/callbacks", messages);

      try {
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

  @Test
  void decodesFragmentedBinaryObjectsAndEncodesBinaryReplies() throws Exception {
    var app =
        new Shoostr()
            .codec(new TextCodec())
            .routes(
                routes ->
                    routes.websocket(
                        "/objects",
                        socket ->
                            socket.onObject(
                                String.class,
                                (connection, value) -> connection.sendObject("reply:" + value))));

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

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void usesTheLastObjectOrRawBinaryRegistration(boolean objectLast) throws Exception {
    var reads = new AtomicInteger();
    var codec =
        new TextCodec() {
          @Override
          public <T> T read(byte[] source, Class<T> type) throws Exception {
            reads.incrementAndGet();
            return super.read(source, type);
          }
        };
    io.github.suppierk.shoostr.WebSocket.MessageHandler<byte[]> raw = WebSocketConnection::send;
    var app =
        new Shoostr()
            .codec(codec)
            .routes(
                routes ->
                    routes.websocket(
                        "/replace",
                        socket -> {
                          if (objectLast) {
                            socket
                                .onBinary(raw)
                                .onObject(String.class, TypedWebSocketConnection::sendObject);
                          } else {
                            socket
                                .onObject(
                                    String.class,
                                    (ignored3, ignored4) -> fail("Replaced object callback"))
                                .onBinary(raw);
                          }
                        }));

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

  @Test
  void preservesNonUtf8CodecBytesAndBypassesConversionForRawSends() throws Exception {
    var writes = new AtomicInteger();
    var codec =
        new TextCodec() {
          @Override
          public byte[] write(Object value) {
            writes.incrementAndGet();
            return new byte[] {(byte) 0xff, 0};
          }
        };
    var app =
        new Shoostr()
            .codec(codec)
            .routes(
                routes ->
                    routes.websocket(
                        "/bytes",
                        socket ->
                            socket.onOpen(
                                connection -> {
                                  connection.send("raw");
                                  connection.send(new byte[] {1, 2});
                                  connection.sendObject("encoded even though this is a String");
                                })));

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

  @Test
  void retainsDecodeFailureIdentityAndClosesBeforeTheObjectCallback() throws Exception {
    var failure = new IOException("private decoder details");
    var observed = new CompletableFuture<Throwable>();
    var callbacks = new AtomicInteger();
    var order = new LinkedBlockingQueue<String>();
    var codec =
        new TextCodec() {
          @Override
          public <T> T read(byte[] source, Class<T> type) throws IOException {
            throw failure;
          }
        };
    var app =
        new Shoostr()
            .codec(codec)
            .routes(
                routes ->
                    routes.websocket(
                        "/failure",
                        socket ->
                            socket
                                .onObject(
                                    String.class,
                                    (ignored5, ignored6) -> callbacks.incrementAndGet())
                                .onError(
                                    (ignored7, error) -> {
                                      order.add("error");
                                      observed.complete(error);
                                    })
                                .onClose((ignored8, ignored9, ignored10) -> order.add("close"))));

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

  @Test
  void allowsRecoveryFromACaughtEncodeFailureWithoutSubmittingBinaryOutput() throws Exception {
    var failure = new IOException("encoding failed");
    var codec =
        new TextCodec() {
          @Override
          public byte[] write(Object value) throws IOException {
            throw failure;
          }
        };
    var app =
        new Shoostr()
            .codec(codec)
            .routes(
                routes ->
                    routes.websocket(
                        "/recover",
                        socket ->
                            socket.onText(
                                (connection, text) -> {
                                  assertSame(
                                      failure,
                                      assertThrows(
                                          IOException.class, () -> connection.sendObject(text)));
                                  connection.send("recovered");
                                })));

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

  @Test
  void rejectsObjectSendsAfterClosureBeforeCallingTheCodec() throws Exception {
    var writes = new AtomicInteger();
    var checked = new CompletableFuture<Boolean>();
    var codec =
        new TextCodec() {
          @Override
          public byte[] write(Object value) {
            writes.incrementAndGet();
            return new byte[] {1};
          }
        };
    var app =
        new Shoostr()
            .codec(codec)
            .routes(
                routes ->
                    routes.websocket(
                        "/closed",
                        socket ->
                            socket.onOpen(
                                connection -> {
                                  connection.close();
                                  assertThrows(
                                      IOException.class, () -> connection.sendObject("late"));
                                  checked.complete(true);
                                })));

    try (var server = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var socket = connect(client, server, "/closed", new Messages());

      try {
        assertTrue(checked.get(3, TimeUnit.SECONDS));
        assertEquals(0, writes.get());
      } finally {
        socket.abort();
      }
    }
  }

  @Test
  void allowsCloseDuringEncodingAndRejectsTheEncodedBytesBeforeSubmission() throws Exception {
    var encoding = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var opened = new CompletableFuture<TypedWebSocketConnection>();
    var codec =
        new TextCodec() {
          @Override
          public byte[] write(Object value) throws Exception {
            encoding.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return new byte[] {1};
          }
        };
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
        var send =
            producers.submit(
                () -> assertThrows(IOException.class, () -> connection.sendObject("blocked")));
        assertTrue(encoding.await(3, TimeUnit.SECONDS));
        connection.close();
        assertFalse(connection.isOpen());
        assertEquals(1000, messages.closed.get(3, TimeUnit.SECONDS));
        release.countDown();
        send.get(3, TimeUnit.SECONDS);
        assertTrue(messages.binary.isEmpty());
      } finally {
        release.countDown();
        socket.abort();
      }
    }
  }

  @Test
  void rejectsDuplicateConfigurationAndFreezesEveryCallbackAfterRegistration() throws Exception {
    try (var app = new Shoostr()) {
      var routes = app.codec(new TextCodec()).routes();
      var saved = new AtomicReference<TypedWebSocket>();
      routes.websocket("/frozen", saved::set);
      var socket = saved.get();
      assertThrows(
          IllegalStateException.class,
          () -> socket.onObject(String.class, (ignored11, ignored12) -> {}));
      assertThrows(IllegalStateException.class, () -> socket.onText((ignored13, ignored14) -> {}));
      assertThrows(
          IllegalStateException.class, () -> socket.onBinary((ignored15, ignored16) -> {}));
      assertThrows(IllegalStateException.class, () -> socket.onOpen(ignored17 -> {}));
      assertThrows(IllegalStateException.class, () -> socket.onError((ignored18, ignored19) -> {}));
      assertThrows(
          IllegalStateException.class,
          () -> socket.onClose((ignored20, ignored21, ignored22) -> {}));
      assertThrows(
          IllegalStateException.class, () -> socket.beforeUpgrade((ignored23, ignored24) -> {}));
      assertThrows(
          IllegalArgumentException.class,
          () -> routes.websocket("/frozen", ignored25 -> fail("Duplicate configuration")));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              routes.websocket(
                  "/retry",
                  ignored26 -> {
                    throw new IllegalArgumentException("retry");
                  }));
      routes.websocket("/retry", events -> events.onText(WebSocketConnection::send));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectsNullDecodedObjectsAndEncodedBytesThroughTerminalHandling(boolean decoding)
      throws Exception {
    var observed = new CompletableFuture<Throwable>();
    var codec =
        new TextCodec() {
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
    var app =
        new Shoostr()
            .codec(codec)
            .routes(
                routes ->
                    routes.websocket(
                        "/null-result",
                        socket ->
                            socket
                                .onOpen(
                                    connection -> {
                                      if (!decoding) {
                                        connection.sendObject("start");
                                      }
                                    })
                                .onObject(
                                    String.class,
                                    (ignored27, ignored28) ->
                                        fail("Null result must not reach the callback"))
                                .onError((ignored29, failure) -> observed.complete(failure))));

    try (var server = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var messages = new Messages();
      var socket = connect(client, server, "/null-result", messages);

      try {
        if (decoding) {
          socket.sendBinary(ByteBuffer.wrap(new byte[] {1}), true).join();
        }

        assertTrue(observed.get(3, TimeUnit.SECONDS) instanceof NullPointerException);
        assertEquals(1011, messages.closed.get(3, TimeUnit.SECONDS));
        assertTrue(messages.binary.isEmpty());
      } finally {
        socket.abort();
      }
    }
  }

  @Test
  void serializesObjectAndRawSendsAndAllowsInterruptionOfAnEstablishedWaiter() throws Exception {
    var encoding = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var opened = new CompletableFuture<TypedWebSocketConnection>();
    var calls = new AtomicInteger();
    var codec =
        new TextCodec() {
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
    var app =
        new Shoostr()
            .codec(codec)
            .routes(
                routes ->
                    routes.websocket("/concurrent", socket -> socket.onOpen(opened::complete)));

    try (var server = TestServer.start(app);
        var client = HttpClient.newHttpClient();
        var producers = Executors.newVirtualThreadPerTaskExecutor()) {
      var messages = new Messages();
      var socket = connect(client, server, "/concurrent", messages);

      try {
        var connection = opened.get(3, TimeUnit.SECONDS);
        var first =
            producers.submit(
                () -> {
                  connection.sendObject("first");
                  return true;
                });
        assertTrue(encoding.await(3, TimeUnit.SECONDS));
        var rawSent = new CompletableFuture<Void>();
        var rawSender =
            Thread.ofVirtual()
                .start(
                    () -> {
                      try {
                        connection.send("raw");
                        rawSent.complete(null);
                      } catch (Exception failure) {
                        rawSent.completeExceptionally(failure);
                      }
                    });
        await()
            .atMost(Duration.ofSeconds(3))
            .until(() -> rawSender.getState() == Thread.State.WAITING);
        var thirdSent = new CompletableFuture<Void>();
        var third =
            Thread.ofVirtual()
                .start(
                    () -> {
                      try {
                        connection.sendObject("third");
                        thirdSent.complete(null);
                      } catch (Exception failure) {
                        thirdSent.completeExceptionally(failure);
                      }
                    });
        await().atMost(Duration.ofSeconds(3)).until(() -> third.getState() == Thread.State.WAITING);
        var interrupted = new CompletableFuture<Void>();
        var second =
            Thread.ofVirtual()
                .start(
                    () -> {
                      try {
                        assertThrows(
                            InterruptedException.class, () -> connection.sendObject("second"));
                        interrupted.complete(null);
                      } catch (Throwable failure) {
                        interrupted.completeExceptionally(failure);
                      }
                    });
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

  private static WebSocket connect(
      HttpClient client, TestServer server, String path, Messages messages) throws Exception {
    return client
        .newWebSocketBuilder()
        .buildAsync(new URI("ws", server.baseUri().getRawAuthority(), path, null, null), messages)
        .get(3, TimeUnit.SECONDS);
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
      binary = new LinkedBlockingQueue<>();
      text = new LinkedBlockingQueue<>();
      bytes = new ByteArrayOutputStream();
      characters = new StringBuilder();
      closed = new CompletableFuture<>();
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
