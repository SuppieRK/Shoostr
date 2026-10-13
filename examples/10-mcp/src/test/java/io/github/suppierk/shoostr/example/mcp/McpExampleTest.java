package io.github.suppierk.shoostr.example.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class McpExampleTest {
  @Test
  @Timeout(20)
  void servesTheConformanceTextToolThroughTheRunnableApplication() throws Exception {
    try (var test = TestServer.start(McpExample.application(Options.defaults()));
        var client =
            McpClient.sync(
                    HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                        .endpoint("/mcp")
                        .build())
                .requestTimeout(Duration.ofSeconds(3))
                .initializationTimeout(Duration.ofSeconds(3))
                .build()) {
      client.initialize();
      var result =
          client.callTool(
              McpSchema.CallToolRequest.builder("test_simple_text").arguments(Map.of()).build());
      assertFalse(result.isError());
      assertEquals(
          "This is a simple text response for testing.",
          assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst()).text());
    }
  }
}
