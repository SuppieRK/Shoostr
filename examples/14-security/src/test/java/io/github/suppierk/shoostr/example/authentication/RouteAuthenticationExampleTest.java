package io.github.suppierk.shoostr.example.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(10)
class RouteAuthenticationExampleTest {
  @Test
  void challengesAMissingIdentityBeforeTheProtectedHandlerRuns() throws Exception {
    try (var test = TestServer.start(RouteAuthenticationExample.application())) {
      var response = test.send(request -> request.path("/private/me"));
      assertEquals(401, response.statusCode());
      assertEquals(
          "Bearer realm=\"account\"",
          response.headers().firstValue("WWW-Authenticate").orElseThrow());
    }
  }

  @ParameterizedTest
  @CsvSource({"reader-demo-token, reader", "admin-demo-token, admin"})
  void exposesOnlyTheIdentityEstablishedByTheSelectedAuthenticator(String token, String name)
      throws Exception {
    try (var test = TestServer.start(RouteAuthenticationExample.application())) {
      var response =
          test.send(
              request -> request.path("/private/me").header("Authorization", "Bearer " + token),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals(name, response.body());
    }
  }

  @Test
  void leavesAnUnselectedRoutePublicEvenWhenCredentialsAreInvalid() throws Exception {
    try (var test = TestServer.start(RouteAuthenticationExample.application())) {
      var response =
          test.send(
              request -> request.path("/public").header("Authorization", "Bearer unknown"),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("public", response.body());
      assertFalse(response.headers().firstValue("WWW-Authenticate").isPresent());
    }
  }

  @ParameterizedTest
  @CsvSource({
    "reader-demo-token, 403, Forbidden",
    "admin-demo-token, 200, admin operation allowed"
  })
  void distinguishesAnAuthenticatedPermissionDenialFromAnAllowedAdministrator(
      String token, int status, String body) throws Exception {
    try (var test = TestServer.start(RouteAuthenticationExample.application())) {
      var response =
          test.send(
              request -> request.path("/private/admin").header("Authorization", "Bearer " + token),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(status, response.statusCode());
      assertEquals(body, response.body());
      assertFalse(response.headers().firstValue("WWW-Authenticate").isPresent());
    }
  }

  @ParameterizedTest
  @MethodSource("rejectedCredentials")
  void rejectsInvalidOrAmbiguousCredentialsOnEveryProtectedRoute(List<String> values)
      throws Exception {
    try (var test = TestServer.start(RouteAuthenticationExample.application())) {
      for (var path : List.of("/private/me", "/private/admin")) {
        var response =
            test.send(
                request -> {
                  request.path(path);
                  values.forEach(value -> request.header("Authorization", value));
                });
        assertEquals(401, response.statusCode(), path);
        assertEquals(
            "Bearer realm=\"account\"",
            response.headers().firstValue("WWW-Authenticate").orElseThrow());
        assertFalse(response.headers().firstValue("Set-Cookie").isPresent());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"bEaReR reader-demo-token", "Bearer   reader-demo-token"})
  void acceptsCaseInsensitiveBearerSchemesAndMultipleSeparatingSpaces(String authorization)
      throws Exception {
    try (var test = TestServer.start(RouteAuthenticationExample.application())) {
      var response =
          test.send(
              request -> request.path("/private/me").header("Authorization", authorization),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("reader", response.body());
    }
  }

  private static Stream<List<String>> rejectedCredentials() {
    return Stream.of(
        List.of(),
        List.of("Bearer unknown"),
        List.of("Bearer"),
        List.of("Basic reader-demo-token"),
        List.of("Bearer reader-demo-token trailing"),
        List.of("Bearer reader-demo-token", "Bearer admin-demo-token"));
  }
}
