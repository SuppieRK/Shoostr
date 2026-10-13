package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class McpNotificationBroadcastTest {
  @Test
  @Timeout(20)
  void deliversCatalogChangesDespiteAnotherSessionHavingNoListeningStream() throws Exception {
    var changed = new LinkedBlockingQueue<List<McpSchema.Resource>>();
    var listening = new CountDownLatch(1);
    var resource = McpSchema.Resource.builder("fixture://catalog", "catalog").build();
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
                        McpSchema.ServerCapabilities.builder().resources(false, true).build())
                    .resources(
                        new McpServerFeatures.SyncResourceSpecification(
                            resource,
                            (_, _) ->
                                McpSchema.ReadResourceResult.builder(
                                        List.of(
                                            McpSchema.TextResourceContents.builder(
                                                    "fixture://catalog", "catalog-contents")
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
                .resourcesChangeConsumer(changed::add)
                .build()) {
      client.initialize();
      assertTrue(listening.await(3, TimeUnit.SECONDS));
      var detached =
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
                "clientInfo":{"name":"detached-fixture","version":"1.0"}}}
              """
                              .getBytes(StandardCharsets.UTF_8)));
      assertEquals(200, detached.statusCode());
      assertTrue(detached.headers().firstValue("Mcp-Session-Id").isPresent());

      assertDoesNotThrow(mcp.server()::notifyResourcesListChanged);
      assertEquals(List.of(resource), changed.poll(3, TimeUnit.SECONDS));
    }
  }
}
