package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.ApplicationExtension;
import io.github.suppierk.shoostr.Lifecycle;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class McpShutdownTest {
  @Test
  void closesAnIdleListeningStreamWhenTheApplicationStops() throws Exception {
    var security =
        new McpHttpSecurity(
            Set.of(),
            ignored -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (ignoredMcpIdentity, ignoredJsonRpcMessage) -> {});
    var mcp = Mcp.sync(security, transport -> McpServer.sync(transport).build());
    var app = new Shoostr();
    app.routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var initialized =
          sendInitializedRequestForClosesAnIdleListeningStreamWhenTheApplicationStops(test);
      var sessionId = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var listening =
          sendListeningRequestForClosesAnIdleListeningStreamWhenTheApplicationStops(
              test, sessionId);

      try (var input = listening.body()) {
        assertEquals(200, listening.statusCode());
        assertDoesNotThrow(app::close);
        assertDoesNotThrow(input::readAllBytes);
      }
    }
  }

  private HttpResponse<byte[]>
      sendInitializedRequestForClosesAnIdleListeningStreamWhenTheApplicationStops(TestServer test)
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
                      "clientInfo":{"name":"shutdown-fixture","version":"1.0"}}}
                    """
                        .getBytes(StandardCharsets.UTF_8)));
  }

  private HttpResponse<InputStream>
      sendListeningRequestForClosesAnIdleListeningStreamWhenTheApplicationStops(
          TestServer test, String sessionId) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/mcp")
                .header("Accept", "text/event-stream")
                .header("Mcp-Session-Id", sessionId)
                .header("MCP-Protocol-Version", "2025-11-25"),
        HttpResponse.BodyHandlers.ofInputStream());
  }

  @Test
  void closesPendingPostStreamWhenTheApplicationStops() throws Exception {
    var security =
        new McpHttpSecurity(
            Set.of(),
            ignoredRequest -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (ignoredMcpIdentity, ignoredJsonRpcMessage) -> {});
    var tool =
        McpSchema.Tool.builder("wait", Map.of("type", "object", "properties", Map.of())).build();
    var mcp = createMcpForClosesPendingPostStreamWhenTheApplicationStops(security, tool);
    var app = new Shoostr();
    app.routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var initialized =
          sendInitializedRequestForClosesPendingPostStreamWhenTheApplicationStops(test);
      var sessionId = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var pending =
          sendPendingRequestForClosesPendingPostStreamWhenTheApplicationStops(test, sessionId);

      try (var input = pending.body()) {
        assertEquals(200, pending.statusCode());
        assertDoesNotThrow(app::close);
        assertDoesNotThrow(input::readAllBytes);
      }
    }
  }

  private Mcp<McpAsyncServer> createMcpForClosesPendingPostStreamWhenTheApplicationStops(
      McpHttpSecurity security, Tool tool) {
    return Mcp.async(
        security,
        transport ->
            McpServer.async(transport)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(
                    McpServerFeatures.AsyncToolSpecification.builder()
                        .tool(tool)
                        .callHandler(
                            (ignoredMcpAsyncServerExchange, ignoredCallToolRequest) -> Mono.never())
                        .build())
                .build());
  }

  private HttpResponse<byte[]>
      sendInitializedRequestForClosesPendingPostStreamWhenTheApplicationStops(TestServer test)
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
                      "clientInfo":{"name":"shutdown-fixture","version":"1.0"}}}
                    """
                        .getBytes(StandardCharsets.UTF_8)));
  }

  private HttpResponse<InputStream>
      sendPendingRequestForClosesPendingPostStreamWhenTheApplicationStops(
          TestServer test, String sessionId) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/mcp")
                .method("POST")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Mcp-Session-Id", sessionId)
                .header("MCP-Protocol-Version", "2025-11-25")
                .body(
                    """
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"wait","arguments":{}}}
                    """
                        .getBytes(StandardCharsets.UTF_8)),
        HttpResponse.BodyHandlers.ofInputStream());
  }

  @Test
  void rejectsStreamRegistrationThatLosesTheRaceWithShutdown() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var stopped = new CountDownLatch(1);
    var security =
        createSecurityForRejectsStreamRegistrationThatLosesTheRaceWithShutdown(entered, release);
    var mcp = Mcp.sync(security, transport -> McpServer.sync(transport).build());
    class ShutdownWitness implements ApplicationExtension<Object> {
      @Override
      public Object configure(Lifecycle lifecycle, Object inherited) {
        return this;
      }

      @Override
      public void beforeStop() {
        stopped.countDown();
      }
    }

    ApplicationExtension<Object> witness = new ShutdownWitness();
    var app = new Shoostr();
    app.withExtension(witness).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app);
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var initialized =
          sendInitializedRequestForRejectsStreamRegistrationThatLosesTheRaceWithShutdown(test);
      var sessionId = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var opening =
          submitOpeningForRejectsStreamRegistrationThatLosesTheRaceWithShutdown(
              workers, test, sessionId);

      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        final var closing =
            submitClosingForRejectsStreamRegistrationThatLosesTheRaceWithShutdown(workers, app);

        assertTrue(stopped.await(5, TimeUnit.SECONDS));

        release.countDown();

        assertEquals(503, opening.get(5, TimeUnit.SECONDS).statusCode());

        closing.get(5, TimeUnit.SECONDS);
      } finally {
        release.countDown();
      }
    }
  }

  private McpHttpSecurity createSecurityForRejectsStreamRegistrationThatLosesTheRaceWithShutdown(
      CountDownLatch entered, CountDownLatch release) {
    return new McpHttpSecurity(
        Set.of(),
        request -> {
          if ("GET".equals(request.method())) {
            entered.countDown();

            try {
              if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test timed out");
              }
            } catch (InterruptedException error) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(error);
            }
          }

          return new McpIdentity("public", McpTransportContext.EMPTY, null);
        },
        (ignoredMcpIdentity, ignoredJsonRpcMessage) -> {});
  }

  private HttpResponse<byte[]>
      sendInitializedRequestForRejectsStreamRegistrationThatLosesTheRaceWithShutdown(
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
                      "clientInfo":{"name":"shutdown-fixture","version":"1.0"}}}
                    """
                        .getBytes(StandardCharsets.UTF_8)));
  }

  private Future<HttpResponse<byte[]>>
      submitOpeningForRejectsStreamRegistrationThatLosesTheRaceWithShutdown(
          ExecutorService workers, TestServer test, String sessionId) {
    return workers.submit(
        () ->
            test.send(
                request ->
                    request
                        .path("/mcp")
                        .header("Accept", "text/event-stream")
                        .header("Mcp-Session-Id", sessionId)
                        .header("MCP-Protocol-Version", "2025-11-25")));
  }

  private Future<Object> submitClosingForRejectsStreamRegistrationThatLosesTheRaceWithShutdown(
      ExecutorService workers, Shoostr app) {
    return workers.submit(
        () -> {
          app.close();

          return null;
        });
  }
}
