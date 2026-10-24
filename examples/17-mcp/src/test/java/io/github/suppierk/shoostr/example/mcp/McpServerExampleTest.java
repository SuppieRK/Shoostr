package io.github.suppierk.shoostr.example.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class McpServerExampleTest {
  @Test
  void discoversAndCallsANativeToolOverStreamableHttp() throws Exception {
    try (var test = TestServer.start(McpServerExample.application());
        var client =
            McpClient.sync(
                    HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                        .endpoint("/mcp")
                        .build())
                .requestTimeout(Duration.ofSeconds(3))
                .initializationTimeout(Duration.ofSeconds(3))
                .build()) {
      client.initialize();

      try {
        assertEquals("greet", client.listTools().tools().getFirst().name());
        var result =
            client.callTool(McpSchema.CallToolRequest.builder("greet").arguments(Map.of()).build());
        assertEquals(
            "Hello from Shoostr",
            assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst()).text());
      } finally {
        assertTrue(client.closeGracefully());
      }
    }
  }

  @Test
  void readsAResourceByItsMcpUriRatherThanAnHttpPath() throws Exception {
    try (var test = TestServer.start(McpServerExample.application());
        var client =
            McpClient.sync(
                    HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                        .endpoint("/mcp")
                        .build())
                .requestTimeout(Duration.ofSeconds(3))
                .initializationTimeout(Duration.ofSeconds(3))
                .build()) {
      client.initialize();

      try {
        assertEquals("guide://start", client.listResources().resources().getFirst().uri());
        var result =
            client.readResource(McpSchema.ReadResourceRequest.builder("guide://start").build());
        var content =
            assertInstanceOf(McpSchema.TextResourceContents.class, result.contents().getFirst());
        assertEquals("guide://start", content.uri());
        assertEquals(
            "Read resources for context; call tools to perform operations.", content.text());
        assertEquals(404, test.send(request -> request.path("/start")).statusCode());
      } finally {
        assertTrue(client.closeGracefully());
      }
    }
  }

  @Test
  void retrievesAPromptTemplateWithoutRunningAModel() throws Exception {
    try (var test = TestServer.start(McpServerExample.application());
        var client =
            McpClient.sync(
                    HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                        .endpoint("/mcp")
                        .build())
                .requestTimeout(Duration.ofSeconds(3))
                .initializationTimeout(Duration.ofSeconds(3))
                .build()) {
      client.initialize();

      try {
        assertEquals("explain", client.listPrompts().prompts().getFirst().name());
        var result =
            client.getPrompt(
                McpSchema.GetPromptRequest.builder("explain").arguments(Map.of()).build());
        var message = result.messages().getFirst();
        assertEquals(McpSchema.Role.USER, message.role());
        assertEquals(
            "Explain the difference between MCP tools and resources.",
            assertInstanceOf(McpSchema.TextContent.class, message.content()).text());
      } finally {
        assertTrue(client.closeGracefully());
      }
    }
  }

  @Test
  void exposesTheSameCatalogThroughTheAsynchronousNativeFactory() throws Exception {
    try (var test = TestServer.start(McpServerExample.asyncApplication());
        var client =
            McpClient.sync(
                    HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                        .endpoint("/mcp")
                        .build())
                .requestTimeout(Duration.ofSeconds(3))
                .initializationTimeout(Duration.ofSeconds(3))
                .build()) {
      client.initialize();

      try {
        var greeting =
            client.callTool(McpSchema.CallToolRequest.builder("greet").arguments(Map.of()).build());
        assertEquals(
            "Hello from Shoostr",
            assertInstanceOf(McpSchema.TextContent.class, greeting.content().getFirst()).text());
        var guide =
            client.readResource(McpSchema.ReadResourceRequest.builder("guide://start").build());
        assertEquals(
            "Read resources for context; call tools to perform operations.",
            assertInstanceOf(McpSchema.TextResourceContents.class, guide.contents().getFirst())
                .text());
        var explanation =
            client.getPrompt(
                McpSchema.GetPromptRequest.builder("explain").arguments(Map.of()).build());
        assertEquals(
            "Explain the difference between MCP tools and resources.",
            assertInstanceOf(
                    McpSchema.TextContent.class, explanation.messages().getFirst().content())
                .text());
      } finally {
        assertTrue(client.closeGracefully());
      }
    }
  }
}
