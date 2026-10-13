package io.github.suppierk.shoostr.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.mcp.Mcp;
import io.github.suppierk.shoostr.mcp.McpHttpSecurity;
import io.github.suppierk.shoostr.mcp.McpIdentity;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class McpConsumerTest {
  @Test
  void consumesTheOptionalPublishedAdapterWithNativeSdkTypesAndProtocolSerialization()
      throws Exception {
    var mcp =
        Mcp.sync(
            new McpHttpSecurity(
                Set.of(),
                _ -> new McpIdentity("public", McpTransportContext.EMPTY, null),
                (_, _) -> {}),
            transport -> McpServer.sync(transport).serverInfo("published-consumer", "1.0").build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app);
        var client =
            McpClient.sync(
                    HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                        .endpoint("/mcp")
                        .build())
                .requestTimeout(Duration.ofSeconds(5))
                .build()) {
      var initialized = client.initialize();
      assertEquals("2025-11-25", initialized.protocolVersion());
      assertEquals("published-consumer", initialized.serverInfo().name());
      assertTrue(client.closeGracefully());
    }
  }
}
