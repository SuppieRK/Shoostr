package io.github.suppierk.shoostr.example.responses;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Timeout(15)
class RedirectExampleTest {
  @ParameterizedTest
  @CsvSource({"/submit, 303, /receipt, GET:receipt", "/forward, 307, /receiver, POST:payload"})
  void followsRedirectsWithTheSelectedMethodAndBody(
      String path, int status, String destination, String expected) throws Exception {
    try (var test =
        TestServer.start(
            RedirectExample.application(),
            client -> client.followRedirects(HttpClient.Redirect.NORMAL))) {
      var response =
          test.send(
              request ->
                  request
                      .path(path)
                      .method("POST")
                      .body("payload".getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals(destination, response.uri().getPath());
      assertEquals(status, response.previousResponse().orElseThrow().statusCode());
      assertEquals(expected, response.body());
    }
  }

  @Test
  void preservesALargePostBodyWhenFollowingATemporaryRedirect() throws Exception {
    var payload = "payload".repeat(32768);

    try (var test =
        TestServer.start(
            RedirectExample.application(),
            client -> client.followRedirects(HttpClient.Redirect.NORMAL))) {
      // Replay spans multiple input buffers; the redirect handler leaves the original body unread.
      var response =
          test.send(
              request ->
                  request
                      .path("/forward")
                      .method("POST")
                      .body(payload.getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("/receiver", response.uri().getPath());
      assertEquals(307, response.previousResponse().orElseThrow().statusCode());
      assertEquals("POST:" + payload, response.body());
    }
  }

  @Test
  void keepsTheApplicationSelectedDestinationWhenAQuerySuggestsAnother() throws Exception {
    try (var test = TestServer.start(RedirectExample.application())) {
      var response =
          test.send(request -> request.path("/old?next=https%3A%2F%2Funtrusted.example%2F"));
      assertEquals(302, response.statusCode());
      assertEquals("/current", response.headers().firstValue("Location").orElseThrow());
    }
  }

  @Test
  void continuesAfterStagingTheRedirectAndClearsThePreviousBody() throws Exception {
    try (var app = RedirectExample.application()) {
      app.routes()
          .get(
              "/continue-check",
              (ignored, response) -> {
                response.text("discarded").setHeader("X-Keep", "kept").redirect("/current");
                assertFalse(response.isCommitted());
                response.setHeader("X-After", "continued");
              });

      try (var test = TestServer.start(app)) {
        var response = test.send(request -> request.path("/continue-check"));
        assertEquals(302, response.statusCode());
        assertEquals("/current", response.headers().firstValue("Location").orElseThrow());
        assertEquals("kept", response.headers().firstValue("X-Keep").orElseThrow());
        assertEquals("continued", response.headers().firstValue("X-After").orElseThrow());
        assertTrue(response.headers().firstValue("Content-Type").isEmpty());
        assertArrayEquals(new byte[0], response.body());
      }
    }
  }

  @ParameterizedTest
  @CsvSource({
    "GET, /old, 302, /current",
    "POST, /submit, 303, /receipt",
    "POST, /forward, 307, /receiver"
  })
  void sendsTheSelectedNavigationStatusWithoutFollowingIt(
      String method, String path, int status, String location) throws Exception {
    try (var test =
        TestServer.start(
            RedirectExample.application(),
            client -> client.followRedirects(HttpClient.Redirect.NEVER))) {
      var response = test.send(request -> request.path(path).method(method));
      assertEquals(status, response.statusCode());
      assertEquals(location, response.headers().firstValue("Location").orElseThrow());
      assertArrayEquals(new byte[0], response.body());
      assertTrue(response.headers().firstValue("Content-Type").isEmpty());
    }
  }
}
