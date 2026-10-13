package io.github.suppierk.shoostr.example.metrics;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class MetricsExampleTest {
  @Test
  void exposesTheCompletedRequestUnderItsRouteTemplate() throws Exception {
    try (var test = TestServer.start(MetricsExample.application())) {
      assertEquals(200, test.send(request -> request.path("/hello/Ada")).statusCode());
      var scrape =
          test.send(request -> request.path("/metrics"), HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, scrape.statusCode()),
          () -> assertTrue(scrape.body().contains("http_server_requests_seconds_count")),
          () -> assertTrue(scrape.body().contains("route=\"/hello/{name}\"")));
    }
  }

  @Test
  void servesTheInstrumentedGreeting() throws Exception {
    try (var test = TestServer.start(MetricsExample.application())) {
      var response =
          test.send(request -> request.path("/hello/Ada"), HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () -> assertEquals("Hello, Ada!", response.body()));
    }
  }
}
