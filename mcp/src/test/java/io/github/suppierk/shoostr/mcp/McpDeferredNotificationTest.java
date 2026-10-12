package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
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
  void discardsADeferredNotificationForADeletedSessionWithoutAffectingAnotherClient()
      throws Exception {
    var alphaNotifications = new LinkedBlockingQueue<McpSchema.ElicitationCompleteNotification>();
    var betaNotifications = new LinkedBlockingQueue<McpSchema.ElicitationCompleteNotification>();
    var listening = new LinkedBlockingQueue<String>();
    Set<String> attached = ConcurrentHashMap.newKeySet();
    var security =
        new McpHttpSecurity(
            Set.of(),
            _ -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (_, _) -> {});
    var mcp = Mcp.async(security, transport -> McpServer.async(transport).build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));
    app.beforeResponseFlush(
        (request, _) -> {
          if ("GET".equals(request.method()) && "/mcp".equals(request.path())) {
            var session = request.header("Mcp-Session-Id").orElseThrow();
            if (attached.add(session)) {
              listening.add(session);
            }
          }
        });

    try (var test = TestServer.start(app);
        var alpha =
            McpClient.sync(
                    HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                        .endpoint("/mcp")
                        .build())
                .requestTimeout(Duration.ofSeconds(3))
                .initializationTimeout(Duration.ofSeconds(3))
                .elicitationCompleteConsumer(alphaNotifications::add)
                .build();
        var beta =
            McpClient.sync(
                    HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                        .endpoint("/mcp")
                        .build())
                .requestTimeout(Duration.ofSeconds(3))
                .initializationTimeout(Duration.ofSeconds(3))
                .elicitationCompleteConsumer(betaNotifications::add)
                .build()) {
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
      var deleted =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .method("DELETE")
                      .header("Mcp-Session-Id", alphaSession)
                      .header("MCP-Protocol-Version", "2025-11-25"));
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
      assertNull(alphaNotifications.poll(250, TimeUnit.MILLISECONDS));
      assertNull(betaNotifications.poll(250, TimeUnit.MILLISECONDS));
    }
  }
}
