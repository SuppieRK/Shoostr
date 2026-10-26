package io.github.suppierk.shoostr;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.ForwardedHeaders;
import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.http.exceptions.UnauthorizedException;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.InetAddress;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.session.DefaultSessionCache;
import org.eclipse.jetty.session.DefaultSessionIdManager;
import org.eclipse.jetty.session.FileSessionDataStore;
import org.eclipse.jetty.session.SessionCache;
import org.eclipse.jetty.session.SessionData;
import org.eclipse.jetty.session.SessionHandler;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class NativeSessionLifecycleTest {
  @Test
  void readsExistingSessionsAfterCommitButRejectsRenewalAndSameExchangeRecreation()
      throws Exception {
    try (var app = new Shoostr().sessions()) {
      app.routes()
          .get(
              "/create",
              (request, response) -> {
                var session = request.getOrCreateSession();
                session.setAttribute("name", "alice");
                response.text(session.getId());
              })
          .get(
              "/stream",
              (request, response) -> {
                var identifier = request.getSession().orElseThrow().getId();
                var output = response.startStream("text/plain");
                output.write("prefix:");
                output.flush();
                assertEquals("alice", request.getOrCreateSession().getAttribute("name"));
                assertThrows(IllegalStateException.class, request::renewSessionId);
                assertEquals(identifier, request.getSession().orElseThrow().getId());
                output.write("alice");
              })
          .get(
              "/invalidate",
              (request, response) -> {
                var session = request.getSession().orElseThrow();
                session.invalidate();
                assertFalse(session.isValid());
                assertTrue(request.getSession().isEmpty());
                assertThrows(IllegalStateException.class, request::getOrCreateSession);
                response.text("invalidated");
              })
          .get(
              "/state",
              (request, response) ->
                  response.text(request.getSession().map(Session::getId).orElse("absent")));

      try (var test = TestServer.start(app)) {
        var created = test.send(r -> r.path("/create"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, created.statusCode());
        var streamed = test.send(r -> r.path("/stream"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, streamed.statusCode());
        assertEquals("prefix:alice", streamed.body());
        var retained = test.send(r -> r.path("/state"), HttpResponse.BodyHandlers.ofString());
        assertEquals(created.body(), retained.body());
        var invalidated =
            test.send(r -> r.path("/invalidate"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, invalidated.statusCode());
        assertEquals("invalidated", invalidated.body());
        var absent = test.send(r -> r.path("/state"), HttpResponse.BodyHandlers.ofString());
        assertEquals("absent", absent.body());
        var replacement = test.send(r -> r.path("/create"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, replacement.statusCode());
        assertNotEquals(created.body(), replacement.body());
      }
    }
  }

  @Test
  void rejectsAHandlerSpecificIdManagerBeforeConnectorStartupAndStopsTheOwnedHandler()
      throws Exception {
    var starts = new AtomicInteger();
    var handler = new AtomicReference<SessionHandler>();

    try (var app = new Shoostr()) {
      app.modifyServer(
              server -> {
                server.addBean(new DefaultSessionIdManager(server));
                for (var connector : server.getConnectors()) {
                  connector.addEventListener(
                      new LifeCycle.Listener() {
                        @Override
                        public void lifeCycleStarted(LifeCycle event) {
                          starts.incrementAndGet();
                        }
                      });
                }
              })
          .sessions(
              nativeHandler -> {
                handler.set(nativeHandler);
                nativeHandler.setSessionIdManager(
                    new DefaultSessionIdManager(nativeHandler.getServer()));
              });
      var failure = assertThrows(IllegalStateException.class, app::start);
      var message = failure.getMessage();
      assertNotNull(message);
      assertTrue(message.contains("SessionIdManager"));
      assertEquals(0, starts.get());
    }

    assertNotNull(handler.get());
    assertTrue(handler.get().isStopped());
  }

  @Test
  void usesAndStopsTheConfiguredServerLevelSessionIdManager() throws Exception {
    var shared = new AtomicReference<DefaultSessionIdManager>();
    var handler = new AtomicReference<SessionHandler>();

    try (var app = new Shoostr()) {
      app.modifyServer(
              server -> {
                var manager = new DefaultSessionIdManager(server);
                manager.setWorkerName("configured");
                server.addBean(manager);
                shared.set(manager);
              })
          .sessions(handler::set);
      app.routes()
          .get(
              "/create",
              (request, response) -> response.text(request.getOrCreateSession().getId()));

      try (var test = TestServer.start(app)) {
        assertSame(shared.get(), handler.get().getSessionIdManager());
        assertTrue(shared.get().isStarted());
        var created = test.send(r -> r.path("/create"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, created.statusCode());
        assertFalse(created.body().isBlank());
        assertTrue(
            created.headers().firstValue("Set-Cookie").orElseThrow().contains(".configured"));
      }
    }

    assertTrue(handler.get().isStopped());
    assertTrue(shared.get().isStopped());
  }

  @ParameterizedTest
  @MethodSource("proxyCookieConfigurations")
  void needsExplicitSecureCookiesBehindTrustedTlsTermination(
      ForwardedHeaders family, boolean forceSecure) throws Exception {
    try (var app = new Shoostr()) {
      app.sessions(
              handler -> {
                if (forceSecure) {
                  handler.setSecureCookies(true);
                }
              })
          .trustedProxies(InetAddress::isLoopbackAddress, family);
      app.routes()
          .get(
              "/session",
              (request, response) -> {
                assertFalse(request.isSecure());
                assertTrue(request.isEffectivelySecure());
                request.getOrCreateSession().setAttribute("theme", "dark");
                response.text("created");
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request -> {
                  request.path("/session");
                  if (family == ForwardedHeaders.RFC7239) {
                    request.header("Forwarded", "for=203.0.113.7;proto=https");
                  } else {
                    request
                        .header("X-Forwarded-For", "203.0.113.7")
                        .header("X-Forwarded-Proto", "https");
                  }
                });
        assertEquals(200, result.statusCode());
        var cookie =
            HttpCookie.parse(result.headers().firstValue("Set-Cookie").orElseThrow()).getFirst();
        assertEquals(forceSecure, cookie.getSecure());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void derivesNativeSecureCookieDefaultsFromActualTransportTls(boolean secure) throws Exception {
    var identity = KeyStore.getInstance("PKCS12");

    try (var input =
        Objects.requireNonNull(getClass().getResourceAsStream("/localhost-test.p12"))) {
      identity.load(input, "changeit".toCharArray());
    }

    var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trust.init(identity);
    var tls = SSLContext.getInstance("TLS");
    tls.init(null, trust.getTrustManagers(), null);

    try (var app = new Shoostr()) {
      app.sessions();
      if (secure) {
        app.tls(
            server -> {
              server.setKeyStore(identity);
              server.setKeyStorePassword("changeit");
            });
      }

      app.routes()
          .get(
              "/session",
              (request, response) -> {
                assertEquals(secure, request.isSecure());
                request.getOrCreateSession().setAttribute("theme", "dark");
                response.text("created");
              });

      try (var test = TestServer.start(app, client -> client.sslContext(tls))) {
        var result = test.send(request -> request.path("/session"));
        assertEquals(200, result.statusCode());
        var cookie =
            HttpCookie.parse(result.headers().firstValue("Set-Cookie").orElseThrow()).getFirst();
        assertEquals(secure, cookie.getSecure());
      }
    }
  }

  @Test
  void bypassesPersistedSessionAccessAndAdmissionForPreflightWithAnExistingCookie(
      @TempDir Path directory) throws Exception {
    var loads = new AtomicInteger();
    var authenticated = new AtomicInteger();
    var writes = new AtomicInteger();
    var cache = new AtomicReference<DefaultSessionCache>();
    var csrf = Csrf.builder().trustedOrigins("https://client.example").build();
    var cors =
        new Cors(
            CorsPolicy.builder()
                .origins("https://client.example")
                .methods(HttpMethods.GET, HttpMethods.POST)
                .credentials(true)
                .allowedHeaders(HttpHeaders.AUTHORIZATION, HttpHeaders.of("X-CSRF-Token"))
                .build());
    var authentication =
        new AuthenticationExtension() {
          @Override
          public void handle(Request request, Response response) {
            authenticated.incrementAndGet();
            if (!request.header("Authorization").filter("Bearer secret"::equals).isPresent()) {
              throw new UnauthorizedException();
            }

            assertEquals("dark", request.getSession().orElseThrow().getAttribute("theme"));
            request.principal(() -> "alice");
          }
        };

    try (var app = new Shoostr()) {
      app.sessions(
              handler -> {
                var store =
                    new FileSessionDataStore() {
                      @Override
                      public SessionData doLoad(String id) throws Exception {
                        loads.incrementAndGet();
                        return Objects.requireNonNull(super.doLoad(id));
                      }
                    };
                store.setStoreDir(directory.toFile());
                var nativeCache = new DefaultSessionCache(handler);
                nativeCache.setSessionDataStore(store);
                nativeCache.setEvictionPolicy(SessionCache.EVICT_ON_SESSION_EXIT);
                handler.setSessionCache(nativeCache);
                cache.set(nativeCache);
              })
          .extensions(csrf, cors)
          .authentication(authentication)
          .configureExtensionsDefaults(
              e -> {
                e.with(authentication);
                e.with(csrf);
                e.with(cors);
              })
          .onRequestHeaders(
              (request, response) -> {
                if ("OPTIONS".equals(request.method())) {
                  assertTrue(request.getSession().isEmpty());
                }
              });
      app.routes()
          .get(
              "/create",
              (request, response) -> {
                request.getOrCreateSession().setAttribute("theme", "dark");
                response.text(csrf.token(request));
              },
              e -> e.without(authentication))
          .post(
              "/write",
              (request, response) -> {
                writes.incrementAndGet();
                response.text(
                    request.principal().orElseThrow().getName()
                        + ":"
                        + request.getSession().orElseThrow().getAttribute("theme"));
              });

      try (var test =
          TestServer.start(
              app,
              client -> client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {
        var created = test.send(r -> r.path("/create"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, created.statusCode());
        var cookie = created.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        await().atMost(Duration.ofSeconds(5)).until(() -> cache.get().getSessionsCurrent() == 0);
        loads.set(0);
        for (var origin : List.of("https://client.example", "https://denied.example")) {
          var preflight =
              test.send(
                  r ->
                      r.path("/write")
                          .method("OPTIONS")
                          .header("Origin", origin)
                          .header("Cookie", cookie)
                          .header("Access-Control-Request-Method", "POST")
                          .header("Access-Control-Request-Headers", "Authorization, X-CSRF-Token"));
          assertEquals("https://client.example".equals(origin) ? 204 : 403, preflight.statusCode());
          assertTrue(preflight.headers().firstValue("Set-Cookie").isEmpty());
        }
        assertEquals(0, loads.get());
        assertEquals(0, authenticated.get());
        assertEquals(0, writes.get());
        var denied =
            test.send(
                r ->
                    r.path("/write")
                        .method("POST")
                        .header("Origin", "https://client.example")
                        .header("Cookie", cookie)
                        .header("Authorization", "Bearer secret"));
        assertEquals(403, denied.statusCode());
        assertTrue(loads.get() > 0);
        assertEquals(1, authenticated.get());
        assertEquals(0, writes.get());
        var accepted =
            test.send(
                r ->
                    r.path("/write")
                        .method("POST")
                        .header("Origin", "https://client.example")
                        .header("Cookie", cookie)
                        .header("Authorization", "Bearer secret")
                        .header("X-CSRF-Token", created.body()),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, accepted.statusCode());
        assertEquals("alice:dark", accepted.body());
        assertEquals(2, authenticated.get());
        assertEquals(1, writes.get());
      }
    }
  }

  @Test
  void rejectsNativeConfigurationThatDetachesTheApplicationBeforeTheListenerStarts()
      throws Exception {
    var listening = new AtomicBoolean();
    var sessionsConfigured = new AtomicBoolean();

    try (var app = new Shoostr()) {
      app.sessions(handler -> sessionsConfigured.set(true))
          .modifyServer(
              server -> {
                server.getConnectors()[0].addEventListener(
                    new LifeCycle.Listener() {
                      @Override
                      public void lifeCycleStarted(LifeCycle event) {
                        listening.set(true);
                      }
                    });
                server.getDescendant(ContextHandler.class).setHandler(new DefaultHandler());
              });
      app.routes().get("/owned", (request, response) -> response.text("owned"));
      assertThrows(IllegalStateException.class, app::start);
      assertFalse(listening.get());
      assertFalse(sessionsConfigured.get());
    }
  }

  @Test
  void requiresSessionsForEffectiveCsrfProtectionButNotAnExcludedSelection() throws Exception {
    var protection = new Csrf();

    try (var app = new Shoostr()) {
      app.extensions(protection);
      app.routes()
          .post(
              "/protected",
              (request, response) -> response.text("changed"),
              e -> e.with(protection));
      assertThrows(IllegalStateException.class, app::start);
    }

    var excluded = new Csrf();

    try (var app = new Shoostr()) {
      app.extensions(excluded).configureExtensionsDefaults(e -> e.with(excluded));
      app.routes()
          .post(
              "/public", (request, response) -> response.text("public"), e -> e.without(excluded));

      try (var test = TestServer.start(app)) {
        assertEquals(200, test.send(r -> r.path("/public").method("POST")).statusCode());
      }
    }
  }

  @Test
  void ownsOneDiscoverableNativeHandlerConfiguredAfterServerHooks() throws Exception {
    var callbacks = new ArrayList<String>();
    var nativeHandler = new AtomicReference<SessionHandler>();

    try (var app = new Shoostr()) {
      app.modifyServer(
          server -> {
            var handler = server.getDescendant(SessionHandler.class);
            assertNotNull(handler);
            nativeHandler.set(handler);
            callbacks.add("server");
          });
      app.sessions(
          handler -> {
            assertEquals(nativeHandler.get(), handler);
            callbacks.add("sessions");
            handler.setSessionCookie("VISITOR");
          });
      app.routes()
          .get(
              "/peek",
              (request, response) ->
                  response.text(request.getSession().isPresent() ? "present" : "absent"))
          .get(
              "/create",
              (request, response) -> {
                var session = request.getOrCreateSession();
                session.setAttribute("theme", "dark");
                response.text(session.getId());
              });

      try (var test = TestServer.start(app)) {
        assertEquals(List.of("server", "sessions"), callbacks);
        assertTrue(nativeHandler.get().isStarted());
        var absent = test.send(r -> r.path("/peek"), HttpResponse.BodyHandlers.ofString());
        assertEquals("absent", absent.body());
        assertFalse(absent.headers().firstValue("Set-Cookie").isPresent());
        var created = test.send(r -> r.path("/create"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, created.statusCode());
        assertTrue(created.headers().firstValue("Set-Cookie").orElseThrow().startsWith("VISITOR="));
        assertFalse(created.body().isBlank());
        assertEquals(
            "present",
            test.send(r -> r.path("/peek"), HttpResponse.BodyHandlers.ofString()).body());
      }
    }

    assertTrue(nativeHandler.get().isStopped());
  }

  private static Stream<Arguments> proxyCookieConfigurations() {
    return Arrays.stream(ForwardedHeaders.values())
        .flatMap(family -> Stream.of(false, true).map(secure -> Arguments.of(family, secure)));
  }
}
