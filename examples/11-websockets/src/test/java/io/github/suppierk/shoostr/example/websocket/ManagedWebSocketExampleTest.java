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
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ManagedWebSocketExampleTest {
  @Test
  @Timeout(15)
  void handlesCompleteTextMessagesInOrderWithAnIndependentCounterForEachConnection()
      throws Exception {
    try (var test = TestServer.start(ManagedWebSocketExample.application());
        var client = HttpClient.newHttpClient()) {
      var firstMessages = new Messages();
      var secondMessages = new Messages();
      var first = connect(client, test, firstMessages);

      try {
        var second = connect(client, test, secondMessages);

        try {
          first.sendText("hel", false).get(3, TimeUnit.SECONDS);
          first.sendText("lo", true).get(3, TimeUnit.SECONDS);
          first.sendText("again", true).get(3, TimeUnit.SECONDS);
          second.sendText("other", true).get(3, TimeUnit.SECONDS);

          assertEquals("lobby:1:hello", firstMessages.text.poll(3, TimeUnit.SECONDS));
          assertEquals("lobby:2:again", firstMessages.text.poll(3, TimeUnit.SECONDS));
          assertEquals("lobby:1:other", secondMessages.text.poll(3, TimeUnit.SECONDS));
        } finally {
          second.abort();
        }
      } finally {
        first.abort();
      }
    }
  }

  @ParameterizedTest
  @MethodSource("rejectedOrigins")
  @Timeout(10)
  void rejectsMissingForeignOrAmbiguousOriginsBeforeUpgrading(List<String> origins)
      throws Exception {
    try (var test = TestServer.start(ManagedWebSocketExample.application());
        var client = HttpClient.newHttpClient()) {
      var builder = client.newWebSocketBuilder();
      origins.forEach(origin -> builder.header("Origin", origin));

      var failure =
          assertThrows(
              ExecutionException.class,
              () ->
                  builder
                      .buildAsync(
                          new URI(
                              "ws", test.baseUri().getRawAuthority(), "/rooms/lobby", null, null),
                          new Messages())
                      .thenApply(
                          socket -> {
                            socket.abort();
                            return socket;
                          })
                      .get(3, TimeUnit.SECONDS));

      var handshake = assertInstanceOf(WebSocketHandshakeException.class, failure.getCause());
      assertEquals(403, handshake.getResponse().statusCode());
    }
  }

  @Test
  @Timeout(10)
  void echoesCompleteBinaryBytesAndAcknowledgesNormalClosure() throws Exception {
    try (var test = TestServer.start(ManagedWebSocketExample.application());
        var client = HttpClient.newHttpClient()) {
      var messages = new Messages();
      var socket = connect(client, test, messages);

      try {
        socket.sendBinary(ByteBuffer.wrap(new byte[] {0, 1}), false).get(3, TimeUnit.SECONDS);
        socket.sendBinary(ByteBuffer.wrap(new byte[] {(byte) 255}), true).get(3, TimeUnit.SECONDS);
        assertArrayEquals(new byte[] {0, 1, (byte) 255}, messages.binary.poll(3, TimeUnit.SECONDS));

        socket.sendClose(WebSocket.NORMAL_CLOSURE, "Finished").get(3, TimeUnit.SECONDS);
        assertEquals(1000, messages.closeCode.get(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(10)
  void reportsAnEmptyMessageWithoutClosingOrIncrementingItsCounter() throws Exception {
    try (var test = TestServer.start(ManagedWebSocketExample.application());
        var client = HttpClient.newHttpClient()) {
      var messages = new Messages();
      var socket = connect(client, test, messages);

      try {
        socket.sendText(" ", true).get(3, TimeUnit.SECONDS);
        assertEquals("error: message required", messages.text.poll(3, TimeUnit.SECONDS));
        socket.sendText("hello", true).get(3, TimeUnit.SECONDS);
        assertEquals("lobby:1:hello", messages.text.poll(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(10)
  void closesAnUnexpectedHandlerFailureWithoutSendingItsPrivateMessage() throws Exception {
    var app = ManagedWebSocketExample.application();
    app.routes()
        .websocket(
            "/fixture/failure",
            socket ->
                socket.onText(
                    (ignored, ignored2) -> {
                      throw new IllegalStateException("private storage credential");
                    }));

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var messages = new Messages();
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/fixture/failure", null, null),
                  messages)
              .get(3, TimeUnit.SECONDS);

      try {
        socket.sendText("hello", true).get(3, TimeUnit.SECONDS);
        assertEquals(1011, messages.closeCode.get(3, TimeUnit.SECONDS));
        assertEquals("Processing failed", messages.closeReason.get(3, TimeUnit.SECONDS));
        assertEquals(0, messages.text.size());
      } finally {
        socket.abort();
      }
    }
  }

  private static Stream<List<String>> rejectedOrigins() {
    return Stream.of(
        List.of(),
        List.of("https://other.example"),
        List.of("null"),
        List.of("https://app.example", "https://app.example"),
        List.of("https://app.example, https://other.example"));
  }

  private static WebSocket connect(HttpClient client, TestServer test, Messages messages)
      throws Exception {
    return client
        .newWebSocketBuilder()
        .header("Origin", "https://app.example")
        .buildAsync(
            new URI("ws", test.baseUri().getRawAuthority(), "/rooms/lobby", null, null), messages)
        .get(3, TimeUnit.SECONDS);
  }

  private static final class Messages implements WebSocket.Listener {
    private final BlockingQueue<String> text;
    private final StringBuilder partial;
    private final BlockingQueue<byte[]> binary;
    private final ByteArrayOutputStream partialBinary;
    private final CompletableFuture<Integer> closeCode;
    private final CompletableFuture<String> closeReason;

    private Messages() {
      text = new LinkedBlockingQueue<>();
      partial = new StringBuilder();
      binary = new LinkedBlockingQueue<>();
      partialBinary = new ByteArrayOutputStream();
      closeCode = new CompletableFuture<>();
      closeReason = new CompletableFuture<>();
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
