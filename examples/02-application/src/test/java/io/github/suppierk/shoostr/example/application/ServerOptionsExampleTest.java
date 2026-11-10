package io.github.suppierk.shoostr.example.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ServerOptionsExampleTest {
  @Test
  void servesTheConfiguredLoopbackListenerAndRejectsOversizedHeaders() throws Exception {
    {
      var app = ServerOptionsExample.application("127.0.0.1", 0);

      try (Closeable appCleanup = app::close;
          var client = HttpClient.newHttpClient()) {
        app.start();

        assertTrue(app.port() > 0);

        var target = URI.create("http://127.0.0.1:" + app.port() + "/echo");
        var request =
            HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(3))
                .POST(HttpRequest.BodyPublishers.ofString("configured"))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("configured", response.body());
        assertEquals(
            431,
            client
                .send(
                    HttpRequest.newBuilder(target)
                        .timeout(Duration.ofSeconds(3))
                        .header("X-Large", "x".repeat(2048))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                    HttpResponse.BodyHandlers.discarding())
                .statusCode());
      }
    }
  }

  @Test
  void echoesBodiesWithinTheConfiguredLimits() throws Exception {

    try (var test = TestServer.start(ServerOptionsExample.application("127.0.0.1", 0))) {
      var response = sendConfiguredEcho(test);

      assertEquals(200, response.statusCode());
      assertEquals("configured", response.body());
    }
  }

  private HttpResponse<String> sendConfiguredEcho(TestServer test) throws Exception {
    return test.send(
        request ->
            request
                .path("/echo")
                .method("POST")
                .body("configured".getBytes(StandardCharsets.UTF_8)),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void rejectsBodiesAboveTheConfiguredRequestLimit() throws Exception {

    try (var test = TestServer.start(ServerOptionsExample.application("127.0.0.1", 0))) {
      var response = sendRequestLimitPayload(test);

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

  private HttpResponse<String> sendRequestLimitPayload(TestServer test) throws Exception {
    return test.send(
        request ->
            request
                .path("/echo")
                .method("POST")
                .body("x".repeat(1024).getBytes(StandardCharsets.UTF_8)),
        HttpResponse.BodyHandlers.ofString());
  }
}
