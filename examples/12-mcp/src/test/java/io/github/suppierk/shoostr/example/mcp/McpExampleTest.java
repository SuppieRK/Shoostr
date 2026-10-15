package io.github.suppierk.shoostr.example.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpStatusCodes;
import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
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

    try (var test = TestServer.start(McpExample.application())) {
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
  void servesTheExampleWelcomeMessageThroughTheRunnableApplication() throws Exception {
    try (var test = TestServer.start(McpExample.application());
        var client = client(test)) {
      client.initialize();
      var result =
          client.callTool(
              McpSchema.CallToolRequest.builder("test_simple_text").arguments(Map.of()).build());
      assertFalse(result.isError());
      assertEquals(
          "Welcome to Shoostr's MCP example.",
          assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst()).text());
    }
  }

  @Test
  @Timeout(20)
  void servesTheGeneratedCheckerboardThroughTheImageTool() throws Exception {
    try (var test = TestServer.start(McpExample.application());
        var client = client(test)) {
      client.initialize();
      var result =
          client.callTool(
              McpSchema.CallToolRequest.builder("test_image_content").arguments(Map.of()).build());
      var content = assertInstanceOf(McpSchema.ImageContent.class, result.content().getFirst());
      assertEquals("image/png", content.mimeType());

      try (var bytes = new ByteArrayInputStream(Base64.getDecoder().decode(content.data()))) {
        var image = ImageIO.read(bytes);
        assertNotNull(image);
        assertEquals(8, image.getWidth());
        assertEquals(8, image.getHeight());
        assertEquals(0xff1d4ed8, image.getRGB(0, 0));
        assertEquals(0xffffc857, image.getRGB(2, 0));
      }
    }
  }

  @Test
  @Timeout(20)
  void servesTheGeneratedPcmPulseThroughTheAudioTool() throws Exception {
    try (var test = TestServer.start(McpExample.application());
        var client = client(test)) {
      client.initialize();
      var result =
          client.callTool(
              McpSchema.CallToolRequest.builder("test_audio_content").arguments(Map.of()).build());
      var content = assertInstanceOf(McpSchema.AudioContent.class, result.content().getFirst());
      assertEquals("audio/wav", content.mimeType());

      try (var bytes = new ByteArrayInputStream(Base64.getDecoder().decode(content.data()));
          var audio = AudioSystem.getAudioInputStream(bytes)) {
        var format = audio.getFormat();
        assertEquals(AudioFormat.Encoding.PCM_SIGNED, format.getEncoding());
        assertEquals(8_000.0f, format.getSampleRate());
        assertEquals(16, format.getSampleSizeInBits());
        assertEquals(1, format.getChannels());
        assertFalse(format.isBigEndian());
        assertEquals(64, audio.getFrameLength());
        var samples = audio.readAllBytes();
        assertEquals(128, samples.length);
        assertTrue(IntStream.range(0, samples.length).anyMatch(index -> samples[index] != 0));
      }
    }
  }

  @Test
  @Timeout(20)
  void publishesTheParcelDeliverySchemaWithoutLosingItsConditionsOrReferences() throws Exception {
    try (var test = TestServer.start(McpExample.application());
        var client = client(test)) {
      client.initialize();
      var schema =
          client.listTools().tools().stream()
              .filter(tool -> "json_schema_2020_12_tool".equals(tool.name()))
              .findFirst()
              .orElseThrow()
              .inputSchema();

      assertEquals("https://json-schema.org/draft/2020-12/schema", schema.get("$schema"));
      assertEquals("object", schema.get("type"));
      assertEquals(List.of("recipient", "delivery"), schema.get("required"));
      assertEquals(false, schema.get("additionalProperties"));
      assertEquals(
          Map.of(
              "recipient", Map.of("type", "string", "minLength", 1),
              "delivery", Map.of("type", "string", "enum", List.of("pickup", "courier")),
              "destination", Map.of("$ref", "#parcelDestination"),
              "collectionPoint", Map.of("type", "string", "minLength", 1)),
          schema.get("properties"));
      assertEquals(
          Map.of(
              "address",
              Map.of(
                  "$anchor",
                  "parcelDestination",
                  "type",
                  "object",
                  "properties",
                  Map.of(
                      "postalCode", Map.of("type", "string", "minLength", 1),
                      "countryCode", Map.of("type", "string", "pattern", "^[A-Z]{2}$")),
                  "required",
                  List.of("postalCode", "countryCode"),
                  "additionalProperties",
                  false)),
          schema.get("$defs"));
      assertEquals(
          List.of(
              Map.of(
                  "anyOf",
                  List.of(
                      Map.of("required", List.of("destination")),
                      Map.of("required", List.of("collectionPoint"))))),
          schema.get("allOf"));
      assertEquals(
          Map.of(
              "properties",
              Map.of("delivery", Map.of("const", "pickup")),
              "required",
              List.of("delivery")),
          schema.get("if"));
      assertEquals(Map.of("required", List.of("collectionPoint")), schema.get("then"));
      assertEquals(Map.of("required", List.of("destination")), schema.get("else"));
    }
  }

  private static McpSyncClient client(TestServer test) {
    return McpClient.sync(
            HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                .endpoint("/mcp")
                .build())
        .requestTimeout(Duration.ofSeconds(3))
        .initializationTimeout(Duration.ofSeconds(3))
        .build();
  }
}
