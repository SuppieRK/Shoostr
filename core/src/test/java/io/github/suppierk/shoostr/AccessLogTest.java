package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.exceptions.ForbiddenException;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.websocket.api.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class AccessLogTest {
  @Test
  @Timeout(10)
  void logsBoundedAcceptedWebSocketUpgradesWithoutHandshakeSecrets() throws Exception {
    var lines = new LinkedBlockingQueue<String>();
    var privateId = "private-id-" + "x".repeat(1024);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.afterRequest(new AccessLog(lines::add));
      app.routes().websocket("/socket/{id}", (_, _) -> new Session.Listener.AutoDemanding() {});
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
        var line = lines.poll(3, TimeUnit.SECONDS);
        assertNotNull(line);
        assertTrue(line.startsWith("method=GET route=/socket/{id} status=101 duration_ns="));
        assertTrue(line.endsWith(" application_failure=false transport_failure=false"));
        assertTrue(line.length() <= 160);
        assertFalse(line.contains("private"));
        assertFalse(line.contains("secret"));
        assertFalse(line.contains("\n"));
        assertFalse(line.contains("\r"));
      } finally {
        socket.abort();
      }
    }

    assertTrue(lines.isEmpty());
  }

  @Test
  @Timeout(10)
  void logsBoundedDeniedWebSocketUpgradesWithoutHandshakeOrFailureSecrets() throws Exception {
    var lines = new LinkedBlockingQueue<String>();
    var factoryCalls = new AtomicInteger();
    var privateId = "private-id-" + "x".repeat(1024);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.afterRequest(new AccessLog(lines::add));
      app.beforeRouteHandler(
          (_, _) -> {
            throw new ForbiddenException("private failure details");
          });
      app.routes()
          .websocket(
              "/socket/{id}",
              (_, _) -> {
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

      var line = lines.poll(3, TimeUnit.SECONDS);
      assertNotNull(line);
      assertTrue(line.startsWith("method=GET route=/socket/{id} status=403 duration_ns="));
      assertTrue(line.endsWith(" application_failure=true transport_failure=false"));
      assertTrue(line.length() <= 160);
      assertFalse(line.contains("private"));
      assertFalse(line.contains("secret"));
      assertFalse(line.contains("\n"));
      assertFalse(line.contains("\r"));
    }

    assertTrue(lines.isEmpty());
  }

  @Test
  void logsTerminalMetadataWithoutCredentialsIdentifiersBodiesOrFailureMessages() throws Exception {
    var lines = new LinkedBlockingQueue<String>();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.afterRequest(new AccessLog(lines::add));
      app.routes()
          .post(
              "/orders/{id}",
              (_, _) -> {
                throw new IllegalStateException("private failure details");
              });

      try (var test =
          TestServer.start(
              app,
              client -> client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {
        var result =
            test.send(
                request ->
                    request
                        .path("/orders/private-id?secret=value")
                        .header("Authorization", "Bearer private-token")
                        .header("Cookie", "session=private-cookie")
                        .method("POST")
                        .body("private body".getBytes(StandardCharsets.UTF_8)),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(500, result.statusCode());
        var line = lines.poll(5, TimeUnit.SECONDS);
        assertNotNull(line);
        assertTrue(line.startsWith("method=POST route=/orders/{id} status=500 duration_ns="));
        assertTrue(line.endsWith(" application_failure=true transport_failure=false"));
        assertFalse(line.contains("private"));
        assertFalse(line.contains("secret"));
        assertTrue(lines.isEmpty());
      }
    }
  }
}
