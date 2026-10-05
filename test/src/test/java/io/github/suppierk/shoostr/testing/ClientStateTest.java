package io.github.suppierk.shoostr.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.suppierk.shoostr.Shoostr;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class ClientStateTest {
  @Test
  void keepsCookiesAcrossRequestsWithinOneFixture() throws Exception {
    try (var test = TestServer.start(cookieApp())) {
      test.send(request -> request.path("/set"));
      assertEquals(
          "value",
          test.send(request -> request.path("/read"), HttpResponse.BodyHandlers.ofString()).body());
    }
  }

  @Test
  void doesNotShareCookiesWithAnotherFixtureOnTheSameHost() throws Exception {
    try (var first = TestServer.start(cookieApp());
        var second = TestServer.start(cookieApp())) {
      first.send(request -> request.path("/set"));
      assertEquals(
          "absent",
          second
              .send(request -> request.path("/read"), HttpResponse.BodyHandlers.ofString())
              .body());
    }
  }

  @Test
  void doesNotFollowRedirectsUnlessExplicitlyConfigured() throws Exception {
    var app = new Shoostr();
    app.routes()
        .get(
            "/redirect",
            (_, response) ->
                response.status(302).setHeader("Location", "/target").text("redirect"));
    app.routes().get("/target", (_, response) -> response.text("target"));

    try (var test = TestServer.start(app)) {
      var reply =
          test.send(request -> request.path("/redirect"), HttpResponse.BodyHandlers.ofString());
      assertEquals(302, reply.statusCode());
      assertEquals("redirect", reply.body());
    }
  }

  private static Shoostr cookieApp() {
    var app = new Shoostr();
    app.routes().get("/set", (_, response) -> response.cookie("token", "value").text("set"));
    app.routes()
        .get(
            "/read",
            (request, response) -> response.text(request.cookie("token").orElse("absent")));
    return app;
  }
}
