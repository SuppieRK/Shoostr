package io.github.suppierk.shoostr;

import static io.github.suppierk.shoostr.Locks.runUnderLock;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.jetty.server.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CsrfConcurrencyTest {
  @Test
  @Timeout(15)
  void releasesSessionLockOwnershipAfterCompletedTokenRequest() throws Exception {
    var registry = (Map<?, ?>) sessionLockField("SESSION_LOCKS");
    var guard = (ReentrantLock) sessionLockField("SESSION_LOCKS_GUARD");
    var session = new AtomicReference<Session>();
    var csrf = new Csrf();
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.sessions();
      app.routes()
          .get(
              "/token",
              (request, response) -> {
                session.set(request.getOrCreateSession());
                response.text(csrf.token(request));
              });

      try (var server = TestServer.start(app)) {
        try {
          var result =
              server.send(request -> request.path("/token"), HttpResponse.BodyHandlers.ofString());
          var completedSession = session.get();

          assertEquals(200, result.statusCode());
          assertFalse(result.body().isBlank());
          assertNotNull(completedSession);
          runUnderLock(guard, () -> assertFalse(registry.containsKey(completedSession)));
        } finally {
          var completedSession = session.get();
          if (completedSession != null) {
            // Retire only this test's session so a failed mutation cannot retain it.
            runUnderLock(guard, () -> registry.remove(completedSession));
          }
        }
      }
    }
  }

  private Object sessionLockField(String name) throws ReflectiveOperationException {
    var type = Class.forName(Csrf.class.getName() + "$SessionLock");
    var field = type.getDeclaredField(name);
    field.setAccessible(true);

    return Objects.requireNonNull(field.get(null));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @Timeout(15)
  void accessesCsrfWhileApplicationHoldsSessionMonitor(boolean verify) throws Exception {
    var session = new AtomicReference<Session>();
    var monitorHeld = new CountDownLatch(1);
    var releaseMonitor = new CountDownLatch(1);
    var app = new Shoostr();

    try (Closeable appCleanup = app::close;
        var monitorExecutor = Executors.newSingleThreadExecutor()) {
      registerTokenEndpoints(app, session);

      try (var server = TestServer.start(app, this::configureClient)) {
        var cookie = createSession(server);
        var proof = verify ? requestToken(server, cookie).body() : "";
        var holder =
            monitorExecutor.submit(
                () -> holdSessionMonitor(session.get(), monitorHeld, releaseMonitor));

        try {
          assertTrue(monitorHeld.await(5, TimeUnit.SECONDS));

          var result =
              verify
                  ? verifyToken(server, cookie, proof, app.port())
                  : requestToken(server, cookie);

          assertEquals(200, result.statusCode());
          assertFalse(result.body().isBlank());
        } finally {
          releaseMonitor.countDown();
        }

        assertTrue(holder.get(5, TimeUnit.SECONDS));
      }
    }
  }

  @Test
  @Timeout(15)
  void allowsAttributeListenerToReadSessionFromAnotherThread() throws Exception {
    var session = new AtomicReference<Session>();
    var app = new Shoostr();

    try (Closeable appCleanup = app::close;
        var readExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
      registerTokenEndpoints(app, session);

      try (var server = TestServer.start(app, this::configureClient)) {
        var cookie = createSession(server);
        session.get().setAttribute("probe", nativeReadListener(readExecutor));

        try {
          var result = requestToken(server, cookie);

          assertEquals(200, result.statusCode());
          assertFalse(result.body().isBlank());
        } finally {
          session.get().removeAttribute("probe");
        }
      }
    }
  }

  private Session.ValueListener nativeReadListener(ExecutorService readExecutor) {
    return new Session.ValueListener() {
      @Override
      public void onSessionAttributeUpdate(
          Session session, String name, Object oldValue, Object newValue) {
        if (!"probe".equals(name)) {
          var attribute =
              CompletableFuture.supplyAsync(() -> session.getAttribute("probe"), readExecutor)
                  .orTimeout(1, TimeUnit.SECONDS)
                  .join();

          assertSame(this, attribute);
        }
      }
    };
  }

  @ParameterizedTest
  @ValueSource(strings = {"/other-token", "/selected-token"})
  @Timeout(15)
  void waitsForTokenPublicationAcrossCsrfInstances(String path) throws Exception {
    var session = new AtomicReference<Session>();
    var publicationStarted = new CountDownLatch(1);
    var releasePublication = new CountDownLatch(1);
    var otherRequestStarted = new CountDownLatch(1);
    var app = new Shoostr();

    try (Closeable appCleanup = app::close;
        var requestExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
      registerTokenEndpoints(app, session, otherRequestStarted);

      try (var server = TestServer.start(app, this::configureClient)) {
        var cookie = createSession(server);
        session
            .get()
            .setAttribute("probe", blockingListener(publicationStarted, releasePublication));

        try {
          var first = requestExecutor.submit(() -> requestToken(server, cookie));

          try {
            assertTrue(publicationStarted.await(5, TimeUnit.SECONDS));

            var other = requestExecutor.submit(() -> requestToken(server, cookie, path));

            assertTrue(otherRequestStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> other.get(200, TimeUnit.MILLISECONDS));
            releasePublication.countDown();

            var firstResponse = first.get(5, TimeUnit.SECONDS);
            var otherResponse = other.get(5, TimeUnit.SECONDS);

            assertEquals(200, firstResponse.statusCode());
            assertEquals(200, otherResponse.statusCode());
            assertEquals(firstResponse.body(), otherResponse.body());
          } finally {
            releasePublication.countDown();
          }
        } finally {
          session.get().removeAttribute("probe");
        }
      }
    }
  }

  @Test
  @Timeout(15)
  void createsTokenForAnotherSessionWhilePublicationIsBlocked() throws Exception {
    var session = new AtomicReference<Session>();
    var publicationStarted = new CountDownLatch(1);
    var releasePublication = new CountDownLatch(1);
    var app = new Shoostr();

    try (Closeable appCleanup = app::close;
        var requestExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
      registerTokenEndpoints(app, session);

      try (var server = TestServer.start(app, this::configureClient)) {
        var firstCookie = createSession(server);
        var firstSession = session.get();
        var otherCookie = createSession(server);

        assertNotEquals(firstCookie, otherCookie);
        firstSession.setAttribute(
            "probe", blockingListener(publicationStarted, releasePublication));

        try {
          var first = requestExecutor.submit(() -> requestToken(server, firstCookie));

          try {
            assertTrue(publicationStarted.await(5, TimeUnit.SECONDS));

            var other = requestToken(server, otherCookie);

            assertEquals(200, other.statusCode());
            assertFalse(other.body().isBlank());
          } finally {
            releasePublication.countDown();
          }

          assertEquals(200, first.get(5, TimeUnit.SECONDS).statusCode());
        } finally {
          firstSession.removeAttribute("probe");
        }
      }
    }
  }

  @Test
  @Timeout(20)
  void keepsWaitingProvidersCoordinatedWhenFirstPublicationFinishes() throws Exception {
    var session = new AtomicReference<Session>();
    var firstPublication = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    var secondPublication = new CountDownLatch(1);
    var releaseSecond = new CountDownLatch(1);
    var otherRequestStarted = new CountDownLatch(1);
    var selectedRequestStarted = new CountDownLatch(1);
    var app = new Shoostr();

    try (Closeable appCleanup = app::close;
        var requestExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
      registerTokenEndpoints(app, session, otherRequestStarted, selectedRequestStarted);

      try (var server = TestServer.start(app, this::configureClient)) {
        var cookie = createSession(server);
        var nativeSession = session.get();
        nativeSession.setAttribute("probe", blockingListener(firstPublication, releaseFirst));

        try {
          final var first = requestExecutor.submit(() -> requestToken(server, cookie));

          assertTrue(firstPublication.await(5, TimeUnit.SECONDS));

          var waiting = requestExecutor.submit(() -> requestToken(server, cookie, "/other-token"));

          assertTrue(otherRequestStarted.await(5, TimeUnit.SECONDS));
          assertThrows(TimeoutException.class, () -> waiting.get(200, TimeUnit.MILLISECONDS));

          var renewed = requestToken(server, cookie, "/renew");
          final var renewedCookie =
              renewed.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];

          nativeSession.removeAttribute("probe");
          nativeSession.setAttribute("probe", blockingListener(secondPublication, releaseSecond));
          releaseFirst.countDown();
          assertTrue(secondPublication.await(5, TimeUnit.SECONDS));

          var oldToken = first.get(5, TimeUnit.SECONDS);

          assertEquals(200, oldToken.statusCode());

          var arriving =
              requestExecutor.submit(() -> requestToken(server, renewedCookie, "/selected-token"));

          assertTrue(selectedRequestStarted.await(5, TimeUnit.SECONDS));
          assertThrows(TimeoutException.class, () -> arriving.get(200, TimeUnit.MILLISECONDS));
          releaseSecond.countDown();

          var waitingToken = waiting.get(5, TimeUnit.SECONDS);
          var arrivingToken = arriving.get(5, TimeUnit.SECONDS);

          assertEquals(200, waitingToken.statusCode());
          assertEquals(200, arrivingToken.statusCode());
          assertNotEquals(oldToken.body(), waitingToken.body());
          assertEquals(waitingToken.body(), arrivingToken.body());
        } finally {
          releaseFirst.countDown();
          releaseSecond.countDown();
          nativeSession.removeAttribute("probe");
        }
      }
    }
  }

  private Session.ValueListener blockingListener(
      CountDownLatch publicationStarted, CountDownLatch releasePublication) {
    return new Session.ValueListener() {
      @Override
      public void onSessionAttributeUpdate(
          Session session, String name, Object oldValue, Object newValue) {
        if (!"probe".equals(name)) {
          publicationStarted.countDown();

          try {
            assertTrue(releasePublication.await(5, TimeUnit.SECONDS));
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
          }
        }
      }
    };
  }

  @Test
  @Timeout(15)
  void returnsSameTokenWhenAttributeListenerReentersOnOwningThread() throws Exception {
    var nestedToken = new AtomicReference<String>();
    var csrf = new Csrf();
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.sessions();
      app.routes()
          .get(
              "/token",
              (request, response) ->
                  respondWithReentrantToken(csrf, request, response, nestedToken));

      try (var server = TestServer.start(app, this::configureClient)) {
        var result = requestToken(server, "");

        assertEquals(200, result.statusCode());
        assertFalse(result.body().isBlank());
        assertEquals(result.body(), nestedToken.get());
      }
    }
  }

  private void respondWithReentrantToken(
      Csrf csrf, Request request, Response response, AtomicReference<String> nestedToken) {
    var session = request.getOrCreateSession();
    session.setAttribute("probe", reentrantListener(csrf, request, nestedToken));

    try {
      response.text(csrf.token(request));
    } finally {
      session.removeAttribute("probe");
    }
  }

  private Session.ValueListener reentrantListener(
      Csrf csrf, Request request, AtomicReference<String> nestedToken) {
    return new Session.ValueListener() {
      @Override
      public void onSessionAttributeUpdate(
          Session session, String name, Object oldValue, Object newValue) {
        if (!"probe".equals(name)) {
          nestedToken.set(csrf.token(request));
        }
      }
    };
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @Timeout(15)
  void acceptsLaterRequestAfterTokenPublicationListenerThrows(boolean fatal) throws Exception {
    var session = new AtomicReference<Session>();
    var app = new Shoostr(virtualOptions());

    try (Closeable appCleanup = app::close) {
      registerTokenEndpoints(app, session);

      try (var server = TestServer.start(app, this::configureClient)) {
        var cookie = createSession(server);
        session.get().setAttribute("probe", failingListener(fatal));

        try {
          var failure = requestToken(server, cookie, "/failed-token");

          assertEquals(500, failure.statusCode());
          assertEquals(fatal ? "LinkageError" : "IllegalStateException", failure.body());

          var retry = requestToken(server, cookie);

          assertEquals(200, retry.statusCode());
          assertFalse(retry.body().isBlank());
        } finally {
          session.get().removeAttribute("probe");
        }
      }
    }
  }

  private Options virtualOptions() {
    var options = new Options();
    options.setVirtualThreads(true);
    return options;
  }

  private Session.ValueListener failingListener(boolean fatal) {
    return new Session.ValueListener() {
      @Override
      public void onSessionAttributeUpdate(
          Session session, String name, Object oldValue, Object newValue) {
        if (!"probe".equals(name)) {
          if (fatal) {
            throw new LinkageError("publication failed");
          }

          throw new IllegalStateException("publication failed");
        }
      }
    };
  }

  private void configureClient(HttpClient.Builder client) {
    client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE));
  }

  private void registerTokenEndpoints(Shoostr app, AtomicReference<Session> session) {
    registerTokenEndpoints(app, session, new CountDownLatch(0));
  }

  private void registerTokenEndpoints(
      Shoostr app, AtomicReference<Session> session, CountDownLatch otherRequestStarted) {
    registerTokenEndpoints(app, session, otherRequestStarted, otherRequestStarted);
  }

  private void registerTokenEndpoints(
      Shoostr app,
      AtomicReference<Session> session,
      CountDownLatch otherRequestStarted,
      CountDownLatch selectedRequestStarted) {
    var csrf = new Csrf();
    var otherCsrf = new Csrf();
    var selectedCsrf = new AtomicReference<Csrf>();
    app.sessions();
    app.routes()
        .get(
            "/session",
            (request, response) -> {
              session.set(request.getOrCreateSession());
              response.text("ready");
            })
        .get("/token", (request, response) -> response.text(csrf.token(request)))
        .get("/renew", (request, response) -> response.text(request.renewSessionId()))
        .get(
            "/failed-token",
            (request, response) -> respondWithTokenFailure(csrf, request, response))
        .get(
            "/other-token",
            (request, response) -> {
              otherRequestStarted.countDown();
              response.text(otherCsrf.token(request));
            })
        .get(
            "/selected-token",
            (request, response) -> {
              selectedRequestStarted.countDown();
              response.text(selectedCsrf.get().token(request));
            },
            lifecycle -> selectedCsrf.set(lifecycle.withExtension(csrf)))
        .post(
            "/verify",
            (request, response) -> response.text("accepted"),
            lifecycle -> lifecycle.withExtension(csrf));
  }

  private String createSession(TestServer server) throws IOException, InterruptedException {
    var result =
        server.send(request -> request.path("/session"), HttpResponse.BodyHandlers.ofString());

    assertEquals(200, result.statusCode());
    return result.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
  }

  private void respondWithTokenFailure(Csrf csrf, Request request, Response response) {
    try {
      response.text(csrf.token(request));
    } catch (RuntimeException | Error failure) {
      response.status(500).text(failure.getClass().getSimpleName());
    }
  }

  private boolean holdSessionMonitor(
      Session session, CountDownLatch monitorHeld, CountDownLatch releaseMonitor)
      throws InterruptedException {
    synchronized (session) {
      monitorHeld.countDown();
      return releaseMonitor.await(10, TimeUnit.SECONDS);
    }
  }

  private HttpResponse<String> requestToken(TestServer server, String cookie)
      throws IOException, InterruptedException {
    return requestToken(server, cookie, "/token");
  }

  private HttpResponse<String> requestToken(TestServer server, String cookie, String path)
      throws IOException, InterruptedException {
    return server.send(
        request -> request.path(path).header("Cookie", cookie).timeout(Duration.ofSeconds(2)),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> verifyToken(TestServer server, String cookie, String proof, int port)
      throws IOException, InterruptedException {
    return server.send(
        request ->
            request
                .path("/verify")
                .method("POST")
                .header("Cookie", cookie)
                .header("Origin", "http://127.0.0.1:" + port)
                .header("X-CSRF-Token", proof)
                .timeout(Duration.ofSeconds(2)),
        HttpResponse.BodyHandlers.ofString());
  }
}
