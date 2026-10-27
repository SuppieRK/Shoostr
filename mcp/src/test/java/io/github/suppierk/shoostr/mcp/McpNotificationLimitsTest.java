package io.github.suppierk.shoostr.mcp;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class McpNotificationLimitsTest {
  @Test
  void boundsOrdinaryNotificationsWhileTheirClientRepliesUseReservedCapacity() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var mcp =
        Mcp.async(
            new McpHttpSecurity(
                Set.of(),
                ignored -> new McpIdentity("alice", McpTransportContext.EMPTY, null),
                (identity, message) -> {}),
            McpSessionLimits.defaults(),
            new McpOperationLimits(4, 4, 1, Duration.ofMinutes(5)),
            McpOperationLimits.defaults(),
            (transport, work) ->
                McpServer.async(transport)
                    .rootsChangeHandler(
                        (exchange, roots) ->
                            work.call(
                                    () -> {
                                      entered.countDown();
                                      if (!release.await(10, TimeUnit.SECONDS)) {
                                        throw new IllegalStateException("Fixture was not released");
                                      }

                                      return true;
                                    })
                                .then())
                    .build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app);
        var client =
            McpClient.sync(
                    HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                        .endpoint("/mcp")
                        .build())
                .capabilities(McpSchema.ClientCapabilities.builder().roots(true).build())
                .roots(new McpSchema.Root("file:///project", "project"))
                .requestTimeout(Duration.ofSeconds(3))
                .build()) {
      client.initialize();

      try {
        await()
            .atMost(Duration.ofSeconds(4))
            .ignoreExceptions()
            .untilAsserted(
                () -> {
                  if (entered.getCount() != 0) {
                    client.rootsListChangedNotification();
                  }

                  assertTrue(entered.await(1, TimeUnit.SECONDS));
                });
        var failure = assertThrows(RuntimeException.class, client::rootsListChangedNotification);
        assertTrue(failure.getMessage().contains("429"), failure.toString());
      } finally {
        release.countDown();
      }

      await()
          .atMost(Duration.ofSeconds(3))
          .ignoreExceptions()
          .untilAsserted(() -> assertDoesNotThrow(client::ping));
    }
  }
}
