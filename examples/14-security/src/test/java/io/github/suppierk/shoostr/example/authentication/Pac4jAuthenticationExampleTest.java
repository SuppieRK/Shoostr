package io.github.suppierk.shoostr.example.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(10)
class Pac4jAuthenticationExampleTest {
  @Test
  void publishesAProfileOnlyAfterTheDirectClientValidatesItsCredentials() throws Exception {
    try (var test = TestServer.start(Pac4jAuthenticationExample.application())) {
      var response =
          test.send(
              request ->
                  request.path("/accounts/me").header("Authorization", basic("reader:local-only")),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("reader", response.body());
    }
  }

  @ParameterizedTest
  @CsvSource({"reader, 403, Forbidden", "admin, 200, permitted"})
  void requiresTheAdministratorRoleAfterSuccessfulCredentialValidation(
      String user, int status, String body) throws Exception {
    try (var test = TestServer.start(Pac4jAuthenticationExample.application())) {
      var response =
          test.send(
              request ->
                  request
                      .path("/accounts/admin")
                      .header("Authorization", basic(user + ":local-only")),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(status, response.statusCode());
      assertEquals(body, response.body());
      assertFalse(response.headers().firstValue("WWW-Authenticate").isPresent());
    }
  }

  @Test
  void challengesMissingCredentialsBeforeRunningTheAccountHandler() throws Exception {
    try (var test = TestServer.start(Pac4jAuthenticationExample.application())) {
      var response = test.send(request -> request.path("/accounts/me"));
      assertEquals(401, response.statusCode());
      assertEquals(
          "Basic realm=\"accounts\", charset=\"UTF-8\"",
          response.headers().firstValue("WWW-Authenticate").orElseThrow());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"reader:wrong", "unknown:local-only", "reader:"})
  void rejectsCredentialsThatTheAuthenticatorDoesNotValidate(String credentials) throws Exception {
    try (var test = TestServer.start(Pac4jAuthenticationExample.application())) {
      var response =
          test.send(
              request -> request.path("/accounts/me").header("Authorization", basic(credentials)));
      assertEquals(401, response.statusCode());
      assertEquals(
          "Basic realm=\"accounts\", charset=\"UTF-8\"",
          response.headers().firstValue("WWW-Authenticate").orElseThrow());
    }
  }

  @Test
  void rejectsMultipleAuthorizationFieldsEvenWhenBothCredentialsAreValid() throws Exception {
    try (var test = TestServer.start(Pac4jAuthenticationExample.application())) {
      var response =
          test.send(
              request ->
                  request
                      .path("/accounts/me")
                      .header("Authorization", basic("reader:local-only"))
                      .header("Authorization", basic("admin:local-only")));
      assertEquals(401, response.statusCode());
    }
  }

  @Test
  void doesNotCreateASessionEvenWhenTheApplicationEnablesSessions() throws Exception {

    var app = Pac4jAuthenticationExample.application().sessions();
    app.routes()
        .path(
            "/",
            scoped ->
                scoped.get(
                    "/fixture/session-state",
                    (request, response) ->
                        response.text(request.getSession().isPresent() ? "present" : "absent")));

    try (var test = TestServer.start(app)) {
      var authenticated =
          test.send(
              request ->
                  request.path("/accounts/me").header("Authorization", basic("reader:local-only")));
      assertEquals(200, authenticated.statusCode());
      assertFalse(authenticated.headers().firstValue("Set-Cookie").isPresent());
      var state =
          test.send(
              request -> request.path("/fixture/session-state"),
              HttpResponse.BodyHandlers.ofString());
      assertEquals("absent", state.body());
    }
  }

  private static String basic(String credentials) {
    return "Basic "
        + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
  }
}
