package io.github.suppierk.shoostr.example.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MethodsExampleTest {
  @ParameterizedTest
  @CsvSource({
    "GET, /notes, 200, notes",
    "POST, /notes, 201, created",
    "PUT, /notes, 200, replaced",
    "PATCH, /notes, 200, updated",
    "DELETE, /notes, 204, ''",
    "HEAD, /notes, 200, ''",
    "OPTIONS, /notes, 204, ''",
    "PROPFIND, /properties, 200, PROPFIND",
    "GET, /read-only, 200, read-only"
  })
  void selectsTheRegisteredMethod(String method, String path, int status, String body)
      throws Exception {
    try (var test = TestServer.start(MethodsExample.application())) {
      var response =
          test.send(
              request -> request.path(path).method(method), HttpResponse.BodyHandlers.ofString());

      assertEquals(status, response.statusCode());
      assertEquals(body, response.body());
    }
  }

  @Test
  void servesExplicitHeadMetadataAndOptions() throws Exception {
    try (var test = TestServer.start(MethodsExample.application())) {
      var head =
          test.send(
              request -> request.path("/notes").method("HEAD"),
              HttpResponse.BodyHandlers.ofString());
      assertEquals("", head.body());
      assertEquals("metadata", head.headers().firstValue("X-Notes-Mode").orElseThrow());

      var options = test.send(request -> request.path("/notes").method("OPTIONS"));
      assertEquals(204, options.statusCode());
      assertEquals(
          Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"),
          Set.of(options.headers().firstValue("Allow").orElseThrow().split(",\\s*")));
    }
  }

  @Test
  void distinguishesUnknownPathsFromUnsupportedMethods() throws Exception {
    try (var test = TestServer.start(MethodsExample.application())) {
      var missing = test.send(request -> request.path("/missing"));
      assertEquals(404, missing.statusCode());
      assertTrue(missing.headers().firstValue("Allow").isEmpty());

      var unsupported = test.send(request -> request.path("/notes").method("TRACE"));
      assertEquals(405, unsupported.statusCode());
      assertEquals(
          Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"),
          Set.of(unsupported.headers().firstValue("Allow").orElseThrow().split(",\\s*")));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"HEAD", "OPTIONS"})
  void doesNotAddImplicitMethodsToAGetRoute(String method) throws Exception {
    try (var test = TestServer.start(MethodsExample.application())) {
      var response = test.send(request -> request.path("/read-only").method(method));

      assertEquals(405, response.statusCode());
      assertEquals("GET", response.headers().firstValue("Allow").orElseThrow());
    }
  }

  @Test
  void acceptsARecognizedWireMethodName() throws Exception {
    try (var app = MethodsExample.application()) {
      app.routes()
          .route("PROPFIND", "/string-method", (ignored, response) -> response.text("registered"));

      try (var test = TestServer.start(app)) {
        var response =
            test.send(
                request -> request.path("/string-method").method("PROPFIND"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("registered", response.body());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"get", "FETCH"})
  void rejectsUnrecognizedOrIncorrectlyCasedMethodNames(String method) throws Exception {
    try (var app = MethodsExample.application()) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              app.routes()
                  .route(method, "/invalid", (ignored2, response) -> response.text("unused")));
    }
  }
}
