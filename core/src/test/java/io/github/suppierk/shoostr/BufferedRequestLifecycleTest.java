package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.http.exceptions.AuthenticationRequiredException;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import io.github.suppierk.shoostr.http.exceptions.ContentTooLargeException;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.QuietException;
import org.eclipse.jetty.server.HttpStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class BufferedRequestLifecycleTest {
  private static final String PAYLOAD = "01234567890123456789012345678901";

  @ParameterizedTest
  @ValueSource(ints = {404, 405})
  void advertisesConnectionClosureWhenRoutingRejectsAnUnavailableBody(int status) throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes().get("/get-only", (_, response) -> response.text("unexpected"));
      app.start();

      try (var socket = new Socket()) {
        connect(socket, app.port());
        var path = status == 404 ? "/missing" : "/get-only";
        socket
            .getOutputStream()
            .write(
                ("POST " + path + " HTTP/1.1\r\nHost: localhost\r\nContent-Length: 32\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        var rejected = receive(HttpTester.from(socket.getInputStream()));
        assertEquals(status, rejected.getStatus());
        assertEquals("close", rejected.get(HttpHeaders.CONNECTION.value()));
        assertEquals(-1, socket.getInputStream().read());
      }
    }
  }

  @Test
  void leavesRejectedBodyReadableByBeforeFlushCallbacks() throws Exception {
    var observedBody = new AtomicReference<String>();
    var authentication =
        new AuthenticationExtension() {
          @Override
          public void handle(Request request, Response response) {
            throw new AuthenticationRequiredException("Basic realm=\"test\"");
          }
        };

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.authentication(authentication)
          .beforeResponseFlush((request, _) -> observedBody.set(request.bodyText()))
          .routes()
          .post(
              "/body",
              (_, response) -> response.text("unexpected"),
              extensions -> extensions.get(authentication).required());

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/body")
                        .method("POST")
                        .body(PAYLOAD.getBytes(StandardCharsets.US_ASCII)));
        assertEquals(401, result.statusCode());
        assertEquals(PAYLOAD, observedBody.get());
      }
    }
  }

  @Test
  void leavesRejectedBodyReadableByACustomAuthenticationErrorRenderer() throws Exception {
    var authentication =
        new AuthenticationExtension() {
          @Override
          public void handle(Request request, Response response) {
            throw new AuthenticationRequiredException("Basic realm=\"test\"");
          }
        };

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.authentication(authentication)
          .exception(
              AuthenticationRequiredException.class,
              (_, request, response) -> response.text(request.bodyText()))
          .routes()
          .post(
              "/body",
              (_, response) -> response.text("unexpected"),
              extensions -> extensions.get(authentication).required());

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/body")
                        .method("POST")
                        .body(PAYLOAD.getBytes(StandardCharsets.US_ASCII)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, result.statusCode());
        assertEquals(PAYLOAD, result.body());
      }
    }
  }

  @Test
  void reusesHttp1ConnectionAfterAuthenticationRejectsAnAvailableBody() throws Exception {
    var authentication =
        new AuthenticationExtension() {
          @Override
          public void handle(Request request, Response response) {
            if (request.header("Authorization").isEmpty()) {
              throw new AuthenticationRequiredException("Basic realm=\"test\"");
            }
          }
        };

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.authentication(authentication)
          .routes()
          .post(
              "/body",
              (request, response) -> response.text(request.bodyText()),
              extensions -> extensions.get(authentication).required());
      app.start();

      try (var socket = new Socket()) {
        connect(socket, app.port());
        var input = HttpTester.from(socket.getInputStream());
        socket
            .getOutputStream()
            .write(
                ("POST /body HTTP/1.1\r\nHost: localhost\r\nContent-Length: 32\r\n\r\n" + PAYLOAD)
                    .getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        var rejected = receive(input);
        assertEquals(401, rejected.getStatus());
        assertNull(rejected.get(HttpHeaders.CONNECTION.value()));
        socket
            .getOutputStream()
            .write(
                ("POST /body HTTP/1.1\r\nHost: localhost\r\nAuthorization: present\r\n"
                        + "Content-Length: 32\r\n\r\n"
                        + PAYLOAD)
                    .getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        var accepted = receive(input);
        assertEquals(200, accepted.getStatus());
        assertEquals(PAYLOAD, accepted.getContent());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void advertisesConnectionClosureWhenAuthenticationRejectsAnUnavailableBody(
      boolean streamingRenderer) throws Exception {
    var authentication =
        new AuthenticationExtension() {
          @Override
          public void handle(Request request, Response response) {
            throw new AuthenticationRequiredException("Basic realm=\"test\"");
          }
        };

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      if (streamingRenderer) {
        app.exception(
            AuthenticationRequiredException.class,
            (_, _, response) ->
                response
                    .startStream(MediaType.TEXT_PLAIN)
                    .write("rejected".getBytes(StandardCharsets.US_ASCII)));
      }

      app.authentication(authentication)
          .routes()
          .post(
              "/body",
              (_, response) -> response.text("unexpected"),
              extensions -> extensions.get(authentication).required());
      app.start();

      try (var socket = new Socket()) {
        connect(socket, app.port());
        socket
            .getOutputStream()
            .write(
                "POST /body HTTP/1.1\r\nHost: localhost\r\nContent-Length: 32\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        var rejected = receive(HttpTester.from(socket.getInputStream()));
        assertEquals(401, rejected.getStatus());
        assertEquals("Basic realm=\"test\"", rejected.get("WWW-Authenticate"));
        assertEquals("close", rejected.get(HttpHeaders.CONNECTION.value()));
        assertEquals(-1, socket.getInputStream().read());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void acceptsAnExactDefaultLimitBodyAtTheLiveListener(boolean knownLength) throws Exception {
    var payload = new byte[1_048_576];
    Arrays.fill(payload, (byte) 0x5A);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
      app.routes()
          .post(
              "/body",
              (request, response) -> {
                assertArrayEquals(payload, request.bodyBytes());
                response.text("accepted");
              });
      app.start();
      var publisher =
          knownLength
              ? HttpRequest.BodyPublishers.ofByteArray(payload)
              : HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(payload));
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/body"))
                  .timeout(Duration.ofSeconds(3))
                  .POST(publisher)
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("accepted", result.body());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectsOneByteBeyondTheDefaultLimitAtTheLiveListener(boolean knownLength) throws Exception {
    var payload = new byte[1_048_577];
    Arrays.fill(payload, (byte) 0x5A);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
      app.routes().post("/body", (request, response) -> response.text(request.bodyText()));
      app.start();
      var publisher =
          knownLength
              ? HttpRequest.BodyPublishers.ofByteArray(payload)
              : HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(payload));
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/body"))
                  .timeout(Duration.ofSeconds(3))
                  // Await header-based rejection instead of racing it with the upload.
                  .expectContinue(knownLength)
                  .POST(publisher)
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(413, result.statusCode());
      assertEquals("Content Too Large", result.body());
    }
  }

  @Test
  void rejectsAnOversizedContentLengthWithoutWaitingForTheBody() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes().post("/body", (request, response) -> response.text(request.bodyText()));
      app.start();

      try (var socket = new Socket()) {
        connect(socket, app.port());
        socket
            .getOutputStream()
            .write(
                "POST /body HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1048577\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        var rejected = receive(HttpTester.from(socket.getInputStream()));
        assertEquals(413, rejected.getStatus());
        assertEquals("Content Too Large", rejected.getContent());
      }
    }
  }

  @Test
  void mapsOversizedChunkedBodyAndReusesHttp1Connection() throws Exception {
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();
    var rejections = new LinkedBlockingQueue<ContentTooLargeException>();

    try (var app = app()) {
      routes(app, outcomes, rejections);
      app.start();

      try (var socket = new Socket()) {
        connect(socket, app.port());
        var input = HttpTester.from(socket.getInputStream());
        socket
            .getOutputStream()
            .write(
                ("POST /body HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
                        + "20\r\n"
                        + PAYLOAD
                        + "\r\n0\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        var rejected = receive(input);
        assertEquals(413, rejected.getStatus());
        assertEquals("too large", rejected.getContent());
        var outcome = completed(outcomes);
        assertEquals(413, outcome.statusCode());
        assertSame(rejections.poll(3, TimeUnit.SECONDS), outcome.applicationFailure());
        assertInstanceOf(ContentTooLargeException.class, outcome.applicationFailure());
        assertNull(outcome.transportFailure());

        socket
            .getOutputStream()
            .write(
                "GET /ok HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        var accepted = receive(input);
        assertEquals(200, accepted.getStatus());
        assertEquals(Integer.toString(socket.getLocalPort()), accepted.getContent());
        assertSuccessfulCompletion(outcomes);
        assertNull(outcomes.poll());
      }
    }
  }

  @Test
  void rejectsOverflowWithoutWaitingForTerminatingChunk() throws Exception {
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();
    var rejections = new LinkedBlockingQueue<ContentTooLargeException>();

    try (var app = app()) {
      routes(app, outcomes, rejections);
      app.start();

      try (var socket = new Socket()) {
        connect(socket, app.port());
        socket
            .getOutputStream()
            .write(
                ("POST /body HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
                        + "11\r\n"
                        + PAYLOAD.substring(0, 17)
                        + "\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        var rejected = receive(HttpTester.from(socket.getInputStream()));
        assertEquals(413, rejected.getStatus());
        assertEquals("too large", rejected.getContent());
        assertEquals("close", rejected.get(HttpHeaders.CONNECTION.value()));
        var outcome = completed(outcomes);
        assertEquals(413, outcome.statusCode());
        assertSame(rejections.poll(3, TimeUnit.SECONDS), outcome.applicationFailure());
        assertInstanceOf(ContentTooLargeException.class, outcome.applicationFailure());
        assertNotNull(outcome.transportFailure());
        assertEquals(-1, socket.getInputStream().read());
        assertNull(outcomes.poll());
      }
    }
  }

  @Test
  void mapsCustomizedLengthMismatchAndReusesHttp1Connection() throws Exception {
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();
    var rejections = new LinkedBlockingQueue<BadRequestException>();

    try (var app = app()) {
      app.modifyHttpConfiguration(
          configuration ->
              configuration.addCustomizer(
                  (nativeRequest, _) -> {
                    if (!"/bad".equals(nativeRequest.getHttpURI().getPath())) {
                      return nativeRequest;
                    }

                    return new org.eclipse.jetty.server.Request.Wrapper(nativeRequest) {
                      @Override
                      public long getLength() {
                        return 3;
                      }
                    };
                  }));
      app.afterRequest(outcomes::add);
      app.exception(
          BadRequestException.class,
          (failure, _, response) -> {
            rejections.add(failure);
            response.text("bad length");
          });
      app.routes().post("/bad", (request, response) -> response.text(request.bodyText()));
      app.routes().get("/ok", (request, response) -> response.text(peerPort(request)));
      app.start();

      try (var socket = new Socket()) {
        connect(socket, app.port());
        var input = HttpTester.from(socket.getInputStream());
        socket
            .getOutputStream()
            .write(
                ("POST /bad HTTP/1.1\r\nHost: localhost\r\nContent-Length: 32\r\n\r\n" + PAYLOAD)
                    .getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        var rejected = receive(input);
        assertEquals(400, rejected.getStatus());
        assertEquals("bad length", rejected.getContent());
        var outcome = completed(outcomes);
        assertEquals(400, outcome.statusCode());
        assertSame(rejections.poll(3, TimeUnit.SECONDS), outcome.applicationFailure());
        assertInstanceOf(BadRequestException.class, outcome.applicationFailure());
        assertNull(outcome.transportFailure());

        socket
            .getOutputStream()
            .write(
                "GET /ok HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        assertEquals(200, receive(input).getStatus());
        assertSuccessfulCompletion(outcomes);
        assertNull(outcomes.poll());
      }
    }
  }

  @Test
  void mapsOversizedBodyAndReusesHttp2Connection() throws Exception {
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();
    var rejections = new LinkedBlockingQueue<ContentTooLargeException>();

    try (var app = app();
        var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build()) {
      app.http2();
      routes(app, outcomes, rejections);
      app.start();
      var base = "http://127.0.0.1:" + app.port();
      var warmup =
          client.send(
              HttpRequest.newBuilder(URI.create(base + "/ok"))
                  .timeout(Duration.ofSeconds(3))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, warmup.statusCode());
      assertEquals(HttpClient.Version.HTTP_2, warmup.version());
      assertSuccessfulCompletion(outcomes);

      var rejected =
          client.send(
              HttpRequest.newBuilder(URI.create(base + "/body"))
                  .timeout(Duration.ofSeconds(3))
                  .POST(
                      HttpRequest.BodyPublishers.ofInputStream(
                          () ->
                              new ByteArrayInputStream(
                                  PAYLOAD.getBytes(StandardCharsets.US_ASCII))))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(413, rejected.statusCode());
      assertEquals(HttpClient.Version.HTTP_2, rejected.version());
      assertEquals("too large", rejected.body());
      assertEquals(warmup.body(), rejected.headers().firstValue("X-Peer-Port").orElseThrow());
      var outcome = completed(outcomes);
      assertEquals(413, outcome.statusCode());
      assertSame(rejections.poll(3, TimeUnit.SECONDS), outcome.applicationFailure());
      assertInstanceOf(ContentTooLargeException.class, outcome.applicationFailure());
      // Rejection before END_STREAM may reset this HTTP/2 stream without closing the connection.
      var transportFailure = outcome.transportFailure();
      if (transportFailure != null) {
        assertSame(
            HttpStream.CONTENT_NOT_CONSUMED,
            assertInstanceOf(QuietException.RuntimeException.class, transportFailure).getCause());
      }

      var accepted =
          client.send(
              HttpRequest.newBuilder(URI.create(base + "/ok"))
                  .timeout(Duration.ofSeconds(3))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, accepted.statusCode());
      assertEquals(HttpClient.Version.HTTP_2, accepted.version());
      assertEquals(warmup.body(), accepted.body());
      assertSuccessfulCompletion(outcomes);
      assertNull(outcomes.poll());
    }
  }

  private static Shoostr app() {
    return new Shoostr(new Options("127.0.0.1", 0, 16, 1024, 64, 30_000));
  }

  private static void routes(
      Shoostr app,
      LinkedBlockingQueue<RequestOutcome> outcomes,
      LinkedBlockingQueue<ContentTooLargeException> rejections) {
    app.afterRequest(outcomes::add);
    app.exception(
        ContentTooLargeException.class,
        (failure, request, response) -> {
          rejections.add(failure);
          assertThrows(ContentTooLargeException.class, request::bodyBytes);
          response.setHeader("X-Peer-Port", peerPort(request));
          response.text("too large");
        });
    app.routes().post("/body", (request, response) -> response.text(request.bodyText()));
    app.routes().get("/ok", (request, response) -> response.text(peerPort(request)));
  }

  private static String peerPort(Request request) {
    return Integer.toString(((InetSocketAddress) request.remoteAddress().orElseThrow()).getPort());
  }

  private static void connect(Socket socket, int port) throws IOException {
    socket.setSoTimeout(3000);
    socket.connect(new InetSocketAddress(InetAddress.getAllByName("127.0.0.1")[0], port), 3000);
  }

  private static HttpTester.Response receive(HttpTester.Input input) throws IOException {
    var response = HttpTester.parseResponse(input);
    assertNotNull(response);
    return response;
  }

  private static RequestOutcome completed(LinkedBlockingQueue<RequestOutcome> outcomes)
      throws InterruptedException {
    var outcome = outcomes.poll(3, TimeUnit.SECONDS);
    assertNotNull(outcome);
    return outcome;
  }

  private static void assertSuccessfulCompletion(LinkedBlockingQueue<RequestOutcome> outcomes)
      throws InterruptedException {
    var outcome = completed(outcomes);
    assertEquals(200, outcome.statusCode());
    assertNull(outcome.applicationFailure());
    assertNull(outcome.transportFailure());
  }
}
