package io.github.suppierk.shoostr.example.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpStatusCodes;
import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class McpExampleTest {
  @ParameterizedTest
  @CsvSource({
    "http://127.0.0.1:8080, OK",
    "http://0.0.0.0:8080, FORBIDDEN",
    "https://untrusted.example, FORBIDDEN"
  })
  @Timeout(20)
  void enforcesTheBrowserOriginIndependentlyOfTheBindAddress(
      String origin, HttpStatusCodes expectedStatus) throws Exception {
    var initialize =
        """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
          "protocolVersion":"2025-11-25","capabilities":{},
          "clientInfo":{"name":"browser-origin-test","version":"1.0"}}}
        """;

    try (var test = TestServer.start(McpExample.application(Options.defaults()))) {
      var reply =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .method("POST")
                      .header(HttpHeaders.ORIGIN, origin)
                      .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON.value())
                      .header(HttpHeaders.ACCEPT, "application/json, text/event-stream")
                      .body(initialize.getBytes(StandardCharsets.UTF_8)));
      assertEquals(expectedStatus.value(), reply.statusCode());
    }
  }

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
