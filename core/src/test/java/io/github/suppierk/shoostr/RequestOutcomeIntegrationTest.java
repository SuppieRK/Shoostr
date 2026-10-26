package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.exceptions.ForbiddenException;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.websocket.api.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class RequestOutcomeIntegrationTest {
  @Test
  @Timeout(10)
  void reportsAcceptedWebSocketUpgradeWithItsRegisteredTemplate() throws Exception {
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();
    var privateId = "private-id-" + "x".repeat(1024);

    try (var app =
            new Shoostr()
                .modifyServer(
                    nativeServer ->
                        ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));
        var client = HttpClient.newHttpClient()) {
      app.afterRequest(outcomes::add);
      app.routes()
          .websocket(
              "/socket/{id}", (ignored, ignored2) -> new Session.Listener.AutoDemanding() {});
      app.start();
      var socket =
          client
              .newWebSocketBuilder()
              .connectTimeout(Duration.ofSeconds(3))
              .header("Authorization", "Bearer private-token-" + "y".repeat(1024))
              .header("Cookie", "session=private-cookie-" + "z".repeat(1024))
              .buildAsync(
                  URI.create(
                      "ws://127.0.0.1:"
                          + app.port()
                          + "/socket/"
                          + privateId
                          + "?secret=private-query"),
                  new WebSocket.Listener() {})
              .get(3, TimeUnit.SECONDS);

      try {
        var outcome = outcomes.poll(3, TimeUnit.SECONDS);
        assertNotNull(outcome);
        assertEquals("GET", outcome.method());
        assertEquals("/socket/{id}", outcome.routePattern());
        assertEquals(101, outcome.statusCode());
        assertTrue(outcome.durationNanos() > 0);
        assertNull(outcome.applicationFailure());
        assertNull(outcome.transportFailure());
      } finally {
        socket.abort();
      }
    }

    assertTrue(outcomes.isEmpty());
  }

  @Test
  @Timeout(10)
  void reportsDeniedWebSocketUpgradeWithoutInvokingTheListenerFactory() throws Exception {
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();
    var factoryCalls = new AtomicInteger();
    var privateId = "private-id-" + "x".repeat(1024);

    try (var app =
            new Shoostr()
                .modifyServer(
                    nativeServer ->
                        ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));
        var client = HttpClient.newHttpClient()) {
      app.afterRequest(outcomes::add);
      app.beforeRouteHandler(
          (ignored3, ignored4) -> {
            throw new ForbiddenException("private failure details");
          });
      app.routes()
          .websocket(
              "/socket/{id}",
              (ignored5, ignored6) -> {
                factoryCalls.incrementAndGet();
                return new Session.Listener.AutoDemanding() {};
              });
      app.start();

      var rejection =
          assertThrows(
              ExecutionException.class,
              () ->
                  client
                      .newWebSocketBuilder()
                      .connectTimeout(Duration.ofSeconds(3))
                      .header("Authorization", "Bearer private-token-" + "y".repeat(1024))
                      .header("Cookie", "session=private-cookie-" + "z".repeat(1024))
                      .buildAsync(
                          URI.create(
                              "ws://127.0.0.1:"
                                  + app.port()
                                  + "/socket/"
                                  + privateId
                                  + "?secret=private-query"),
                          new WebSocket.Listener() {})
                      .get(3, TimeUnit.SECONDS));
      var handshake = assertInstanceOf(WebSocketHandshakeException.class, rejection.getCause());
      assertEquals(403, handshake.getResponse().statusCode());
      assertEquals(0, factoryCalls.get());

      var outcome = outcomes.poll(3, TimeUnit.SECONDS);
      assertNotNull(outcome);
      assertEquals("GET", outcome.method());
      assertEquals("/socket/{id}", outcome.routePattern());
      assertEquals(403, outcome.statusCode());
      assertTrue(outcome.durationNanos() > 0);
      assertInstanceOf(ForbiddenException.class, outcome.applicationFailure());
      assertNull(outcome.transportFailure());
    }

    assertTrue(outcomes.isEmpty());
  }

  @Test
  void reportsTheSameComposedTemplateAcrossDifferentIdentifiersAndQueries() throws Exception {
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();
    var app = new Shoostr().afterRequest(outcomes::add);
    app.routes()
        .path(
            "/orders",
            orders -> orders.get("/{id}", (ignored, response) -> response.text("ready")));

    try (var test = TestServer.start(app)) {
      for (var id : new String[] {"first-private-id", "second-private-id"}) {
        var response =
            test.send(
                request ->
                    request
                        .path("/orders/" + id + "?secret=" + id)
                        .header("Authorization", "Bearer private-token")
                        .header("Cookie", "session=private-cookie"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("ready", response.body());
        var outcome = outcomes.poll(5, TimeUnit.SECONDS);
        assertNotNull(outcome);
        assertEquals("GET", outcome.method());
        assertEquals("/orders/{id}", outcome.routePattern());
        assertEquals(200, outcome.statusCode());
        assertNull(outcome.applicationFailure());
        assertNull(outcome.transportFailure());
      }
    }

    assertTrue(outcomes.isEmpty());
  }

  @Test
  void retainsTheSelectedTemplateAndDiagnosticFailureAfterTheHandlerThrows() throws Exception {
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();
    var app = new Shoostr().afterRequest(outcomes::add);
    app.routes()
        .get(
            "/orders/{id}",
            (ignored, ignored2) -> {
              throw new IllegalStateException("private failure details");
            });

    try (var test = TestServer.start(app)) {
      assertEquals(
          500,
          test.send(request -> request.path("/orders/private-id?secret=private-query"))
              .statusCode());
      var outcome = outcomes.poll(5, TimeUnit.SECONDS);
      assertNotNull(outcome);
      assertEquals("/orders/{id}", outcome.routePattern());
      assertEquals(500, outcome.statusCode());
      var failure = assertInstanceOf(IllegalStateException.class, outcome.applicationFailure());
      assertEquals("private failure details", failure.getMessage());
      assertNull(outcome.transportFailure());
    }

    assertTrue(outcomes.isEmpty());
  }

  @Test
  void leavesTheTemplateAbsentInsteadOfSubstitutingAnUnmatchedRequestPath() throws Exception {
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();
    var app = new Shoostr().afterRequest(outcomes::add);
    app.routes().get("/orders/{id}", (ignored, response) -> response.text("ready"));

    try (var test = TestServer.start(app)) {
      assertEquals(
          404,
          test.send(request -> request.path("/missing-private-path?secret=private-query"))
              .statusCode());
      var outcome = outcomes.poll(5, TimeUnit.SECONDS);
      assertNotNull(outcome);
      assertNull(outcome.routePattern());
      assertEquals(404, outcome.statusCode());
      assertNull(outcome.applicationFailure());
      assertNull(outcome.transportFailure());
    }

    assertTrue(outcomes.isEmpty());
  }
}
