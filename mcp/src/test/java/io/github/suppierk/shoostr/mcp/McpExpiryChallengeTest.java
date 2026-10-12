package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpStatusCodes;
import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.http.exceptions.AuthenticationRequiredException;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class McpExpiryChallengeTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @Timeout(15)
  void includesDiscoveryMetadataWhenATokenExpiresBeforeStreamAdmission(boolean forkJoin)
      throws Exception {
    ExecutorService selected = forkJoin ? new ForkJoinPool(1) : Executors.newSingleThreadExecutor();
    var passedIdentityCheck = new AtomicBoolean();
    var security =
        new McpHttpSecurity(
            Set.of(),
            request ->
                new McpIdentity(
                    "alice:client",
                    McpTransportContext.EMPTY,
                    request.header("X-Expiring").isPresent() ? Instant.now().plusSeconds(2) : null),
            (identity, _) -> {
              var expiry = identity.expiresAt();
              if (expiry == null) {
                return;
              }

              assertTrue(
                  Instant.now().isBefore(expiry), "Reach authorization with a fresh identity");
              passedIdentityCheck.set(true);

              try {
                var wait = new CountDownLatch(1);
                while (Instant.now().isBefore(expiry.plusMillis(100))) {
                  assertFalse(wait.await(100, TimeUnit.MILLISECONDS));
                }
              } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("The unarmed expiry wait was interrupted", failure);
              }
            },
            URI.create("https://example.com/.well-known/oauth-protected-resource/mcp"));
    var mcp = Mcp.sync(security, transport -> McpServer.sync(transport).build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", group -> mcp.routes(group, selected)));

    try (selected;
        var test = TestServer.start(app)) {
      var initialized =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .method("POST")
                      .header("Content-Type", "application/json")
                      .header("Accept", "application/json, text/event-stream")
                      .body(
                          """
              {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                "protocolVersion":"2025-11-25","capabilities":{},
                "clientInfo":{"name":"expiry-challenge","version":"1.0"}}}
              """
                              .getBytes(StandardCharsets.UTF_8)));
      var session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var rejected =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .method("POST")
                      .header("Content-Type", "application/json")
                      .header("Accept", "application/json, text/event-stream")
                      .header("Mcp-Session-Id", session)
                      .header("X-Expiring", "true")
                      .body(
                          "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}"
                              .getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());

      assertTrue(passedIdentityCheck.get(), "Exercise the later stream-admission expiry check");
      assertEquals(401, rejected.statusCode());
      assertEquals(
          "Bearer error=\"invalid_token\", resource_metadata=\"https://example.com/.well-known/oauth-protected-resource/mcp\"",
          rejected.headers().firstValue("WWW-Authenticate").orElseThrow());
      assertFalse(
          rejected.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @Timeout(15)
  void includesDiscoveryMetadataWhenAnArmedTokenExpiresBeforeTheFirstFlush(boolean forkJoin)
      throws Exception {
    ExecutorService selected = forkJoin ? new ForkJoinPool(1) : Executors.newSingleThreadExecutor();
    var deadlineInterrupted = new AtomicBoolean();
    var mcp =
        Mcp.sync(
            new McpHttpSecurity(
                Set.of(),
                request ->
                    new McpIdentity(
                        "alice:client",
                        McpTransportContext.EMPTY,
                        request.header("X-Expiring").isPresent()
                            ? Instant.now().plusSeconds(2)
                            : null),
                (_, _) -> {},
                URI.create("https://example.com/.well-known/oauth-protected-resource/mcp")),
            transport -> McpServer.sync(transport).build());
    var app = new Shoostr();
    app.extensions(mcp)
        .routes(
            routes ->
                routes.path(
                    "/mcp",
                    group -> mcp.routes(group, selected),
                    extensions ->
                        extensions.beforeResponseFlush(
                            (request, _) -> {
                              if (request.header("X-Expiring").isPresent()) {
                                try {
                                  assertFalse(new CountDownLatch(1).await(4, TimeUnit.SECONDS));
                                } catch (InterruptedException _) {
                                  // Let the following MCP flush guard render the expired token.
                                  deadlineInterrupted.set(true);
                                }
                              }
                            })));

    try (selected;
        var test = TestServer.start(app)) {
      var initialized =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .method("POST")
                      .header("Content-Type", "application/json")
                      .header("Accept", "application/json, text/event-stream")
                      .body(
                          """
              {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                "protocolVersion":"2025-11-25","capabilities":{},
                "clientInfo":{"name":"flush-expiry","version":"1.0"}}}
              """
                              .getBytes(StandardCharsets.UTF_8)));
      var session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var rejected =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .method("POST")
                      .header("Content-Type", "application/json")
                      .header("Accept", "application/json, text/event-stream")
                      .header("Mcp-Session-Id", session)
                      .header("X-Expiring", "true")
                      .body(
                          "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}"
                              .getBytes(StandardCharsets.UTF_8)));

      assertTrue(deadlineInterrupted.get(), "Exercise the armed lifetime's first flush guard");
      assertEquals(HttpStatusCodes.UNAUTHORIZED.value(), rejected.statusCode());
      assertEquals(
          "Bearer error=\"invalid_token\", resource_metadata=\"https://example.com/.well-known/oauth-protected-resource/mcp\"",
          rejected.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE.value()).orElseThrow());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"GET", "POST", "DELETE"})
  void preservesTheExpiryChallengeWhenARouteRendererCustomizesTheBody(String method)
      throws Exception {
    var mcp =
        Mcp.sync(
            new McpHttpSecurity(
                Set.of(),
                _ -> new McpIdentity("alice:client", McpTransportContext.EMPTY, Instant.EPOCH),
                (_, _) -> {},
                URI.create("https://example.com/.well-known/oauth-protected-resource/mcp")),
            transport -> McpServer.sync(transport).build());
    var app = new Shoostr();
    app.extensions(mcp)
        .routes(
            routes ->
                routes.path(
                    "/mcp",
                    group -> {
                      group.exception(
                          AuthenticationRequiredException.class,
                          (_, _, response) ->
                              response
                                  .setHeader(HttpHeaders.WWW_AUTHENTICATE.value(), "Basic")
                                  .body(
                                      MediaType.TEXT_PLAIN,
                                      "Please authenticate".getBytes(StandardCharsets.UTF_8)));
                      mcp.routes(group);
                    }));

    try (var test = TestServer.start(app)) {
      var rejected =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .method(method)
                      .header(HttpHeaders.ACCEPT, "application/json, text/event-stream"),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(HttpStatusCodes.UNAUTHORIZED.value(), rejected.statusCode());
      assertEquals("Please authenticate", rejected.body());
      assertEquals(
          "Bearer error=\"invalid_token\", resource_metadata=\"https://example.com/.well-known/oauth-protected-resource/mcp\"",
          rejected.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE.value()).orElseThrow());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void retainsTheGenericExpiryChallengeWithoutMetadata(boolean explicitNull) throws Exception {
    Function<Request, McpIdentity> identity =
        _ -> new McpIdentity("alice:client", McpTransportContext.EMPTY, Instant.EPOCH);
    var security =
        explicitNull
            ? new McpHttpSecurity(Set.of(), identity, (_, _) -> {}, null)
            : new McpHttpSecurity(Set.of(), identity, (_, _) -> {});
    var mcp = Mcp.sync(security, transport -> McpServer.sync(transport).build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var rejected =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .method("POST")
                      .header(HttpHeaders.ACCEPT, "application/json, text/event-stream"));
      assertEquals(HttpStatusCodes.UNAUTHORIZED.value(), rejected.statusCode());
      assertEquals(
          "Bearer error=\"invalid_token\"",
          rejected.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE.value()).orElseThrow());
    }
  }
}
