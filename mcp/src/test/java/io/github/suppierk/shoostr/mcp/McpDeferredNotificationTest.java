package io.github.suppierk.shoostr.mcp;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ElicitationCompleteNotification;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class McpDeferredNotificationTest {
  @Test
  @Timeout(20)
  void discardsDeferredNotificationForDeletedSessionWithoutAffectingAnotherClient()
      throws Exception {
    var alphaNotifications = new LinkedBlockingQueue<McpSchema.ElicitationCompleteNotification>();
    var betaNotifications = new LinkedBlockingQueue<McpSchema.ElicitationCompleteNotification>();
    var listening = new LinkedBlockingQueue<String>();
    Set<String> attached = ConcurrentHashMap.newKeySet();
    var security =
        new McpHttpSecurity(
            Set.of(),
            ignored -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (ignoredMcpIdentity, ignoredJsonRpcMessage) -> {});
    var mcp = Mcp.async(security, transport -> McpServer.async(transport).build());
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

    try (var test = TestServer.start(app);
        var alpha = openClient(test, alphaNotifications);
        var beta = openClient(test, betaNotifications)) {
      alpha.initialize();
      var alphaSession = listening.poll(3, TimeUnit.SECONDS);

      assertNotNull(alphaSession);

      beta.initialize();
      var betaSession = listening.poll(3, TimeUnit.SECONDS);

      assertNotNull(betaSession);

      var deferred =
          mcp.server()
              .sendElicitationComplete(
                  alphaSession,
                  new McpSchema.ElicitationCompleteNotification("deleted-notification", Map.of()));
      var deleted = sendDeletedRequestForDeletedSession(test, alphaSession);

      assertEquals(200, deleted.statusCode());
      assertDoesNotThrow(() -> deferred.block(Duration.ofSeconds(3)));

      mcp.server()
          .sendElicitationComplete(
              betaSession,
              new McpSchema.ElicitationCompleteNotification("live-notification", Map.of()))
          .block(Duration.ofSeconds(3));

      assertEquals(
          new McpSchema.ElicitationCompleteNotification("live-notification", Map.of()),
          betaNotifications.poll(3, TimeUnit.SECONDS));

      await("no notification reaches the deleted session")
          .pollDelay(Duration.ZERO)
          .pollInterval(Duration.ofMillis(10))
          .during(Duration.ofMillis(250))
          .atMost(Duration.ofMillis(1250))
          .failFast(
              "the deleted session received a notification", () -> !alphaNotifications.isEmpty())
          .until(alphaNotifications::isEmpty);
      await("no duplicate notification reaches the live session")
          .pollDelay(Duration.ZERO)
          .pollInterval(Duration.ofMillis(10))
          .during(Duration.ofMillis(250))
          .atMost(Duration.ofMillis(1250))
          .failFast(
              "the live session received a duplicate notification",
              () -> !betaNotifications.isEmpty())
          .until(betaNotifications::isEmpty);
    }
  }

  private McpSyncClient openClient(
      TestServer test, LinkedBlockingQueue<ElicitationCompleteNotification> notifications) {
    return clientProperties(test, notifications).build();
  }

  private SyncSpec clientProperties(
      TestServer test, LinkedBlockingQueue<ElicitationCompleteNotification> notifications) {
    return McpClient.sync(
            HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                .endpoint("/mcp")
                .build())
        .requestTimeout(Duration.ofSeconds(3))
        .initializationTimeout(Duration.ofSeconds(3))
        .elicitationCompleteConsumer(notifications::add);
  }

  private HttpResponse<byte[]> sendDeletedRequestForDeletedSession(
      TestServer test, String alphaSession) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/mcp")
                .method("DELETE")
                .header("Mcp-Session-Id", alphaSession)
                .header("MCP-Protocol-Version", "2025-11-25"));
  }
}
