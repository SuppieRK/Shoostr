package io.github.suppierk.shoostr.example.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(10)
class CsrfProtectionExampleTest {
  @Test
  void issuesANonCacheableTokenThatRemainsStableForTheSameSession() throws Exception {
    try (var test = start()) {
      var first =
          test.send(request -> request.path("/csrf-token"), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, first.statusCode());
      assertFalse(first.body().isBlank());
      assertEquals("no-store", first.headers().firstValue("Cache-Control").orElseThrow());
      var issued = first.headers().firstValue("Set-Cookie").orElseThrow();
      assertTrue(issued.contains("Secure"));
      var sameSession =
          test.send(
              request -> request.path("/csrf-token").header("Cookie", issued.split(";", 2)[0]),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(first.body(), sameSession.body());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"header", "form"})
  void changesTheSessionPreferenceWithExactlyOneSupportedTokenChannel(String channel)
      throws Exception {
    try (var test = start()) {
      var issued =
          test.send(request -> request.path("/csrf-token"), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, issued.statusCode());
      var cookie = issued.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
      var changed =
          test.send(
              request -> {
                request
                    .path("/preferences")
                    .method("POST")
                    .header("Cookie", cookie)
                    .header("Origin", origin(test))
                    .header("Content-Type", "application/x-www-form-urlencoded");
                var body = "theme=light";
                if ("header".equals(channel)) {
                  request.header("X-CSRF-Token", issued.body());
                } else {
                  body += "&_csrf=" + issued.body();
                }

                request.body(body.getBytes(StandardCharsets.UTF_8));
              },
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, changed.statusCode());
      assertEquals("light", changed.body());
      var state =
          test.send(
              request -> request.path("/preferences").header("Cookie", cookie),
              HttpResponse.BodyHandlers.ofString());
      assertEquals("light", state.body());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "no-token",
        "wrong-token",
        "duplicate-header",
        "duplicate-form",
        "both",
        "foreign-origin",
        "no-origin",
        "other-session",
        "no-session"
      })
  void rejectsUnverifiedWritesBeforeChangingSessionState(String scenario) throws Exception {
    try (var test = start()) {
      var issued =
          test.send(request -> request.path("/csrf-token"), HttpResponse.BodyHandlers.ofString());
      var session =
          "other-session".equals(scenario)
              ? test.send(
                  request -> request.path("/csrf-token"), HttpResponse.BodyHandlers.ofString())
              : issued;
      var cookie = session.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
      var rejected =
          test.send(
              request -> {
                request
                    .path("/preferences")
                    .method("POST")
                    .header("Content-Type", "application/x-www-form-urlencoded");
                if (!"no-session".equals(scenario)) {
                  request.header("Cookie", cookie);
                }

                if (!"no-origin".equals(scenario)) {
                  request.header(
                      "Origin",
                      "foreign-origin".equals(scenario) ? "https://other.example" : origin(test));
                }

                if (!"no-token".equals(scenario) && !"duplicate-form".equals(scenario)) {
                  request.header(
                      "X-CSRF-Token",
                      "wrong-token".equals(scenario) ? "not-issued" : issued.body());
                }

                if ("duplicate-header".equals(scenario)) {
                  request.header("X-CSRF-Token", issued.body());
                }

                var body = "theme=light";
                if ("both".equals(scenario) || "duplicate-form".equals(scenario)) {
                  body += "&_csrf=" + issued.body();
                }

                if ("duplicate-form".equals(scenario)) {
                  body += "&_csrf=" + issued.body();
                }

                request.body(body.getBytes(StandardCharsets.UTF_8));
              });
      assertEquals(403, rejected.statusCode());
      var state =
          test.send(
              request -> request.path("/preferences").header("Cookie", cookie),
              HttpResponse.BodyHandlers.ofString());
      assertEquals("unset", state.body());
    }
  }

  @Test
  void doesNotProtectAnUnselectedRouteMerelyBecauseTheExtensionIsInstalled() throws Exception {
    var app = CsrfProtectionExample.application();
    app.routes().post("/fixture/unselected", (ignored, response) -> response.text("unselected"));

    try (var test = TestServer.start(app)) {
      var response =
          test.send(
              request -> request.path("/fixture/unselected").method("POST"),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("unselected", response.body());
      assertFalse(response.headers().firstValue("Set-Cookie").isPresent());
    }
  }

  @Test
  void rotatesTheTokenWithTheSessionIdentifierAndRejectsThePreviousToken() throws Exception {
    try (var test = start()) {
      var issued =
          test.send(request -> request.path("/csrf-token"), HttpResponse.BodyHandlers.ofString());
      var cookie = issued.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
      var renewed =
          test.send(
              request ->
                  request
                      .path("/session/renew")
                      .method("POST")
                      .header("Cookie", cookie)
                      .header("Origin", origin(test))
                      .header("X-CSRF-Token", issued.body()),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, renewed.statusCode());
      assertNotEquals(issued.body(), renewed.body());
      var replacement = renewed.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
      assertNotEquals(cookie, replacement);
      for (var token : new String[] {issued.body(), renewed.body()}) {
        var response =
            test.send(
                request ->
                    request
                        .path("/preferences")
                        .method("POST")
                        .header("Cookie", replacement)
                        .header("Origin", origin(test))
                        .header("X-CSRF-Token", token)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .body("theme=light".getBytes(StandardCharsets.UTF_8)));
        assertEquals(token.equals(issued.body()) ? 403 : 200, response.statusCode());
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"false, 200", "true, 403"})
  void usesTheRefererOnlyWhenAnOriginHeaderIsAbsent(boolean foreignOrigin, int status)
      throws Exception {
    try (var test = start()) {
      var issued =
          test.send(request -> request.path("/csrf-token"), HttpResponse.BodyHandlers.ofString());
      var cookie = issued.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
      var response =
          test.send(
              request -> {
                request
                    .path("/preferences")
                    .method("POST")
                    .header("Cookie", cookie)
                    .header("Referer", origin(test) + "/settings")
                    .header("X-CSRF-Token", issued.body())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .body("theme=dark".getBytes(StandardCharsets.UTF_8));
                if (foreignOrigin) {
                  request.header("Origin", "https://other.example");
                }
              });
      assertEquals(status, response.statusCode());
    }
  }

  private static TestServer start() throws Exception {
    return TestServer.start(
        CsrfProtectionExample.application(),
        client -> client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)));
  }

  private static String origin(TestServer test) {
    return test.baseUri().getScheme() + "://" + test.baseUri().getRawAuthority();
  }
}
