package io.github.suppierk.shoostr.example.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.Cookie;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class ResponseCookiesExampleTest {
  @Test
  void replacesAndDeletesOnlyTheSelectedNameDomainAndPath() throws Exception {
    var scoped = new Cookie("theme", "old").withPath("/preferences").withDomain("example.test");
    var replacement =
        new Cookie("theme", "new").withPath("/preferences").withDomain("example.test");

    try (var app = ResponseCookiesExample.application()) {
      app.routes()
          .get(
              "/scope-check",
              (ignored, response) -> {
                response.cookie("theme", "root").cookie(scoped).cookie(replacement);
                assertEquals(
                    List.of(
                        "theme=root; Path=/", "theme=new; Path=/preferences; Domain=example.test"),
                    response.headers("Set-Cookie"));
                response.removeCookie(scoped).text("checked");
              });

      try (var test = TestServer.start(app)) {
        var response =
            test.send(
                request -> request.path("/scope-check"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("checked", response.body());
        assertEquals(
            List.of(
                "theme=root; Path=/",
                "theme=; Path=/preferences; Domain=example.test; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT"),
            response.headers().allValues("Set-Cookie"));
      }
    }
  }

  @Test
  void keepsExplicitExpiryAlongsideTheRequestedMaxAge() {
    var cookie = new Cookie("theme", "dark").withMaxAge(3600).withExpires(Instant.EPOCH);
    assertEquals(3600, cookie.maxAge());
    assertEquals(Instant.EPOCH, cookie.expires());
    assertEquals(
        "theme=dark; Path=/; Max-Age=3600; Expires=Thu, 01 Jan 1970 00:00:00 GMT",
        cookie.headerValue());
  }

  @Test
  void validatesEveryImmutableCopyBeforeTheNextChange() {
    var original = new Cookie("theme", "dark");
    var scoped = original.withPath("/preferences");
    assertEquals("/", original.path());
    assertEquals("/preferences", scoped.path());
    assertThrows(IllegalArgumentException.class, () -> original.withSameSite(Cookie.SameSite.NONE));
    assertFalse(original.secure());
    var secure = Cookie.secure("__Host-theme", "dark");
    assertThrows(IllegalArgumentException.class, () -> secure.withPath("/preferences"));
    assertTrue(secure.secure());
    assertEquals("/", secure.path());
  }

  @Test
  void preservesSecureAttributesWhenDeletingTheSameScope() throws Exception {
    try (var test = TestServer.start(ResponseCookiesExample.application())) {
      var set = test.send(request -> request.path("/secure-preference").method("POST"));
      assertEquals(204, set.statusCode());
      assertEquals(
          List.of("__Host-theme=dark; Path=/; Secure; HttpOnly; SameSite=None"),
          set.headers().allValues("Set-Cookie"));
      var deleted = test.send(request -> request.path("/secure-preference").method("DELETE"));
      assertEquals(204, deleted.statusCode());
      assertEquals(
          List.of(
              "__Host-theme=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Secure; HttpOnly; SameSite=None"),
          deleted.headers().allValues("Set-Cookie"));
    }
  }

  @Test
  void storesAndDeletesAPathScopedPreference() throws Exception {
    try (var test = TestServer.start(ResponseCookiesExample.application())) {
      assertEquals(
          "light",
          test.send(
                  request -> request.path("/preferences/theme"),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      var set = test.send(request -> request.path("/preferences/theme").method("POST"));
      assertEquals(204, set.statusCode());
      assertEquals(
          List.of("theme=dark; Path=/preferences; Max-Age=3600; SameSite=Lax"),
          set.headers().allValues("Set-Cookie"));
      assertTrue(set.headers().firstValue("Cache-Control").isEmpty());
      assertEquals(
          "dark",
          test.send(
                  request -> request.path("/preferences/theme"),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertEquals(
          "light",
          test.send(request -> request.path("/outside"), HttpResponse.BodyHandlers.ofString())
              .body());
      var deleted = test.send(request -> request.path("/preferences/theme").method("DELETE"));
      assertEquals(204, deleted.statusCode());
      assertEquals(
          List.of(
              "theme=; Path=/preferences; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; SameSite=Lax"),
          deleted.headers().allValues("Set-Cookie"));
      assertEquals(
          "light",
          test.send(
                  request -> request.path("/preferences/theme"),
                  HttpResponse.BodyHandlers.ofString())
              .body());
    }
  }
}
