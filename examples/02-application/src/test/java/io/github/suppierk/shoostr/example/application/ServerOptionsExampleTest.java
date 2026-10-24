package io.github.suppierk.shoostr.example.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ServerOptionsExampleTest {
  @Test
  void echoesBodiesWithinTheConfiguredLimits() throws Exception {
    try (var test = TestServer.start(ServerOptionsExample.application())) {
      var response =
          test.send(
              request ->
                  request
                      .path("/echo")
                      .method("POST")
                      .body("configured".getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(200, response.statusCode());
      assertEquals("configured", response.body());
    }
  }

  @Test
  void rejectsBodiesAboveTheConfiguredRequestLimit() throws Exception {
    try (var test = TestServer.start(ServerOptionsExample.application())) {
      var response =
          test.send(
              request ->
                  request
                      .path("/echo")
                      .method("POST")
                      .body("x".repeat(1024).getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("x".repeat(1024), response.body());
      assertEquals(
          413,
          test.send(
                  request -> request.path("/echo").method("POST").body(new byte[1025]),
                  HttpResponse.BodyHandlers.discarding())
              .statusCode());
    }
  }
}
