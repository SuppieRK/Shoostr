package io.github.suppierk.shoostr.mcp;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpStatusCodes;
import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.http.exceptions.AuthenticationRequiredException;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class McpExpiryChallengeTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @Timeout(15)
  void includesDiscoveryMetadataWhenTokenExpiresBeforeStreamAdmission(boolean forkJoin)
      throws Exception {
    ExecutorService selected = forkJoin ? new ForkJoinPool(1) : Executors.newSingleThreadExecutor();
    var passedIdentityCheck = new AtomicBoolean();
    var security =
        createSecurityForIncludesDiscoveryMetadataWhenTokenExpiresBeforeStreamAdmission(
            passedIdentityCheck);
    var mcp = Mcp.sync(security, transport -> McpServer.sync(transport).build());
    var app = new Shoostr();
    app.routes(routes -> routes.path("/mcp", group -> mcp.routes(group, selected)));

    try (selected;
        var test = TestServer.start(app)) {
      var initialized =
          sendInitializedRequestForIncludesDiscoveryMetadataWhenTokenExpiresBeforeStreamAdmission(
              test);
      var session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var rejected =
          sendRejectedRequestForIncludesDiscoveryMetadataWhenTokenExpiresBeforeStreamAdmission(
              test, session);

      assertTrue(passedIdentityCheck.get(), "Exercise the later stream-admission expiry check");
      assertEquals(401, rejected.statusCode());
      assertEquals(
          "Bearer error=\"invalid_token\", resource_metadata=\"https://example.com/.well-known/oauth-protected-resource/mcp\"",
          rejected.headers().firstValue("WWW-Authenticate").orElseThrow());
      assertFalse(
          rejected.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"));
    }
  }

  private McpHttpSecurity
      createSecurityForIncludesDiscoveryMetadataWhenTokenExpiresBeforeStreamAdmission(
          AtomicBoolean passedIdentityCheck) {
    return new McpHttpSecurity(
        Set.of(),
        request ->
            new McpIdentity(
                "alice:client",
                McpTransportContext.EMPTY,
                request.header("X-Expiring").isPresent() ? Instant.now().plusSeconds(2) : null),
        (identity, ignored) -> {
          var expiry = identity.expiresAt();
          if (expiry == null) {
            return;
          }

          assertTrue(Instant.now().isBefore(expiry), "Reach authorization with a fresh identity");

          passedIdentityCheck.set(true);

          await("authorization reaches the verified expiry before stream admission")
              .pollInSameThread()
              .pollDelay(Duration.ZERO)
              .pollInterval(Duration.ofMillis(10))
              .atMost(Duration.ofSeconds(5))
              .until(
                  () -> {
                    if (Thread.interrupted()) {
                      Thread.currentThread().interrupt();
                      throw new IllegalStateException(
                          "The unarmed expiry wait was interrupted", new InterruptedException());
                    }

                    return !Instant.now().isBefore(expiry.plusMillis(100));
                  });
        },
        URI.create("https://example.com/.well-known/oauth-protected-resource/mcp"));
  }

  private HttpResponse<byte[]>
      sendInitializedRequestForIncludesDiscoveryMetadataWhenTokenExpiresBeforeStreamAdmission(
          TestServer test) throws IOException, InterruptedException {
    return test.send(
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
  }

  private HttpResponse<String>
      sendRejectedRequestForIncludesDiscoveryMetadataWhenTokenExpiresBeforeStreamAdmission(
          TestServer test, String session) throws IOException, InterruptedException {
    return test.send(
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
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @Timeout(15)
  void includesDiscoveryMetadataWhenAnArmedTokenExpiresBeforeTheFirstFlush(boolean forkJoin)
      throws Exception {
    ExecutorService selected = forkJoin ? new ForkJoinPool(1) : Executors.newSingleThreadExecutor();
    var verifiedExpiry = new AtomicReference<Instant>();
    var interruptedAt = new AtomicReference<Instant>();
    var mcp =
        createMcpForIncludesDiscoveryMetadataWhenAnArmedTokenExpiresBeforeTheFirstFlush(
            verifiedExpiry);
    var app = new Shoostr();
    app.routes(
        routes ->
            routes.path(
                "/mcp",
                group -> mcp.routes(group, selected),
                extensions ->
                    extensions.beforeResponseFlush(
                        (request, ignoredResponse) -> {
                          if (request.header("X-Expiring").isPresent()) {
                            try {
                              // Only the verified deadline may release this first flush.
                              if (!new CountDownLatch(1).await(10, TimeUnit.SECONDS)) {
                                fail("The token deadline must interrupt the held first flush");
                              }
                            } catch (InterruptedException ignoredFailure) {
                              // Let the following MCP flush guard render the expired token.
                              interruptedAt.set(Instant.now());
                            }
                          }
                        })));

    try (selected;
        var test = TestServer.start(app)) {
      var initialized =
          sendInitializedRequestForIncludesDiscoveryMetadataWhenAnArmedTokenExpiresBeforeTheFirstFlush(
              test);
      var session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var rejected =
          sendRejectedRequestForIncludesDiscoveryMetadataWhenAnArmedTokenExpiresBeforeTheFirstFlush(
              test, session);

      var observedInterruption = interruptedAt.get();

      assertNotNull(observedInterruption, "Exercise the armed lifetime's first flush guard");
      assertFalse(
          observedInterruption.isBefore(verifiedExpiry.get()),
          "The deadline must not interrupt an identity that is still valid");
      assertEquals(HttpStatusCodes.UNAUTHORIZED.value(), rejected.statusCode());
      assertEquals(HttpStatusCodes.UNAUTHORIZED.reasonPhrase(), rejected.body());
      assertEquals(
          "Bearer error=\"invalid_token\", resource_metadata=\"https://example.com/.well-known/oauth-protected-resource/mcp\"",
          rejected.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE.value()).orElseThrow());
      assertFalse(
          rejected
              .headers()
              .firstValue(HttpHeaders.CONTENT_TYPE.value())
              .orElse("")
              .contains("text/event-stream"));
    }
  }

  private Mcp<McpSyncServer>
      createMcpForIncludesDiscoveryMetadataWhenAnArmedTokenExpiresBeforeTheFirstFlush(
          AtomicReference<Instant> verifiedExpiry) {
    return Mcp.sync(
        new McpHttpSecurity(
            Set.of(),
            request -> {
              var expiry =
                  request.header("X-Expiring").isPresent() ? Instant.now().plusSeconds(2) : null;
              if (expiry != null) {
                verifiedExpiry.set(expiry);
              }

              return new McpIdentity("alice:client", McpTransportContext.EMPTY, expiry);
            },
            (ignoredMcpIdentity, ignoredJsonRpcMessage) -> {},
            URI.create("https://example.com/.well-known/oauth-protected-resource/mcp")),
        transport -> McpServer.sync(transport).build());
  }

  private HttpResponse<byte[]>
      sendInitializedRequestForIncludesDiscoveryMetadataWhenAnArmedTokenExpiresBeforeTheFirstFlush(
          TestServer test) throws IOException, InterruptedException {
    return test.send(
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
  }

  private HttpResponse<String>
      sendRejectedRequestForIncludesDiscoveryMetadataWhenAnArmedTokenExpiresBeforeTheFirstFlush(
          TestServer test, String session) throws IOException, InterruptedException {
    return test.send(
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
  }

  @ParameterizedTest
  @ValueSource(strings = {"GET", "POST", "DELETE"})
  void preservesTheExpiryChallengeWhenRouteRendererCustomizesTheBody(String method)
      throws Exception {
    var mcp = createMcpForPreservesTheExpiryChallengeWhenRouteRendererCustomizesTheBody();
    var app = new Shoostr();
    app.routes(
        routes ->
            routes.path(
                "/mcp",
                group -> {
                  group.exception(
                      AuthenticationRequiredException.class,
                      (ignoredFailure, ignoredRequest, response) ->
                          response
                              .setHeader(HttpHeaders.WWW_AUTHENTICATE.value(), "Basic")
                              .body(
                                  MediaType.TEXT_PLAIN,
                                  "Please authenticate".getBytes(StandardCharsets.UTF_8)));
                  mcp.routes(group);
                }));

    try (var test = TestServer.start(app)) {
      var rejected =
          sendRejectedRequestForPreservesTheExpiryChallengeWhenRouteRendererCustomizesTheBody(
              test, method);

      assertEquals(HttpStatusCodes.UNAUTHORIZED.value(), rejected.statusCode());
      assertEquals("Please authenticate", rejected.body());
      assertEquals(
          "Bearer error=\"invalid_token\", resource_metadata=\"https://example.com/.well-known/oauth-protected-resource/mcp\"",
          rejected.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE.value()).orElseThrow());
    }
  }

  private Mcp<McpSyncServer>
      createMcpForPreservesTheExpiryChallengeWhenRouteRendererCustomizesTheBody() {
    return Mcp.sync(
        new McpHttpSecurity(
            Set.of(),
            ignoredRequest ->
                new McpIdentity("alice:client", McpTransportContext.EMPTY, Instant.EPOCH),
            (ignoredMcpIdentity, ignoredJsonRpcMessage) -> {},
            URI.create("https://example.com/.well-known/oauth-protected-resource/mcp")),
        transport -> McpServer.sync(transport).build());
  }

  private HttpResponse<String>
      sendRejectedRequestForPreservesTheExpiryChallengeWhenRouteRendererCustomizesTheBody(
          TestServer test, String method) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/mcp")
                .method(method)
                .header(HttpHeaders.ACCEPT, "application/json, text/event-stream"),
        HttpResponse.BodyHandlers.ofString());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void retainsTheGenericExpiryChallengeWithoutMetadata(boolean explicitNull) throws Exception {
    Function<Request, McpIdentity> identity =
        ignoredRequest -> new McpIdentity("alice:client", McpTransportContext.EMPTY, Instant.EPOCH);
    var security = expirySecurity(explicitNull, identity);
    var mcp = Mcp.sync(security, transport -> McpServer.sync(transport).build());
    var app = new Shoostr();
    app.routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var rejected = sendRejectedRequestForRetainsTheGenericExpiryChallengeWithoutMetadata(test);

      assertEquals(HttpStatusCodes.UNAUTHORIZED.value(), rejected.statusCode());
      assertEquals(
          "Bearer error=\"invalid_token\"",
          rejected.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE.value()).orElseThrow());
    }
  }

  private McpHttpSecurity expirySecurity(
      boolean explicitNull, Function<Request, McpIdentity> identity) {
    return explicitNull
        ? new McpHttpSecurity(
            Set.of(), identity, (ignoredMcpIdentity, ignoredJsonRpcMessage) -> {}, null)
        : new McpHttpSecurity(
            Set.of(), identity, (ignoredMcpIdentity, ignoredJsonRpcMessage) -> {});
  }

  private HttpResponse<byte[]>
      sendRejectedRequestForRetainsTheGenericExpiryChallengeWithoutMetadata(TestServer test)
          throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/mcp")
                .method("POST")
                .header(HttpHeaders.ACCEPT, "application/json, text/event-stream"));
  }
}
