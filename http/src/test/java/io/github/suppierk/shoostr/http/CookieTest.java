package io.github.suppierk.shoostr.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class CookieTest {
  @Test
  void constructorNormalizesDomainAndExpiryBeforeFormatting() {
    var expiry = Instant.parse("2030-01-01T00:00:00.987Z");
    var cookie =
        new Cookie(
            "session",
            "value",
            "/app",
            ".EXAMPLE.test",
            60,
            true,
            true,
            Cookie.SameSite.NONE,
            expiry);

    assertEquals("example.test", cookie.domain());
    assertEquals(Instant.parse("2030-01-01T00:00:00Z"), cookie.expires());
    assertEquals(
        "session=value; Path=/app; Domain=example.test; Max-Age=60; "
            + "Expires=Tue, 01 Jan 2030 00:00:00 GMT; Secure; HttpOnly; SameSite=None",
        cookie.headerValue());
  }

  @Test
  void constructorRejectsInvalidPathAndAge() {
    var base = new Cookie("session", "value");

    assertThrows(IllegalArgumentException.class, () -> base.withPath("relative"));
    assertThrows(IllegalArgumentException.class, () -> base.withPath("/bad;path"));
    assertThrows(IllegalArgumentException.class, () -> base.withMaxAge(-2));
  }

  @Test
  void constructorRejectsInsecureCookiePolicies() {
    var base = new Cookie("session", "value");
    var host = Cookie.secure("__Host-session", "value");

    assertThrows(IllegalArgumentException.class, () -> base.withSameSite(Cookie.SameSite.NONE));
    assertThrows(IllegalArgumentException.class, () -> new Cookie("__Secure-token", "value"));
    assertThrows(IllegalArgumentException.class, () -> host.withDomain("example.test"));
    assertThrows(IllegalArgumentException.class, () -> host.withPath("/app"));
  }

  @Test
  void constructorRejectsInvalidDomainAndUnrepresentableExpiry() {
    var base = new Cookie("session", "value");
    var tooEarly = Instant.parse("1600-12-31T23:59:59Z");

    assertThrows(IllegalArgumentException.class, () -> base.withDomain("invalid..test"));
    assertThrows(IllegalArgumentException.class, () -> base.withExpires(tooEarly));
  }
}
