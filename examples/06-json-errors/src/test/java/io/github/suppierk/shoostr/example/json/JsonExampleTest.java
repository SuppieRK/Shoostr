package io.github.suppierk.shoostr.example.json;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonExampleTest {
  @ParameterizedTest
  @ValueSource(strings = {"{\"name\":\"Ada\"} trailing", "{\"name\":\"Ada\"}{}"})
  void rejectsTrailingContentAfterAValidJsonObject(String body) throws Exception {
    try (var test = TestServer.start(JsonExample.application())) {
      var response =
          test.send(
              request ->
                  request
                      .path("/users")
                      .method(HttpMethods.POST)
                      .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON.value())
                      .body(body.getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(400, response.statusCode()),
          () -> assertEquals("{\"error\":\"invalid_user\"}", response.body()));
    }
  }

  @Test
  void rendersMalformedJsonAsAnInputErrorRatherThanAServerFailure() throws Exception {
    try (var test = TestServer.start(JsonExample.application())) {
      var response =
          test.send(
              request ->
                  request
                      .path("/users")
                      .method(HttpMethods.POST)
                      .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON.value())
                      .body("{not-json".getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(400, response.statusCode()),
          () -> assertEquals("{\"error\":\"invalid_user\"}", response.body()));
    }
  }

  @Test
  void rendersAnUnexpectedFailureWithoutLeakingItsMessage() throws Exception {
    try (var test = TestServer.start(JsonExample.application())) {
      var response =
          test.send(
              request -> request.path("/users/failure"), HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(500, response.statusCode()),
          () -> assertEquals("{\"error\":\"internal_error\"}", response.body()),
          () ->
              assertEquals(
                  MediaType.APPLICATION_JSON.value(),
                  response.headers().firstValue(HttpHeaders.CONTENT_TYPE.value()).orElseThrow()));
    }
  }

  @Test
  void retainsPlainTextResponsesWhenTheCodecIsEnabled() throws Exception {
    try (var test = TestServer.start(JsonExample.application())) {
      var response =
          test.send(request -> request.path("/users/help"), HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () -> assertEquals("POST a JSON object with a nonblank name", response.body()),
          () ->
              assertEquals(
                  "text/plain; charset=utf-8",
                  response.headers().firstValue(HttpHeaders.CONTENT_TYPE.value()).orElseThrow()));
    }
  }

  @Test
  void rendersInvalidUserInputThroughTheScopedErrorHandler() throws Exception {
    try (var test = TestServer.start(JsonExample.application())) {
      var response =
          test.send(
              request ->
                  request
                      .path("/users")
                      .method(HttpMethods.POST)
                      .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON.value())
                      .body("{\"name\":\" \"}".getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(400, response.statusCode()),
          () -> assertEquals("{\"error\":\"invalid_user\"}", response.body()),
          () ->
              assertEquals(
                  MediaType.APPLICATION_JSON.value(),
                  response.headers().firstValue(HttpHeaders.CONTENT_TYPE.value()).orElseThrow()));
    }
  }

  @Test
  void returnsTheValidatedTypedUserAsJson() throws Exception {
    try (var test = TestServer.start(JsonExample.application())) {
      var response =
          test.send(
              request ->
                  request
                      .path("/users")
                      .method(HttpMethods.POST)
                      .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON.value())
                      .body("{\"name\":\"Ada\"}".getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(201, response.statusCode()),
          () -> assertEquals("{\"name\":\"Ada\"}", response.body()),
          () ->
              assertEquals(
                  MediaType.APPLICATION_JSON.value(),
                  response.headers().firstValue(HttpHeaders.CONTENT_TYPE.value()).orElseThrow()));
    }
  }
}
