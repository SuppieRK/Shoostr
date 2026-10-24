package io.github.suppierk.shoostr.example.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(20)
class McpSecurityExampleTest {
  private static final String INITIALIZE =
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
        "protocolVersion":"2025-11-25","capabilities":{},
        "clientInfo":{"name":"security-recipe","version":"1.0"}}}
      """;
  private static final String IDENTITY =
      """
      {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"identity","arguments":{}}}
      """;

  @ParameterizedTest
  @ValueSource(
      strings = {"", "Basic reader", "Bearer", "Bearer unknown-token", "Bearer expired-token"})
  void rejectsMissingMalformedUnknownAndExpiredCredentials(String authorization) throws Exception {
    try (var test = TestServer.start(McpSecurityExample.application())) {
      var response =
          test.send(
              request -> {
                request.path("/mcp");
                if (!authorization.isEmpty()) {
                  request.header("Authorization", authorization);
                }
              });
      assertEquals(401, response.statusCode());
      var challenge = response.headers().firstValue("WWW-Authenticate").orElseThrow();
      assertTrue(challenge.startsWith("Bearer "));
      assertTrue(
          challenge.contains(
              "resource_metadata=\"https://resource.example/.well-known/oauth-protected-resource\""));
    }
  }

  @Test
  void deliversDetachedVerifiedClaimsToTheNativeTool() throws Exception {
    try (var test = TestServer.start(McpSecurityExample.application());
        var client =
            McpClient.sync(
                    HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                        .endpoint("/mcp")
                        .addHttpRequestCustomizer(
                            (builder, ignored, ignored2, ignored3, ignored4) ->
                                builder.header("Authorization", "Bearer reader-token"))
                        .build())
                .requestTimeout(Duration.ofSeconds(3))
                .initializationTimeout(Duration.ofSeconds(3))
                .build()) {
      client.initialize();

      try {
        var result =
            client.callTool(
                McpSchema.CallToolRequest.builder("identity").arguments(Map.of()).build());
        assertEquals(
            "alice/desktop",
            assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst()).text());
      } finally {
        assertTrue(client.closeGracefully());
      }
    }
  }

  @Test
  void checksCurrentScopesAndAllowsCredentialRefreshWithinTheSameSession() throws Exception {
    try (var test = TestServer.start(McpSecurityExample.application())) {
      var initialized = message(test, "reader-token", "", INITIALIZE);
      assertEquals(200, initialized.statusCode());
      var session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      assertEquals(
          202,
          message(
                  test,
                  "reader-token",
                  session,
                  "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
              .statusCode());
      var denied = message(test, "restricted-token", session, IDENTITY);
      assertEquals(403, denied.statusCode());
      assertEquals(
          "Bearer error=\"insufficient_scope\", scope=\"tools:read\", "
              + "resource_metadata=\"https://resource.example/.well-known/oauth-protected-resource\"",
          denied.headers().firstValue("WWW-Authenticate").orElseThrow());
      var refreshed = message(test, "refreshed-token", session, IDENTITY);
      assertEquals(200, refreshed.statusCode());
      var text = new String(refreshed.body(), StandardCharsets.UTF_8);
      var payload =
          text.lines()
              .filter(line -> line.startsWith("data:"))
              .map(line -> line.substring(5).strip())
              .findFirst()
              .orElse(text);
      var mapper = McpJsonDefaults.getMapper();
      var rpc = mapper.readValue(payload, McpSchema.JSONRPCResponse.class);
      var result = mapper.convertValue(rpc.result(), McpSchema.CallToolResult.class);
      assertEquals(
          "alice/desktop",
          assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst()).text());
      assertEquals(403, message(test, "restricted-token", session, IDENTITY).statusCode());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"other-client-token", "other-user-token"})
  void rejectsSessionUseAndDeletionByAnotherClientOrSubject(String token) throws Exception {
    try (var test = TestServer.start(McpSecurityExample.application())) {
      var initialized = message(test, "reader-token", "", INITIALIZE);
      assertEquals(200, initialized.statusCode());
      var session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var ping = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}";
      assertEquals(404, message(test, token, session, ping).statusCode());
      assertEquals(
          404,
          test.send(
                  request ->
                      request
                          .path("/mcp")
                          .method("DELETE")
                          .header("Authorization", "Bearer " + token)
                          .header("Mcp-Session-Id", session)
                          .header("MCP-Protocol-Version", "2025-11-25"))
              .statusCode());
      assertEquals(200, message(test, "reader-token", session, ping).statusCode());
    }
  }

  @Test
  void rejectsRepeatedCredentialsAndUntrustedOriginsButAdmitsTheExactOrigin() throws Exception {
    try (var test = TestServer.start(McpSecurityExample.application())) {
      assertEquals(
          401,
          test.send(
                  request ->
                      request
                          .path("/mcp")
                          .header("Authorization", "Bearer reader-token")
                          .header("Authorization", "Bearer reader-token"))
              .statusCode());
      assertEquals(
          403,
          test.send(request -> request.path("/mcp").header("Origin", "https://untrusted.example"))
              .statusCode());
      assertEquals(
          403,
          test.send(
                  request ->
                      request
                          .path("/mcp")
                          .header("Origin", "https://client.example")
                          .header("Origin", "https://client.example"))
              .statusCode());
      var accepted =
          test.send(
              request ->
                  request
                      .method("POST")
                      .path("/mcp")
                      .header("Authorization", "Bearer reader-token")
                      .header("Origin", "https://client.example")
                      .header("Content-Type", "application/json")
                      .header("Accept", "application/json, text/event-stream")
                      .body(INITIALIZE.getBytes(StandardCharsets.UTF_8)));
      assertEquals(200, accepted.statusCode());
      assertTrue(accepted.headers().firstValue("Mcp-Session-Id").isPresent());
    }
  }

  @Test
  void enforcesSessionQuotasAndReleasesDeletedCapacity() throws Exception {
    try (var test = TestServer.start(McpSecurityExample.application())) {
      var first = message(test, "reader-token", "", INITIALIZE);
      assertEquals(200, first.statusCode());
      var firstSession = first.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var second = message(test, "reader-token", "", INITIALIZE);
      assertEquals(200, second.statusCode());
      assertEquals(429, message(test, "reader-token", "", INITIALIZE).statusCode());
      assertEquals(200, message(test, "other-client-token", "", INITIALIZE).statusCode());
      assertEquals(200, message(test, "other-user-token", "", INITIALIZE).statusCode());
      assertEquals(503, message(test, "other-client-token", "", INITIALIZE).statusCode());
      assertEquals(
          200,
          test.send(
                  request ->
                      request
                          .path("/mcp")
                          .method("DELETE")
                          .header("Authorization", "Bearer reader-token")
                          .header("Mcp-Session-Id", firstSession)
                          .header("MCP-Protocol-Version", "2025-11-25"))
              .statusCode());
      assertEquals(200, message(test, "other-client-token", "", INITIALIZE).statusCode());
      var ping = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}";
      assertEquals(404, message(test, "reader-token", firstSession, ping).statusCode());
      assertEquals(
          200,
          message(
                  test,
                  "reader-token",
                  second.headers().firstValue("Mcp-Session-Id").orElseThrow(),
                  ping)
              .statusCode());
    }
  }

  @Test
  @Timeout(30)
  void expiresAQuietSessionAndReclaimsItsCapacityWithoutKeepalivePolling() throws Exception {
    try (var readers = Executors.newVirtualThreadPerTaskExecutor();
        var test = TestServer.start(McpSecurityExample.application())) {
      var initialized = message(test, "reader-token", "", INITIALIZE);
      assertEquals(200, initialized.statusCode());
      var session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      assertEquals(200, message(test, "reader-token", "", INITIALIZE).statusCode());
      assertEquals(429, message(test, "reader-token", "", INITIALIZE).statusCode());
      var listening =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .header("Authorization", "Bearer reader-token")
                      .header("Mcp-Session-Id", session)
                      .header("MCP-Protocol-Version", "2025-11-25")
                      .header("Accept", "text/event-stream"),
              HttpResponse.BodyHandlers.ofInputStream());
      assertEquals(200, listening.statusCode());

      try (var input = listening.body()) {
        try {
          readers.submit(input::readAllBytes).get(15, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
          assertInstanceOf(IOException.class, failure.getCause());
        }
      }

      assertEquals(
          404,
          message(
                  test,
                  "reader-token",
                  session,
                  "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}")
              .statusCode());
      var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      var replacement = message(test, "reader-token", "", INITIALIZE);
      while (replacement.statusCode() == 429 && System.nanoTime() < deadline) {
        Thread.sleep(25);
        replacement = message(test, "reader-token", "", INITIALIZE);
      }
      assertEquals(200, replacement.statusCode());
    }
  }

  @Test
  void endsAnOpenStreamAtItsFixedTokenExpiryWithoutDeletingTheOwnersSession() throws Exception {
    try (var readers = Executors.newVirtualThreadPerTaskExecutor();
        var test = TestServer.start(McpSecurityExample.application())) {
      var initialized = message(test, "short-token", "", INITIALIZE);
      assertEquals(200, initialized.statusCode());
      var session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var listening =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .header("Authorization", "Bearer short-token")
                      .header("Mcp-Session-Id", session)
                      .header("MCP-Protocol-Version", "2025-11-25")
                      .header("Accept", "text/event-stream"),
              HttpResponse.BodyHandlers.ofInputStream());
      assertEquals(200, listening.statusCode());

      try (var input = listening.body()) {
        try {
          readers.submit(input::readAllBytes).get(8, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
          assertInstanceOf(IOException.class, failure.getCause());
        }
      }

      var ping = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}";
      assertEquals(401, message(test, "short-token", session, ping).statusCode());
      assertEquals(200, message(test, "reader-token", session, ping).statusCode());
    }
  }

  @Test
  void requiresBasePermissionForRequestsNotificationsRepliesAndSessionTransport() throws Exception {
    try (var test = TestServer.start(McpSecurityExample.application())) {
      var initialized = message(test, "reader-token", "", INITIALIZE);
      assertEquals(200, initialized.statusCode());
      var session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      for (var body :
          List.of(
              "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}",
              "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
              "{\"jsonrpc\":\"2.0\",\"id\":\"issued-request\",\"result\":{}}")) {
        var denied = message(test, "blocked-token", session, body);
        assertEquals(403, denied.statusCode());
        assertEquals(
            "Bearer error=\"insufficient_scope\", scope=\"mcp:access\", "
                + "resource_metadata=\"https://resource.example/.well-known/oauth-protected-resource\"",
            denied.headers().firstValue("WWW-Authenticate").orElseThrow());
      }
      for (var method : List.of("GET", "DELETE")) {
        assertEquals(
            403,
            test.send(
                    request ->
                        request
                            .path("/mcp")
                            .method(method)
                            .header("Authorization", "Bearer blocked-token")
                            .header("Mcp-Session-Id", session)
                            .header("MCP-Protocol-Version", "2025-11-25")
                            .header("Accept", "text/event-stream"))
                .statusCode());
      }
      assertEquals(
          200,
          message(
                  test,
                  "reader-token",
                  session,
                  "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}")
              .statusCode());
    }
  }

  private static HttpResponse<byte[]> message(
      TestServer test, String token, String session, String body) throws Exception {
    return test.send(
        request -> {
          request
              .method("POST")
              .path("/mcp")
              .header("Authorization", "Bearer " + token)
              .header("Content-Type", "application/json")
              .header("Accept", "application/json, text/event-stream")
              .header("MCP-Protocol-Version", "2025-11-25")
              .body(body.getBytes(StandardCharsets.UTF_8));
          if (!session.isEmpty()) {
            request.header("Mcp-Session-Id", session);
          }
        });
  }
}
