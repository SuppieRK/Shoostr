package io.github.suppierk.shoostr.example.websocket;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class WebSocketExampleTest {
  @ParameterizedTest
  @ValueSource(strings = {"missing", "unknown", "anonymous"})
  @Timeout(15)
  void deniesUnauthenticatedSessionsDespiteAnAllowedOriginAndAuthorizationHeader(
      String sessionState) throws Exception {
    var listenerCreations = new AtomicInteger();

    try (var test = TestServer.start(sessionApplication(listenerCreations));
        var client = HttpClient.newHttpClient()) {
      var issued = sessionCookie(client, test, "/fixture/authenticated");
      var cookie =
          switch (sessionState) {
            case "missing" -> "";
            case "unknown" -> issued.split("=", 2)[0] + "=not-issued";
            case "anonymous" -> sessionCookie(client, test, "/fixture/anonymous");
            default -> throw new IllegalArgumentException(sessionState);
          };
      assertDenied(client, test, "/session-echo", cookie, List.of("https://app.example"));
      assertDenied(client, test, "/fixture/native", cookie, List.of("https://app.example"));
      assertEquals(0, listenerCreations.get());
    }
  }

  @ParameterizedTest
  @MethodSource("rejectedOrigins")
  @Timeout(10)
  void deniesUntrustedOrAmbiguousOriginsEvenWithAnAuthenticatedSession(List<String> origins)
      throws Exception {
    var listenerCreations = new AtomicInteger();

    try (var test = TestServer.start(sessionApplication(listenerCreations));
        var client = HttpClient.newHttpClient()) {
      var cookie = sessionCookie(client, test, "/fixture/authenticated");
      assertDenied(client, test, "/session-echo", cookie, origins);
      assertDenied(client, test, "/fixture/native", cookie, origins);
      assertEquals(0, listenerCreations.get());
    }
  }

  @Test
  @Timeout(15)
  void echoesPrivateMessagesForAnAuthenticatedSessionWithTheExactOrigin() throws Exception {
    var listenerCreations = new AtomicInteger();

    try (var test = TestServer.start(sessionApplication(listenerCreations));
        var client = HttpClient.newHttpClient()) {
      var cookie = sessionCookie(client, test, "/fixture/authenticated");
      var received = new Messages();
      var socket =
          client
              .newWebSocketBuilder()
              .connectTimeout(Duration.ofSeconds(3))
              .header("Origin", "https://app.example")
              .header("Cookie", cookie)
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/session-echo", null, null),
                  received)
              .get(3, TimeUnit.SECONDS);

      try {
        socket.sendText("private message", true).get(3, TimeUnit.SECONDS);
        assertEquals("private message", received.text.poll(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }

      var nativeSocket =
          client
              .newWebSocketBuilder()
              .connectTimeout(Duration.ofSeconds(3))
              .header("Origin", "https://app.example")
              .header("Cookie", cookie)
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/fixture/native", null, null),
                  new Messages())
              .get(3, TimeUnit.SECONDS);

      try {
        assertEquals(1, listenerCreations.get());
      } finally {
        nativeSocket.abort();
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"2147483647, double=4294967294", "-2147483648, double=-4294967296"})
  @Timeout(10)
  void doublesIntegerBoundaryValuesWithoutOverflow(String input, String expected) throws Exception {
    try (var test = TestServer.start(WebSocketExample.application());
        var client = HttpClient.newHttpClient()) {
      var received = new Messages();
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/numbers", null, null), received)
              .get(3, TimeUnit.SECONDS);

      try {
        socket.sendText(input, true).get(3, TimeUnit.SECONDS);
        assertEquals(expected, received.text.poll(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(10)
  void echoesACompleteMessageAfterFragmentedText() throws Exception {
    try (var test = TestServer.start(WebSocketExample.application());
        var client = HttpClient.newHttpClient()) {
      var received = new Messages();
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/echo", null, null), received)
              .get(3, TimeUnit.SECONDS);

      try {
        socket.sendText("hel", false).get(3, TimeUnit.SECONDS);
        socket.sendText("lo", true).get(3, TimeUnit.SECONDS);
        assertEquals("hello", received.text.poll(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(10)
  void acknowledgesANormalClientClose() throws Exception {
    try (var test = TestServer.start(WebSocketExample.application());
        var client = HttpClient.newHttpClient()) {
      var received = new Messages();
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/echo", null, null), received)
              .get(3, TimeUnit.SECONDS);

      try {
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "Finished").get(3, TimeUnit.SECONDS);
        assertEquals(1000, received.closeCode.get(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(10)
  void closesTheTextProcessorForUnsupportedBinaryMessages() throws Exception {
    try (var test = TestServer.start(WebSocketExample.application());
        var client = HttpClient.newHttpClient()) {
      var received = new Messages();
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/numbers", null, null), received)
              .get(3, TimeUnit.SECONDS);

      try {
        socket.sendBinary(ByteBuffer.wrap(new byte[] {1}), true).get(3, TimeUnit.SECONDS);
        assertAll(
            () -> assertEquals(1003, received.closeCode.get(3, TimeUnit.SECONDS)),
            () ->
                assertEquals(
                    "Text messages required", received.closeReason.get(3, TimeUnit.SECONDS)));
      } finally {
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(10)
  void closesWithASafeReasonAfterAnApplicationFailure() throws Exception {
    try (var test = TestServer.start(WebSocketExample.application());
        var client = HttpClient.newHttpClient()) {
      var received = new Messages();
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/numbers", null, null), received)
              .get(3, TimeUnit.SECONDS);

      try {
        socket.sendText("fail", true).get(3, TimeUnit.SECONDS);
        assertAll(
            () -> assertEquals(1011, received.closeCode.get(3, TimeUnit.SECONDS)),
            () -> assertEquals("Processing failed", received.closeReason.get(3, TimeUnit.SECONDS)));
      } finally {
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(10)
  void recoversFromInvalidNumericInputOnTheSameConnection() throws Exception {
    try (var test = TestServer.start(WebSocketExample.application());
        var client = HttpClient.newHttpClient()) {
      var received = new Messages();
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/numbers", null, null), received)
              .get(3, TimeUnit.SECONDS);

      try {
        socket.sendText("not a number", true).get(3, TimeUnit.SECONDS);
        assertEquals("error: integer required", received.text.poll(3, TimeUnit.SECONDS));
        socket.sendText("21", true).get(3, TimeUnit.SECONDS);
        assertEquals("double=42", received.text.poll(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(10)
  void doublesANumericTextMessage() throws Exception {
    try (var test = TestServer.start(WebSocketExample.application());
        var client = HttpClient.newHttpClient()) {
      var received = new Messages();
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/numbers", null, null), received)
              .get(3, TimeUnit.SECONDS);

      try {
        socket.sendText("21", true).get(3, TimeUnit.SECONDS);
        assertEquals("double=42", received.text.poll(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(10)
  void rejectsAnOversizedRoomDuringTheHttpHandshake() throws Exception {
    try (var test = TestServer.start(WebSocketExample.application());
        var client = HttpClient.newHttpClient()) {
      var failure =
          assertThrows(
              ExecutionException.class,
              () -> {
                var socket =
                    client
                        .newWebSocketBuilder()
                        .buildAsync(
                            new URI(
                                "ws",
                                test.baseUri().getRawAuthority(),
                                "/rooms/" + "x".repeat(33),
                                null,
                                null),
                            new Messages())
                        .get(3, TimeUnit.SECONDS);
                socket.abort();
              });
      var response =
          assertInstanceOf(WebSocketHandshakeException.class, failure.getCause()).getResponse();
      assertAll(
          () -> assertEquals(400, response.statusCode()),
          () -> assertEquals("Invalid room", response.body()),
          () ->
              assertEquals(
                  "text/plain; charset=utf-8",
                  response.headers().firstValue(HttpHeaders.CONTENT_TYPE.value()).orElseThrow()));
    }
  }

  @Test
  @Timeout(15)
  void keepsRoomContextSeparateBetweenConnections() throws Exception {
    try (var test = TestServer.start(WebSocketExample.application());
        var client = HttpClient.newHttpClient()) {
      var alphaMessages = new Messages();
      var alpha =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/rooms/alpha", null, null),
                  alphaMessages)
              .get(3, TimeUnit.SECONDS);

      try {
        var betaMessages = new Messages();
        var beta =
            client
                .newWebSocketBuilder()
                .buildAsync(
                    new URI("ws", test.baseUri().getRawAuthority(), "/rooms/beta", null, null),
                    betaMessages)
                .get(3, TimeUnit.SECONDS);

        try {
          alpha.sendText("hello", true).get(3, TimeUnit.SECONDS);
          beta.sendText("hello", true).get(3, TimeUnit.SECONDS);
          assertAll(
              () -> assertEquals("alpha: hello", alphaMessages.text.poll(3, TimeUnit.SECONDS)),
              () -> assertEquals("beta: hello", betaMessages.text.poll(3, TimeUnit.SECONDS)));
        } finally {
          beta.abort();
        }
      } finally {
        alpha.abort();
      }
    }
  }

  @Test
  @Timeout(10)
  void echoesBinaryThenContinuesWithText() throws Exception {
    try (var test = TestServer.start(WebSocketExample.application());
        var client = HttpClient.newHttpClient()) {
      var received = new Messages();
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/echo", null, null), received)
              .get(3, TimeUnit.SECONDS);

      try {
        socket.sendBinary(ByteBuffer.wrap(new byte[] {0, 1, -1}), true).get(3, TimeUnit.SECONDS);
        assertArrayEquals(new byte[] {0, 1, -1}, received.binary.poll(3, TimeUnit.SECONDS));
        socket.sendText("after binary", true).get(3, TimeUnit.SECONDS);
        assertEquals("after binary", received.text.poll(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  @Test
  @Timeout(10)
  void echoesRepeatedTextMessagesOnOneConnection() throws Exception {
    try (var test = TestServer.start(WebSocketExample.application());
        var client = HttpClient.newHttpClient()) {
      var received = new Messages();
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", test.baseUri().getRawAuthority(), "/echo", null, null), received)
              .get(3, TimeUnit.SECONDS);

      try {
        socket.sendText("hello", true).get(3, TimeUnit.SECONDS);
        assertEquals("hello", received.text.poll(3, TimeUnit.SECONDS));
        socket.sendText("again", true).get(3, TimeUnit.SECONDS);
        assertEquals("again", received.text.poll(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }

  @Test
  void servesThePlainTextEndpointList() throws Exception {
    try (var test = TestServer.start(WebSocketExample.application())) {
      var response = test.send(request -> request.path("/"), HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () ->
              assertEquals("WebSocket endpoints: /echo, /rooms/{room}, /numbers", response.body()),
          () ->
              assertEquals(
                  "text/plain; charset=utf-8",
                  response.headers().firstValue(HttpHeaders.CONTENT_TYPE.value()).orElseThrow()));
    }
  }

  private static Shoostr sessionApplication(AtomicInteger listenerCreations) {
    var app = new Shoostr(Options.defaults().withPort(0));
    app.sessions();
    app.routes()
        .post(
            "/fixture/authenticated",
            (request, response) -> {
              request.session(true).orElseThrow().setAttribute("authenticated", true);
              response.text("created");
            })
        .post(
            "/fixture/anonymous",
            (request, response) -> {
              request.session(true).orElseThrow().setAttribute("authenticated", false);
              response.text("created");
            });
    WebSocketExample.sessionEcho(app.routes());
    app.routes()
        .websocket(
            "/fixture/native",
            (request, _) -> {
              WebSocketExample.requireSessionOrigin(request);
              listenerCreations.incrementAndGet();
              return new org.eclipse.jetty.websocket.api.Session.Listener() {};
            });
    return app;
  }

  private static Stream<List<String>> rejectedOrigins() {
    return Stream.of(
        List.of(),
        List.of("https://evil.example"),
        List.of("not an origin"),
        List.of("null"),
        List.of("https://app.example", "https://app.example"),
        List.of("https://app.example", "https://evil.example"),
        List.of("https://app.example, https://evil.example"));
  }

  private static void assertDenied(
      HttpClient client, TestServer test, String path, String cookie, List<String> origins) {
    var builder =
        client
            .newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .header("Authorization", "Bearer unverified");
    if (!cookie.isEmpty()) {
      builder.header("Cookie", cookie);
    }

    for (var origin : origins) {
      builder.header("Origin", origin);
    }
    var received = new Messages();
    var failure =
        assertThrows(
            ExecutionException.class,
            () -> {
              var socket =
                  builder
                      .buildAsync(
                          new URI("ws", test.baseUri().getRawAuthority(), path, null, null),
                          received)
                      .get(3, TimeUnit.SECONDS);
              socket.abort();
            });
    var response =
        assertInstanceOf(WebSocketHandshakeException.class, failure.getCause()).getResponse();
    assertEquals(403, response.statusCode());
    assertTrue(response.headers().allValues("Set-Cookie").isEmpty());
    assertNull(received.text.poll());
  }

  private static String sessionCookie(HttpClient client, TestServer test, String path)
      throws Exception {
    var created =
        client.send(
            HttpRequest.newBuilder(test.baseUri().resolve(path))
                .timeout(Duration.ofSeconds(3))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, created.statusCode());
    assertEquals("created", created.body());
    return created.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
  }

  private static final class Messages implements WebSocket.Listener {
    private final BlockingQueue<String> text;
    private final StringBuilder partialText;
    private final BlockingQueue<byte[]> binary;
    private final ByteArrayOutputStream partialBinary;
    private final CompletableFuture<Integer> closeCode;
    private final CompletableFuture<String> closeReason;

    private Messages() {
      text = new LinkedBlockingQueue<>();
      partialText = new StringBuilder();
      binary = new LinkedBlockingQueue<>();
      partialBinary = new ByteArrayOutputStream();
      closeCode = new CompletableFuture<>();
      closeReason = new CompletableFuture<>();
    }

    @Override
    public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
      partialText.append(data);
      if (last) {
        text.add(partialText.toString());
        partialText.setLength(0);
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
    public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
      closeCode.complete(statusCode);
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
