package io.github.suppierk.shoostr.example.tracing;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class TelemetryExampleTest {
  @Test
  void continuesTheIncomingW3cTraceInTheHandler() throws Exception {
    try (var test = TestServer.start(TelemetryExample.application(Options.defaults()))) {
      var response =
          test.send(
              request ->
                  request
                      .path("/hello/Ada")
                      .header(
                          "traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00"),
              HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () -> assertEquals("Hello, Ada!", response.body()),
          () ->
              assertEquals(
                  "4bf92f3577b34da6a3ce929d0e0e4736",
                  response.headers().firstValue("X-Trace-Id").orElseThrow()));
    }
  }
}
