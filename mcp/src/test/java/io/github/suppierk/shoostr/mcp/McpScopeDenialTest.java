package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.RequestOutcome;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.exceptions.AuthorizationDeniedException;
import io.github.suppierk.shoostr.http.exceptions.ForbiddenException;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class McpScopeDenialTest {
  private static final String INITIALIZATION = initializationRequest();

  private static Mcp<McpSyncServer> refusingServer() {
    var security = createSecurityForRefusingServer();

    return Mcp.sync(security, transport -> McpServer.sync(transport).build());
  }

  private static McpHttpSecurity createSecurityForRefusingServer() {
    return new McpHttpSecurity(
        Set.of(),
        ignoredRequest -> new McpIdentity("alice:client", McpTransportContext.EMPTY, null),
        (ignoredMcpIdentity, ignoredJsonRpcMessage) -> {
          throw McpHttpSecurity.scopeDenied(
              Set.of("tools:read"),
              URI.create("https://example.com/.well-known/oauth-protected-resource/mcp"));
        });
  }

  private static String initializationRequest() {
    return
        """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
          "protocolVersion":"2025-11-25","capabilities":{},
          "clientInfo":{"name":"permission-fixture","version":"1.0"}}}
        """;
  }

  @Test
  void deniesInitializationWithTheRequiredScopesAndMetadataBeforeCreatingSession()
      throws Exception {
    var starts = new AtomicInteger();
    var security = createSecurityForDeniedInitialization();
    var mcp = createMcpForDeniedInitialization(security, starts);
    var app = new Shoostr();
    app.routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var denied = sendDeniedRequestForDeniedInitialization(test);

      assertEquals(403, denied.statusCode());
      assertEquals(
          "Bearer error=\"insufficient_scope\", scope=\"tools:read tools:write\", "
              + "resource_metadata=\"https://example.com/.well-known/oauth-protected-resource/mcp\"",
          denied.headers().firstValue("WWW-Authenticate").orElseThrow());
      assertTrue(denied.headers().firstValue("Mcp-Session-Id").isEmpty());
      assertEquals(
          0, starts.get(), "Denied initialization must not enter the native session factory");

      var granted = sendGrantedRequestForDeniedInitialization(test);

      assertEquals(200, granted.statusCode());
      assertFalse(granted.headers().firstValue("Mcp-Session-Id").orElseThrow().isBlank());
      assertEquals(1, starts.get(), "Granted initialization must reach the real native factory");
    }
  }

  private McpHttpSecurity createSecurityForDeniedInitialization() {
    return new McpHttpSecurity(
        Set.of(),
        request ->
            new McpIdentity(
                "alice:client",
                McpTransportContext.create(
                    Map.of("initializeAllowed", request.header("X-Verified-Grants").isPresent())),
                null),
        (identity, ignored) -> {
          if (!Boolean.TRUE.equals(identity.context().get("initializeAllowed"))) {
            throw McpHttpSecurity.scopeDenied(
                Set.of("tools:write", "tools:read"),
                URI.create("https://example.com/.well-known/oauth-protected-resource/mcp"));
          }
        });
  }

  private Mcp<McpSyncServer> createMcpForDeniedInitialization(
      McpHttpSecurity security, AtomicInteger starts) {
    return Mcp.sync(
        security, transport -> McpServer.sync(countingTransport(transport, starts)).build());
  }

  private static McpStreamableServerTransportProvider countingTransport(
      McpStreamableServerTransportProvider transport, AtomicInteger starts) {
    return new McpStreamableServerTransportProvider() {
      @Override
      public void setSessionFactory(McpStreamableServerSession.Factory factory) {
        transport.setSessionFactory(
            request -> {
              starts.incrementAndGet();

              return factory.startSession(request);
            });
      }

      @Override
      public Mono<Void> notifyClients(String method, Object params) {
        return transport.notifyClients(method, params);
      }

      @Override
      public Mono<Void> closeGracefully() {
        return transport.closeGracefully();
      }

      @Override
      public List<String> protocolVersions() {
        return transport.protocolVersions();
      }
    };
  }

  private HttpResponse<byte[]> sendDeniedRequestForDeniedInitialization(TestServer test)
      throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/mcp")
                .method("POST")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .body(INITIALIZATION.getBytes(StandardCharsets.UTF_8)));
  }

  private HttpResponse<byte[]> sendGrantedRequestForDeniedInitialization(TestServer test)
      throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/mcp")
                .method("POST")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("X-Verified-Grants", "tools:read tools:write")
                .body(INITIALIZATION.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void deniesToolBeforeItsSideEffectsAndAcceptsGrantedRetryOnTheSameSession() throws Exception {
    var calls = new AtomicInteger();
    var security = createSecurityForGrantedRetry();
    var mcp = createMcpForGrantedRetry(security, calls);
    var app = new Shoostr();
    app.routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var initialized = sendInitializedRequestForGrantedRetry(test);
      var sessionId = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var call =
          """
          {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"write","arguments":{}}}
          """
              .getBytes(StandardCharsets.UTF_8);
      var denied = sendDeniedRequestForGrantedRetry(test, sessionId, call);

      assertEquals(403, denied.statusCode());
      assertEquals(
          "Bearer error=\"insufficient_scope\", scope=\"tools:write\", "
              + "resource_metadata=\"https://example.com/.well-known/oauth-protected-resource/mcp\"",
          denied.headers().firstValue("WWW-Authenticate").orElseThrow());
      assertFalse(
          denied.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"));
      assertEquals(0, calls.get());

      var retried = sendRetriedRequestForGrantedRetry(test, sessionId, call);

      assertEquals(200, retried.statusCode());
      assertTrue(retried.body().contains("write completed"));
      assertEquals(1, calls.get());
    }
  }

  private McpHttpSecurity createSecurityForGrantedRetry() {
    return new McpHttpSecurity(
        Set.of(),
        request ->
            new McpIdentity(
                "alice:client",
                McpTransportContext.create(
                    Map.of("writeAllowed", request.header("X-Verified-Grants").isPresent())),
                null),
        (identity, message) -> {
          if (message instanceof McpSchema.JSONRPCRequest rpc
              && McpSchema.METHOD_TOOLS_CALL.equals(rpc.method())
              && !Boolean.TRUE.equals(identity.context().get("writeAllowed"))) {
            throw McpHttpSecurity.scopeDenied(
                Set.of("tools:write"),
                URI.create("https://example.com/.well-known/oauth-protected-resource/mcp"));
          }
        });
  }

  private Mcp<McpSyncServer> createMcpForGrantedRetry(
      McpHttpSecurity security, AtomicInteger calls) {
    return Mcp.sync(
        security,
        transport ->
            McpServer.sync(transport)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(
                    McpServerFeatures.SyncToolSpecification.builder()
                        .tool(McpSchema.Tool.builder("write", Map.of("type", "object")).build())
                        .callHandler(
                            (ignoredMcpSyncServerExchange, ignoredCallToolRequest) -> {
                              calls.incrementAndGet();

                              return McpSchema.CallToolResult.builder()
                                  .addTextContent("write completed")
                                  .build();
                            })
                        .build())
                .build());
  }

  private HttpResponse<byte[]> sendInitializedRequestForGrantedRetry(TestServer test)
      throws IOException, InterruptedException {
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
                      "clientInfo":{"name":"permission-fixture","version":"1.0"}}}
                    """
                        .getBytes(StandardCharsets.UTF_8)));
  }

  private HttpResponse<byte[]> sendDeniedRequestForGrantedRetry(
      TestServer test, String sessionId, byte[] call) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/mcp")
                .method("POST")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Mcp-Session-Id", sessionId)
                .header("MCP-Protocol-Version", "2025-11-25")
                .body(call));
  }

  private HttpResponse<String> sendRetriedRequestForGrantedRetry(
      TestServer test, String sessionId, byte[] call) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/mcp")
                .method("POST")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Mcp-Session-Id", sessionId)
                .header("MCP-Protocol-Version", "2025-11-25")
                .header("X-Verified-Grants", "tools:write")
                .body(call),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void allowsAnAppRendererToCustomizeTheScopeDenialBody() throws Exception {
    var mcp = refusingServer();
    var app = new Shoostr();
    app.exception(
            ForbiddenException.class,
            (ignoredFailure, ignoredRequest, response) -> response.text("app"))
        .routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var denied = sendDeniedRequestForAllowsAnAppRendererToCustomizeTheScopeDenialBody(test);

      assertEquals(403, denied.statusCode());
      assertEquals("app", denied.body());
      assertEquals(
          "Bearer error=\"insufficient_scope\", scope=\"tools:read\", "
              + "resource_metadata=\"https://example.com/.well-known/oauth-protected-resource/mcp\"",
          denied.headers().firstValue("WWW-Authenticate").orElseThrow());
    }
  }

  private HttpResponse<String> sendDeniedRequestForAllowsAnAppRendererToCustomizeTheScopeDenialBody(
      TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/mcp")
                .method("POST")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .body(INITIALIZATION.getBytes(StandardCharsets.UTF_8)),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void reportsScopeDenialAsTheTerminalApplicationFailure() throws Exception {
    var observed = new CompletableFuture<RequestOutcome>();
    var mcp = refusingServer();
    var app = new Shoostr();
    app.afterRequest(observed::complete).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var denied = sendDeniedRequestForReportsScopeDenialAsTheTerminalApplicationFailure(test);

      assertEquals(403, denied.statusCode());
      assertInstanceOf(
          AuthorizationDeniedException.class,
          observed.get(3, TimeUnit.SECONDS).applicationFailure());
    }
  }

  private HttpResponse<byte[]>
      sendDeniedRequestForReportsScopeDenialAsTheTerminalApplicationFailure(TestServer test)
          throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/mcp")
                .method("POST")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .body(INITIALIZATION.getBytes(StandardCharsets.UTF_8)));
  }
}
