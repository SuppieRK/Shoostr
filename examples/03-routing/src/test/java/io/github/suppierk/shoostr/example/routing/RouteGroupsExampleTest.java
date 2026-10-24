package io.github.suppierk.shoostr.example.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RouteGroupsExampleTest {
  @ParameterizedTest
  @CsvSource({
    "/api/v1/notes, notes",
    "/api/v1/notes/, notes with slash",
    "/api/v1/notes/7, note:7",
    "/api/v2/notes, notes",
    "/api/v2/notes/8, note:8",
    "/api/accounts/acme/notes/42, acme:42",
    "/status, ready",
    "/public, public"
  })
  void reusesDefinitionsWithoutLosingParentPrefixes(String path, String body) throws Exception {
    try (var test = TestServer.start(RouteGroupsExample.application())) {
      var response = test.send(request -> request.path(path), HttpResponse.BodyHandlers.ofString());

      assertEquals(200, response.statusCode());
      assertEquals(body, response.body());
    }
  }

  @ParameterizedTest
  @CsvSource({
    "/api/v1/notes, yes, , ",
    "/api/v2/notes, yes, 2, ",
    "/api/accounts/acme/notes/42, yes, , ",
    "/status, , , status",
    "/public, , , "
  })
  void inheritsConfigurationWithoutLeakingItToSiblings(
      String path, String apiHeader, String versionHeader, String groupHeader) throws Exception {
    try (var test = TestServer.start(RouteGroupsExample.application())) {
      var response = test.send(request -> request.path(path));

      assertEquals(200, response.statusCode());
      assertEquals(apiHeader, response.headers().firstValue("X-API").orElse(null));
      assertEquals(versionHeader, response.headers().firstValue("X-Version").orElse(null));
      assertEquals(groupHeader, response.headers().firstValue("X-Group").orElse(null));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"/notes", "/v1/notes", "/v2/notes", "/api/status"})
  void keepsLeadingSlashesWithinTheirParentGroup(String path) throws Exception {
    try (var test = TestServer.start(RouteGroupsExample.application())) {
      assertEquals(404, test.send(request -> request.path(path)).statusCode());
    }
  }
}
