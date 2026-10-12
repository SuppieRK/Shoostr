package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class McpResourceUnsubscribeTest {
  @Test
  @Timeout(20)
  void stopsUpdatesForAnUnsubscribedUriWithoutClosingOtherSubscriptions() throws Exception {
    var alpha = new AtomicReference<>("alpha-initial");
    var updates = new LinkedBlockingQueue<List<McpSchema.ResourceContents>>();
    var listening = new CountDownLatch(1);
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
                            McpSchema.Resource.builder("fixture://control", "control").build(),
                            (_, _) ->
                                McpSchema.ReadResourceResult.builder(
                                        List.of(
                                            McpSchema.TextResourceContents.builder(
                                                    "fixture://control", "control-updated")
                                                .mimeType("text/plain")
                                                .build()))
                                    .build()))
                    .build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));
    app.beforeResponseFlush(
        (request, _) -> {
          if ("GET".equals(request.method()) && "/mcp".equals(request.path())) {
            listening.countDown();
          }
        });

    try (var test = TestServer.start(app);
        var client =
            McpClient.sync(
                    HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                        .endpoint("/mcp")
                        .build())
                .requestTimeout(Duration.ofSeconds(3))
                .initializationTimeout(Duration.ofSeconds(3))
                .resourcesUpdateConsumer(updates::add)
                .build()) {
      client.initialize();
      assertTrue(listening.await(3, TimeUnit.SECONDS));
      client.subscribeResource(McpSchema.SubscribeRequest.builder("fixture://alpha").build());
      client.subscribeResource(McpSchema.SubscribeRequest.builder("fixture://control").build());

      alpha.set("alpha-before-unsubscribe");
      mcp.server()
          .notifyResourcesUpdated(new McpSchema.ResourcesUpdatedNotification("fixture://alpha"));
      assertEquals(
          List.of(
              McpSchema.TextResourceContents.builder("fixture://alpha", "alpha-before-unsubscribe")
                  .mimeType("text/plain")
                  .build()),
          updates.poll(3, TimeUnit.SECONDS));

      client.unsubscribeResource(McpSchema.UnsubscribeRequest.builder("fixture://alpha").build());
      alpha.set("alpha-after-unsubscribe");
      mcp.server()
          .notifyResourcesUpdated(new McpSchema.ResourcesUpdatedNotification("fixture://alpha"));
      assertNull(updates.poll(250, TimeUnit.MILLISECONDS));

      mcp.server()
          .notifyResourcesUpdated(new McpSchema.ResourcesUpdatedNotification("fixture://control"));
      assertEquals(
          List.of(
              McpSchema.TextResourceContents.builder("fixture://control", "control-updated")
                  .mimeType("text/plain")
                  .build()),
          updates.poll(3, TimeUnit.SECONDS));
      assertNull(updates.poll(250, TimeUnit.MILLISECONDS));
    }
  }
}
