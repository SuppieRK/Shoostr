package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Mono;

class McpSubscriptionDeletionRaceTest {
  @Test
  @Timeout(20)
  void deletionCancelsAnInstalledNativeSubscriptionBeforeItsProducerReturns() throws Exception {
    var installed = new CountDownLatch(1);
    var cancelled = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var returned = new CountDownLatch(1);
    var cancellations = new AtomicInteger();
    var security =
        new McpHttpSecurity(
            Set.of(),
            ignored -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (ignoredMcpIdentity, ignoredJSONRPCMessage) -> {});
    var tool = McpSchema.Tool.builder("wait", Map.of("type", "object")).build();
    var mcp =
        createMcpForDeletionCancelsAnInstalledNativeSubscriptionBeforeItsProducerReturns(
            security, tool, cancellations, cancelled, installed, release, returned);
    var app = new Shoostr();
    app.routes(routes -> routes.path("/mcp", mcp::routes));

    try (var workers = Executors.newVirtualThreadPerTaskExecutor();
        var test = TestServer.start(app)) {
      var initialized =
          sendInitializedRequestForDeletionCancelsAnInstalledNativeSubscriptionBeforeItsProducerReturns(
              test);
      var sessionId = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var opening =
          submitOpeningForDeletionCancelsAnInstalledNativeSubscriptionBeforeItsProducerReturns(
              workers, test, sessionId);

      try {
        assertTrue(installed.await(5, TimeUnit.SECONDS));

        var deleted =
            sendDeletedRequestForDeletionCancelsAnInstalledNativeSubscriptionBeforeItsProducerReturns(
                test, sessionId);

        assertEquals(200, deleted.statusCode());
        assertTrue(cancelled.await(3, TimeUnit.SECONDS));
        assertEquals(1, returned.getCount(), "Cancellation must not wait for producer return");
        assertEquals(1, cancellations.get());

        release.countDown();

        assertTrue(returned.await(5, TimeUnit.SECONDS));

        var response = opening.get(5, TimeUnit.SECONDS);

        try (var body = response.body()) {
          assertEquals(200, response.statusCode());

          workers.submit(body::readAllBytes).get(3, TimeUnit.SECONDS);
        }
      } finally {
        release.countDown();
      }
    }
  }

  private Mcp<McpAsyncServer>
      createMcpForDeletionCancelsAnInstalledNativeSubscriptionBeforeItsProducerReturns(
          McpHttpSecurity security,
          Tool tool,
          AtomicInteger cancellations,
          CountDownLatch cancelled,
          CountDownLatch installed,
          CountDownLatch release,
          CountDownLatch returned) {
    return Mcp.async(
        security,
        transport ->
            McpServer.async(transport)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(
                    McpServerFeatures.AsyncToolSpecification.builder()
                        .tool(tool)
                        .callHandler(
                            (ignoredMcpAsyncServerExchange, ignoredCallToolRequest) ->
                                Mono.create(
                                    sink -> {
                                      sink.onCancel(
                                          () -> {
                                            cancellations.incrementAndGet();
                                            cancelled.countDown();
                                          });
                                      installed.countDown();
                                      awaitProducerRelease(release);
                                      returned.countDown();
                                    }))
                        .build())
                .build());
  }

  private HttpResponse<byte[]>
      sendInitializedRequestForDeletionCancelsAnInstalledNativeSubscriptionBeforeItsProducerReturns(
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
                "clientInfo":{"name":"subscription-race-fixture","version":"1.0"}}}
              """
                        .getBytes(StandardCharsets.UTF_8)));
  }

  private Future<HttpResponse<InputStream>>
      submitOpeningForDeletionCancelsAnInstalledNativeSubscriptionBeforeItsProducerReturns(
          ExecutorService workers, TestServer test, String sessionId) {
    return workers.submit(
        () ->
            test.send(
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
              {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                "name":"wait","arguments":{}}}
              """
                                .getBytes(StandardCharsets.UTF_8)),
                HttpResponse.BodyHandlers.ofInputStream()));
  }

  private HttpResponse<byte[]>
      sendDeletedRequestForDeletionCancelsAnInstalledNativeSubscriptionBeforeItsProducerReturns(
          TestServer test, String sessionId) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/mcp")
                .method("DELETE")
                .header("Mcp-Session-Id", sessionId)
                .header("MCP-Protocol-Version", "2025-11-25"));
  }

  private static void awaitProducerRelease(CountDownLatch release) {
    boolean interrupted = false;

    try {
      while (true) {
        try {
          if (!release.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("test producer was not released");
          }

          return;
        } catch (InterruptedException ignoredFailure) {
          interrupted = true;
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
