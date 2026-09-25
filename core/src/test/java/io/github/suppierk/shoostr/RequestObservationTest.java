package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class RequestObservationTest {
  @Test
  void keepsTheObservationOpenAfterInvocationUntilTheSlowTransportTerminates() throws Exception {
    var scopeClosed = new CountDownLatch(1);
    var terminal = new CompletableFuture<RequestOutcome>();

    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 16 * 1024 * 1024, 1024, 10000));
        var socket = new Socket()) {
      app.observe(
          request ->
              new RequestObservation() {
                @Override
                public void close() {
                  scopeClosed.countDown();
                }

                @Override
                public void complete(RequestOutcome outcome) {
                  terminal.complete(outcome);
                }
              });
      app.routes()
          .get(
              "/large",
              (request, response) ->
                  response.body("application/octet-stream", new byte[16 * 1024 * 1024]));
      app.start();
      socket.setReceiveBufferSize(1024);
      socket.setSoTimeout(5000);
      socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), app.port()), 5000);
      socket
          .getOutputStream()
          .write(
              "GET /large HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                  .getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      assertTrue(scopeClosed.await(5, TimeUnit.SECONDS));
      assertTrue(!terminal.isDone());
      socket.setSoLinger(true, 0);
      socket.close();
      var outcome = terminal.get(5, TimeUnit.SECONDS);
      assertNotNull(outcome.transportFailure());
    }
  }

  @Test
  void isolatesFactoryScopeAndCompletionFailuresWithoutRejectingTraffic() throws Exception {
    var result = new CompletableFuture<RequestOutcome>();
    var closed = new AtomicBoolean();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.observe(
          request -> {
            throw new IllegalStateException("factory failed");
          });
      app.observe(
          request ->
              new RequestObservation() {
                @Override
                public void close() {
                  throw new IllegalStateException("scope failed");
                }

                @Override
                public void complete(RequestOutcome outcome) {
                  throw new IllegalStateException("recorder failed");
                }
              });
      app.observe(
          request ->
              new RequestObservation() {
                @Override
                public void close() {
                  closed.set(true);
                }

                @Override
                public void complete(RequestOutcome outcome) {
                  result.complete(outcome);
                }
              });
      app.routes().get("/ok", (request, response) -> response.text("ok"));
      app.start();
      var response =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/ok"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals("ok", response.body());
      var outcome = result.get(5, TimeUnit.SECONDS);
      assertEquals(200, outcome.statusCode());
      assertTrue(closed.get());
      assertNull(outcome.applicationFailure());
    }
  }

  @Test
  void scopesTheInvocationAndCompletesAfterCleanupForUnmatchedRequests() throws Exception {
    var current = new ThreadLocal<String>();
    var closed = new AtomicBoolean();
    var result = new CompletableFuture<RequestOutcome>();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.observe(
          request -> {
            current.set("observed");
            return new RequestObservation() {
              @Override
              public void close() {
                assertEquals("observed", current.get());
                current.remove();
                closed.set(true);
              }

              @Override
              public void complete(RequestOutcome outcome) {
                assertTrue(closed.get());
                result.complete(outcome);
              }
            };
          });
      app.onRequestHeaders((request, response) -> assertEquals("observed", current.get()));
      app.start();

      var response =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/missing"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      assertEquals(404, response.statusCode());
      assertEquals(404, result.get(5, TimeUnit.SECONDS).statusCode());
      assertNull(result.get().routePattern());
      assertThrows(IllegalStateException.class, () -> app.observe(request -> outcome -> {}));
    }
  }
}
