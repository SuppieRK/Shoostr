package io.github.suppierk.shoostr;

import static io.github.suppierk.shoostr.Locks.getUnderLock;
import static io.github.suppierk.shoostr.Locks.runUnderLock;
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

import io.github.suppierk.shoostr.extensions.AdmissionExtension;
import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.http.exceptions.AuthenticationRequiredException;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import io.github.suppierk.shoostr.http.exceptions.ForbiddenException;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.UnknownHostException;
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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpFields.Mutable;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.MetaData.ConnectRequest;
import org.eclipse.jetty.http2.api.Session.Listener;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.server.ServerUpgradeResponse;
import org.eclipse.jetty.websocket.server.ServerWebSocketContainer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WebSocketRoutesTest {
  @TempDir Path temporaryDirectory;

  private static AuthenticationExtension chatAuthentication() {
    return new AuthenticationExtension() {
      @Override
      public void handle(Request request, Response response) {
        if (request.header("Authorization").filter("Bearer secret"::equals).isEmpty()) {
          throw new AuthenticationRequiredException("Bearer realm=\"chat\"");
        }

        request.principal(() -> "alice");
      }
    };
  }

  private static org.eclipse.jetty.http2.api.Session connectHttp2(HTTP2Client client, Shoostr app)
      throws Exception {
    return connectHttp2(client, app, false);
  }

  private static org.eclipse.jetty.http2.api.Session connectHttp2(
      HTTP2Client client, Shoostr app, boolean secure) throws Exception {
    var advertised = new CompletableFuture<Integer>();
    client.setConnectTimeout(3000);
    client.setIdleTimeout(3000);
    client.start();
    var connection = createConnectionForConnectHttp2(client, secure, app, advertised);

    assertEquals(1, advertised.get(3, TimeUnit.SECONDS));

    return connection;
  }

  private static org.eclipse.jetty.http2.api.Session createConnectionForConnectHttp2(
      HTTP2Client client, boolean secure, Shoostr app, CompletableFuture<Integer> advertised)
      throws InterruptedException, ExecutionException, TimeoutException, UnknownHostException {
    return client
        .connect(
            secure ? client.getClientConnector().getSslContextFactory() : null,
            new InetSocketAddress(InetAddress.getAllByName("127.0.0.1")[0], app.port()),
            new Listener() {
              @Override
              public void onSettings(
                  org.eclipse.jetty.http2.api.Session session, SettingsFrame frame) {
                var enabled = frame.getSettings().get(SettingsFrame.ENABLE_CONNECT_PROTOCOL);
                if (enabled != null) {
                  advertised.complete(enabled);
                }
              }
            })
        .get(3, TimeUnit.SECONDS);
  }

  private static MetaData.Response http2ConnectResponse(
      org.eclipse.jetty.http2.api.Session connection,
      Shoostr app,
      String path,
      String protocol,
      HttpFields fields)
      throws Exception {
    var received = new CompletableFuture<MetaData.Response>();
    var request =
        new MetaData.ConnectRequest(
            "http", new HostPortHttpField("127.0.0.1:" + app.port()), path, fields, protocol);
    connection
        .newStream(
            new HeadersFrame(request, null, false),
            new Stream.Listener() {
              @Override
              public void onHeaders(Stream stream, HeadersFrame frame) {
                received.complete(assertInstanceOf(MetaData.Response.class, frame.getMetaData()));
                if (!frame.isEndStream()) {
                  stream.demand();
                }
              }
            })
        .get(3, TimeUnit.SECONDS);
    var response = received.get(3, TimeUnit.SECONDS);

    assertEquals(HttpVersion.HTTP_2, response.getHttpVersion());

    return response;
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
    var handshake = createHandshakeForUpgradeUnreadPeer(path);
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

  private static String createHandshakeForUpgradeUnreadPeer(String path) {
    return "GET "
        + path
        + " HTTP/1.1\r\n"
        + "Host: localhost\r\n"
        + "Upgrade: websocket\r\n"
        + "Connection: Upgrade\r\n"
        + "Sec-WebSocket-Version: 13\r\n"
        + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n";
  }

  private static List<String> handshake(Shoostr app, int version) throws Exception {
    try (var socket = new Socket(InetAddress.getAllByName("127.0.0.1")[0], app.port())) {
      socket.setSoTimeout(3000);
      var request = createRequestForHandshake(version);
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

  private static String createRequestForHandshake(int version) {
    return "GET /wire HTTP/1.1\r\n"
        + "Host: localhost\r\n"
        + "Upgrade: websocket\r\n"
        + "Connection: Upgrade\r\n"
        + "Sec-WebSocket-Version: "
        + version
        + "\r\n"
        + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n";
  }

  private static int failedHandshakeStatus(HttpClient client, Shoostr app, String path) {
    var failure = assertFailureForFailedHandshakeStatus(client, app, path);

    return assertInstanceOf(WebSocketHandshakeException.class, failure.getCause())
        .getResponse()
        .statusCode();
  }

  private static ExecutionException assertFailureForFailedHandshakeStatus(
      HttpClient client, Shoostr app, String path) {
    return assertThrows(
        ExecutionException.class,
        () ->
            client
                .newWebSocketBuilder()
                .buildAsync(
                    URI.create("ws://127.0.0.1:" + app.port() + path), new WebSocket.Listener() {})
                .get(3, TimeUnit.SECONDS));
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

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @Timeout(15)
  void skipsHttpCsrfOnlyForRecognizedWebSocketsAndRetainsNativeSessions(boolean managed)
      throws Exception {
    var factories = new AtomicInteger();
    var writes = new AtomicInteger();
    var authentication = chatAuthentication();
    var csrf = Csrf.builder().trustedOrigins("https://socket.example").build();
    Consumer<Request> admit =
        createAdmitForSkipsHttpCsrfOnlyForRecognizedWebSocketsAndRetainsNativeSessions(factories);

    var configuredOptions = new Options();
    configuredOptions.setHttp2(true);

    {
      var app =
          new Shoostr(configuredOptions)
              .modifyServer(server -> ((ServerConnector) server.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup = app::close;
          var http = HttpClient.newHttpClient();
          var client = new HTTP2Client()) {
        app.sessions().withExtension(authentication).withExtension(csrf);
        app.routes()
            .get(
                "/session",
                (request, response) -> {
                  request.getOrCreateSession().setAttribute("marker", "native");
                  response.text("session");
                },
                lifecycle -> lifecycle.withoutExtension(authentication))
            .post(
                "/socket",
                (request, response) -> {
                  writes.incrementAndGet();
                  response.text("written");
                });
        if (managed) {
          app.routes()
              .websocket(
                  "/socket",
                  socket ->
                      socket
                          .beforeUpgrade((request, response) -> admit.accept(request))
                          .onText(WebSocketConnection::send));
        } else {
          app.routes()
              .websocket(
                  "/socket",
                  (request, response) -> {
                    admit.accept(request);

                    return new EchoListener();
                  });
        }

        app.start();
        var base = "http://127.0.0.1:" + app.port();
        var created =
            http.send(
                HttpRequest.newBuilder(URI.create(base + "/session")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, created.statusCode());

        var cookie = created.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        var message = new CompletableFuture<String>();
        var socket =
            createSocketForSkipsHttpCsrfOnlyForRecognizedWebSocketsAndRetainsNativeSessions(
                http, cookie, app, message);

        try {
          socket.sendText("ping", true).get(3, TimeUnit.SECONDS);

          assertEquals("ping", message.get(3, TimeUnit.SECONDS));
        } finally {
          socket.abort();
        }

        var connection = connectHttp2(client, app);
        var response =
            sendResponseForSkipsHttpCsrfOnlyForRecognizedWebSocketsAndRetainsNativeSessions(
                connection, app, cookie);

        assertEquals(200, response.getStatus());
        assertEquals(2, factories.get());

        try (var peer = new Socket(InetAddress.getAllByName("127.0.0.1")[0], app.port());
            var reader =
                new BufferedReader(
                    new InputStreamReader(peer.getInputStream(), StandardCharsets.US_ASCII))) {
          peer.setSoTimeout(3000);
          var request =
              createRequestForSkipsHttpCsrfOnlyForRecognizedWebSocketsAndRetainsNativeSessions(
                  app, base, cookie);
          peer.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
          var status = Objects.requireNonNull(reader.readLine());

          assertTrue(status.startsWith("HTTP/1.1 403"), status);
        }

        assertEquals(0, writes.get());
        assertEquals(2, factories.get());
      }
    }
  }

  private Consumer<Request>
      createAdmitForSkipsHttpCsrfOnlyForRecognizedWebSocketsAndRetainsNativeSessions(
          AtomicInteger factories) {
    return request -> {
      if (!request.headers("Origin").equals(List.of("https://socket.example"))) {
        throw new ForbiddenException();
      }

      assertEquals("alice", request.principal().orElseThrow().getName());
      assertEquals("native", request.getSession().orElseThrow().getAttribute("marker"));

      factories.incrementAndGet();
    };
  }

  private WebSocket createSocketForSkipsHttpCsrfOnlyForRecognizedWebSocketsAndRetainsNativeSessions(
      HttpClient http, String cookie, Shoostr app, CompletableFuture<String> message)
      throws InterruptedException, ExecutionException, TimeoutException {
    return http.newWebSocketBuilder()
        .header("Origin", "https://socket.example")
        .header("Cookie", cookie)
        .header("Authorization", "Bearer secret")
        .buildAsync(URI.create("ws://127.0.0.1:" + app.port() + "/socket"), receiving(message))
        .get(3, TimeUnit.SECONDS);
  }

  private org.eclipse.jetty.http.MetaData.Response
      sendResponseForSkipsHttpCsrfOnlyForRecognizedWebSocketsAndRetainsNativeSessions(
          org.eclipse.jetty.http2.api.Session connection, Shoostr app, String cookie)
          throws Exception {
    return http2ConnectResponse(
        connection,
        app,
        "/socket",
        "websocket",
        HttpFields.build()
            .put("Sec-WebSocket-Version", "13")
            .put("Origin", "https://socket.example")
            .put("Authorization", "Bearer secret")
            .put("Cookie", cookie)
            .asImmutable());
  }

  private String createRequestForSkipsHttpCsrfOnlyForRecognizedWebSocketsAndRetainsNativeSessions(
      Shoostr app, String base, String cookie) {
    return "POST /socket HTTP/1.1\r\nHost: 127.0.0.1:"
        + app.port()
        + "\r\nOrigin: "
        + base
        + "\r\nAuthorization: Bearer secret\r\nCookie: "
        + cookie
        + "\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n"
        + "Sec-WebSocket-Version: 13\r\nContent-Length: 0\r\n\r\n";
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @Timeout(15)
  void usesExplicitWebSocketOriginAdmissionInsteadOfHttpCorsForBothProtocols(boolean managed)
      throws Exception {
    var factories = new AtomicInteger();
    var authentication = chatAuthentication();
    var cors =
        new Cors(
            CorsPolicy.builder().origins("https://http.example").methods(HttpMethods.POST).build());
    Consumer<Request> admit = createAdmitForExplicitOriginAdmission(factories);

    var configuredOptions = new Options();
    configuredOptions.setHttp2(true);

    {
      var app =
          new Shoostr(configuredOptions)
              .modifyServer(server -> ((ServerConnector) server.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup1 = app::close;
          var http = HttpClient.newHttpClient();
          var client = new HTTP2Client()) {
        app.withExtension(authentication).withExtension(cors);
        if (managed) {
          app.routes()
              .websocket(
                  "/socket",
                  socket ->
                      socket
                          .beforeUpgrade((request, response) -> admit.accept(request))
                          .onText(WebSocketConnection::send));
        } else {
          app.routes()
              .websocket(
                  "/socket",
                  (request, response) -> {
                    admit.accept(request);

                    return new EchoListener();
                  });
        }

        app.start();
        var address = URI.create("ws://127.0.0.1:" + app.port() + "/socket");
        var message = new CompletableFuture<String>();
        var socket = createSocketForExplicitOriginAdmission(http, address, message);

        try {
          socket.sendText("ping", true).get(3, TimeUnit.SECONDS);

          assertEquals("ping", message.get(3, TimeUnit.SECONDS));
        } finally {
          socket.abort();
        }

        assertEquals(1, factories.get());

        for (var origin : List.of("https://http.example", "")) {
          var builder = http.newWebSocketBuilder().header("Authorization", "Bearer secret");
          if (!origin.isEmpty()) {
            builder.header("Origin", origin);
          }

          var failure = assertFailureForExplicitOriginAdmission(builder, address);

          assertEquals(
              403,
              assertInstanceOf(WebSocketHandshakeException.class, failure.getCause())
                  .getResponse()
                  .statusCode());
        }
        var unauthenticated = assertUnauthenticatedForExplicitOriginAdmission(http, address);

        assertEquals(
            401,
            assertInstanceOf(WebSocketHandshakeException.class, unauthenticated.getCause())
                .getResponse()
                .statusCode());
        assertEquals(1, factories.get());

        var connection = connectHttp2(client, app);
        var allowed =
            HttpFields.build()
                .put("Sec-WebSocket-Version", "13")
                .put("Origin", "https://socket.example")
                .put("Authorization", "Bearer secret");
        var accepted =
            http2ConnectResponse(connection, app, "/socket", "websocket", allowed.asImmutable());

        assertEquals(200, accepted.getStatus());
        assertNull(accepted.getHttpFields().get("Access-Control-Allow-Origin"));
        assertEquals(2, factories.get());

        for (var origin : List.of("https://http.example", "")) {
          var fields =
              HttpFields.build()
                  .put("Sec-WebSocket-Version", "13")
                  .put("Authorization", "Bearer secret");
          if (!origin.isEmpty()) {
            fields.put("Origin", origin);
          }

          assertEquals(
              403,
              http2ConnectResponse(connection, app, "/socket", "websocket", fields.asImmutable())
                  .getStatus());
        }

        assertEquals(
            401,
            http2ConnectResponse(
                    connection,
                    app,
                    "/socket",
                    "websocket",
                    HttpFields.build()
                        .put("Sec-WebSocket-Version", "13")
                        .put("Origin", "https://socket.example")
                        .asImmutable())
                .getStatus());
        assertEquals(2, factories.get());
      }
    }
  }

  private Consumer<Request> createAdmitForExplicitOriginAdmission(AtomicInteger factories) {
    return request -> {
      if (!request.headers("Origin").equals(List.of("https://socket.example"))) {
        throw new ForbiddenException();
      }

      assertEquals("alice", request.principal().orElseThrow().getName());

      factories.incrementAndGet();
    };
  }

  private WebSocket createSocketForExplicitOriginAdmission(
      HttpClient http, URI address, CompletableFuture<String> message)
      throws InterruptedException, ExecutionException, TimeoutException {
    return http.newWebSocketBuilder()
        .header("Origin", "https://socket.example")
        .header("Authorization", "Bearer secret")
        .buildAsync(address, receiving(message))
        .get(3, TimeUnit.SECONDS);
  }

  private ExecutionException assertFailureForExplicitOriginAdmission(
      WebSocket.Builder builder, URI address) {
    return assertThrows(
        ExecutionException.class,
        () -> builder.buildAsync(address, new WebSocket.Listener() {}).get(3, TimeUnit.SECONDS));
  }

  private ExecutionException assertUnauthenticatedForExplicitOriginAdmission(
      HttpClient http, URI address) {
    return assertThrows(
        ExecutionException.class,
        () ->
            http.newWebSocketBuilder()
                .header("Origin", "https://socket.example")
                .buildAsync(address, new WebSocket.Listener() {})
                .get(3, TimeUnit.SECONDS));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @Timeout(15)
  void echoesTextOverNegotiatedHttp2WebSocketConnectWithoutBreakingHttp1(boolean secure)
      throws Exception {
    var factories = new AtomicInteger();
    var headers = new CompletableFuture<MetaData.Response>();
    var received = new CompletableFuture<byte[]>();
    var content = new ByteArrayOutputStream();
    var keyStore = KeyStore.getInstance("PKCS12");

    try (var input =
        Objects.requireNonNull(getClass().getResourceAsStream("/localhost-test.p12"))) {
      keyStore.load(input, "changeit".toCharArray());
    }

    var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trust.init(keyStore);
    var context = SSLContext.getInstance("TLS");
    context.init(null, trust.getTrustManagers(), null);

    var configuredOptions = new Options();
    configuredOptions.setHttp2(true);

    {
      var app =
          new Shoostr(configuredOptions)
              .modifyServer(server -> ((ServerConnector) server.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup2 = app::close;
          var http =
              HttpClient.newBuilder()
                  .sslContext(context)
                  .connectTimeout(Duration.ofSeconds(3))
                  .build();
          var client = new HTTP2Client()) {

        if (secure) {
          app.tls(tls -> {
            tls.setKeyStore(keyStore);
            tls.setKeyStorePassword("changeit");
          });
        }

        app.routes()
            .websocket(
                "/connect",
                (request, ignored) -> {
                  factories.incrementAndGet();

                  assertEquals(secure, request.isSecure());

                  return new EchoListener();
                });
        app.start();
        var firstMessage = new CompletableFuture<String>();
        var firstSocket =
            createFirstSocketForEchoesTextOverNegotiatedHttp2WebSocketConnectWithoutBreakingHttp1(
                http, secure, app, firstMessage);

        try {
          firstSocket.sendText("ping", true).get(3, TimeUnit.SECONDS);

          assertEquals("ping", firstMessage.get(3, TimeUnit.SECONDS));
        } finally {
          firstSocket.abort();
        }

        assertEquals(1, factories.get());

        var ssl = new SslContextFactory.Client();
        ssl.setTrustStore(keyStore);
        client.getClientConnector().setSslContextFactory(ssl);
        var connection = connectHttp2(client, app, secure);

        var request =
            createRequestForEchoesTextOverNegotiatedHttp2WebSocketConnectWithoutBreakingHttp1(
                secure, app);
        final var stream =
            createStreamForEchoesTextOverNegotiatedHttp2WebSocketConnectWithoutBreakingHttp1(
                connection, request, headers, content, received);
        var result = headers.get(3, TimeUnit.SECONDS);

        assertEquals(HttpVersion.HTTP_2, result.getHttpVersion());
        assertEquals(200, result.getStatus(), "WebSocket factory calls: " + factories.get());
        assertEquals(2, factories.get());

        stream
            .data(
                new DataFrame(
                    stream.getId(),
                    ByteBuffer.wrap(
                        new byte[] {(byte) 0x81, (byte) 0x84, 0, 0, 0, 0, 'p', 'i', 'n', 'g'}),
                    false))
            .get(3, TimeUnit.SECONDS);

        assertArrayEquals(
            new byte[] {(byte) 0x81, 4, 'p', 'i', 'n', 'g'}, received.get(3, TimeUnit.SECONDS));
      }
    }
  }

  private WebSocket
      createFirstSocketForEchoesTextOverNegotiatedHttp2WebSocketConnectWithoutBreakingHttp1(
          HttpClient http, boolean secure, Shoostr app, CompletableFuture<String> firstMessage)
          throws InterruptedException, ExecutionException, TimeoutException {
    return http.newWebSocketBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .buildAsync(
            URI.create((secure ? "wss" : "ws") + "://localhost:" + app.port() + "/connect"),
            receiving(firstMessage))
        .get(3, TimeUnit.SECONDS);
  }

  private ConnectRequest
      createRequestForEchoesTextOverNegotiatedHttp2WebSocketConnectWithoutBreakingHttp1(
          boolean secure, Shoostr app) {
    return new MetaData.ConnectRequest(
        secure ? "https" : "http",
        new HostPortHttpField("127.0.0.1:" + app.port()),
        "/connect",
        HttpFields.build().put("Sec-WebSocket-Version", "13").asImmutable(),
        "websocket");
  }

  private Stream createStreamForEchoesTextOverNegotiatedHttp2WebSocketConnectWithoutBreakingHttp1(
      org.eclipse.jetty.http2.api.Session connection,
      ConnectRequest request,
      CompletableFuture<org.eclipse.jetty.http.MetaData.Response> headers,
      ByteArrayOutputStream content,
      CompletableFuture<byte[]> received)
      throws InterruptedException, ExecutionException, TimeoutException {
    return connection
        .newStream(
            new HeadersFrame(request, null, false),
            new Stream.Listener() {
              @Override
              public void onHeaders(Stream stream, HeadersFrame frame) {
                headers.complete(assertInstanceOf(MetaData.Response.class, frame.getMetaData()));
                if (!frame.isEndStream()) {
                  stream.demand();
                }
              }

              @Override
              public void onDataAvailable(Stream stream) {
                var data = stream.readData();
                if (data == null) {
                  stream.demand();
                  return;
                }

                try {
                  var buffer = data.frame().getByteBuffer();
                  var bytes = new byte[buffer.remaining()];
                  buffer.get(bytes);
                  content.writeBytes(bytes);
                  if (content.size() >= 6 || data.frame().isEndStream()) {
                    received.complete(content.toByteArray());
                  } else {
                    stream.demand();
                  }
                } finally {
                  data.release();
                }
              }
            })
        .get(3, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(15)
  void deniesUnauthenticatedHttp2WebSocketsBeforeCallingTheirListenerFactory() throws Exception {
    var factories = new AtomicInteger();
    var authentication = chatAuthentication();

    var configuredOptions = new Options();
    configuredOptions.setHttp2(true);

    {
      var app =
          new Shoostr(configuredOptions)
              .modifyServer(server -> ((ServerConnector) server.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup3 = app::close;
          var client = new HTTP2Client()) {

        app.routes()
            .websocket(
                "/authenticated",
                (request, ignoredServerUpgradeResponse) -> {
                  assertEquals("alice", request.principal().orElseThrow().getName());

                  factories.incrementAndGet();

                  return new EchoListener();
                },
                extensions -> extensions.withExtension(authentication));
        app.start();
        var connection = connectHttp2(client, app);
        var fields = HttpFields.build().put("Sec-WebSocket-Version", "13");
        var accepted =
            createAcceptedForDeniesUnauthenticatedHttp2WebSocketsBeforeCallingTheirListenerFactory(
                connection, app, fields);

        assertEquals(200, accepted.getStatus());
        assertEquals(1, factories.get());

        var rejected =
            http2ConnectResponse(
                connection, app, "/authenticated", "websocket", fields.asImmutable());

        assertEquals(401, rejected.getStatus());
        assertEquals("Bearer realm=\"chat\"", rejected.getHttpFields().get("WWW-Authenticate"));
        assertEquals(1, factories.get());
      }
    }
  }

  private org.eclipse.jetty.http.MetaData.Response
      createAcceptedForDeniesUnauthenticatedHttp2WebSocketsBeforeCallingTheirListenerFactory(
          org.eclipse.jetty.http2.api.Session connection, Shoostr app, Mutable fields)
          throws Exception {
    return http2ConnectResponse(
        connection,
        app,
        "/authenticated",
        "websocket",
        HttpFields.build(fields).put("Authorization", "Bearer secret").asImmutable());
  }

  @Test
  @Timeout(15)
  void keepsOtherExtendedConnectProtocolsOnOrdinaryConnectRoutes() throws Exception {
    var factories = new AtomicInteger();

    var configuredOptions = new Options();
    configuredOptions.setHttp2(true);

    {
      var app =
          new Shoostr(configuredOptions)
              .modifyServer(server -> ((ServerConnector) server.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup4 = app::close;
          var client = new HTTP2Client()) {

        app.routes()
            .websocket(
                "/connect",
                (ignoredRequest, ignoredServerUpgradeResponse) -> {
                  factories.incrementAndGet();

                  return new EchoListener();
                });
        app.routes()
            .route(
                HttpMethods.CONNECT,
                "/connect",
                (ignoredRequest, response) -> response.status(204));
        app.start();
        var connection = connectHttp2(client, app);
        var result = http2ConnectResponse(connection, app, "/connect", "other", HttpFields.EMPTY);

        assertEquals(204, result.getStatus());
        assertEquals(0, factories.get());
      }
    }
  }

  @Test
  @Timeout(15)
  void rejectsUnsupportedHttp2WebSocketVersionsWithoutHttp1UpgradeHeaders() throws Exception {
    var factories = new AtomicInteger();

    var configuredOptions = new Options();
    configuredOptions.setHttp2(true);

    {
      var app =
          new Shoostr(configuredOptions)
              .modifyServer(server -> ((ServerConnector) server.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup5 = app::close;
          var client = new HTTP2Client()) {

        app.routes()
            .websocket(
                "/version",
                (ignoredRequest, ignoredServerUpgradeResponse) -> {
                  factories.incrementAndGet();

                  return new EchoListener();
                });
        app.start();
        var connection = connectHttp2(client, app);
        var result =
            sendResultForRejectsUnsupportedHttp2WebSocketVersionsWithoutHttp1UpgradeHeaders(
                connection, app);

        assertEquals(426, result.getStatus());
        assertEquals("13", result.getHttpFields().get("Sec-WebSocket-Version"));
        assertNull(result.getHttpFields().get("Upgrade"));
        assertEquals(0, factories.get());
      }
    }
  }

  private org.eclipse.jetty.http.MetaData.Response
      sendResultForRejectsUnsupportedHttp2WebSocketVersionsWithoutHttp1UpgradeHeaders(
          org.eclipse.jetty.http2.api.Session connection, Shoostr app) throws Exception {
    return http2ConnectResponse(
        connection,
        app,
        "/version",
        "websocket",
        HttpFields.build().put("Sec-WebSocket-Version", "12").asImmutable());
  }

  @Test
  @Timeout(15)
  void matchesHttp2WebSocketTemplatesBeforeRunningRouteCallbacks() throws Exception {
    var configuredOptions = new Options();
    configuredOptions.setHttp2(true);

    {
      var app =
          new Shoostr(configuredOptions)
              .modifyServer(server -> ((ServerConnector) server.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup6 = app::close;
          var client = new HTTP2Client()) {

        app.routes()
            .websocket(
                "/rooms/{room}",
                (request, upgrade) -> {
                  assertEquals("€", request.attribute("matched-room").orElseThrow());

                  upgrade.getHeaders().put("X-Matched-Room", "ready");

                  return new EchoListener();
                },
                extensions ->
                    extensions.onRouteMatched((request, ignoredResponse) -> {
                      assertEquals("CONNECT", request.method());
                      assertEquals("/rooms/{room}", request.routePattern().orElseThrow());

                      request.attribute("matched-room", request.pathParam("room").orElseThrow());
                    }));
        app.start();
        var connection = connectHttp2(client, app);
        var result =
            sendResultForMatchesHttp2WebSocketTemplatesBeforeRunningRouteCallbacks(connection, app);

        assertEquals(200, result.getStatus());
        assertEquals("ready", result.getHttpFields().get("X-Matched-Room"));
      }
    }
  }

  private org.eclipse.jetty.http.MetaData.Response
      sendResultForMatchesHttp2WebSocketTemplatesBeforeRunningRouteCallbacks(
          org.eclipse.jetty.http2.api.Session connection, Shoostr app) throws Exception {
    return http2ConnectResponse(
        connection,
        app,
        "/rooms/%E2%82%AC",
        "websocket",
        HttpFields.build().put("Sec-WebSocket-Version", "13").asImmutable());
  }

  @Test
  @Timeout(15)
  void hidesUnavailableHttp2WebSocketRoutesBeforeCallingTheirListenerFactory() throws Exception {
    var available = new AtomicBoolean(true);
    var factories = new AtomicInteger();

    var configuredOptions = new Options();
    configuredOptions.setHttp2(true);

    {
      var app =
          new Shoostr(configuredOptions)
              .modifyServer(server -> ((ServerConnector) server.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup7 = app::close;
          var client = new HTTP2Client()) {

        app.routes()
            .when(
                available::get,
                routes ->
                    routes.websocket(
                        "/available",
                        (ignoredRequest, ignoredServerUpgradeResponse) -> {
                          factories.incrementAndGet();

                          return new EchoListener();
                        }));
        app.start();
        var connection = connectHttp2(client, app);
        var fields = HttpFields.build().put("Sec-WebSocket-Version", "13").asImmutable();

        assertEquals(
            200,
            http2ConnectResponse(connection, app, "/available", "websocket", fields).getStatus());
        assertEquals(1, factories.get());

        available.set(false);
        var result = http2ConnectResponse(connection, app, "/available", "websocket", fields);

        assertEquals(404, result.getStatus());
        assertEquals("no-store", result.getHttpFields().get("Cache-Control"));
        assertEquals(1, factories.get());
      }
    }
  }

  @Test
  @Timeout(15)
  void sendsServerFirstMessageThenRepliesWithSuccessfulNativeSendCallbacks() throws Exception {
    var firstSent = new CompletableFuture<Void>();
    var replySent = new CompletableFuture<Void>();
    var received = new LinkedBlockingQueue<String>(2);
    var pending = new StringBuilder();

    {
      var app =
          new Shoostr()
              .modifyServer(server -> ((ServerConnector) server.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup8 = app::close;
          var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
        app.routes()
            .websocket(
                "/greeting",
                (ignoredRequest, ignoredServerUpgradeResponse) ->
                    new GreetingListener(firstSent, replySent));
        app.start();

        var socket =
            createSocketForSendsServerFirstMessageThenRepliesWithSuccessfulNativeSendCallbacks(
                client, app, pending, received);

        try {
          assertEquals("server-first", received.poll(3, TimeUnit.SECONDS));

          firstSent.get(3, TimeUnit.SECONDS);
          socket.sendText("ping", true).get(3, TimeUnit.SECONDS);

          assertEquals("reply:ping", received.poll(3, TimeUnit.SECONDS));

          replySent.get(3, TimeUnit.SECONDS);

          assertTrue(received.isEmpty());
        } finally {
          socket.abort();
        }
      }
    }
  }

  private WebSocket
      createSocketForSendsServerFirstMessageThenRepliesWithSuccessfulNativeSendCallbacks(
          HttpClient client,
          Shoostr app,
          StringBuilder pending,
          LinkedBlockingQueue<String> received)
          throws InterruptedException, ExecutionException, TimeoutException {
    return client
        .newWebSocketBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .buildAsync(
            URI.create("ws://127.0.0.1:" + app.port() + "/greeting"),
            new WebSocket.Listener() {
              @Override
              public CompletionStage<?> onText(
                  WebSocket webSocket, CharSequence data, boolean last) {
                pending.append(data);
                if (last) {
                  received.add(pending.toString());
                  pending.setLength(0);
                }

                webSocket.request(1);

                return CompletableFuture.completedFuture(null);
              }
            })
        .get(3, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(10)
  void echoesTenRapidDistinctMessagesCompletelyAndInOrder() throws Exception {
    var expected =
        List.of("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten");
    var received = new CompletableFuture<List<String>>();
    var messages = new ArrayList<String>();
    var partial = new StringBuilder();

    {
      var app =
          new Shoostr()
              .modifyServer(server -> ((ServerConnector) server.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup9 = app::close;
          var client = HttpClient.newHttpClient()) {
        app.routes()
            .websocket(
                "/burst", (ignoredRequest, ignoredServerUpgradeResponse) -> new EchoListener());
        app.start();
        var socket =
            createSocketForEchoesTenRapidDistinctMessagesCompletelyAndInOrder(
                client, app, partial, messages, expected, received);

        try {
          var sends = CompletableFuture.completedFuture(socket);
          for (var message : expected) {
            sends = sends.thenCompose(webSocket -> webSocket.sendText(message, true));
          }
          sends.get(3, TimeUnit.SECONDS);

          assertEquals(expected, received.get(3, TimeUnit.SECONDS));
        } finally {
          socket.abort();
        }
      }
    }
  }

  private WebSocket createSocketForEchoesTenRapidDistinctMessagesCompletelyAndInOrder(
      HttpClient client,
      Shoostr app,
      StringBuilder partial,
      List<String> messages,
      List<String> expected,
      CompletableFuture<List<String>> received)
      throws InterruptedException, ExecutionException, TimeoutException {
    return client
        .newWebSocketBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .buildAsync(
            URI.create("ws://127.0.0.1:" + app.port() + "/burst"),
            new WebSocket.Listener() {
              @Override
              public CompletionStage<?> onText(
                  WebSocket webSocket, CharSequence data, boolean last) {
                partial.append(data);
                if (last) {
                  messages.add(partial.toString());
                  partial.setLength(0);
                  if (messages.size() == expected.size()) {
                    received.complete(List.copyOf(messages));
                  }
                }

                webSocket.request(1);

                return CompletableFuture.completedFuture(null);
              }

              @Override
              public void onError(WebSocket webSocket, Throwable error) {
                received.completeExceptionally(error);
              }
            })
        .get(3, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(10)
  void echoesTextThroughWebSocketRoute() throws Exception {
    var received = new LinkedBlockingQueue<String>();
    var factoryCalls = new AtomicInteger();
    var completedSends = new CountDownLatch(3);
    var sendFailure = new AtomicReference<Throwable>();

    {
      var app =
          new Shoostr()
              .modifyServer(server -> ((ServerConnector) server.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup10 = app::close;
          var client = HttpClient.newHttpClient()) {
        app.routes().get("/echo", (ignoredRequest, response) -> response.text("ordinary"));
        app.routes()
            .websocket(
                "/echo",
                (ignoredRequest, ignoredServerUpgradeResponse) -> {
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

        var socket = createSocketForEchoesTextThroughWebSocketRoute(client, app, received);

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
  }

  private WebSocket createSocketForEchoesTextThroughWebSocketRoute(
      HttpClient client, Shoostr app, LinkedBlockingQueue<String> received)
      throws InterruptedException, ExecutionException, TimeoutException {
    return client
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

    {
      var app =
          new Shoostr()
              .modifyServer(server -> ((ServerConnector) server.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup11 = app::close;
          var client = HttpClient.newBuilder().sslContext(context).build()) {
        app.tls(tls -> {
          tls.setKeyStorePath(keyStorePath.toString());
          tls.setKeyStorePassword("changeit");
        });
        app.routes()
            .websocket(
                "/secure",
                (request, ignoredServerUpgradeResponse) -> {
                  assertTrue(request.isSecure());
                  assertEquals("https", request.scheme().orElseThrow());

                  return new EchoListener("secure:");
                });
        app.start();

        var socket =
            createSocketForUpgradesAndExchangesWebSocketMessagesOverTls(client, app, received);

        socket.sendText("hello", true).get(3, TimeUnit.SECONDS);

        assertEquals("secure:hello", received.poll(3, TimeUnit.SECONDS));

        socket.abort();
      }
    }
  }

  private WebSocket createSocketForUpgradesAndExchangesWebSocketMessagesOverTls(
      HttpClient client, Shoostr app, LinkedBlockingQueue<String> received)
      throws InterruptedException, ExecutionException, TimeoutException {
    return client
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
  }

  @Test
  @Timeout(10)
  void rejectsWebSocketRoutesWithWrongLiteralCaseBeforeCallingTheFactory() throws Exception {
    var factoryCalls = new AtomicInteger();
    var received = new CompletableFuture<String>();

    {
      var app =
          new Shoostr()
              .modifyServer(server -> ((ServerConnector) server.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup12 = app::close;
          var client = HttpClient.newHttpClient()) {
        app.routes()
            .websocket(
                "/rooms/{id}",
                (request, ignoredServerUpgradeResponse) -> {
                  factoryCalls.incrementAndGet();

                  return new EchoListener(request.pathParam("id").orElseThrow() + ":");
                });
        app.start();
        var socket =
            createSocketForRejectsWebSocketRoutesWithWrongLiteralCaseBeforeCallingTheFactory(
                client, app, received);

        try {
          socket.sendText("hello", true).get(3, TimeUnit.SECONDS);

          assertEquals("lobby:hello", received.get(3, TimeUnit.SECONDS));
          assertEquals(1, factoryCalls.get());
          assertEquals(404, failedHandshakeStatus(client, app, "/Rooms/lobby"));
          assertEquals(1, factoryCalls.get());
        } finally {
          socket.abort();
        }
      }
    }
  }

  private WebSocket
      createSocketForRejectsWebSocketRoutesWithWrongLiteralCaseBeforeCallingTheFactory(
          HttpClient client, Shoostr app, CompletableFuture<String> received)
          throws InterruptedException, ExecutionException, TimeoutException {
    return client
        .newWebSocketBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .buildAsync(
            URI.create("ws://127.0.0.1:" + app.port() + "/rooms/lobby"), receiving(received))
        .get(3, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(15)
  void passesHandshakeMetadataAndExistingSessionToTheListenerFactory() throws Exception {
    var received = new CompletableFuture<String>();

    {
      var app =
          new Shoostr()
              .modifyServer(server -> ((ServerConnector) server.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup13 = app::close;
          var client = HttpClient.newHttpClient()) {

        app.sessions();
        app.routes()
            .path(
                "/",
                scoped ->
                    scoped.get(
                        "/session",
                        (request, response) -> {
                          var session = request.getOrCreateSession();
                          session.setAttribute("name", "alice");
                          response.text(session.getId());
                        }));
        app.routes()
            .path(
                "/",
                scoped ->
                    scoped.websocket(
                        "/metadata",
                        (request, ignoredServerUpgradeResponse) -> {
                          var session = request.getSession().orElseThrow();
                          var prefix = createPrefixForHandshakeSession(request, session);

                          return new EchoListener(prefix + "|");
                        }));
        app.start();

        var created = sendCreatedRequestForHandshakeSession(client, app);

        assertEquals(200, created.statusCode());
        assertFalse(created.body().isBlank());

        var cookie = created.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        var socket = createSocketForHandshakeSession(client, cookie, app, received);

        try {
          socket.sendText("hello", true).get(3, TimeUnit.SECONDS);

          assertEquals(
              "München city|dark|alice|"
                  + created.body()
                  + "|present|127.0.0.1:"
                  + app.port()
                  + "|hello",
              received.get(3, TimeUnit.SECONDS));
        } finally {
          socket.abort();
        }
      }
    }
  }

  private String createPrefixForHandshakeSession(
      Request request, org.eclipse.jetty.server.Session session) {
    return String.join(
        "|",
        request.queryParam("city").orElseThrow(),
        request.cookie("theme").orElseThrow(),
        Objects.toString(session.getAttribute("name")),
        session.getId(),
        request.header("X-Handshake").orElseThrow(),
        request.authority().orElseThrow());
  }

  private HttpResponse<String> sendCreatedRequestForHandshakeSession(HttpClient client, Shoostr app)
      throws IOException, InterruptedException {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/session"))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private WebSocket createSocketForHandshakeSession(
      HttpClient client, String cookie, Shoostr app, CompletableFuture<String> received)
      throws InterruptedException, ExecutionException, TimeoutException {
    return client
        .newWebSocketBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .header("Cookie", cookie + "; theme=dark")
        .header("X-Handshake", "present")
        .buildAsync(
            URI.create("ws://127.0.0.1:" + app.port() + "/metadata?city=M%C3%BCnchen+city"),
            receiving(received))
        .get(3, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(10)
  void passesParsedProtocolsAndPathParametersToListenerFactoryForEachConnection() throws Exception {
    var factoryCalls = new AtomicInteger();
    var firstMessage = new CompletableFuture<String>();
    var secondMessage = new CompletableFuture<String>();

    {
      var app =
          new Shoostr()
              .modifyServer(server -> ((ServerConnector) server.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup14 = app::close;
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

                          return new EchoListener(request.pathParam("id").orElseThrow() + ":");
                        }));
        app.start();

        var first =
            createFirstForPassesParsedProtocolsAndPathParametersToListenerFactoryForEachConnection(
                client, app, firstMessage);
        var second =
            createSecondForPassesParsedProtocolsAndPathParametersToListenerFactoryForEachConnection(
                client, app, secondMessage);

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
  }

  private WebSocket
      createFirstForPassesParsedProtocolsAndPathParametersToListenerFactoryForEachConnection(
          HttpClient client, Shoostr app, CompletableFuture<String> firstMessage)
          throws InterruptedException, ExecutionException, TimeoutException {
    return client
        .newWebSocketBuilder()
        .subprotocols("chat.v1", "chat.v2")
        .buildAsync(
            URI.create("ws://127.0.0.1:" + app.port() + "/rooms/first"), receiving(firstMessage))
        .get(3, TimeUnit.SECONDS);
  }

  private WebSocket
      createSecondForPassesParsedProtocolsAndPathParametersToListenerFactoryForEachConnection(
          HttpClient client, Shoostr app, CompletableFuture<String> secondMessage)
          throws InterruptedException, ExecutionException, TimeoutException {
    return client
        .newWebSocketBuilder()
        .subprotocols("chat.v1", "chat.v2")
        .buildAsync(
            URI.create("ws://127.0.0.1:" + app.port() + "/rooms/second"), receiving(secondMessage))
        .get(3, TimeUnit.SECONDS);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @Timeout(10)
  void prefersLiteralWebSocketRoutesAndFallsBackAfterSuffixMismatch(boolean literalFirst)
      throws Exception {
    {
      var app =
          new Shoostr()
              .modifyServer(server -> ((ServerConnector) server.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup15 = app::close;
          var client = HttpClient.newHttpClient()) {
        BiFunction<Request, ServerUpgradeResponse, Session.Listener> literal =
            (ignoredRequest, ignoredServerUpgradeResponse) -> new EchoListener("literal:");
        BiFunction<Request, ServerUpgradeResponse, Session.Listener> parameter =
            (request, ignoredServerUpgradeResponse) ->
                new EchoListener("parameter:" + request.pathParam("id").orElseThrow() + ":");
        if (literalFirst) {
          app.routes().websocket("/rooms/latest", literal);
          app.routes().websocket("/rooms/{id}", parameter);
        } else {
          app.routes().websocket("/rooms/{id}", parameter);
          app.routes().websocket("/rooms/latest", literal);
        }

        app.routes()
            .websocket(
                "/fallback/fixed/x",
                (ignoredRequest, ignoredServerUpgradeResponse) -> new EchoListener("x:"));
        app.routes()
            .websocket(
                "/fallback/{name}/y",
                (request, ignoredServerUpgradeResponse) ->
                    new EchoListener(request.pathParam("name").orElseThrow() + ":"));
        app.start();

        assertEquals("literal:ping", sendAndReceive(client, app, "/rooms/latest"));
        assertEquals("parameter:42:ping", sendAndReceive(client, app, "/rooms/42"));
        assertEquals("fixed:ping", sendAndReceive(client, app, "/fallback/fixed/y"));
      }
    }
  }

  @Test
  @Timeout(10)
  void upgradesCatchAllWebSocketRoutesWithoutChangingSingleSegmentPrecedence() throws Exception {
    {
      var app =
          new Shoostr()
              .modifyServer(server -> ((ServerConnector) server.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup16 = app::close;
          var client = HttpClient.newHttpClient()) {
        app.routes()
            .path(
                "/rooms",
                rooms -> {
                  rooms.websocket(
                      "/{*path}",
                      (request, ignoredServerUpgradeResponse) ->
                          new EchoListener(
                              "tail:" + request.pathParam("path").orElseThrow() + ":"));
                  rooms.websocket(
                      "/{id}",
                      (request, ignoredServerUpgradeResponse) ->
                          new EchoListener(
                              "single:" + request.pathParam("id").orElseThrow() + ":"));
                  rooms.websocket(
                      "/latest",
                      (ignoredRequest, ignoredServerUpgradeResponse) ->
                          new EchoListener("literal:"));
                });
        app.start();

        assertEquals("literal:ping", sendAndReceive(client, app, "/rooms/latest"));
        assertEquals("single:a:ping", sendAndReceive(client, app, "/rooms/a"));
        assertEquals("tail:a/b:ping", sendAndReceive(client, app, "/rooms/a/b"));
      }
    }
  }

  @Test
  @Timeout(10)
  void upgradesConstrainedWebSocketRoutesAndFallsBackToPlainParameters() throws Exception {
    {
      var app =
          new Shoostr()
              .modifyServer(server -> ((ServerConnector) server.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup17 = app::close;
          var client = HttpClient.newHttpClient()) {
        app.routes()
            .path(
                "/rooms",
                rooms -> {
                  rooms.websocket(
                      "/{name}",
                      (request, ignoredServerUpgradeResponse) ->
                          new EchoListener(
                              "plain:" + request.pathParam("name").orElseThrow() + ":"));
                  rooms.websocket(
                      "/{id:[0-9]+}",
                      (request, ignoredServerUpgradeResponse) ->
                          new EchoListener(
                              "digits:" + request.pathParam("id").orElseThrow() + ":"));
                });
        app.start();

        assertEquals("digits:42:ping", sendAndReceive(client, app, "/rooms/42"));
        assertEquals("plain:alpha:ping", sendAndReceive(client, app, "/rooms/alpha"));
      }
    }
  }

  @Test
  @Timeout(10)
  void rejectsAnOriginInRouteAdmissionBeforeCallingTheListenerFactory() throws Exception {
    var factoryCalls = new AtomicInteger();

    {
      var app =
          new Shoostr()
              .modifyServer(server -> ((ServerConnector) server.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup18 = app::close;
          var client = HttpClient.newHttpClient()) {
        var admission =
            createAdmissionForRejectsAnOriginInRouteAdmissionBeforeCallingTheListenerFactory();
        app.routes()
            .path(
                "/",
                routes ->
                    routes.websocket(
                        "/private",
                        (ignoredRequest, ignoredServerUpgradeResponse) -> {
                          factoryCalls.incrementAndGet();

                          return new EchoListener();
                        }),
                lifecycle -> lifecycle.withExtension(admission));
        app.start();

        var failure =
            assertFailureForRejectsAnOriginInRouteAdmissionBeforeCallingTheListenerFactory(
                client, app);

        assertEquals(
            403,
            assertInstanceOf(WebSocketHandshakeException.class, failure.getCause())
                .getResponse()
                .statusCode());
        assertEquals(0, factoryCalls.get());
      }
    }
  }

  private AdmissionExtension
      createAdmissionForRejectsAnOriginInRouteAdmissionBeforeCallingTheListenerFactory() {
    return new AdmissionExtension((request, ignoredResponse) -> {
      if (request.header("Origin").filter("https://allowed.example"::equals).isEmpty()) {
        throw new ForbiddenException();
      }
    });
  }

  private ExecutionException
      assertFailureForRejectsAnOriginInRouteAdmissionBeforeCallingTheListenerFactory(
          HttpClient client, Shoostr app) {
    return assertThrows(
        ExecutionException.class,
        () ->
            client
                .newWebSocketBuilder()
                .header("Origin", "https://denied.example")
                .buildAsync(
                    URI.create("ws://127.0.0.1:" + app.port() + "/private"),
                    new WebSocket.Listener() {})
                .get(3, TimeUnit.SECONDS));
  }

  @Test
  @Timeout(10)
  void authenticatesBeforeUpgradeAndPassesThePrincipalToTheNewListener() throws Exception {
    var accepted = new CompletableFuture<String>();

    {
      var app =
          new Shoostr()
              .modifyServer(server -> ((ServerConnector) server.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup19 = app::close;
          var client = HttpClient.newHttpClient()) {
        var admission = chatAuthentication();
        app.routes()
            .path(
                "/",
                routes ->
                    routes.websocket(
                        "/authenticated",
                        (request, ignoredServerUpgradeResponse) ->
                            new EchoListener(request.principal().orElseThrow().getName() + ":")),
                lifecycle -> lifecycle.withExtension(admission));
        app.start();

        var rejected =
            assertRejectedForAuthenticatesBeforeUpgradeAndPassesThePrincipalToTheNewListener(
                client, app);
        var challenge =
            assertInstanceOf(WebSocketHandshakeException.class, rejected.getCause()).getResponse();

        assertEquals(401, challenge.statusCode());
        assertEquals(
            "Bearer realm=\"chat\"",
            challenge.headers().firstValue("WWW-Authenticate").orElseThrow());

        var socket =
            createSocketForAuthenticatesBeforeUpgradeAndPassesThePrincipalToTheNewListener(
                client, app, accepted);
        socket.sendText("hello", true).get(3, TimeUnit.SECONDS);

        assertEquals("alice:hello", accepted.get(3, TimeUnit.SECONDS));

        socket.abort();
      }
    }
  }

  private ExecutionException
      assertRejectedForAuthenticatesBeforeUpgradeAndPassesThePrincipalToTheNewListener(
          HttpClient client, Shoostr app) {
    return assertThrows(
        ExecutionException.class,
        () ->
            client
                .newWebSocketBuilder()
                .buildAsync(
                    URI.create("ws://127.0.0.1:" + app.port() + "/authenticated"),
                    new WebSocket.Listener() {})
                .get(3, TimeUnit.SECONDS));
  }

  private WebSocket createSocketForAuthenticatesBeforeUpgradeAndPassesThePrincipalToTheNewListener(
      HttpClient client, Shoostr app, CompletableFuture<String> accepted)
      throws InterruptedException, ExecutionException, TimeoutException {
    return client
        .newWebSocketBuilder()
        .header("Authorization", "Bearer secret")
        .buildAsync(
            URI.create("ws://127.0.0.1:" + app.port() + "/authenticated"), receiving(accepted))
        .get(3, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(10)
  void completesInvalidUpgradeWith426AndAllowsFactoryHandshakeHeader() throws Exception {
    var factoryCalls = new AtomicInteger();

    {
      var app =
          new Shoostr()
              .modifyServer(server -> ((ServerConnector) server.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup20 = app::close) {
        app.routes()
            .websocket(
                "/wire",
                (ignoredRequest, upgrade) -> {
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
  }

  @Test
  @Timeout(10)
  void mapsFactoryRejectionAndReportsOneApplicationFailure() throws Exception {
    var outcome = new CompletableFuture<RequestOutcome>();
    var notifications = new AtomicInteger();

    {
      var app =
          new Shoostr()
              .modifyServer(server -> ((ServerConnector) server.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup21 = app::close;
          var client = HttpClient.newHttpClient()) {
        app.exception(
            BadRequestException.class,
            (ignoredFailure, ignoredRequest, response) ->
                response.status(422).setHeader("X-Error-Mapped", "yes").text("mapped"));
        app.afterRequest(result -> {
          notifications.incrementAndGet();
          outcome.complete(result);
        });
        app.routes()
            .websocket(
                "/reject",
                (ignoredRequest, ignoredServerUpgradeResponse) -> {
                  throw new BadRequestException();
                });
        app.start();

        var failure =
            assertFailureForMapsFactoryRejectionAndReportsOneApplicationFailure(client, app);
        var handshake =
            assertInstanceOf(WebSocketHandshakeException.class, failure.getCause()).getResponse();

        assertEquals(422, handshake.statusCode());
        assertEquals("yes", handshake.headers().firstValue("X-Error-Mapped").orElseThrow());
        assertEquals(422, outcome.get(3, TimeUnit.SECONDS).statusCode());
        assertInstanceOf(BadRequestException.class, outcome.getNow(null).applicationFailure());
        assertEquals(1, notifications.get());
      }
    }
  }

  private ExecutionException assertFailureForMapsFactoryRejectionAndReportsOneApplicationFailure(
      HttpClient client, Shoostr app) {
    return assertThrows(
        ExecutionException.class,
        () ->
            client
                .newWebSocketBuilder()
                .buildAsync(
                    URI.create("ws://127.0.0.1:" + app.port() + "/reject"),
                    new WebSocket.Listener() {})
                .get(3, TimeUnit.SECONDS));
  }

  @Test
  @Timeout(10)
  void retainsFactoryFailureWhenTheErrorResponseFlushHookAlsoFails() throws Exception {
    var original = new BadRequestException();
    var postFlush = new IOException("after flush");
    var outcome = new CompletableFuture<RequestOutcome>();
    var notifications = new AtomicInteger();

    {
      var app =
          new Shoostr()
              .modifyServer(server -> ((ServerConnector) server.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup22 = app::close;
          var client = HttpClient.newHttpClient()) {
        app.afterRequest(result -> {
          notifications.incrementAndGet();
          outcome.complete(result);
        });
        app.afterResponseFlush((ignoredRequest, ignoredResponse) -> {
          throw postFlush;
        });
        app.exception(
            BadRequestException.class,
            (ignoredFailure, ignoredRequest, response) -> response.text("mapped"));
        app.routes()
            .websocket(
                "/flush-failure",
                (ignoredRequest, ignoredServerUpgradeResponse) -> {
                  throw original;
                });
        app.start();

        var failure =
            assertFailureForRetainsFactoryFailureWhenTheErrorResponseFlushHookAlsoFails(
                client, app);

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
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @Timeout(10)
  void retainsFactoryAndRendererFailuresWhenTheHandshakeResponseAborts(boolean sameFailure)
      throws Exception {
    var original = new BadRequestException();
    var renderer = new LinkageError("error renderer failed");
    var completed = new CompletableFuture<RequestOutcome>();
    var notifications = new AtomicInteger();
    var factories = new AtomicInteger();
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.modifyServer(server -> {
        ((ServerConnector) server.getConnectors()[0]).setPort(0);
        server.setStopTimeout(0);
      });
      app.afterRequest(outcome -> {
        notifications.incrementAndGet();
        completed.complete(outcome);
      });
      app.exception(
          BadRequestException.class,
          (failure, request, response) -> {
            if (sameFailure) {
              response.startStream("text/plain");
              throw original;
            }

            throw renderer;
          });
      app.routes()
          .websocket(
              "/renderer-failure",
              (request, upgrade) -> {
                factories.incrementAndGet();
                throw original;
              });
      app.start();

      sendSingleRejectedRendererHandshake(app);
      var outcome = completed.get(3, TimeUnit.SECONDS);
      assertSame(original, outcome.applicationFailure());
      if (sameFailure) {
        assertEquals(0, original.getSuppressed().length);
      } else {
        assertTrue(List.of(original.getSuppressed()).contains(renderer));
      }

      var transport = outcome.transportFailure();
      assertNotNull(transport);
      assertSame(sameFailure ? original : renderer, rootCause(transport));
      assertEquals(1, notifications.get());
      assertEquals(1, factories.get());
    }
  }

  private void sendSingleRejectedRendererHandshake(Shoostr app) throws Exception {
    try (var peer = new Socket(InetAddress.getAllByName("127.0.0.1")[0], app.port())) {
      peer.setSoTimeout(3000);
      var handshake = createHandshakeForUpgradeUnreadPeer("/renderer-failure");
      peer.getOutputStream().write(handshake.getBytes(StandardCharsets.US_ASCII));
      peer.getOutputStream().flush();
      var reader =
          new BufferedReader(
              new InputStreamReader(peer.getInputStream(), StandardCharsets.US_ASCII));

      try {
        var status = reader.readLine();
        if (status != null) {
          assertTrue(
              status.startsWith("HTTP/1.1 400") || status.startsWith("HTTP/1.1 500"), status);
        }
      } catch (SocketException expectedAbort) {
        // A renderer Error may abort transport before any HTTP response can be written.
      }
    }
  }

  private Throwable rootCause(Throwable failure) {
    while (failure.getCause() != null) {
      failure = failure.getCause();
    }
    return failure;
  }

  private ExecutionException
      assertFailureForRetainsFactoryFailureWhenTheErrorResponseFlushHookAlsoFails(
          HttpClient client, Shoostr app) {
    return assertThrows(
        ExecutionException.class,
        () ->
            client
                .newWebSocketBuilder()
                .buildAsync(
                    URI.create("ws://127.0.0.1:" + app.port() + "/flush-failure"),
                    new WebSocket.Listener() {})
                .get(3, TimeUnit.SECONDS));
  }

  @Test
  @Timeout(10)
  void doesNotAcceptUnexpectedFailuresNullListenersOrUnofferedProtocols() throws Exception {
    {
      var app =
          new Shoostr()
              .modifyServer(server -> ((ServerConnector) server.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup23 = app::close;
          var client = HttpClient.newHttpClient()) {
        app.routes()
            .websocket(
                "/failure",
                (ignoredRequest, ignoredServerUpgradeResponse) -> {
                  throw new IllegalStateException("broken factory");
                });
        app.routes().websocket("/null", (ignoredRequest, ignoredServerUpgradeResponse) -> null);
        app.routes()
            .websocket(
                "/protocol",
                (ignoredRequest, upgrade) -> {
                  upgrade.setAcceptedSubProtocol("not-offered");

                  return new EchoListener();
                });
        app.start();

        assertEquals(500, failedHandshakeStatus(client, app, "/failure"));
        assertEquals(500, failedHandshakeStatus(client, app, "/null"));
        assertNotEquals(101, failedHandshakeStatus(client, app, "/protocol"));
      }
    }
  }

  @Test
  @Timeout(10)
  void exchangesBinaryPingPongAndNormalCloseThroughTheNativeSession() throws Exception {
    var binary = new CompletableFuture<byte[]>();
    var pong = new CompletableFuture<byte[]>();
    var closed = new CompletableFuture<Integer>();

    {
      var app =
          new Shoostr()
              .modifyServer(server -> ((ServerConnector) server.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup24 = app::close;
          var client = HttpClient.newHttpClient()) {
        app.routes()
            .websocket(
                "/binary",
                (ignoredRequest, ignoredServerUpgradeResponse) -> new BinaryEchoListener());
        app.start();
        var socket =
            createSocketForExchangesBinaryPingPongAndNormalCloseThroughTheNativeSession(
                client, app, binary, pong, closed);

        var payload = new byte[] {1, 2, 3};
        socket.sendBinary(ByteBuffer.wrap(payload), true).get(3, TimeUnit.SECONDS);

        assertArrayEquals(payload, binary.get(3, TimeUnit.SECONDS));

        socket.sendPing(ByteBuffer.wrap(payload)).get(3, TimeUnit.SECONDS);

        assertArrayEquals(payload, pong.get(3, TimeUnit.SECONDS));

        socket.sendClose(1000, "done").get(3, TimeUnit.SECONDS);

        assertEquals(1000, closed.get(3, TimeUnit.SECONDS));
      }
    }
  }

  private WebSocket createSocketForExchangesBinaryPingPongAndNormalCloseThroughTheNativeSession(
      HttpClient client,
      Shoostr app,
      CompletableFuture<byte[]> binary,
      CompletableFuture<byte[]> pong,
      CompletableFuture<Integer> closed)
      throws InterruptedException, ExecutionException, TimeoutException {
    return client
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
  }

  @Test
  @Timeout(15)
  void closesAnActiveWebSocketWhenTheAppStops() throws Exception {
    var terminated = new CompletableFuture<Void>();

    {
      var app =
          new Shoostr()
              .modifyServer(server -> ((ServerConnector) server.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup25 = app::close;
          var client = HttpClient.newHttpClient()) {
        app.routes()
            .websocket(
                "/live", (ignoredRequest, ignoredServerUpgradeResponse) -> new EchoListener());
        app.start();
        var socket = createSocketForClosesAnActiveWebSocketWhenTheAppStops(client, app, terminated);

        app.close();
        terminated.get(3, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(3)).until(socket::isOutputClosed);
        socket.abort();
      }
    }
  }

  private WebSocket createSocketForClosesAnActiveWebSocketWhenTheAppStops(
      HttpClient client, Shoostr app, CompletableFuture<Void> terminated)
      throws InterruptedException, ExecutionException, TimeoutException {
    return client
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
  }

  @Test
  @Timeout(10)
  void boundsOutgoingFramesAndAllowsPerSessionMessageLimits() throws Exception {
    var defaultOutgoing = new CompletableFuture<Integer>();
    var configuredOutgoing = new CompletableFuture<Integer>();
    var accepted = new CompletableFuture<String>();
    var closed = new CompletableFuture<Integer>();

    {
      var app =
          new Shoostr()
              .modifyServer(server -> ((ServerConnector) server.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup26 = app::close;
          var client = HttpClient.newHttpClient()) {
        app.routes()
            .websocket(
                "/bounded",
                (ignoredRequest, ignoredServerUpgradeResponse) ->
                    new LimitedListener(defaultOutgoing, configuredOutgoing, accepted));
        app.start();
        var socket =
            createSocketForBoundsOutgoingFramesAndAllowsPerSessionMessageLimits(
                client, app, closed);

        assertEquals(32, defaultOutgoing.get(3, TimeUnit.SECONDS));
        assertEquals(2, configuredOutgoing.get(3, TimeUnit.SECONDS));

        socket.sendText("four", true).get(3, TimeUnit.SECONDS);

        assertEquals("four", accepted.get(3, TimeUnit.SECONDS));

        socket.sendText("five!", true).get(3, TimeUnit.SECONDS);

        assertEquals(1009, closed.get(3, TimeUnit.SECONDS));
      }
    }
  }

  private WebSocket createSocketForBoundsOutgoingFramesAndAllowsPerSessionMessageLimits(
      HttpClient client, Shoostr app, CompletableFuture<Integer> closed)
      throws InterruptedException, ExecutionException, TimeoutException {
    return client
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
  }

  @Test
  @Timeout(15)
  void failsQueuedSendsToAnUnreadPeerAndReleasesTheSessionOnDisconnect() throws Exception {
    var opened = new CompletableFuture<Session>();
    var terminal = new CompletableFuture<Void>();
    var sendFailure = new CompletableFuture<Throwable>();
    var server = new AtomicReference<Server>();

    {
      var app =
          new Shoostr()
              .modifyServer(nativeServerConfiguration ->
                  ((ServerConnector) nativeServerConfiguration.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup27 = app::close;
          var peer = new Socket()) {
        app.modifyServer(server::set);
        app.routes()
            .websocket(
                "/slow",
                (ignoredRequest, ignoredServerUpgradeResponse) ->
                    new SlowPeerListener(opened, terminal));
        app.start();
        final var container =
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
  }

  @Test
  @Timeout(15)
  void rejectsProducerOverflowAndClearsQueuedMessagesAfterPeerDisconnect() throws Exception {
    var opened = new CompletableFuture<SerializedProducerListener>();
    var completed = new CountDownLatch(1);
    var sendFailure = new AtomicReference<Throwable>();

    {
      var app =
          new Shoostr()
              .modifyServer(server -> ((ServerConnector) server.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup28 = app::close;
          var peer = new Socket()) {
        app.routes()
            .websocket(
                "/producer",
                (ignoredRequest, ignoredServerUpgradeResponse) ->
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
  }

  @Test
  @Timeout(15)
  void serializesConcurrentProducersUntilEachNativeSendCallbackCompletes() throws Exception {
    var opened = new CompletableFuture<SerializedProducerListener>();
    var completed = new CountDownLatch(20);
    var sendFailure = new AtomicReference<Throwable>();
    var received = new LinkedBlockingQueue<String>();
    var current = new StringBuilder();

    {
      var app =
          new Shoostr()
              .modifyServer(server -> ((ServerConnector) server.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup29 = app::close;
          var client = HttpClient.newHttpClient();
          var producers = Executors.newFixedThreadPool(4)) {
        app.routes()
            .websocket(
                "/ordered",
                (ignoredRequest, ignoredServerUpgradeResponse) ->
                    new SerializedProducerListener(opened, completed, sendFailure));
        app.start();
        final var socket =
            createSocketForSerializesConcurrentProducersUntilEachNativeSendCallbackCompletes(
                client, app, current, received);
        var listener = opened.get(3, TimeUnit.SECONDS);
        var begin = new CountDownLatch(1);
        var tasks = new ArrayList<Future<?>>();
        for (int producer = 0; producer < 4; producer++) {
          final int id = producer;
          tasks.add(
              producers.submit(() -> {
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
  }

  private WebSocket
      createSocketForSerializesConcurrentProducersUntilEachNativeSendCallbackCompletes(
          HttpClient client,
          Shoostr app,
          StringBuilder current,
          LinkedBlockingQueue<String> received)
          throws InterruptedException, ExecutionException, TimeoutException {
    return client
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
  }

  public static final class GreetingListener implements Session.Listener {
    private final AtomicReference<Session> connection;
    private final CompletableFuture<Void> firstSent;
    private final CompletableFuture<Void> replySent;

    public GreetingListener(CompletableFuture<Void> firstSent, CompletableFuture<Void> replySent) {
      this.connection = new AtomicReference<>();
      this.firstSent = Objects.requireNonNull(firstSent);
      this.replySent = Objects.requireNonNull(replySent);
    }

    @Override
    public void onWebSocketOpen(Session session) {
      connection.set(session);
      session.sendText(
          "server-first",
          Callback.from(() -> firstSent.complete(null), firstSent::completeExceptionally));
      session.demand();
    }

    @Override
    public void onWebSocketText(String message) {
      var session = Objects.requireNonNull(connection.get());
      session.sendText(
          "reply:" + message,
          Callback.from(
              () -> {
                replySent.complete(null);
                session.demand();
              },
              replySent::completeExceptionally));
    }

    @Override
    public void onWebSocketError(Throwable failure) {
      firstSent.completeExceptionally(failure);
      replySent.completeExceptionally(failure);
    }
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
    private final ReentrantLock producerLock;
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
      this.producerLock = new ReentrantLock();
      this.session = Optional.empty();
    }

    @Override
    public void onWebSocketOpen(Session connection) {
      session = Optional.of(connection);
      connection.demand();
      opened.complete(this);
    }

    public void submit(String message) {
      if (enqueue(message)) {
        sendNext();
      }
    }

    private boolean enqueue(String message) {
      return Boolean.TRUE.equals(
          getUnderLock(
              producerLock,
              () -> {
                if (terminal || pending.size() >= maxPending) {
                  throw new IllegalStateException("WebSocket producer cannot accept more messages");
                }

                accepted.add(message);
                pending.addLast(message);
                final var start = !sending;
                sending = true;

                return start;
              }));
    }

    public List<String> accepted() {
      return Objects.requireNonNull(getUnderLock(producerLock, () -> List.copyOf(accepted)));
    }

    public int pendingCount() {
      return Objects.requireNonNull(getUnderLock(producerLock, pending::size));
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

    private void stop() {
      runUnderLock(
          producerLock,
          () -> {
            terminal = true;
            pending.clear();
            sending = false;
          });
    }

    private void sendNext() {
      final var next = pollNext();
      if (next == null) {
        return;
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

    private @Nullable String pollNext() {
      return getUnderLock(
          producerLock,
          () -> {
            final var next = pending.pollFirst();
            if (next == null) {
              sending = false;
            }

            return next;
          });
    }
  }
}
