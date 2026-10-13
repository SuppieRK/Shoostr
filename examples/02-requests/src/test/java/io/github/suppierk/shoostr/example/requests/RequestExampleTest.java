package io.github.suppierk.shoostr.example.requests;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class RequestExampleTest {
  @Test
  void preservesRepeatedFormValuesInOrder() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> name))) {
      var response =
          test.send(
              request ->
                  request
                      .path("/forms")
                      .method(HttpMethods.POST)
                      .header(
                          HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED.value())
                      .body("name=Ada&tag=java&tag=HTTP%20API".getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () -> assertEquals("Ada; tags=java,HTTP API", response.body()));
    }
  }

  @Test
  void requiresThePreviewHeaderWhenTheLiveFlagIsEnabled() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> name, () -> true))) {
      var response = test.send(request -> request.path("/preview"));
      assertAll(
          () -> assertEquals(404, response.statusCode()),
          () ->
              assertEquals(
                  "no-store",
                  response.headers().firstValue(HttpHeaders.CACHE_CONTROL.value()).orElseThrow()));
    }
  }

  @Test
  void rejectsAnIncorrectPreviewHeaderValue() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> name, () -> true))) {
      assertEquals(
          404,
          test.send(request -> request.path("/preview").header("X-Preview", "false")).statusCode());
    }
  }

  @Test
  void leavesPreviewDisabledInTheOneArgumentFactory() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> name))) {
      assertEquals(
          404,
          test.send(request -> request.path("/preview").header("X-Preview", "true")).statusCode());
    }
  }

  @Test
  void retainsMethodDiscoveryForAnUnavailablePreviewRoute() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> name))) {
      var response = test.send(request -> request.path("/preview").method(HttpMethods.POST));
      assertAll(
          () -> assertEquals(405, response.statusCode()),
          () ->
              assertEquals(
                  "GET", response.headers().firstValue(HttpHeaders.ALLOW.value()).orElseThrow()));
    }
  }

  @Test
  void rejectsPathSegmentsThatDoNotMatchTheNumericConstraint() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> name))) {
      assertEquals(404, test.send(request -> request.path("/items/abc")).statusCode());
    }
  }

  @Test
  void rejectsFormsWithAnUnsupportedMediaType() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> name))) {
      assertEquals(
          415,
          test.send(
                  request ->
                      request
                          .path("/forms")
                          .method(HttpMethods.POST)
                          .header(
                              HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM.value())
                          .body("name=Ada".getBytes(StandardCharsets.UTF_8)))
              .statusCode());
    }
  }

  @Test
  void rejectsAnExplicitlyBlankFormName() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> name))) {
      assertEquals(
          400,
          test.send(
                  request ->
                      request
                          .path("/forms")
                          .method(HttpMethods.POST)
                          .header(
                              HttpHeaders.CONTENT_TYPE,
                              MediaType.APPLICATION_FORM_URLENCODED.value())
                          .body("name=%20".getBytes(StandardCharsets.UTF_8)))
              .statusCode());
    }
  }

  @Test
  void doesNotUseAQueryNameAsAMissingFormName() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> name))) {
      assertEquals(
          400,
          test.send(
                  request ->
                      request
                          .path("/forms?name=Ada")
                          .method(HttpMethods.POST)
                          .header(
                              HttpHeaders.CONTENT_TYPE,
                              MediaType.APPLICATION_FORM_URLENCODED.value())
                          .body("tag=java".getBytes(StandardCharsets.UTF_8)))
              .statusCode());
    }
  }

  @Test
  void returnsNotFoundForAnUnregisteredPath() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> name))) {
      assertEquals(404, test.send(request -> request.path("/unknown")).statusCode());
    }
  }

  @Test
  void listsTheEchoMethodsWhenAnotherMethodIsRejected() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> name))) {
      var response = test.send(request -> request.path("/echo").method(HttpMethods.QUERY));
      assertAll(
          () -> assertEquals(405, response.statusCode()),
          () ->
              assertEquals(
                  Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"),
                  Set.of(
                      response
                          .headers()
                          .firstValue(HttpHeaders.ALLOW.value())
                          .orElseThrow()
                          .split(",\\s*"))));
    }
  }

  @Test
  void reevaluatesTheLivePreviewFlagWithoutRestartingTheApp() throws Exception {
    var enabled = new AtomicBoolean(false);

    try (var test = TestServer.start(RequestExample.application(name -> name, enabled::get))) {
      var disabled = test.send(request -> request.path("/preview").header("X-Preview", "true"));
      assertAll(
          () -> assertEquals(404, disabled.statusCode()),
          () ->
              assertEquals(
                  "no-store",
                  disabled.headers().firstValue(HttpHeaders.CACHE_CONTROL.value()).orElseThrow()));
      enabled.set(true);
      var available =
          test.send(
              request -> request.path("/preview").header("X-Preview", "true"),
              HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, available.statusCode()),
          () -> assertEquals("Preview enabled", available.body()));
      enabled.set(false);
      assertEquals(
          404,
          test.send(request -> request.path("/preview").header("X-Preview", "true")).statusCode());
    }
  }

  @Test
  void capturesTheRemainingPathInANamedCatchAll() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> name))) {
      var response =
          test.send(
              request -> request.path("/paths/guides/HTTP%20API"),
              HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () -> assertEquals("guides/HTTP API", response.body()));
    }
  }

  @Test
  void capturesADigitOnlyNamedPathParameter() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> name))) {
      var response =
          test.send(request -> request.path("/items/42"), HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () -> assertEquals("42", response.body()));
    }
  }

  @Test
  void echoesTheCallerSuppliedRequestIdHeader() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> name))) {
      var response =
          test.send(request -> request.path("/preferences").header("X-Request-Id", "demo-42"));
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () ->
              assertEquals("demo-42", response.headers().firstValue("X-Request-Id").orElseThrow()));
    }
  }

  @Test
  void readsTheCookieSetOnThePreviousResponse() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> name))) {
      var initial =
          test.send(
              request -> request.path("/preferences?theme=dark"),
              HttpResponse.BodyHandlers.ofString());
      var subsequent =
          test.send(request -> request.path("/preferences"), HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, initial.statusCode()),
          () -> assertEquals("unset", initial.body()),
          () -> assertEquals(200, subsequent.statusCode()),
          () -> assertEquals("dark", subsequent.body()));
    }
  }

  @Test
  void decodesTheFormNameIndependentlyOfQueryParameters() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> "Welcome " + name))) {
      var response =
          test.send(
              request ->
                  request
                      .path("/forms?name=QueryName")
                      .method(HttpMethods.POST)
                      .header(
                          HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED.value())
                      .body("name=Ada+Lovelace".getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () -> assertEquals("Welcome Ada Lovelace; tags=", response.body()));
    }
  }

  @Test
  void acceptsAQueryBodyThroughGenericEnumMethodRegistration() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> name))) {
      var response =
          test.send(
              request ->
                  request
                      .path("/query")
                      .method(HttpMethods.QUERY)
                      .body("tag=java".getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () -> assertEquals("tag=java", response.body()));
    }
  }

  @Test
  void advertisesTheSevenExplicitEchoMethodsThroughOptions() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> name))) {
      var response = test.send(request -> request.path("/echo").method(HttpMethods.OPTIONS));
      assertAll(
          () -> assertEquals(204, response.statusCode()),
          () ->
              assertEquals(
                  "GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS",
                  response.headers().firstValue(HttpHeaders.ALLOW.value()).orElseThrow()),
          () -> assertArrayEquals(new byte[0], response.body()));
    }
  }

  @Test
  void returnsExplicitHeadMetadataWithoutAResponseBody() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> name))) {
      var response = test.send(request -> request.path("/echo").method(HttpMethods.HEAD));
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () ->
              assertEquals("stateless", response.headers().firstValue("X-Echo-Mode").orElseThrow()),
          () -> assertArrayEquals(new byte[0], response.body()));
    }
  }

  @Test
  void returnsNoContentForDelete() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> name))) {
      var response = test.send(request -> request.path("/echo").method(HttpMethods.DELETE));
      assertAll(
          () -> assertEquals(204, response.statusCode()),
          () -> assertArrayEquals(new byte[0], response.body()));
    }
  }

  @Test
  void echoesPatchTextAndIdentifiesTheRequestMethod() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> name))) {
      var response =
          test.send(
              request ->
                  request
                      .path("/echo")
                      .method(HttpMethods.PATCH)
                      .body("partial input".getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () -> assertEquals("partial input", response.body()),
          () ->
              assertEquals(
                  "PATCH", response.headers().firstValue("X-Request-Method").orElseThrow()));
    }
  }

  @Test
  void preservesRawBytesSubmittedWithPut() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> name))) {
      var response =
          test.send(
              request ->
                  request
                      .path("/echo")
                      .method(HttpMethods.PUT)
                      .body(new byte[] {0, 1, 127, -128, -1}));
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () -> assertArrayEquals(new byte[] {0, 1, 127, -128, -1}, response.body()),
          () ->
              assertEquals(
                  "application/octet-stream",
                  response.headers().firstValue(HttpHeaders.CONTENT_TYPE.value()).orElseThrow()));
    }
  }

  @Test
  void echoesUtf8TextSubmittedWithPost() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> name))) {
      var response =
          test.send(
              request ->
                  request
                      .path("/echo")
                      .method(HttpMethods.POST)
                      .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN.value())
                      .body("Hello, 世界!".getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () -> assertEquals("Hello, 世界!", response.body()));
    }
  }

  @Test
  void returnsTextFromTheGroupedGetEndpoint() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> name))) {
      var response =
          test.send(request -> request.path("/echo"), HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () -> assertEquals("Send text with POST or PATCH, or bytes with PUT", response.body()));
    }
  }

  @Test
  void passesTheDecodedExplicitQueryNameToTheDependency() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> "Welcome " + name))) {
      var response =
          test.send(
              request -> request.path("/welcome?name=Ada%20Lovelace"),
              HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () -> assertEquals("Welcome Ada Lovelace", response.body()));
    }
  }

  @Test
  void returnsEmptySearchTextWhenNoTagsAreSupplied() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> name))) {
      var response =
          test.send(request -> request.path("/search"), HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()), () -> assertEquals("", response.body()));
    }
  }

  @Test
  void rejectsABlankExplicitQueryNameRatherThanApplyingTheDefault() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> name))) {
      assertEquals(400, test.send(request -> request.path("/welcome?name=%20")).statusCode());
    }
  }

  @Test
  void usesWorldWhenTheOptionalQueryNameIsAbsent() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> "Welcome " + name))) {
      var response =
          test.send(request -> request.path("/welcome"), HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () -> assertEquals("Welcome World", response.body()));
    }
  }

  @Test
  void preservesRepeatedQueryValuesInOrder() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> name))) {
      var response =
          test.send(
              request -> request.path("/search?tag=java&tag=HTTP%20API"),
              HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () -> assertEquals("java,HTTP API", response.body()));
    }
  }

  @Test
  void usesTheDependencySuppliedBeforeStartingTheFixture() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> "Test " + name))) {
      var response =
          test.send(
              request -> request.path("/greetings/Ada"), HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () -> assertEquals("Test Ada", response.body()));
    }
  }
}
