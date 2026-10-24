package io.github.suppierk.shoostr.example.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class TypedSseExampleTest {
  @Test
  @Timeout(15)
  void sendsJsonObjectsAsCompleteSseDataEvents() throws Exception {
    try (var server = TestServer.start(TypedSseExample.application())) {
      var result =
          server.send(
              request -> request.path("/progress"),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(200, result.statusCode());
      assertEquals(
          "text/event-stream; charset=utf-8",
          result.headers().firstValue("Content-Type").orElseThrow());
      // Assert the wire representation, not just objects decoded by the server's codec.
      assertEquals(
          ": ready\n\ndata: {\"completed\":10}\n\n:\n\ndata: {\"completed\":100}\n\n",
          result.body());
    }
  }
}
