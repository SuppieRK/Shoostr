package io.github.suppierk.shoostr.example.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PathPatternsExampleTest {
  @ParameterizedTest
  @CsvSource({
    "GET, /items/latest, latest",
    "GET, /items/42, id:42",
    "GET, /items/Ada, name:Ada",
    "GET, /items/a%20b, name:a b",
    "GET, /items/caf%C3%A9, name:café",
    "GET, /items/a+b, name:a+b",
    "GET, /items/a/b, tail:a/b",
    "GET, /items/a%20b/c+d/, tail:a b/c+d/",
    "POST, /items/latest, post:latest",
    "GET, /codes/7, single:7",
    "GET, /codes/12, digits:12",
    "GET, /fallback/fixed/y, fallback:fixed",
    "GET, /status, ok"
  })
  void selectsTheMostSpecificCompleteMatch(String method, String path, String body)
      throws Exception {
    try (var test = TestServer.start(PathPatternsExample.application())) {
      var response =
          test.send(
              request -> request.path(path).method(method), HttpResponse.BodyHandlers.ofString());

      assertEquals(200, response.statusCode());
      assertEquals(body, response.body());
    }
  }

  @Test
  void discoversMethodsAcrossAllMatchingPatterns() throws Exception {
    try (var test = TestServer.start(PathPatternsExample.application())) {
      var response = test.send(request -> request.path("/items/latest").method("DELETE"));

      assertEquals(405, response.statusCode());
      assertEquals(
          Set.of("GET", "POST"),
          Set.of(response.headers().firstValue("Allow").orElseThrow().split(",\\s*")));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"/Status", "/status/", "/items", "/items/", "/codes/1234", "/codes/abc"})
  void rejectsPathsOutsideTheRegisteredShapes(String path) throws Exception {
    try (var test = TestServer.start(PathPatternsExample.application())) {
      assertEquals(404, test.send(request -> request.path(path)).statusCode());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"/items/%2F", "/items/%252F"})
  void rejectsEncodedSlashesBeforeServingTheCapture(String path) throws Exception {
    try (var test = TestServer.start(PathPatternsExample.application())) {
      assertEquals(400, test.send(request -> request.path(path)).statusCode());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/invalid/{id}.json",
        "/invalid/{*tail}/more",
        "/invalid/{id:(a+)+}",
        "/invalid/{id}/{id}"
      })
  void rejectsUnsupportedPatternSyntaxAtRegistration(String pattern) throws Exception {
    try (var app = PathPatternsExample.application()) {
      var routes = app.routes();
      assertThrows(
          IllegalArgumentException.class,
          () -> routes.get(pattern, (ignored, response) -> response.text("unused")));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"/items/{other}", "/items/{other:[0-9]+}", "/items/{*other}"})
  void rejectsEquivalentShapesEvenWithDifferentParameterNames(String pattern) throws Exception {
    try (var app = PathPatternsExample.application()) {
      var routes = app.routes();
      assertThrows(
          IllegalArgumentException.class,
          () -> routes.get(pattern, (ignored2, response) -> response.text("unused")));
    }
  }
}
