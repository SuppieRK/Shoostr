package io.github.suppierk.shoostr.mcp;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class McpResourceSubscriptionTest {
  @Test
  @Timeout(20)
  void deliversResourceUpdatesOnlyToSubscribersOfThatUri() throws Exception {
    var alpha = new AtomicReference<>("alpha-initial");
    var beta = new AtomicReference<>("beta-initial");
    var alphaUpdates = new LinkedBlockingQueue<List<McpSchema.ResourceContents>>();
    var betaUpdates = new LinkedBlockingQueue<List<McpSchema.ResourceContents>>();
    var listening = new CountDownLatch(2);
    Set<String> attached = ConcurrentHashMap.newKeySet();
    var security =
        new McpHttpSecurity(
            Set.of(),
            _ -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (_, _) -> {});
    var mcp =
        Mcp.sync(
            security,
            transport ->
                McpServer.sync(transport)
                    .capabilities(
                        McpSchema.ServerCapabilities.builder().resources(true, true).build())
                    .resources(
                        new McpServerFeatures.SyncResourceSpecification(
                            McpSchema.Resource.builder("fixture://alpha", "alpha").build(),
                            (_, _) ->
                                McpSchema.ReadResourceResult.builder(
                                        List.of(
                                            McpSchema.TextResourceContents.builder(
                                                    "fixture://alpha", alpha.get())
                                                .mimeType("text/plain")
                                                .build()))
                                    .build()),
                        new McpServerFeatures.SyncResourceSpecification(
                            McpSchema.Resource.builder("fixture://beta", "beta").build(),
                            (_, _) ->
                                McpSchema.ReadResourceResult.builder(
                                        List.of(
                                            McpSchema.TextResourceContents.builder(
                                                    "fixture://beta", beta.get())
                                                .mimeType("text/plain")
                                                .build()))
                                    .build()))
                    .build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));
    app.beforeResponseFlush(
        (request, _) -> {
          if ("GET".equals(request.method())
              && "/mcp".equals(request.path())
              && attached.add(request.header("Mcp-Session-Id").orElseThrow())) {
            listening.countDown();
          }
        });

    try (var test = TestServer.start(app);
        var alphaClient =
            McpClient.sync(
                    HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                        .endpoint("/mcp")
                        .build())
                .requestTimeout(Duration.ofSeconds(3))
                .initializationTimeout(Duration.ofSeconds(3))
                .resourcesUpdateConsumer(alphaUpdates::add)
                .build();
        var betaClient =
            McpClient.sync(
                    HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                        .endpoint("/mcp")
                        .build())
                .requestTimeout(Duration.ofSeconds(3))
                .initializationTimeout(Duration.ofSeconds(3))
                .resourcesUpdateConsumer(betaUpdates::add)
                .build()) {
      alphaClient.initialize();
      betaClient.initialize();
      assertTrue(listening.await(3, TimeUnit.SECONDS));
      alphaClient.subscribeResource(McpSchema.SubscribeRequest.builder("fixture://alpha").build());
      betaClient.subscribeResource(McpSchema.SubscribeRequest.builder("fixture://beta").build());

      alpha.set("alpha-updated");
      mcp.server()
          .notifyResourcesUpdated(new McpSchema.ResourcesUpdatedNotification("fixture://alpha"));
      assertEquals(
          List.of(
              McpSchema.TextResourceContents.builder("fixture://alpha", "alpha-updated")
                  .mimeType("text/plain")
                  .build()),
          alphaUpdates.poll(3, TimeUnit.SECONDS));
      await("the beta client receives no unrelated resource update")
          .pollDelay(Duration.ZERO)
          .pollInterval(Duration.ofMillis(10))
          .during(Duration.ofMillis(250))
          .atMost(Duration.ofMillis(1250))
          .failFast("the beta client received an unrelated update", () -> !betaUpdates.isEmpty())
          .until(betaUpdates::isEmpty);

      beta.set("beta-updated");
      mcp.server()
          .notifyResourcesUpdated(new McpSchema.ResourcesUpdatedNotification("fixture://beta"));
      assertEquals(
          List.of(
              McpSchema.TextResourceContents.builder("fixture://beta", "beta-updated")
                  .mimeType("text/plain")
                  .build()),
          betaUpdates.poll(3, TimeUnit.SECONDS));
      await("the beta client receives no duplicate resource update")
          .pollDelay(Duration.ZERO)
          .pollInterval(Duration.ofMillis(10))
          .during(Duration.ofMillis(250))
          .atMost(Duration.ofMillis(1250))
          .failFast("the beta client received a duplicate update", () -> !betaUpdates.isEmpty())
          .until(betaUpdates::isEmpty);
      await("the alpha client receives no unrelated resource update")
          .pollDelay(Duration.ZERO)
          .pollInterval(Duration.ofMillis(10))
          .during(Duration.ofMillis(250))
          .atMost(Duration.ofMillis(1250))
          .failFast("the alpha client received an unrelated update", () -> !alphaUpdates.isEmpty())
          .until(alphaUpdates::isEmpty);
    }
  }
}
