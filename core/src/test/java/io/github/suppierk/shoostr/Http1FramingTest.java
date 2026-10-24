package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.exceptions.AuthenticationRequiredException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class Http1FramingTest {
  private static final String PROTECTED_REQUEST =
      "GET /protected/value HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer allowed\r\n\r\n";

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Content-Length: 4\r\nContent-Length: 5\r\n",
        "Content-Length: 5\r\nTransfer-Encoding: chunked\r\n",
        "Transfer-Encoding: chunked\r\nContent-Length: 5\r\n"
      })
  void rejectsAmbiguousHeadersBeforeAdmittingTheirTrailingProtectedRequest(String headers)
      throws Exception {
    var admissions = new AtomicInteger();
    var authenticationCalls = new AtomicInteger();
    var protectedCalls = new AtomicInteger();

    try (var app = app()) {
      // Jetty 12.1.14's default RFC9110 policy rejects both ambiguous framing forms.
      app.onRequestHeaders((ignored, ignored2) -> admissions.incrementAndGet());
      protectedRoute(app, authenticationCalls, protectedCalls);
      app.routes().post("/body", (request, response) -> response.text(request.bodyText()));
      app.routes().get("/healthy", (ignored3, response) -> response.text("healthy"));
      app.start();

      try (var socket = connect(app.port())) {
        var input = HttpTester.from(socket.getInputStream());
        write(
            socket,
            "POST /body HTTP/1.1\r\nHost: localhost\r\n"
                + headers
                + "\r\n0\r\n\r\n"
                + PROTECTED_REQUEST);
        var rejected = receive(input);
        assertTrue(rejected.getStatus() >= 400 && rejected.getStatus() < 500);
        assertNull(HttpTester.parseResponse(input));
        assertTrue(input.isEOF());
        assertEquals(0, admissions.get());
        assertEquals(0, authenticationCalls.get());
        assertEquals(0, protectedCalls.get());
      }

      assertHealthy(app);
    }
  }

  @Test
  void acceptsAChunkedBodyAndReusesTheSameHttp1Connection() throws Exception {
    try (var app = app()) {
      app.routes().post("/body", (request, response) -> response.text(request.bodyText()));
      app.routes().get("/healthy", (ignored4, response) -> response.text("healthy"));
      app.start();

      try (var socket = connect(app.port())) {
        var input = HttpTester.from(socket.getInputStream());
        write(
            socket,
            """
            POST /body HTTP/1.1\r
            Host: localhost\r
            Transfer-Encoding: chunked\r
            \r
            4\r
            body\r
            0\r
            \r
            """);
        var body = receive(input);
        assertEquals(200, body.getStatus());
        assertEquals("body", body.getContent());

        write(socket, "GET /healthy HTTP/1.1\r\nHost: localhost\r\n\r\n");
        var healthy = receive(input);
        assertEquals(200, healthy.getStatus());
        assertEquals("healthy", healthy.getContent());
      }
    }
  }

  @Test
  void keepsPipelinedResponsesOrderedAndAuthenticatesEachProtectedRequest() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var authenticationCalls = new AtomicInteger();
    var protectedCalls = new AtomicInteger();
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();

    try (var app = app()) {
      app.afterRequest(outcomes::add);
      protectedRoute(app, authenticationCalls, protectedCalls);
      app.exception(
          AuthenticationRequiredException.class,
          (ignored5, ignored6, response) -> response.text("denied"));
      app.routes()
          .get(
              "/slow",
              (ignored7, response) -> {
                entered.countDown();
                assertTrue(release.await(3, TimeUnit.SECONDS));
                response.text("first");
              });
      app.start();

      try (var socket = connect(app.port())) {
        try {
          var input = HttpTester.from(socket.getInputStream());
          write(
              socket,
              """
                  GET /slow HTTP/1.1\r
                  Host: localhost\r
                  \r
                  GET /protected/value HTTP/1.1\r
                  Host: localhost\r
                  \r
                  """
                  + PROTECTED_REQUEST);
          assertTrue(entered.await(3, TimeUnit.SECONDS));
          release.countDown();

          var first = receive(input);
          assertEquals(200, first.getStatus());
          assertEquals("first", first.getContent());
          var denied = receive(input);
          assertEquals(401, denied.getStatus());
          assertEquals("denied", denied.getContent());
          var permitted = receive(input);
          assertEquals(200, permitted.getStatus());
          assertEquals("permitted", permitted.getContent());
          assertEquals(200, completed(outcomes).statusCode());
          assertEquals(401, completed(outcomes).statusCode());
          assertEquals(200, completed(outcomes).statusCode());
          assertNull(outcomes.poll());
          assertEquals(2, authenticationCalls.get());
          assertEquals(1, protectedCalls.get());
        } finally {
          release.countDown();
        }
      }
    }
  }

  @Test
  void terminatesAnAdmittedMalformedBodyWithoutExecutingTheTrailingProtectedRequest()
      throws Exception {
    var initialRead = new CountDownLatch(1);
    var admissions = new AtomicInteger();
    var authenticationCalls = new AtomicInteger();
    var protectedCalls = new AtomicInteger();
    var failures = new LinkedBlockingQueue<IOException>();
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();

    try (var app = app()) {
      app.onRequestHeaders((ignored8, ignored9) -> admissions.incrementAndGet());
      app.afterRequest(outcomes::add);
      protectedRoute(app, authenticationCalls, protectedCalls);
      app.routes()
          .post(
              "/body",
              (request, ignored10) -> {
                var source = request.input();
                assertEquals('a', source.read());
                initialRead.countDown();
                var failure = assertThrows(IOException.class, source::read);
                failures.add(failure);
                throw failure;
              });
      app.routes().get("/healthy", (ignored11, response) -> response.text("healthy"));
      app.start();

      try (var socket = connect(app.port())) {
        var input = HttpTester.from(socket.getInputStream());
        write(
            socket,
            """
            POST /body HTTP/1.1\r
            Host: localhost\r
            Transfer-Encoding: chunked\r
            \r
            1\r
            a\r
            """);
        assertTrue(initialRead.await(3, TimeUnit.SECONDS));
        write(socket, "Z\r\nmalformed\r\n0\r\n\r\n" + PROTECTED_REQUEST);

        var rejected = receive(input);
        assertTrue(rejected.getStatus() >= 400 && rejected.getStatus() < 600);
        assertNull(HttpTester.parseResponse(input));
        assertTrue(input.isEOF());
        var failure = failures.poll(3, TimeUnit.SECONDS);
        assertNotNull(failure);
        assertSame(failure, completed(outcomes).applicationFailure());
        assertNull(outcomes.poll());
        assertEquals(1, admissions.get());
        assertEquals(0, authenticationCalls.get());
        assertEquals(0, protectedCalls.get());
      }

      assertHealthy(app);
    }
  }

  private static Shoostr app() {
    return new Shoostr(new Options(1024, 1024, 256, 1000, MultipartOptions.defaults()))
        .modifyServer(
            nativeServer -> {
              var nativeConnector = (ServerConnector) nativeServer.getConnectors()[0];
              nativeConnector.setHost("127.0.0.1");
              nativeConnector.setPort(0);
              nativeConnector.setIdleTimeout(30_000);
            });
  }

  private static void protectedRoute(
      Shoostr app, AtomicInteger authenticationCalls, AtomicInteger protectedCalls) {
    var authentication =
        new AuthenticationExtension() {
          @Override
          public void handle(Request request, Response response) {
            authenticationCalls.incrementAndGet();
            if (!"Bearer allowed".equals(request.header("Authorization").orElse(""))) {
              throw new AuthenticationRequiredException("Bearer realm=\"wire-test\"");
            }
          }
        };
    app.authentication(authentication);
    app.routes()
        .path(
            "/protected",
            routes ->
                routes.get(
                    "/value",
                    (ignored12, response) -> {
                      protectedCalls.incrementAndGet();
                      response.text("permitted");
                    }),
            extensions -> extensions.with(authentication));
  }

  private static Socket connect(int port) throws IOException {
    var socket = new Socket();

    try {
      socket.setSoTimeout(3000);
      socket.connect(new InetSocketAddress(InetAddress.getAllByName("127.0.0.1")[0], port), 3000);
      return socket;
    } catch (IOException failure) {
      socket.close();
      throw failure;
    }
  }

  private static void write(Socket socket, String request) throws IOException {
    socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
    socket.getOutputStream().flush();
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

  private static void assertHealthy(Shoostr app) throws IOException {
    try (var socket = connect(app.port())) {
      write(socket, "GET /healthy HTTP/1.1\r\nHost: localhost\r\n\r\n");
      var response = receive(HttpTester.from(socket.getInputStream()));
      assertEquals(200, response.getStatus());
      assertEquals("healthy", response.getContent());
    }
  }
}
