package io.github.suppierk.shoostr;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.http.HttpCookie.SameSite;
import org.eclipse.jetty.session.DefaultSessionCache;
import org.eclipse.jetty.session.FileSessionDataStore;
import org.eclipse.jetty.session.SessionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionTest {
  @Test
  void leavesSessionsDisabledUntilConfigured() throws Exception {
    try (var app = new Shoostr()) {
      app.routes()
          .get(
              "/peek",
              (request, response) -> {
                assertTrue(request.getSession().isEmpty());
                assertThrows(IllegalStateException.class, request::getOrCreateSession);
                response.text("disabled");
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(request -> request.path("/peek"), HttpResponse.BodyHandlers.ofString());
        assertEquals("disabled", result.body());
        assertFalse(result.headers().firstValue("Set-Cookie").isPresent());
      }
    }
  }

  @Test
  void createsSessionOnlyWhenRequestedAndRetainsAttributesAcrossRequests() throws Exception {
    try (var app = new Shoostr()) {
      app.sessions();
      app.routes()
          .get(
              "/peek",
              (request, response) -> {
                var session = request.getSession();
                response.text(
                    session.map(value -> (String) value.getAttribute("name")).orElse("absent"));
              })
          .get(
              "/create",
              (request, response) -> {
                var session = request.getOrCreateSession();
                session.setAttribute("name", "alice");
                response.text(session.getId());
              });

      try (var test =
          TestServer.start(
              app,
              client -> client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {

        var absent =
            test.send(request -> request.path("/peek"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, absent.statusCode());
        assertEquals("absent", absent.body());
        assertFalse(absent.headers().firstValue("Set-Cookie").isPresent());

        var created =
            test.send(request -> request.path("/create"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, created.statusCode());
        var setCookie = created.headers().firstValue("Set-Cookie").orElseThrow();
        var cookie = setCookie.split(";", 2)[0];
        assertTrue(cookie.startsWith("JSESSIONID="));
        assertTrue(setCookie.contains("Path=/"));
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("SameSite=Lax"));
        assertFalse(setCookie.contains("Domain="));
        assertFalse(created.body().isBlank());

        var reused =
            test.send(
                request -> request.path("/peek").header("Cookie", cookie),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, reused.statusCode());
        assertEquals("alice", reused.body());
        assertFalse(reused.headers().firstValue("Set-Cookie").isPresent());
      }
    }
  }

  @Test
  void renewsSessionIdWithoutLosingAttributesOrAcceptingTheOldCookie() throws Exception {
    try (var app = new Shoostr()) {
      app.sessions();
      app.routes()
          .get(
              "/create",
              (request, response) -> {
                request.getOrCreateSession().setAttribute("name", "alice");
                response.text("created");
              })
          .get("/renew", (request, response) -> response.text(request.renewSessionId()))
          .get(
              "/peek",
              (request, response) -> {
                var session = request.getSession();
                response.text(
                    session.map(value -> (String) value.getAttribute("name")).orElse("absent"));
              });

      try (var test =
          TestServer.start(
              app,
              client -> client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {

        var oldCookie =
            test.send(request -> request.path("/create"), HttpResponse.BodyHandlers.ofString())
                .headers()
                .firstValue("Set-Cookie")
                .orElseThrow()
                .split(";", 2)[0];
        var renewed =
            test.send(
                request -> request.path("/renew").header("Cookie", oldCookie),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, renewed.statusCode());
        var newCookie = renewed.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        assertNotEquals(oldCookie, newCookie);
        assertEquals(
            "absent",
            test.send(
                    request -> request.path("/peek").header("Cookie", oldCookie),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            "alice",
            test.send(
                    request -> request.path("/peek").header("Cookie", newCookie),
                    HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void rejectsUnknownIdsAndInvalidatedSessionsWithoutAdoptingTheirIdentifiers() throws Exception {
    try (var app = new Shoostr()) {
      app.sessions();
      app.routes()
          .get(
              "/create", (request, response) -> response.text(request.getOrCreateSession().getId()))
          .get(
              "/peek",
              (request, response) ->
                  response.text(request.getSession().isEmpty() ? "absent" : "present"))
          .get(
              "/logout",
              (request, response) -> {
                request.getSession().orElseThrow().invalidate();
                response.text("logged out");
              });

      try (var test =
          TestServer.start(
              app,
              client -> client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {

        assertEquals(
            "absent",
            test.send(
                    request ->
                        request.path("/peek").header("Cookie", "JSESSIONID=chosen-by-attacker"),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        var created =
            test.send(
                request ->
                    request.path("/create").header("Cookie", "JSESSIONID=chosen-by-attacker"),
                HttpResponse.BodyHandlers.ofString());
        var cookie = created.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        assertFalse(cookie.contains("chosen-by-attacker"));
        assertEquals(
            "present",
            test.send(
                    request -> request.path("/peek").header("Cookie", cookie),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            "logged out",
            test.send(
                    request -> request.path("/logout").header("Cookie", cookie),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            "absent",
            test.send(
                    request -> request.path("/peek").header("Cookie", cookie),
                    HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void appliesSafeCookieDefaultsAndAllowsNativeJettyOverrides() throws Exception {
    try (var app = new Shoostr()) {
      app.sessions(
          handler -> {
            handler.setSessionCookie("WEBSESSION");
            handler.setSameSite(SameSite.STRICT);
            handler.setMaxCookieAge(60);
          });
      app.routes()
          .get(
              "/create",
              (request, response) -> response.text(request.getOrCreateSession().getId()));

      try (var test =
          TestServer.start(
              app,
              client -> client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {

        var cookie =
            test.send(request -> request.path("/create"), HttpResponse.BodyHandlers.ofString())
                .headers()
                .firstValue("Set-Cookie")
                .orElseThrow();
        assertTrue(cookie.startsWith("WEBSESSION="));
        assertTrue(cookie.contains("Path=/"));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Strict"));
        assertTrue(cookie.contains("Max-Age=60"));
        assertFalse(cookie.contains("Domain="));
      }
    }
  }

  @Test
  void expiresIdleSessionsAndClearsMemoryWhenApplicationCloses() throws Exception {
    String cookie;

    try (var app = new Shoostr()) {
      app.sessions(handler -> handler.setMaxInactiveInterval(1));
      app.routes()
          .get(
              "/create", (request, response) -> response.text(request.getOrCreateSession().getId()))
          .get(
              "/peek",
              (request, response) ->
                  response.text(request.getSession().isEmpty() ? "absent" : "present"));

      try (var test =
          TestServer.start(
              app,
              client -> client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {
        cookie =
            test.send(request -> request.path("/create"), HttpResponse.BodyHandlers.ofString())
                .headers()
                .firstValue("Set-Cookie")
                .orElseThrow()
                .split(";", 2)[0];
        assertEquals(
            "present",
            test.send(
                    request -> request.path("/peek").header("Cookie", cookie),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        await()
            .pollDelay(Duration.ofMillis(1200))
            .pollInterval(Duration.ofMillis(1200))
            .atMost(Duration.ofSeconds(5))
            .untilAsserted(
                () ->
                    assertEquals(
                        "absent",
                        test.send(
                                request -> request.path("/peek").header("Cookie", cookie),
                                HttpResponse.BodyHandlers.ofString())
                            .body()));
      }
    }

    try (var restarted = new Shoostr()) {
      restarted.sessions();
      restarted
          .routes()
          .get(
              "/peek",
              (request, response) ->
                  response.text(request.getSession().isEmpty() ? "absent" : "present"));

      try (var test =
          TestServer.start(
              restarted,
              client -> client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {
        assertEquals(
            "absent",
            test.send(
                    request -> request.path("/peek").header("Cookie", cookie),
                    HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void closesLiveInMemorySessionCacheBeforeARecreatedApplicationCanAcceptItsCookie()
      throws Exception {
    var sessionHandler = new AtomicReference<SessionHandler>();
    String cookie;

    try (var app = new Shoostr()) {
      app.sessions(sessionHandler::set);
      app.routes()
          .get(
              "/create",
              (request, response) -> response.text(request.getOrCreateSession().getId()));

      try (var test =
          TestServer.start(
              app,
              client -> client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {
        cookie =
            test.send(request -> request.path("/create"), HttpResponse.BodyHandlers.ofString())
                .headers()
                .firstValue("Set-Cookie")
                .orElseThrow()
                .split(";", 2)[0];
        assertTrue(sessionHandler.get().isStarted());
        assertEquals(
            1, ((DefaultSessionCache) sessionHandler.get().getSessionCache()).getSessionsCurrent());
      }
    }

    assertTrue(sessionHandler.get().isStopped());
    var cache = (DefaultSessionCache) sessionHandler.get().getSessionCache();
    assertTrue(cache.isStopped());
    assertEquals(0, cache.getSessionsCurrent());

    try (var restarted = new Shoostr()) {
      restarted.sessions();
      restarted
          .routes()
          .get(
              "/peek",
              (request, response) ->
                  response.text(request.getSession().isEmpty() ? "absent" : "present"));

      try (var test =
          TestServer.start(
              restarted,
              client -> client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {
        assertEquals(
            "absent",
            test.send(
                    request -> request.path("/peek").header("Cookie", cookie),
                    HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void retainsNewSessionCookieWhenApplicationErrorIsHandled() throws Exception {
    try (var app = new Shoostr()) {
      app.sessions();
      app.exception(
          IllegalArgumentException.class,
          (ignored, ignored2, response) -> response.status(409).text("handled"));
      app.routes()
          .get(
              "/start",
              (request, response) -> {
                request.getOrCreateSession().setAttribute("name", "alice");
                response.cookie("discard", "unsafe");
                throw new IllegalArgumentException("failure after session creation");
              })
          .get(
              "/peek",
              (request, response) -> {
                var session = request.getSession();
                response.text(
                    session.map(value -> (String) value.getAttribute("name")).orElse("absent"));
              });

      try (var test =
          TestServer.start(
              app,
              client -> client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {

        var failed =
            test.send(request -> request.path("/start"), HttpResponse.BodyHandlers.ofString());
        assertEquals(409, failed.statusCode());
        var cookie = failed.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        assertEquals(1, failed.headers().allValues("Set-Cookie").size());
        assertEquals(
            "alice",
            test.send(
                    request -> request.path("/peek").header("Cookie", cookie),
                    HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void acceptsJettyStoreConfigurationAndReloadsSessionAcrossApplicationInstances(
      @TempDir Path directory) throws Exception {
    String cookie;

    try (var app = new Shoostr()) {
      app.sessions(handler -> fileStore(handler, directory));
      app.routes()
          .get(
              "/create",
              (request, response) -> {
                request.getOrCreateSession().setAttribute("name", "alice");
                response.text("created");
              });

      try (var test =
          TestServer.start(
              app,
              client -> client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {
        cookie =
            test.send(request -> request.path("/create"), HttpResponse.BodyHandlers.ofString())
                .headers()
                .firstValue("Set-Cookie")
                .orElseThrow()
                .split(";", 2)[0];
      }
    }

    try (var restarted = new Shoostr()) {
      restarted.sessions(handler -> fileStore(handler, directory));
      restarted
          .routes()
          .get(
              "/peek",
              (request, response) -> {
                var session = request.getSession();
                response.text(
                    session.map(value -> (String) value.getAttribute("name")).orElse("absent"));
              });

      try (var test =
          TestServer.start(
              restarted,
              client -> client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {
        assertEquals(
            "alice",
            test.send(
                    request -> request.path("/peek").header("Cookie", cookie),
                    HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void persistsCsrfTokenWithJettyStoreAcrossApplicationInstances(@TempDir Path directory)
      throws Exception {
    String cookie;
    String token;

    try (var app = new Shoostr();
        var csrf = new Csrf()) {
      app.sessions(handler -> fileStore(handler, directory));
      app.routes().get("/form", (request, response) -> response.text(csrf.token(request)));

      try (var test =
          TestServer.start(
              app,
              client -> client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {
        var result =
            test.send(request -> request.path("/form"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        cookie = result.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        token = result.body();
      }
    }

    try (var restarted = new Shoostr()) {
      var csrf = new Csrf();
      restarted.sessions(handler -> fileStore(handler, directory));
      restarted.extensions(csrf);
      restarted
          .routes()
          .path(
              "/",
              routes -> routes.post("/submit", (ignored3, response) -> response.text("accepted")),
              e -> e.with(csrf));

      try (var test =
          TestServer.start(
              restarted,
              client -> client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {
        var response =
            test.send(
                request ->
                    request
                        .path("/submit")
                        .header("Cookie", cookie)
                        .header("Origin", "http://127.0.0.1:" + restarted.port())
                        .header("X-CSRF-Token", token)
                        .method("POST"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("accepted", response.body());
      }
    }
  }

  @Test
  void sharesSessionAttributesAcrossConcurrentRequests() throws Exception {
    var ready = new CountDownLatch(8);
    var release = new CountDownLatch(1);

    try (var app = new Shoostr();
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      app.sessions();
      app.routes()
          .get(
              "/create", (request, response) -> response.text(request.getOrCreateSession().getId()))
          .get(
              "/write/{key}",
              (request, response) -> {
                var session = request.getSession().orElseThrow();
                ready.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("Concurrent writers did not arrive");
                }

                session.setAttribute(request.path(), "seen");
                response.text("written");
              })
          .get(
              "/count",
              (request, response) ->
                  response.text(
                      Integer.toString(
                          request.getSession().orElseThrow().getAttributeNameSet().size())));

      try (var test =
          TestServer.start(
              app,
              client -> client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {
        var cookie =
            test.send(request -> request.path("/create"), HttpResponse.BodyHandlers.ofString())
                .headers()
                .firstValue("Set-Cookie")
                .orElseThrow()
                .split(";", 2)[0];
        var futures = new ArrayList<Future<HttpResponse<String>>>();
        for (int index = 0; index < 8; index++) {
          var path = "/write/" + index;
          futures.add(
              workers.submit(
                  () ->
                      test.send(
                          request -> request.path(path).header("Cookie", cookie),
                          HttpResponse.BodyHandlers.ofString())));
        }

        try {
          assertTrue(ready.await(5, TimeUnit.SECONDS));
        } finally {
          release.countDown();
        }

        for (var future : futures) {
          assertEquals(200, future.get(5, TimeUnit.SECONDS).statusCode());
        }

        assertEquals(
            "8",
            test.send(
                    request -> request.path("/count").header("Cookie", cookie),
                    HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void rejectsTwoDifferentValidSessionCookies() throws Exception {
    try (var app = new Shoostr()) {
      app.sessions();
      app.routes()
          .get(
              "/create", (request, response) -> response.text(request.getOrCreateSession().getId()))
          .get("/peek", (ignored4, response) -> response.text("handled"));

      try (var test =
          TestServer.start(
              app,
              client -> client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {
        var first =
            test.send(request -> request.path("/create"), HttpResponse.BodyHandlers.ofString())
                .headers()
                .firstValue("Set-Cookie")
                .orElseThrow()
                .split(";", 2)[0];
        var second =
            test.send(request -> request.path("/create"), HttpResponse.BodyHandlers.ofString())
                .headers()
                .firstValue("Set-Cookie")
                .orElseThrow()
                .split(";", 2)[0];
        assertNotEquals(first, second);
        var ambiguous =
            test.send(
                request -> request.path("/peek").header("Cookie", first + "; " + second),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, ambiguous.statusCode());
        assertNotEquals("handled", ambiguous.body());
      }
    }
  }

  @Test
  void rejectsSessionCreationAfterStreamingHasCommittedTheResponse() throws Exception {
    try (var app = new Shoostr()) {
      app.sessions();
      app.routes()
          .get(
              "/stream",
              (request, response) -> {
                var stream = response.startStream("text/plain");
                assertThrows(IllegalStateException.class, request::getOrCreateSession);
                stream.write("ok");
              });

      try (var test =
          TestServer.start(
              app,
              client -> client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {
        var result =
            test.send(request -> request.path("/stream"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("ok", result.body());
        assertFalse(result.headers().firstValue("Set-Cookie").isPresent());
      }
    }
  }

  private static void fileStore(SessionHandler handler, Path directory) {
    var store = new FileSessionDataStore();
    store.setStoreDir(directory.toFile());
    var cache = new DefaultSessionCache(handler);
    cache.setSessionDataStore(store);
    handler.setSessionCache(cache);
  }
}
