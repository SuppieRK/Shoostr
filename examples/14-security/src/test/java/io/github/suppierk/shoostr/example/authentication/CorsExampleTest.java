package io.github.suppierk.shoostr.example.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.AuthenticationExtension;
import io.github.suppierk.shoostr.CorsPolicy;
import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.Response;
import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.http.exceptions.AuthenticationRequiredException;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(10)
class CorsExampleTest {
  @Test
  void replacesReportOriginsHeadersCredentialsAndCacheLifetime() throws Exception {
    var origin = "https://reports.example";

    try (var test = TestServer.start(CorsExample.application())) {
      var preflight =
          test.send(
              request ->
                  request
                      .path("/reports/daily")
                      .method("OPTIONS")
                      .header("Origin", origin)
                      .header("Access-Control-Request-Method", "GET")
                      .header("Access-Control-Request-Headers", "X-Report-Format"));
      assertEquals(204, preflight.statusCode());
      assertEquals("0", preflight.headers().firstValue("Access-Control-Max-Age").orElseThrow());
      assertFalse(preflight.headers().firstValue("Access-Control-Allow-Credentials").isPresent());
      var actual =
          test.send(
              request ->
                  request
                      .path("/reports/daily")
                      .header("Origin", origin)
                      .header("X-Report-Format", "csv"),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, actual.statusCode());
      assertEquals("csv", actual.body());
      assertEquals(
          origin, actual.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
      assertFalse(actual.headers().firstValue("Access-Control-Expose-Headers").isPresent());
      assertEquals(
          403,
          test.send(
                  request -> request.path("/reports/daily").header("Origin", "https://app.example"))
              .statusCode());
      assertEquals(
          403,
          test.send(
                  request ->
                      request
                          .path("/reports/daily")
                          .method("OPTIONS")
                          .header("Origin", origin)
                          .header("Access-Control-Request-Method", "GET")
                          .header("Access-Control-Request-Headers", "Authorization"))
              .statusCode());
      assertFalse(actual.headers().firstValue("Access-Control-Allow-Credentials").isPresent());
      assertEquals(
          403,
          test.send(
                  request -> request.path("/messages").header("Origin", "https://reports.example"))
              .statusCode());
    }
  }

  @Test
  void keepsMissingTargetsAndUnsupportedMethodsAsRoutingErrorsWithoutCorsGrants() throws Exception {
    try (var test = TestServer.start(CorsExample.application())) {
      for (var path : new String[] {"/reports/missing", "/reports/daily"}) {
        var preflight =
            test.send(
                request ->
                    request
                        .path(path)
                        .method("OPTIONS")
                        .header("Origin", "https://reports.example")
                        .header("Access-Control-Request-Method", "POST"));
        assertEquals(path.endsWith("daily") ? 405 : 404, preflight.statusCode());
        assertFalse(preflight.headers().firstValue("Access-Control-Allow-Origin").isPresent());
        var actual =
            test.send(
                request ->
                    request.path(path).method("POST").header("Origin", "https://reports.example"));
        assertEquals(path.endsWith("daily") ? 405 : 404, actual.statusCode());
        assertFalse(actual.headers().firstValue("Access-Control-Allow-Origin").isPresent());
        if (path.endsWith("daily")) {
          assertEquals("GET", actual.headers().firstValue("Allow").orElseThrow());
        }
      }
    }
  }

  @Test
  void givesASeparatelyRegisteredEndpointApplicationDefaultsInsteadOfTheReportsPolicy()
      throws Exception {
    var app = CorsExample.application();
    app.routes().get("/reports/outside", (request, response) -> response.text("outside"));

    try (var test = TestServer.start(app)) {
      var preflight =
          test.send(
              request ->
                  request
                      .path("/reports/outside")
                      .method("OPTIONS")
                      .header("Origin", "https://reports.example")
                      .header("Access-Control-Request-Method", "GET"));
      assertEquals(403, preflight.statusCode());
      var actual =
          test.send(
              request ->
                  request.path("/reports/outside").header("Origin", "https://reports.example"),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(403, actual.statusCode());
      assertFalse(actual.headers().firstValue("Access-Control-Allow-Origin").isPresent());
      var allowed =
          test.send(
              request -> request.path("/reports/outside").header("Origin", "https://app.example"),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, allowed.statusCode());
      assertEquals("outside", allowed.body());
      assertEquals(
          "https://app.example",
          allowed.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
    }
  }

  @Test
  void answersAnAllowedCredentialedPreflightWithoutAnOptionsHandler() throws Exception {
    try (var test = TestServer.start(CorsExample.application())) {
      var response =
          test.send(
              request ->
                  request
                      .path("/messages")
                      .method("OPTIONS")
                      .header("Origin", "https://app.example")
                      .header("Access-Control-Request-Method", "POST")
                      .header("Access-Control-Request-Headers", "content-type, authorization"));
      assertEquals(204, response.statusCode());
      assertEquals(0, response.body().length);
      assertEquals(
          "https://app.example",
          response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
      assertEquals(
          "true", response.headers().firstValue("Access-Control-Allow-Credentials").orElseThrow());
      assertEquals(
          "POST", response.headers().firstValue("Access-Control-Allow-Methods").orElseThrow());
      assertEquals(
          Set.of("authorization", "content-type"),
          Set.of(
              response
                  .headers()
                  .firstValue("Access-Control-Allow-Headers")
                  .orElseThrow()
                  .toLowerCase(Locale.ROOT)
                  .split(",\\s*")));
      assertEquals("600", response.headers().firstValue("Access-Control-Max-Age").orElseThrow());
      assertTrue(
          String.join(",", response.headers().allValues("Vary"))
              .toLowerCase(Locale.ROOT)
              .contains("access-control-request-method"));
    }
  }

  @ParameterizedTest
  @CsvSource({"GET, 200, hello", "POST, 201, accepted"})
  void sharesAnAllowedResponseAndExposesOnlyTheConfiguredExtraHeader(
      String method, int status, String body) throws Exception {
    try (var test = TestServer.start(CorsExample.application())) {
      var response =
          test.send(
              request ->
                  request.path("/messages").method(method).header("Origin", "https://app.example"),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(status, response.statusCode());
      assertEquals(body, response.body());
      assertEquals(
          "https://app.example",
          response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
      assertEquals(
          "true", response.headers().firstValue("Access-Control-Allow-Credentials").orElseThrow());
      assertEquals(
          "x-request-id",
          response
              .headers()
              .firstValue("Access-Control-Expose-Headers")
              .orElseThrow()
              .toLowerCase(Locale.ROOT));
      assertEquals("demo", response.headers().firstValue("X-Request-Id").orElseThrow());
    }
  }

  @ParameterizedTest
  @CsvSource({"https://other.example, 403", "null, 403", "https://app.example/path, 400", "*, 400"})
  void rejectsForeignOrMalformedOriginsInsteadOfSharingTheResponse(String origin, int status)
      throws Exception {
    try (var test = TestServer.start(CorsExample.application())) {
      var response = test.send(request -> request.path("/messages").header("Origin", origin));
      assertEquals(status, response.statusCode());
      assertFalse(response.headers().firstValue("Access-Control-Allow-Origin").isPresent());
    }
  }

  @ParameterizedTest
  @CsvSource({"DELETE, authorization, 405", "POST, x-unlisted, 403"})
  void rejectsAPreflightRequestingAnUnregisteredMethodOrUnlistedHeader(
      String method, String header, int status) throws Exception {
    try (var test = TestServer.start(CorsExample.application())) {
      var response =
          test.send(
              request ->
                  request
                      .path("/messages")
                      .method("OPTIONS")
                      .header("Origin", "https://app.example")
                      .header("Access-Control-Request-Method", method)
                      .header("Access-Control-Request-Headers", header));
      assertEquals(status, response.statusCode());
      assertFalse(response.headers().firstValue("Access-Control-Allow-Origin").isPresent());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void keepsRequestsWithoutAnOriginOrFromTheSameOriginInNormalDispatch(boolean sameOrigin)
      throws Exception {
    try (var test = TestServer.start(CorsExample.application())) {
      var response =
          test.send(
              request -> {
                request.path("/messages");
                if (sameOrigin) {
                  request.header(
                      "Origin",
                      test.baseUri().getScheme() + "://" + test.baseUri().getRawAuthority());
                }
              },
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("hello", response.body());
      assertFalse(response.headers().firstValue("Access-Control-Allow-Origin").isPresent());
    }
  }

  @Test
  void rejectsDuplicateOriginFields() throws Exception {
    try (var test = TestServer.start(CorsExample.application())) {
      var response =
          test.send(
              request ->
                  request
                      .path("/messages")
                      .header("Origin", "https://app.example")
                      .header("Origin", "https://app.example"));
      assertEquals(400, response.statusCode());
    }
  }

  @Test
  void admitsPreflightButStillRequiresAuthenticationForTheActualOperation() throws Exception {
    var authentication =
        new AuthenticationExtension() {
          @Override
          public void handle(Request request, Response response) {
            throw new AuthenticationRequiredException("Bearer realm=\"private\"");
          }
        };
    var app = CorsExample.application().authentication(authentication);
    app.routes()
        .get(
            "/fixture/private",
            (ignored, response) -> response.text("private"),
            extensions -> extensions.with(authentication));

    try (var test = TestServer.start(app)) {
      var preflight =
          test.send(
              request ->
                  request
                      .path("/fixture/private")
                      .method("OPTIONS")
                      .header("Origin", "https://app.example")
                      .header("Access-Control-Request-Method", "GET"));
      assertEquals(204, preflight.statusCode());
      assertFalse(preflight.headers().firstValue("WWW-Authenticate").isPresent());
      var actual =
          test.send(
              request -> request.path("/fixture/private").header("Origin", "https://app.example"));
      assertEquals(401, actual.statusCode());
      assertEquals(
          "Bearer realm=\"private\"",
          actual.headers().firstValue("WWW-Authenticate").orElseThrow());
      assertEquals(
          "https://app.example",
          actual.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
    }
  }

  @Test
  void excludedHealthEndpointExecutesWithoutGrantingCrossOriginSharing() throws Exception {
    try (var test = TestServer.start(CorsExample.application())) {
      var actual =
          test.send(
              request -> request.path("/health").header("Origin", "https://unlisted.example"),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, actual.statusCode());
      assertEquals("ready", actual.body());
      assertFalse(actual.headers().firstValue("Access-Control-Allow-Origin").isPresent());
      var preflight =
          test.send(
              request ->
                  request
                      .path("/health")
                      .method("OPTIONS")
                      .header("Origin", "https://app.example")
                      .header("Access-Control-Request-Method", "GET"));
      assertEquals(403, preflight.statusCode());
      assertFalse(preflight.headers().firstValue("Access-Control-Allow-Origin").isPresent());
    }
  }

  @Test
  void rejectsWildcardOriginsWhenCredentialedSharingIsEnabled() {
    var origins = Set.of("*");
    var methods = Set.of(HttpMethods.GET);
    var headers = Set.<HttpHeaders>of();
    assertThrows(
        IllegalArgumentException.class,
        () -> new CorsPolicy(origins, methods, headers, true, headers, 0));
  }
}
