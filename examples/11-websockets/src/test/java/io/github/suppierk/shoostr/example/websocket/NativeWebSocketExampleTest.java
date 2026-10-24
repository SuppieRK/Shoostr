package io.github.suppierk.shoostr.example.websocket;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NativeWebSocketExampleTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "https://evil.example",
        "null",
        "not an origin",
        "https://app.example, https://evil.example",
        "duplicate"
      })
  @Timeout(10)
  void rejectsMissingForeignAndAmbiguousOrigins(String origin) throws Exception {
    try (var test = TestServer.start(NativeWebSocketExample.application());
        var client = HttpClient.newHttpClient()) {
      var builder = client.newWebSocketBuilder().subprotocols("chat.v2");
      if ("duplicate".equals(origin)) {
        builder.header("Origin", "https://app.example").header("Origin", "https://app.example");
      } else if (!origin.isEmpty()) {
        builder.header("Origin", origin);
      }

      var failure =
          assertThrows(
              ExecutionException.class,
              () ->
                  builder
                      .buildAsync(
                          new URI(
                              "ws", test.baseUri().getRawAuthority(), "/native/lobby", null, null),
                          new Messages())
                      .thenApply(
                          socket -> {
                            socket.abort();
                            return socket;
                          })
                      .get(3, TimeUnit.SECONDS));
      assertEquals(
          403,
          assertInstanceOf(WebSocketHandshakeException.class, failure.getCause())
              .getResponse()
              .statusCode());
    }
  }

  @Test
  @Timeout(10)
  void selectsAnOfferedSubprotocolAndDemandsEachCompleteTextMessage() throws Exception {
    try (var test = TestServer.start(NativeWebSocketExample.application());
        var client = HttpClient.newHttpClient()) {
      var messages = new Messages();
      var socket = connect(client, test, messages);

      try {
        assertEquals("chat.v2", socket.getSubprotocol());
        socket.sendText("hel", false).get(3, TimeUnit.SECONDS);
        socket.sendText("lo", true).get(3, TimeUnit.SECONDS);
        socket.sendText("again", true).get(3, TimeUnit.SECONDS);
        assertEquals("lobby:hello", messages.text.poll(3, TimeUnit.SECONDS));
        assertEquals("lobby:again", messages.text.poll(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(10)
  void echoesBinaryAfterItsReceiveBufferIsReleasedAndContinuesReceiving() throws Exception {
    try (var test = TestServer.start(NativeWebSocketExample.application());
        var client = HttpClient.newHttpClient()) {
      var messages = new Messages();
      var socket = connect(client, test, messages);

      try {
        socket.sendBinary(ByteBuffer.wrap(new byte[] {0, 1}), false).get(3, TimeUnit.SECONDS);
        socket.sendBinary(ByteBuffer.wrap(new byte[] {(byte) 255}), true).get(3, TimeUnit.SECONDS);
        assertArrayEquals(new byte[] {0, 1, (byte) 255}, messages.binary.poll(3, TimeUnit.SECONDS));
        socket.sendText("after binary", true).get(3, TimeUnit.SECONDS);
        assertEquals("lobby:after binary", messages.text.poll(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"text", "binary"})
  @Timeout(10)
  void closesIncomingMessagesAboveTheConfiguredLimit(String type) throws Exception {
    try (var test = TestServer.start(NativeWebSocketExample.application());
        var client = HttpClient.newHttpClient()) {
      var messages = new Messages();
      var socket = connect(client, test, messages);

      try {
        if ("text".equals(type)) {
          socket.sendText("a".repeat(65), true).get(3, TimeUnit.SECONDS);
        } else {
          socket.sendBinary(ByteBuffer.allocate(65), true).get(3, TimeUnit.SECONDS);
        }

        assertEquals(1009, messages.closeCode.get(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(10)
  void acceptsMessagesAtTheLimitAndAcknowledgesNormalClosure() throws Exception {
    try (var test = TestServer.start(NativeWebSocketExample.application());
        var client = HttpClient.newHttpClient()) {
      var messages = new Messages();
      var socket = connect(client, test, messages);

      try {
        socket.sendText("a".repeat(64), true).get(3, TimeUnit.SECONDS);
        assertEquals("lobby:" + "a".repeat(64), messages.text.poll(3, TimeUnit.SECONDS));
        socket.sendBinary(ByteBuffer.allocate(64), true).get(3, TimeUnit.SECONDS);
        assertArrayEquals(new byte[64], messages.binary.poll(3, TimeUnit.SECONDS));
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "Finished").get(3, TimeUnit.SECONDS);
        assertEquals(1000, messages.closeCode.get(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {32, 33})
  @Timeout(10)
  void limitsMultibyteTextByItsUtf8BytesRatherThanItsCharacterCount(int characters)
      throws Exception {
    try (var test = TestServer.start(NativeWebSocketExample.application());
        var client = HttpClient.newHttpClient()) {
      var messages = new Messages();
      var socket = connect(client, test, messages);

      try {
        socket.sendText("é".repeat(characters), true).get(3, TimeUnit.SECONDS);
        if (characters == 32) {
          assertEquals("lobby:" + "é".repeat(32), messages.text.poll(3, TimeUnit.SECONDS));
        } else {
          assertEquals(1009, messages.closeCode.get(3, TimeUnit.SECONDS));
        }
      } finally {
        socket.abort();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "chat.v1"})
  @Timeout(10)
  void rejectsAHandshakeThatDoesNotOfferTheRequiredSubprotocol(String protocol) throws Exception {
    try (var test = TestServer.start(NativeWebSocketExample.application());
        var client = HttpClient.newHttpClient()) {
      var builder = client.newWebSocketBuilder().header("Origin", "https://app.example");
      if (!protocol.isEmpty()) {
        builder.subprotocols(protocol);
      }

      var failure =
          assertThrows(
              ExecutionException.class,
              () ->
                  builder
                      .buildAsync(
                          new URI(
                              "ws", test.baseUri().getRawAuthority(), "/native/lobby", null, null),
                          new Messages())
                      .thenApply(
                          socket -> {
                            socket.abort();
                            return socket;
                          })
                      .get(3, TimeUnit.SECONDS));
      var handshake = assertInstanceOf(WebSocketHandshakeException.class, failure.getCause());
      assertEquals(400, handshake.getResponse().statusCode());
    }
  }

  private static WebSocket connect(HttpClient client, TestServer test, Messages messages)
      throws Exception {
    return client
        .newWebSocketBuilder()
        .header("Origin", "https://app.example")
        .subprotocols("chat.v1", "chat.v2")
        .buildAsync(
            new URI("ws", test.baseUri().getRawAuthority(), "/native/lobby", null, null), messages)
        .get(3, TimeUnit.SECONDS);
  }

  private static final class Messages implements WebSocket.Listener {
    private final BlockingQueue<String> text;
    private final StringBuilder partial;
    private final BlockingQueue<byte[]> binary;
    private final ByteArrayOutputStream partialBinary;
    private final CompletableFuture<Integer> closeCode;

    private Messages() {
      text = new LinkedBlockingQueue<>();
      partial = new StringBuilder();
      binary = new LinkedBlockingQueue<>();
      partialBinary = new ByteArrayOutputStream();
      closeCode = new CompletableFuture<>();
    }

    @Override
    public void onOpen(WebSocket socket) {
      socket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
      partial.append(data);
      if (last) {
        text.add(partial.toString());
        partial.setLength(0);
      }

      socket.request(1);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
      var bytes = new byte[data.remaining()];
      data.get(bytes);
      partialBinary.writeBytes(bytes);
      if (last) {
        binary.add(partialBinary.toByteArray());
        partialBinary.reset();
      }

      socket.request(1);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
      closeCode.complete(code);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket socket, Throwable failure) {
      closeCode.completeExceptionally(failure);
    }
  }
}
