package io.github.suppierk.shoostr.example.authentication;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class AuthenticationExampleTest {
  @Test
  void leavesThePublicRouteOutsideTheAuthenticationGroup() throws Exception {
    try (var test = TestServer.start(AuthenticationExample.application(Options.defaults()))) {
      var response =
          test.send(request -> request.path("/public"), HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () -> assertEquals("public", response.body()));
    }
  }

  @Test
  void exposesTheAuthenticatedPrincipalOnTheProtectedGroup() throws Exception {
    try (var test = TestServer.start(AuthenticationExample.application(Options.defaults()))) {
      var credentials =
          Base64.getEncoder().encodeToString("demo:local-only".getBytes(StandardCharsets.UTF_8));
      var response =
          test.send(
              request ->
                  request
                      .path("/private/me")
                      .header(HttpHeaders.AUTHORIZATION, "Basic " + credentials),
              HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () -> assertEquals("demo", response.body()));
    }
  }

  @Test
  void permitsTheAdministratorToUseTheRoleProtectedOperation() throws Exception {
    try (var test = TestServer.start(AuthenticationExample.application(Options.defaults()))) {
      var credentials =
          Base64.getEncoder().encodeToString("admin:local-only".getBytes(StandardCharsets.UTF_8));
      var response =
          test.send(
              request ->
                  request
                      .path("/private/admin")
                      .header(HttpHeaders.AUTHORIZATION, "Basic " + credentials),
              HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () -> assertEquals("admin operation allowed", response.body()));
    }
  }

  @Test
  void rejectsAnIncorrectPasswordBeforeInvokingTheProtectedHandler() throws Exception {
    try (var test = TestServer.start(AuthenticationExample.application(Options.defaults()))) {
      var credentials =
          Base64.getEncoder().encodeToString("demo:wrong".getBytes(StandardCharsets.UTF_8));
      var response =
          test.send(
              request ->
                  request
                      .path("/private/me")
                      .header(HttpHeaders.AUTHORIZATION, "Basic " + credentials));
      assertEquals(401, response.statusCode());
    }
  }

  @Test
  void rejectsAnAuthenticatedReaderWithoutTheAdminRole() throws Exception {
    try (var test = TestServer.start(AuthenticationExample.application(Options.defaults()))) {
      var credentials =
          Base64.getEncoder().encodeToString("demo:local-only".getBytes(StandardCharsets.UTF_8));
      var response =
          test.send(
              request ->
                  request
                      .path("/private/admin")
                      .header(HttpHeaders.AUTHORIZATION, "Basic " + credentials),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(403, response.statusCode());
    }
  }

  @Test
  void challengesAnUnauthenticatedRequestToTheProtectedGroup() throws Exception {
    try (var test = TestServer.start(AuthenticationExample.application(Options.defaults()))) {
      var response = test.send(request -> request.path("/private/me"));
      assertAll(
          () -> assertEquals(401, response.statusCode()),
          () ->
              assertEquals(
                  "Basic realm=\"shoostr-demo\", charset=\"UTF-8\"",
                  response
                      .headers()
                      .firstValue(HttpHeaders.WWW_AUTHENTICATE.value())
                      .orElseThrow()));
    }
  }
}
