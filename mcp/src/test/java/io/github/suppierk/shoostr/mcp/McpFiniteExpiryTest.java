package io.github.suppierk.shoostr.mcp;

import static io.github.suppierk.shoostr.mcp.McpExpiryAssertions.assertNotBeforeExpiry;
import static io.github.suppierk.shoostr.mcp.McpExpiryAssertions.awaitExpiry;
import static io.github.suppierk.shoostr.mcp.McpExpiryAssertions.awaitTermination;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.SocketException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
                (ignored, ignored2) -> authorizations.incrementAndGet()),
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

      // Close the socket before joining a reader that may be blocked in native I/O.

      try (var reader = Executors.newSingleThreadExecutor();
          var socket = new Socket(test.baseUri().getHost(), test.baseUri().getPort())) {
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
        // Read the early rejection while the upload is delayed, before a later write can reset it.
        var received = reader.submit(() -> responseHeaders(socket));
        await("MCP identity extracted")
            .atMost(Duration.ofSeconds(5))
            .until(() -> extracted.getCount() == 0);
        assertTrue(expiry.get().isAfter(Instant.now()));

        awaitExpiry(expiry.get().plusMillis(100));

        try {
          socket.getOutputStream().write(body);
          socket.getOutputStream().flush();
        } catch (SocketException ignored) {
          // Early rejection may also reject the upload; a complete 401 is still required below.
        }

        await("complete MCP expiry challenge")
            .atMost(Duration.ofSeconds(2))
            .until(received::isDone);
        var response = received.get();
        assertTrue(response.getFirst().startsWith("HTTP/1.1 401 "), response.toString());
        assertEquals(
            List.of("Bearer error=\"invalid_token\""),
            response.stream()
                .skip(1)
                .filter(line -> line.toLowerCase(Locale.ROOT).startsWith("www-authenticate:"))
                .map(line -> line.substring(line.indexOf(':') + 1).strip())
                .toList());
        assertFalse(
            response.stream()
                .skip(1)
                .anyMatch(line -> line.toLowerCase(Locale.ROOT).startsWith("mcp-session-id:")));
        assertEquals(0, authorizations.get());
      }
    }
  }

  @Test
  @Timeout(15)
  void returnsTheExpiryChallengeWhenInitializationAuthorizationOutlivesItsToken() throws Exception {
    var expiry = new AtomicReference<Instant>();
    var interruptedAt = new AtomicReference<Instant>();
    var hold = new CountDownLatch(1);
    var mcp =
        Mcp.sync(
            new McpHttpSecurity(
                Set.of(),
                ignored3 -> {
                  var verifiedExpiry = Instant.now().plusSeconds(1);
                  expiry.set(verifiedExpiry);
                  return new McpIdentity("alice:client", McpTransportContext.EMPTY, verifiedExpiry);
                },
                (ignored4, ignored5) -> {
                  try {
                    // Keep authorization interruptible so the deadline can abort it.
                    hold.await();
                  } catch (InterruptedException failure) {
                    interruptedAt.set(Instant.now());
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Authorization was interrupted", failure);
                  }
                }),
            transport -> McpServer.sync(transport).build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var client = Executors.newSingleThreadExecutor();
        var test = TestServer.start(app)) {
      try {
        var received =
            client.submit(
                () ->
                    test.send(
                        request ->
                            request
                                .path("/mcp")
                                .method("POST")
                                .header("Content-Type", "application/json")
                                .header("Accept", "application/json, text/event-stream")
                                .body(INITIALIZE.getBytes(StandardCharsets.UTF_8)),
                        HttpResponse.BodyHandlers.ofString()));
        await("authorization received a verified identity")
            .atMost(Duration.ofSeconds(5))
            .until(() -> expiry.get() != null);
        var verifiedExpiry = expiry.get();
        awaitTermination(verifiedExpiry, () -> received.isDone() && interruptedAt.get() != null);
        var response = received.get();
        assertEquals(401, response.statusCode());
        assertEquals(
            "Bearer error=\"invalid_token\"",
            response.headers().firstValue("WWW-Authenticate").orElseThrow());
        assertTrue(response.headers().firstValue("Mcp-Session-Id").isEmpty());
        assertNotBeforeExpiry(verifiedExpiry, interruptedAt.get());
      } finally {
        hold.countDown();
      }
    }
  }

  private static List<String> responseHeaders(Socket socket) throws IOException {
    var input =
        new BufferedReader(
            new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
    var headers = new ArrayList<String>();
    for (String line; (line = input.readLine()) != null; ) {
      if (line.isEmpty()) {
        return headers;
      }

      headers.add(line);
    }
    throw new EOFException("MCP response ended before its headers were complete");
  }
}
