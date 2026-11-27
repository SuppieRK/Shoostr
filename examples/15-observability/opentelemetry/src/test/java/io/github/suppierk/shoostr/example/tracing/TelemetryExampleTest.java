package io.github.suppierk.shoostr.example.tracing;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.IOException;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class TelemetryExampleTest {
  @Test
  void servesTheGreetingWithoutDiagnosticTraceHeader() throws Exception {

    try (var test = TestServer.start(TelemetryExample.application())) {
      var response = sendResponseForServesTheGreetingWithoutDiagnosticTraceHeader(test);

      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () -> assertEquals("Hello, Ada!", response.body()),
          () -> assertTrue(response.headers().firstValue("X-Trace-Id").isEmpty()));
    }
  }

  private HttpResponse<String> sendResponseForServesTheGreetingWithoutDiagnosticTraceHeader(
      TestServer test) throws IOException, InterruptedException {
    return test.send(
        // An unsampled parent keeps this HTTP-only check independent of a trace backend.
        request ->
            request
                .pathTemplate("/hello/{name}", path -> path.pathParam("name", "Ada"))
                .header("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00"),
        HttpResponse.BodyHandlers.ofString());
  }
}
