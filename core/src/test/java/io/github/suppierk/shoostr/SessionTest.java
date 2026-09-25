package io.github.suppierk.shoostr;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.session.DefaultSessionCache;
import org.eclipse.jetty.session.FileSessionDataStore;
import org.eclipse.jetty.session.SessionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionTest {
  @Test
  void leavesSessionsDisabledUntilConfigured() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/peek",
              (request, response) ->
                  response.text(request.session(true) == null ? "disabled" : "created"));
      app.start();
      var result = send(client, app, "/peek", Optional.empty());
      assertEquals("disabled", result.body());
      assertFalse(result.headers().firstValue("Set-Cookie").isPresent());
    }
  }

  @Test
  void createsSessionOnlyWhenRequestedAndRetainsAttributesAcrossRequests() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.sessions();
      app.routes()
          .get(
              "/peek",
              (request, response) -> {
                var session = request.session(false);
                response.text(session == null ? "absent" : (String) session.getAttribute("name"));
              })
          .get(
              "/create",
              (request, response) -> {
                var session = Objects.requireNonNull(request.session(true));
                session.setAttribute("name", "alice");
                response.text(session.getId());
              });
      app.start();

      var absent = send(client, app, "/peek", Optional.empty());
      assertEquals(200, absent.statusCode());
      assertEquals("absent", absent.body());
      assertFalse(absent.headers().firstValue("Set-Cookie").isPresent());

      var created = send(client, app, "/create", Optional.empty());
      assertEquals(200, created.statusCode());
      var setCookie = created.headers().firstValue("Set-Cookie").orElseThrow();
      var cookie = setCookie.split(";", 2)[0];
      assertTrue(cookie.startsWith("JSESSIONID="));
      assertTrue(setCookie.contains("Path=/"));
      assertTrue(setCookie.contains("HttpOnly"));
      assertTrue(setCookie.contains("SameSite=Lax"));
      assertFalse(setCookie.contains("Domain="));
      assertFalse(created.body().isBlank());

      var reused = send(client, app, "/peek", Optional.of(cookie));
      assertEquals(200, reused.statusCode());
      assertEquals("alice", reused.body());
      assertFalse(reused.headers().firstValue("Set-Cookie").isPresent());
    }
  }

  @Test
  void renewsSessionIdWithoutLosingAttributesOrAcceptingTheOldCookie() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.sessions();
      app.routes()
          .get(
              "/create",
              (request, response) -> {
                Objects.requireNonNull(request.session(true)).setAttribute("name", "alice");
                response.text("created");
              })
          .get("/renew", (request, response) -> response.text(request.renewSessionId()))
          .get(
              "/peek",
              (request, response) -> {
                var session = request.session(false);
                response.text(session == null ? "absent" : (String) session.getAttribute("name"));
              });
      app.start();

      var oldCookie =
          send(client, app, "/create", Optional.empty())
              .headers()
              .firstValue("Set-Cookie")
              .orElseThrow()
              .split(";", 2)[0];
      var renewed = send(client, app, "/renew", Optional.of(oldCookie));
      assertEquals(200, renewed.statusCode());
      var newCookie = renewed.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
      assertFalse(oldCookie.equals(newCookie));
      assertEquals("absent", send(client, app, "/peek", Optional.of(oldCookie)).body());
      assertEquals("alice", send(client, app, "/peek", Optional.of(newCookie)).body());
    }
  }

  @Test
  void rejectsUnknownIdsAndInvalidatedSessionsWithoutAdoptingTheirIdentifiers() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.sessions();
      app.routes()
          .get(
              "/create",
              (request, response) ->
                  response.text(Objects.requireNonNull(request.session(true)).getId()))
          .get(
              "/peek",
              (request, response) ->
                  response.text(request.session(false) == null ? "absent" : "present"))
          .get(
              "/logout",
              (request, response) -> {
                Objects.requireNonNull(request.session(false)).invalidate();
                response.text("logged out");
              });
      app.start();

      assertEquals(
          "absent",
          send(client, app, "/peek", Optional.of("JSESSIONID=chosen-by-attacker")).body());
      var created = send(client, app, "/create", Optional.of("JSESSIONID=chosen-by-attacker"));
      var cookie = created.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
      assertFalse(cookie.contains("chosen-by-attacker"));
      assertEquals("present", send(client, app, "/peek", Optional.of(cookie)).body());
      assertEquals("logged out", send(client, app, "/logout", Optional.of(cookie)).body());
      assertEquals("absent", send(client, app, "/peek", Optional.of(cookie)).body());
    }
  }

  @Test
  void appliesSafeCookieDefaultsAndAllowsNativeJettyOverrides() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.sessions(
          handler -> {
            handler.setSessionCookie("WEBSESSION");
            handler.setSameSite(org.eclipse.jetty.http.HttpCookie.SameSite.STRICT);
            handler.setMaxCookieAge(60);
          });
      app.routes()
          .get(
              "/create",
              (request, response) ->
                  response.text(Objects.requireNonNull(request.session(true)).getId()));
      app.start();

      var cookie =
          send(client, app, "/create", Optional.empty())
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

  @Test
  void expiresIdleSessionsAndClearsMemoryWhenApplicationCloses() throws Exception {
    String cookie;

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.sessions(handler -> handler.setMaxInactiveInterval(1));
      app.routes()
          .get(
              "/create",
              (request, response) ->
                  response.text(Objects.requireNonNull(request.session(true)).getId()))
          .get(
              "/peek",
              (request, response) ->
                  response.text(request.session(false) == null ? "absent" : "present"));
      app.start();
      cookie =
          send(client, app, "/create", Optional.empty())
              .headers()
              .firstValue("Set-Cookie")
              .orElseThrow()
              .split(";", 2)[0];
      assertEquals("present", send(client, app, "/peek", Optional.of(cookie)).body());
      await()
          .pollDelay(Duration.ofMillis(1200))
          .pollInterval(Duration.ofMillis(1200))
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(
              () -> assertEquals("absent", send(client, app, "/peek", Optional.of(cookie)).body()));
    }

    try (var restarted = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      restarted.sessions();
      restarted
          .routes()
          .get(
              "/peek",
              (request, response) ->
                  response.text(request.session(false) == null ? "absent" : "present"));
      restarted.start();
      assertEquals("absent", send(client, restarted, "/peek", Optional.of(cookie)).body());
    }
  }

  @Test
  void closesLiveInMemorySessionCacheBeforeARecreatedApplicationCanAcceptItsCookie()
      throws Exception {
    var sessionHandler = new AtomicReference<SessionHandler>();
    String cookie;

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.sessions(sessionHandler::set);
      app.routes()
          .get(
              "/create",
              (request, response) ->
                  response.text(Objects.requireNonNull(request.session(true)).getId()));
      app.start();
      cookie =
          send(client, app, "/create", Optional.empty())
              .headers()
              .firstValue("Set-Cookie")
              .orElseThrow()
              .split(";", 2)[0];
      assertTrue(sessionHandler.get().isStarted());
      assertEquals(
          1, ((DefaultSessionCache) sessionHandler.get().getSessionCache()).getSessionsCurrent());
    }

    assertTrue(sessionHandler.get().isStopped());
    var cache = (DefaultSessionCache) sessionHandler.get().getSessionCache();
    assertTrue(cache.isStopped());
    assertEquals(0, cache.getSessionsCurrent());

    try (var restarted = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      restarted.sessions();
      restarted
          .routes()
          .get(
              "/peek",
              (request, response) ->
                  response.text(request.session(false) == null ? "absent" : "present"));
      restarted.start();
      assertEquals("absent", send(client, restarted, "/peek", Optional.of(cookie)).body());
    }
  }

  @Test
  void retainsNewSessionCookieWhenApplicationErrorIsHandled() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.sessions();
      app.exception(
          IllegalArgumentException.class,
          (failure, request, response) -> response.status(409).text("handled"));
      app.routes()
          .get(
              "/start",
              (request, response) -> {
                Objects.requireNonNull(request.session(true)).setAttribute("name", "alice");
                response.cookie("discard", "unsafe");
                throw new IllegalArgumentException("failure after session creation");
              })
          .get(
              "/peek",
              (request, response) -> {
                var session = request.session(false);
                response.text(session == null ? "absent" : (String) session.getAttribute("name"));
              });
      app.start();

      var failed = send(client, app, "/start", Optional.empty());
      assertEquals(409, failed.statusCode());
      var cookie = failed.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
      assertEquals(1, failed.headers().allValues("Set-Cookie").size());
      assertEquals("alice", send(client, app, "/peek", Optional.of(cookie)).body());
    }
  }

  @Test
  void acceptsJettyStoreConfigurationAndReloadsSessionAcrossApplicationInstances(
      @TempDir Path directory) throws Exception {
    String cookie;

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.sessions(handler -> fileStore(handler, directory));
      app.routes()
          .get(
              "/create",
              (request, response) -> {
                Objects.requireNonNull(request.session(true)).setAttribute("name", "alice");
                response.text("created");
              });
      app.start();
      cookie =
          send(client, app, "/create", Optional.empty())
              .headers()
              .firstValue("Set-Cookie")
              .orElseThrow()
              .split(";", 2)[0];
    }

    try (var restarted = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      restarted.sessions(handler -> fileStore(handler, directory));
      restarted
          .routes()
          .get(
              "/peek",
              (request, response) -> {
                var session = request.session(false);
                response.text(session == null ? "absent" : (String) session.getAttribute("name"));
              });
      restarted.start();
      assertEquals("alice", send(client, restarted, "/peek", Optional.of(cookie)).body());
    }
  }

  @Test
  void persistsCsrfTokenWithJettyStoreAcrossApplicationInstances(@TempDir Path directory)
      throws Exception {
    String cookie;
    String token;

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      var csrf = new Csrf();
      app.sessions(handler -> fileStore(handler, directory));
      app.routes().get("/form", (request, response) -> response.text(csrf.token(request)));
      app.start();
      var result = send(client, app, "/form", Optional.empty());
      assertEquals(200, result.statusCode());
      cookie = result.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
      token = result.body();
    }

    try (var restarted = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      var csrf = new Csrf();
      restarted.sessions(handler -> fileStore(handler, directory));
      restarted
          .routes()
          .protect(
              csrf::verify,
              routes -> routes.post("/submit", (request, response) -> response.text("accepted")));
      restarted.start();
      var response =
          client.send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + restarted.port() + "/submit"))
                  .header("Cookie", cookie)
                  .header("Origin", "http://localhost:" + restarted.port())
                  .header("X-CSRF-Token", token)
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("accepted", response.body());
    }
  }

  @Test
  void sharesSessionAttributesAcrossConcurrentRequests() throws Exception {
    var ready = new CountDownLatch(8);
    var release = new CountDownLatch(1);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient();
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      app.sessions();
      app.routes()
          .get(
              "/create",
              (request, response) ->
                  response.text(Objects.requireNonNull(request.session(true)).getId()))
          .get(
              "/write/{key}",
              (request, response) -> {
                var session = Objects.requireNonNull(request.session(false));
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
                          Objects.requireNonNull(request.session(false))
                              .getAttributeNameSet()
                              .size())));
      app.start();
      var cookie =
          send(client, app, "/create", Optional.empty())
              .headers()
              .firstValue("Set-Cookie")
              .orElseThrow()
              .split(";", 2)[0];
      var futures = new ArrayList<Future<HttpResponse<String>>>();
      for (int index = 0; index < 8; index++) {
        var path = "/write/" + index;
        futures.add(workers.submit(() -> send(client, app, path, Optional.of(cookie))));
      }

      try {
        assertTrue(ready.await(5, TimeUnit.SECONDS));
      } finally {
        release.countDown();
      }

      for (var future : futures) {
        assertEquals(200, future.get(5, TimeUnit.SECONDS).statusCode());
      }

      assertEquals("8", send(client, app, "/count", Optional.of(cookie)).body());
    }
  }

  @Test
  void rejectsTwoDifferentValidSessionCookies() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.sessions();
      app.routes()
          .get(
              "/create",
              (request, response) ->
                  response.text(Objects.requireNonNull(request.session(true)).getId()))
          .get("/peek", (request, response) -> response.text("handled"));
      app.start();
      var first =
          send(client, app, "/create", Optional.empty())
              .headers()
              .firstValue("Set-Cookie")
              .orElseThrow()
              .split(";", 2)[0];
      var second =
          send(client, app, "/create", Optional.empty())
              .headers()
              .firstValue("Set-Cookie")
              .orElseThrow()
              .split(";", 2)[0];
      assertFalse(first.equals(second));
      var ambiguous = send(client, app, "/peek", Optional.of(first + "; " + second));
      assertEquals(400, ambiguous.statusCode());
      assertFalse("handled".equals(ambiguous.body()));
    }
  }

  @Test
  void rejectsSessionCreationAfterStreamingHasCommittedTheResponse() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.sessions();
      app.routes()
          .get(
              "/stream",
              (request, response) -> {
                var stream = response.startStream("text/plain");
                assertThrows(IllegalStateException.class, () -> request.session(true));
                stream.write("ok");
              });
      app.start();
      var result = send(client, app, "/stream", Optional.empty());
      assertEquals(200, result.statusCode());
      assertEquals("ok", result.body());
      assertFalse(result.headers().firstValue("Set-Cookie").isPresent());
    }
  }

  private static void fileStore(SessionHandler handler, Path directory) {
    var store = new FileSessionDataStore();
    store.setStoreDir(directory.toFile());
    var cache = new DefaultSessionCache(handler);
    cache.setSessionDataStore(store);
    handler.setSessionCache(cache);
  }

  private static HttpResponse<String> send(
      HttpClient client, Shoostr app, String path, Optional<String> cookie) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path));
    cookie.ifPresent(value -> builder.header("Cookie", value));

    return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
  }
}
