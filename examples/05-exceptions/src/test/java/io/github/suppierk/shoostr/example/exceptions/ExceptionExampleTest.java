package io.github.suppierk.shoostr.example.exceptions;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExceptionExampleTest {
  @Test
  void recoversFromAMissingIntegerInsideTheHandler() throws Exception {
    try (var test = TestServer.start(ExceptionExample.application())) {
      var response =
          test.send(request -> request.path("/number"), HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(400, response.statusCode()),
          () -> assertEquals("Integer required", response.body()),
          () ->
              assertEquals(
                  "text/plain; charset=utf-8",
                  response.headers().firstValue(HttpHeaders.CONTENT_TYPE.value()).orElseThrow()));
    }
  }

  @Test
  void keepsAThrownNotFoundSeparateFromTheGeneratedStatusRenderer() throws Exception {
    try (var test = TestServer.start(ExceptionExample.application())) {
      var response =
          test.send(
              request -> request.path("/builtin/not-found"), HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(404, response.statusCode()),
          () -> assertEquals("Not Found", response.body()),
          () ->
              assertEquals(
                  "text/plain; charset=utf-8",
                  response.headers().firstValue(HttpHeaders.CONTENT_TYPE.value()).orElseThrow()));
    }
  }

  @Test
  void rendersARouterGeneratedNotFoundWithTheStatusHandler() throws Exception {
    try (var test = TestServer.start(ExceptionExample.application())) {
      var response =
          test.send(request -> request.path("/unknown"), HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(404, response.statusCode()),
          () -> assertEquals("Route not found", response.body()),
          () ->
              assertEquals(
                  "text/plain; charset=utf-8",
                  response.headers().firstValue(HttpHeaders.CONTENT_TYPE.value()).orElseThrow()));
    }
  }

  @Test
  void inheritsTheRootRendererWhenTheGroupDoesNotReplaceIt() throws Exception {
    try (var test = TestServer.start(ExceptionExample.application())) {
      var response =
          test.send(
              request -> request.path("/inherited/input"), HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(400, response.statusCode()),
          () -> assertEquals("Route input error", response.body()),
          () ->
              assertEquals(
                  "text/plain; charset=utf-8",
                  response.headers().firstValue(HttpHeaders.CONTENT_TYPE.value()).orElseThrow()));
    }
  }

  @Test
  void usesTheGroupOverrideInsteadOfTheInheritedRenderer() throws Exception {
    try (var test = TestServer.start(ExceptionExample.application())) {
      var response =
          test.send(request -> request.path("/group/input"), HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(400, response.statusCode()),
          () -> assertEquals("Group input error", response.body()),
          () ->
              assertEquals(
                  "text/plain; charset=utf-8",
                  response.headers().firstValue(HttpHeaders.CONTENT_TYPE.value()).orElseThrow()));
    }
  }

  @Test
  void rendersARootScopedInputFailure() throws Exception {
    try (var test = TestServer.start(ExceptionExample.application())) {
      var response =
          test.send(
              request -> request.path("/route-failure"), HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(400, response.statusCode()),
          () -> assertEquals("Route input error", response.body()),
          () ->
              assertEquals(
                  "text/plain; charset=utf-8",
                  response.headers().firstValue(HttpHeaders.CONTENT_TYPE.value()).orElseThrow()));
    }
  }

  @Test
  void rendersAnApplicationFailureWithoutExposingStagedOutputOrDiagnostics() throws Exception {
    try (var test = TestServer.start(ExceptionExample.application())) {
      var response =
          test.send(request -> request.path("/app-failure"), HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(500, response.statusCode()),
          () -> assertEquals("Application failure", response.body()),
          () ->
              assertEquals(
                  "text/plain; charset=utf-8",
                  response.headers().firstValue(HttpHeaders.CONTENT_TYPE.value()).orElseThrow()));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"abc", "2147483648"})
  void recoversFromAnInvalidIntegerInsideTheHandler(String value) throws Exception {
    try (var test = TestServer.start(ExceptionExample.application())) {
      var response =
          test.send(
              request -> request.path("/number?value=" + value),
              HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(400, response.statusCode()),
          () -> assertEquals("Integer required", response.body()),
          () ->
              assertEquals(
                  "text/plain; charset=utf-8",
                  response.headers().firstValue(HttpHeaders.CONTENT_TYPE.value()).orElseThrow()));
    }
  }

  @Test
  void returnsAValidIntegerWithoutInvokingAnErrorRenderer() throws Exception {
    try (var test = TestServer.start(ExceptionExample.application())) {
      var response =
          test.send(
              request -> request.path("/number?value=42"), HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () -> assertEquals("42", response.body()),
          () ->
              assertEquals(
                  "text/plain; charset=utf-8",
                  response.headers().firstValue(HttpHeaders.CONTENT_TYPE.value()).orElseThrow()));
    }
  }

  @Test
  void rendersAnHttpExceptionWithoutExposingItsDiagnostic() throws Exception {
    try (var test = TestServer.start(ExceptionExample.application())) {
      var response =
          test.send(
              request -> request.path("/builtin/bad-request"),
              HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(400, response.statusCode()),
          () -> assertEquals("Bad Request", response.body()),
          () ->
              assertEquals(
                  "text/plain; charset=utf-8",
                  response.headers().firstValue(HttpHeaders.CONTENT_TYPE.value()).orElseThrow()));
    }
  }
}
