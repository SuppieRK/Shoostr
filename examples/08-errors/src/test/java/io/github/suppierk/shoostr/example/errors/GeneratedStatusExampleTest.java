package io.github.suppierk.shoostr.example.errors;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class GeneratedStatusExampleTest {
  @Test
  void usesTheLocalRendererForASelectedButUnavailableRoute() throws Exception {
    try (var test = TestServer.start(GeneratedStatusExample.application())) {
      var response =
          test.send(
              request -> request.path("/reports/daily"), HttpResponse.BodyHandlers.ofString());
      assertEquals(404, response.statusCode());
      assertEquals("Report unavailable", response.body());
      assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"/read-only", "/reports/daily"})
  void usesTheApplicationMethodRendererAndPreservesAllow(String path) throws Exception {
    try (var test = TestServer.start(GeneratedStatusExample.application())) {
      var response =
          test.send(
              request -> request.path(path).method("POST"), HttpResponse.BodyHandlers.ofString());
      assertEquals(405, response.statusCode());
      assertEquals("Method unavailable", response.body());
      assertEquals(
          Set.of("GET"),
          Set.of(response.headers().firstValue("Allow").orElseThrow().split(",\\s*")));
    }
  }

  @ParameterizedTest
  @CsvSource({
    "/missing, 404, Route not found",
    "/reports/missing, 404, Route not found",
    "/thrown, 404, Not Found",
    "/manual, 404, Chosen by the handler",
    "/read-only, 200, ready"
  })
  void distinguishesGeneratedThrownAndManualResponses(String path, int status, String body)
      throws Exception {
    try (var test = TestServer.start(GeneratedStatusExample.application())) {
      var response = test.send(request -> request.path(path), HttpResponse.BodyHandlers.ofString());
      assertEquals(status, response.statusCode());
      assertEquals(body, response.body());
      assertEquals(
          "text/plain; charset=utf-8", response.headers().firstValue("Content-Type").orElseThrow());
    }
  }
}
