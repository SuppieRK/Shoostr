package io.github.suppierk.shoostr.example.authentication;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class AuthenticationExampleTest {
  @Test
  void leavesThePublicRouteOutsideTheAuthenticationGroup() throws Exception {

    try (var test = TestServer.start(AuthenticationExample.application())) {
      var response =
          test.send(request -> request.path("/public"), HttpResponse.BodyHandlers.ofString());

      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () -> assertEquals("public", response.body()));
    }
  }

  @Test
  void exposesTheAuthenticatedPrincipalOnTheProtectedGroup() throws Exception {

    try (var test = TestServer.start(AuthenticationExample.application())) {
      var credentials =
          Base64.getEncoder().encodeToString("demo:local-only".getBytes(StandardCharsets.UTF_8));
      var response =
          sendResponseForExposesTheAuthenticatedPrincipalOnTheProtectedGroup(test, credentials);

      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () -> assertEquals("demo", response.body()));
    }
  }

  private HttpResponse<String> sendResponseForExposesTheAuthenticatedPrincipalOnTheProtectedGroup(
      TestServer test, String credentials) throws IOException, InterruptedException {
    return test.send(
        request ->
            request.path("/private/me").header(HttpHeaders.AUTHORIZATION, "Basic " + credentials),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void permitsTheAdministratorToUseTheRoleProtectedOperation() throws Exception {

    try (var test = TestServer.start(AuthenticationExample.application())) {
      var credentials =
          Base64.getEncoder().encodeToString("admin:local-only".getBytes(StandardCharsets.UTF_8));
      var response =
          sendResponseForPermitsTheAdministratorToUseTheRoleProtectedOperation(test, credentials);

      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () -> assertEquals("admin operation allowed", response.body()));
    }
  }

  private HttpResponse<String> sendResponseForPermitsTheAdministratorToUseTheRoleProtectedOperation(
      TestServer test, String credentials) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/private/admin")
                .header(HttpHeaders.AUTHORIZATION, "Basic " + credentials),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void rejectsAnIncorrectPasswordBeforeInvokingTheProtectedHandler() throws Exception {

    try (var test = TestServer.start(AuthenticationExample.application())) {
      var credentials =
          Base64.getEncoder().encodeToString("demo:wrong".getBytes(StandardCharsets.UTF_8));
      var response =
          sendResponseForRejectsAnIncorrectPasswordBeforeInvokingTheProtectedHandler(
              test, credentials);

      assertEquals(401, response.statusCode());
    }
  }

  private HttpResponse<byte[]>
      sendResponseForRejectsAnIncorrectPasswordBeforeInvokingTheProtectedHandler(
          TestServer test, String credentials) throws IOException, InterruptedException {
    return test.send(
        request ->
            request.path("/private/me").header(HttpHeaders.AUTHORIZATION, "Basic " + credentials));
  }

  @Test
  void rejectsAnAuthenticatedReaderWithoutTheAdminRole() throws Exception {

    try (var test = TestServer.start(AuthenticationExample.application())) {
      var credentials =
          Base64.getEncoder().encodeToString("demo:local-only".getBytes(StandardCharsets.UTF_8));
      var response =
          sendResponseForRejectsAnAuthenticatedReaderWithoutTheAdminRole(test, credentials);

      assertEquals(403, response.statusCode());
    }
  }

  private HttpResponse<String> sendResponseForRejectsAnAuthenticatedReaderWithoutTheAdminRole(
      TestServer test, String credentials) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/private/admin")
                .header(HttpHeaders.AUTHORIZATION, "Basic " + credentials),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void challengesAnUnauthenticatedRequestToTheProtectedGroup() throws Exception {

    try (var test = TestServer.start(AuthenticationExample.application())) {
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
