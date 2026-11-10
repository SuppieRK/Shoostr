package io.github.suppierk.shoostr.mcp;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpClient.SyncSpec;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class McpSharedResourceTest {
  private AtomicReference<String> contents;
  private BlockingQueue<String> listening;
  private Mcp<McpSyncServer> mcp;
  private TestServer test;

  @BeforeEach
  void startServer() throws Exception {
    contents = new AtomicReference<>("shared-initial");
    listening = new LinkedBlockingQueue<>();
    Set<String> attached = ConcurrentHashMap.newKeySet();
    var security =
        new McpHttpSecurity(
            Set.of(),
            ignored -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (ignoredMcpIdentity, ignoredJSONRPCMessage) -> {});
    mcp = createMcp(security);

    var app = new Shoostr();
    app.routes(routes -> routes.path("/mcp", mcp::routes));
    app.beforeResponseFlush(
        (request, ignoredResponse) -> {
          if ("GET".equals(request.method()) && "/mcp".equals(request.path())) {
            var session = request.header("Mcp-Session-Id").orElseThrow();
            if (attached.add(session)) {
              listening.add(session);
            }
          }
        });
    test = TestServer.start(app);
  }

  private Mcp<McpSyncServer> createMcp(McpHttpSecurity security) {
    return Mcp.sync(
        security,
        transport ->
            McpServer.sync(transport)
                .capabilities(McpSchema.ServerCapabilities.builder().resources(true, true).build())
                .resources(
                    new McpServerFeatures.SyncResourceSpecification(
                        McpSchema.Resource.builder("fixture://shared", "shared").build(),
                        (ignoredMcpSyncServerExchange, ignoredReadResourceRequest) ->
                            McpSchema.ReadResourceResult.builder(
                                    List.of(
                                        McpSchema.TextResourceContents.builder(
                                                "fixture://shared", contents.get())
                                            .mimeType("text/plain")
                                            .build()))
                                .build()))
                .build());
  }

  @AfterEach
  void stopServer() throws Exception {
    if (test != null) {
      test.close();
    }
  }

  @Test
  @Timeout(20)
  void deliversAResourceUpdateToEverySubscriberOfTheSameUri() throws Exception {
    var alphaUpdates = new LinkedBlockingQueue<List<McpSchema.ResourceContents>>();
    var betaUpdates = new LinkedBlockingQueue<List<McpSchema.ResourceContents>>();

    try (var alpha = client(alphaUpdates);
        var beta = client(betaUpdates)) {
      alpha.initialize();

      assertNotNull(listening.poll(3, TimeUnit.SECONDS));

      beta.initialize();

      assertNotNull(listening.poll(3, TimeUnit.SECONDS));

      alpha.subscribeResource(McpSchema.SubscribeRequest.builder("fixture://shared").build());
      beta.subscribeResource(McpSchema.SubscribeRequest.builder("fixture://shared").build());

      contents.set("shared-updated");

      mcp.server()
          .notifyResourcesUpdated(new McpSchema.ResourcesUpdatedNotification("fixture://shared"));

      var expected =
          List.of(
              McpSchema.TextResourceContents.builder("fixture://shared", "shared-updated")
                  .mimeType("text/plain")
                  .build());

      assertEquals(expected, alphaUpdates.poll(3, TimeUnit.SECONDS));
      assertEquals(expected, betaUpdates.poll(3, TimeUnit.SECONDS));
    }
  }

  @Test
  @Timeout(20)
  void deletingOneSubscriberLeavesOtherSubscribersOfTheSameUriUsable() throws Exception {
    var alphaUpdates = new LinkedBlockingQueue<List<McpSchema.ResourceContents>>();
    var betaUpdates = new LinkedBlockingQueue<List<McpSchema.ResourceContents>>();

    try (var alpha = client(alphaUpdates);
        var beta = client(betaUpdates)) {
      alpha.initialize();
      var alphaSession = listening.poll(3, TimeUnit.SECONDS);

      assertNotNull(alphaSession);

      beta.initialize();

      assertNotNull(listening.poll(3, TimeUnit.SECONDS));

      alpha.subscribeResource(McpSchema.SubscribeRequest.builder("fixture://shared").build());
      beta.subscribeResource(McpSchema.SubscribeRequest.builder("fixture://shared").build());

      var deleted =
          sendDeletedRequestForDeletingOneSubscriberLeavesOtherSubscribersOfTheSameUriUsable(
              alphaSession);

      assertEquals(200, deleted.statusCode());

      contents.set("shared-after-deletion");

      mcp.server()
          .notifyResourcesUpdated(new McpSchema.ResourcesUpdatedNotification("fixture://shared"));

      assertEquals(
          List.of(
              McpSchema.TextResourceContents.builder("fixture://shared", "shared-after-deletion")
                  .mimeType("text/plain")
                  .build()),
          betaUpdates.poll(3, TimeUnit.SECONDS));

      await("the deleted session receives no shared resource update")
          .pollDelay(Duration.ZERO)
          .pollInterval(Duration.ofMillis(10))
          .during(Duration.ofMillis(250))
          .atMost(Duration.ofMillis(1250))
          .failFast("the deleted session received an update", () -> !alphaUpdates.isEmpty())
          .until(alphaUpdates::isEmpty);

      var absent =
          sendAbsentRequestForDeletingOneSubscriberLeavesOtherSubscribersOfTheSameUriUsable(
              alphaSession);

      assertEquals(404, absent.statusCode());
    }
  }

  private HttpResponse<byte[]>
      sendDeletedRequestForDeletingOneSubscriberLeavesOtherSubscribersOfTheSameUriUsable(
          String alphaSession) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/mcp")
                .method("DELETE")
                .header("Mcp-Session-Id", alphaSession)
                .header("MCP-Protocol-Version", "2025-11-25"));
  }

  private HttpResponse<byte[]>
      sendAbsentRequestForDeletingOneSubscriberLeavesOtherSubscribersOfTheSameUriUsable(
          String alphaSession) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/mcp")
                .header("Accept", "text/event-stream")
                .header("Mcp-Session-Id", alphaSession)
                .header("MCP-Protocol-Version", "2025-11-25"));
  }

  private McpSyncClient client(BlockingQueue<List<McpSchema.ResourceContents>> updates) {
    return clientProperties(updates).build();
  }

  private SyncSpec clientProperties(BlockingQueue<List<McpSchema.ResourceContents>> updates) {
    return McpClient.sync(
            HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                .endpoint("/mcp")
                .build())
        .requestTimeout(Duration.ofSeconds(3))
        .initializationTimeout(Duration.ofSeconds(3))
        .resourcesUpdateConsumer(updates::add);
  }
}
