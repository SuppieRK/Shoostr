package io.github.suppierk.shoostr.example.json;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class JsonCodecExampleTest {
  @Test
  void leavesMediaEnforcementToTheHandlerRatherThanTheCodec() throws Exception {
    try (var test = TestServer.start(JsonCodecExample.application())) {
      var response =
          test.send(
              request ->
                  request
                      .path("/users")
                      .method("POST")
                      .header("Content-Type", "text/plain")
                      .header("Accept", "application/xml")
                      .body("{\"name\":\"Ada\"}".getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(201, response.statusCode());
      assertEquals("application/json", response.headers().firstValue("Content-Type").orElseThrow());
      assertEquals("{\"name\":\"Ada\"}", response.body());
    }
  }

  @Test
  void keepsOrdinaryTextAvailableWithATypedApplication() throws Exception {
    try (var test = TestServer.start(JsonCodecExample.application())) {
      var response =
          test.send(request -> request.path("/help"), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals(
          "text/plain; charset=utf-8", response.headers().firstValue("Content-Type").orElseThrow());
      assertEquals("Send a JSON object with a nonblank name", response.body());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "{broken",
        "[]",
        "null",
        "{}",
        "{\"name\":null}",
        "{\"name\":1}",
        "{\"name\":\" \"}",
        "{\"name\":\"Ada\"} trailing",
        "{\"name\":\"Ada\"}{}"
      })
  void rejectsMalformedJsonAndInvalidNamesWithASafeJsonError(String body) throws Exception {
    try (var test = TestServer.start(JsonCodecExample.application())) {
      var response =
          test.send(
              request ->
                  request
                      .path("/users")
                      .method("POST")
                      .header("Content-Type", "application/json")
                      .body(body.getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(400, response.statusCode());
      assertEquals("application/json", response.headers().firstValue("Content-Type").orElseThrow());
      assertEquals("{\"error\":\"invalid_user\"}", response.body());
    }
  }

  @Test
  void returnsAValidatedUserAsJsonWithUnicodeAndEscapedCharacters() throws Exception {
    try (var test = TestServer.start(JsonCodecExample.application())) {
      var response =
          test.send(
              request ->
                  request
                      .path("/users")
                      .method("POST")
                      .header("Content-Type", "application/json")
                      .body("{\"name\":\"Zoë \\\"Z\\\"\\nLee\"}".getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(201, response.statusCode());
      assertEquals("application/json", response.headers().firstValue("Content-Type").orElseThrow());
      assertEquals("{\"name\":\"Zoë \\\"Z\\\"\\nLee\"}", response.body());
    }
  }
}
