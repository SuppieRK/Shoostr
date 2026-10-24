package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RequestObservationTest {
  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void keepsTheObservationOpenAfterInvocationUntilTheSlowTransportTerminates(
      boolean local, boolean virtualApplication) throws Exception {
    var scopeClosed = new CountDownLatch(1);
    var terminal = new CompletableFuture<RequestOutcome>();

    try (var app =
            withExecutionMode(
                    new Shoostr(
                        new Options(
                            1024, 16 * 1024 * 1024, 1024, 1000, MultipartOptions.defaults())),
                    virtualApplication)
                .modifyServer(
                    nativeServer -> {
                      var nativeConnector = (ServerConnector) nativeServer.getConnectors()[0];
                      nativeConnector.setHost("127.0.0.1");
                      nativeConnector.setPort(0);
                      nativeConnector.setIdleTimeout(10000);
                    });
        var socket = new Socket()) {
      Function<Request, RequestObservation> factory =
          ignored ->
              new RequestObservation() {
                @Override
                public void close() {
                  scopeClosed.countDown();
                }

                @Override
                public void complete(RequestOutcome outcome) {
                  terminal.complete(outcome);
                }
              };
      if (!local) {
        app.observe(factory);
      }

      app.routes()
          .get(
              "/large",
              (ignored2, response) ->
                  response.body("application/octet-stream", new byte[16 * 1024 * 1024]),
              e -> {
                if (local) {
                  e.lifecycle().observe(factory);
                }
              });
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
      assertFalse(terminal.isDone());
      socket.setSoLinger(true, 0);
      socket.close();
      var outcome = terminal.get(5, TimeUnit.SECONDS);
      assertNotNull(outcome.transportFailure());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void isolatesFactoryScopeAndCompletionFailuresWithoutRejectingTraffic(boolean virtualApplication)
      throws Exception {
    var result = new CompletableFuture<RequestOutcome>();
    var closed = new AtomicBoolean();

    try (var app = withExecutionMode(new Shoostr(), virtualApplication)) {
      app.observe(
          ignored3 -> {
            throw new IllegalStateException("factory failed");
          });
      app.observe(
          ignored4 ->
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
          ignored5 ->
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
      app.routes().get("/ok", (ignored6, response) -> response.text("ok"));

      try (var test = TestServer.start(app)) {
        var response =
            test.send(request -> request.path("/ok"), HttpResponse.BodyHandlers.ofString());
        assertEquals("ok", response.body());
        var outcome = result.get(5, TimeUnit.SECONDS);
        assertEquals(200, outcome.statusCode());
        assertTrue(closed.get());
        assertNull(outcome.applicationFailure());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void scopesTheInvocationAndCompletesAfterCleanupForUnmatchedRequests(boolean virtualApplication)
      throws Exception {
    var current = new ThreadLocal<String>();
    var closed = new AtomicBoolean();
    var result = new CompletableFuture<RequestOutcome>();

    try (var app = withExecutionMode(new Shoostr(), virtualApplication)) {
      app.observe(
          ignored7 -> {
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
      app.onRequestHeaders((ignored8, ignored9) -> assertEquals("observed", current.get()));

      try (var test = TestServer.start(app)) {

        var response =
            test.send(request -> request.path("/missing"), HttpResponse.BodyHandlers.discarding());
        assertEquals(404, response.statusCode());
        assertEquals(404, result.get(5, TimeUnit.SECONDS).statusCode());
        assertNull(result.get().routePattern());
        assertThrows(IllegalStateException.class, () -> app.observe(ignored10 -> ignored11 -> {}));
      }
    }
  }

  private static Shoostr withExecutionMode(Shoostr app, boolean virtualApplication) {
    if (virtualApplication) {
      app.virtualThreads();
    }

    return app.onRequestHeaders(
        (request, response) ->
            assertEquals(virtualApplication, Thread.currentThread().isVirtual()));
  }
}
