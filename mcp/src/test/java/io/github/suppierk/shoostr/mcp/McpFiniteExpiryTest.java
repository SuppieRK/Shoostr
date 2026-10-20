package io.github.suppierk.shoostr.mcp;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import java.net.Socket;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class McpFiniteExpiryTest {
  private static final String INITIALIZE =
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
        "protocolVersion":"2025-11-25","capabilities":{},
        "clientInfo":{"name":"finite-expiry","version":"1.0"}}}
      """;

  @ParameterizedTest
  @ValueSource(strings = {"initialize", "notification", "reply"})
  @Timeout(15)
  void rejectsAFiniteMessageWhoseBodyArrivesAfterVerifiedExpiry(String kind) throws Exception {
    var extracted = new CountDownLatch(1);
    var expiry = new AtomicReference<Instant>();
    var authorizations = new AtomicInteger();
    var mcp =
        Mcp.sync(
            new McpHttpSecurity(
                Set.of(),
                request -> {
                  if (request.header("X-Expiring").isEmpty()) {
                    return new McpIdentity("alice:client", McpTransportContext.EMPTY, null);
                  }

                  var deadline = Instant.now().plusSeconds(1);
                  expiry.set(deadline);
                  extracted.countDown();
                  return new McpIdentity("alice:client", McpTransportContext.EMPTY, deadline);
                },
                (_, _) -> authorizations.incrementAndGet()),
            transport -> McpServer.sync(transport).build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      String sessionHeader = "";
      if (!"initialize".equals(kind)) {
        var initialized =
            test.send(
                request ->
                    request
                        .path("/mcp")
                        .method("POST")
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .body(INITIALIZE.getBytes(StandardCharsets.UTF_8)));
        assertEquals(200, initialized.statusCode());
        sessionHeader =
            "Mcp-Session-Id: "
                + initialized.headers().firstValue("Mcp-Session-Id").orElseThrow()
                + "\r\n";
        authorizations.set(0);
      }

      try (var socket = new Socket(test.baseUri().getHost(), test.baseUri().getPort())) {
        socket.setSoTimeout(5000);
        var message =
            switch (kind) {
              case "notification" ->
                  "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
              case "reply" -> "{\"jsonrpc\":\"2.0\",\"id\":\"issued-request\",\"result\":{}}";
              default -> INITIALIZE;
            };
        var body = message.getBytes(StandardCharsets.UTF_8);
        var headers =
            "POST /mcp HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                + "Content-Type: application/json\r\nAccept: application/json, text/event-stream\r\n"
                + sessionHeader
                + "X-Expiring: true\r\n"
                + "Content-Length: "
                + body.length
                + "\r\n\r\n";
        socket.getOutputStream().write(headers.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
        assertTrue(extracted.await(5, TimeUnit.SECONDS));
        assertTrue(expiry.get().isAfter(Instant.now()));

        var target = expiry.get().plusMillis(100);
        var started = System.nanoTime();
        var initial = Duration.between(Instant.now(), target);
        await("the finite request body follows the verified expiry")
            .pollInSameThread()
            .pollDelay(Duration.ZERO)
            .pollInterval(Duration.ofMillis(10))
            .atMost(Duration.ofSeconds(5))
            .until(
                () -> {
                  if (Thread.interrupted()) {
                    throw new InterruptedException("The finite expiry wait was interrupted");
                  }

                  return Duration.ofNanos(System.nanoTime() - started).compareTo(initial) >= 0
                      && !Instant.now().isBefore(target);
                });

        socket.getOutputStream().write(body);
        socket.getOutputStream().flush();
        var response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(response.startsWith("HTTP/1.1 401"), response);
        assertTrue(
            response
                .toLowerCase(Locale.ROOT)
                .contains("www-authenticate: bearer error=\"invalid_token\""));
        assertFalse(response.toLowerCase(Locale.ROOT).contains("mcp-session-id:"));
        assertEquals(0, authorizations.get());
      }
    }
  }

  @Test
  @Timeout(15)
  void returnsTheExpiryChallengeWhenInitializationAuthorizationOutlivesItsToken() throws Exception {
    var mcp =
        Mcp.sync(
            new McpHttpSecurity(
                Set.of(),
                _ ->
                    new McpIdentity(
                        "alice:client", McpTransportContext.EMPTY, Instant.now().plusSeconds(1)),
                (_, _) -> {
                  try {
                    // Keep authorization interruptible so the deadline can abort it.
                    new CountDownLatch(1).await(5, TimeUnit.SECONDS);
                  } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Authorization was interrupted", failure);
                  }
                }),
            transport -> McpServer.sync(transport).build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var response =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .method("POST")
                      .header("Content-Type", "application/json")
                      .header("Accept", "application/json, text/event-stream")
                      .body(INITIALIZE.getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(401, response.statusCode());
      assertEquals(
          "Bearer error=\"invalid_token\"",
          response.headers().firstValue("WWW-Authenticate").orElseThrow());
      assertTrue(response.headers().firstValue("Mcp-Session-Id").isEmpty());
    }
  }
}
