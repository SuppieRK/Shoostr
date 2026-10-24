package io.github.suppierk.shoostr.example.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(10)
class SessionExampleTest {
  @Test
  void createsASecurePreferenceCookieAndKeepsStateOnlyForItsSession(@TempDir Path directory)
      throws Exception {
    try (var test =
        TestServer.start(
            SessionExample.application(directory),
            client -> client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {
      var created =
          test.send(
              request -> request.path("/preferences/session"),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, created.statusCode());
      assertEquals("dark", created.body());
      var issued = created.headers().firstValue("Set-Cookie").orElseThrow();
      assertTrue(issued.startsWith("PREFERENCES="));
      assertTrue(issued.contains("Path=/"));
      assertTrue(issued.contains("HttpOnly"));
      assertTrue(issued.contains("Secure"));
      assertTrue(issued.contains("SameSite=Strict"));
      assertTrue(issued.contains("Max-Age=900"));
      assertFalse(issued.contains("Domain="));

      var retained =
          test.send(
              request ->
                  request.path("/preferences/state").header("Cookie", issued.split(";", 2)[0]),
              HttpResponse.BodyHandlers.ofString());
      assertEquals("dark", retained.body());
      var anonymous =
          test.send(
              request -> request.path("/preferences/state"), HttpResponse.BodyHandlers.ofString());
      assertEquals("absent", anonymous.body());
      assertFalse(anonymous.headers().firstValue("Set-Cookie").isPresent());
    }
  }

  @Test
  void renewsTheSessionIdWithoutLosingStateOrAcceptingTheOldCookie(@TempDir Path directory)
      throws Exception {
    try (var test =
        TestServer.start(
            SessionExample.application(directory),
            client -> client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {
      var original = createSession(test);
      var renewed =
          test.send(
              request ->
                  request.path("/preferences/renew").method("POST").header("Cookie", original));
      assertEquals(200, renewed.statusCode());
      var replacement = renewed.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
      assertNotEquals(original, replacement);
      assertEquals(
          "dark",
          test.send(
                  request -> request.path("/preferences/state").header("Cookie", replacement),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertEquals(
          "absent",
          test.send(
                  request -> request.path("/preferences/state").header("Cookie", original),
                  HttpResponse.BodyHandlers.ofString())
              .body());
    }
  }

  @Test
  void reloadsThePreferenceFromTheConfiguredStoreAfterTheApplicationCloses(@TempDir Path directory)
      throws Exception {
    String cookie;

    try (var first =
        TestServer.start(
            SessionExample.application(directory),
            client -> client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {
      cookie = createSession(first);
    }

    try (var next =
        TestServer.start(
            SessionExample.application(directory),
            client -> client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {
      var restored =
          next.send(
              request -> request.path("/preferences/state").header("Cookie", cookie),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, restored.statusCode());
      assertEquals("dark", restored.body());
    }
  }

  @Test
  void invalidatesTheSessionWithoutAllowingTheOldCookieToRestoreItsState(@TempDir Path directory)
      throws Exception {
    try (var test =
        TestServer.start(
            SessionExample.application(directory),
            client -> client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {
      var cookie = createSession(test);
      var reset =
          test.send(
              request ->
                  request.path("/preferences/reset").method("POST").header("Cookie", cookie));
      assertEquals(200, reset.statusCode());
      var absent =
          test.send(
              request -> request.path("/preferences/state").header("Cookie", cookie),
              HttpResponse.BodyHandlers.ofString());
      assertEquals("absent", absent.body());
      assertFalse(absent.headers().firstValue("Set-Cookie").isPresent());
    }
  }

  @Test
  void rejectsRenewalWithoutCreatingANewSession(@TempDir Path directory) throws Exception {
    try (var test =
        TestServer.start(
            SessionExample.application(directory),
            client -> client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {
      var response = test.send(request -> request.path("/preferences/renew").method("POST"));
      assertEquals(404, response.statusCode());
      assertFalse(response.headers().firstValue("Set-Cookie").isPresent());
    }
  }

  @Test
  void rejectsMissingOrNonDirectoryStorageBeforeAnApplicationCanStart(@TempDir Path directory)
      throws Exception {
    var file = Files.createFile(directory.resolve("file"));
    assertThrows(IllegalArgumentException.class, () -> SessionExample.application(file));
    assertThrows(IOException.class, () -> SessionExample.application(directory.resolve("missing")));
  }

  private static String createSession(TestServer test) throws Exception {
    var created = test.send(request -> request.path("/preferences/session"));
    assertEquals(200, created.statusCode());
    return created.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
  }
}
