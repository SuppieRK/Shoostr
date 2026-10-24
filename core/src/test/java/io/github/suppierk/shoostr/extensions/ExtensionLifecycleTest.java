package io.github.suppierk.shoostr.extensions;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.suppierk.shoostr.ApplicationCallbacks;
import io.github.suppierk.shoostr.AuthenticationExtension;
import io.github.suppierk.shoostr.Cors;
import io.github.suppierk.shoostr.CorsPolicy;
import io.github.suppierk.shoostr.Csrf;
import io.github.suppierk.shoostr.Extension;
import io.github.suppierk.shoostr.Extensions;
import io.github.suppierk.shoostr.Handler;
import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.RequestOutcome;
import io.github.suppierk.shoostr.Response;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.exceptions.UnauthorizedException;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExtensionLifecycleTest {
  @ParameterizedTest
  @ValueSource(strings = {"csrf", "cors"})
  void exclusionDoesNotAllowReplacingAnInheritedSecurityOwner(String policy) throws Exception {
    Extension<?> first =
        "csrf".equals(policy)
            ? new Csrf()
            : new Cors(CorsPolicy.builder().origins("https://first.example").build());
    Extension<?> second =
        "csrf".equals(policy)
            ? new Csrf()
            : new Cors(CorsPolicy.builder().origins("https://second.example").build());

    try (var app = new Shoostr()) {
      app.sessions().extensions(first, second).configureExtensionsDefaults(e -> e.with(first));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              app.routes()
                  .get(
                      "/value",
                      (request, response) -> response.text("unexpected"),
                      e -> {
                        e.without(first);
                        e.with(second);
                      }));
      app.routes().get("/value", (request, response) -> response.text("kept"));

      try (var test = TestServer.start(app)) {
        var response =
            test.send(
                r -> r.path("/value").header("Origin", "https://first.example"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("kept", response.body());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"endpoint", "group", "callback"})
  void rejectsDefaultsAfterAnyRouteOrGroupRegistrationStarts(String registration) throws Exception {
    try (var app = new Shoostr()) {
      var routes = app.routes();
      switch (registration) {
        case "endpoint" -> routes.get("/value", (request, response) -> response.text("ok"));
        case "group" -> routes.path("/empty", child -> {});
        case "callback" -> app.routes(root -> {});
        default -> fail("Unknown registration: " + registration);
      }

      assertThrows(IllegalStateException.class, () -> app.configureExtensionsDefaults(e -> {}));
    }
  }

  @Test
  void freezesDefaultsAndRejectsRepeatedSetupButAllowsObtainingRoutesBeforehand() throws Exception {
    var admission =
        new AdmissionExtension((request, response) -> response.setHeader("X-Policy", "once"));
    var retained = new AtomicReference<Extensions>();

    try (var app = new Shoostr()) {
      var routes = app.routes();
      app.extensions(admission)
          .configureExtensionsDefaults(
              e -> {
                retained.set(e);
                assertThrows(NullPointerException.class, () -> e.with(null));
                assertThrows(NullPointerException.class, () -> e.without(null));
                e.with(admission);
                assertThrows(
                    IllegalStateException.class,
                    () -> app.configureExtensionsDefaults(nested -> {}));
                assertThrows(
                    IllegalStateException.class,
                    () -> routes.get("/during", (request, response) -> {}));
                assertThrows(IllegalStateException.class, app::start);
              });
      assertThrows(IllegalStateException.class, () -> app.configureExtensionsDefaults(e -> {}));
      assertThrows(IllegalStateException.class, () -> retained.get().with(admission));
      assertThrows(IllegalStateException.class, () -> retained.get().without(admission));
      routes.get("/value", (request, response) -> response.text("ok"));

      try (var test = TestServer.start(app)) {
        assertEquals(
            "once",
            test.send(r -> r.path("/value")).headers().firstValue("X-Policy").orElseThrow());
        assertEquals(404, test.send(r -> r.path("/during")).statusCode());
      }
    }
  }

  @Test
  void failedDefaultsFreezeRetainedScopesWithoutPublishingContributionsAndAllowCorrection()
      throws Exception {
    var installed =
        new AdmissionExtension((request, response) -> response.setHeader("X-Policy", "corrected"));
    var missing =
        new AdmissionExtension(
            (request, response) -> response.setHeader("X-Missing", "unexpected"));
    var retained = new AtomicReference<Extensions>();

    try (var app = new Shoostr()) {
      app.extensions(installed);
      assertThrows(NullPointerException.class, () -> app.configureExtensionsDefaults(null));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              app.configureExtensionsDefaults(
                  e -> {
                    retained.set(e);
                    e.with(installed);
                    assertThrows(IllegalArgumentException.class, () -> e.without(missing));
                    e.with(missing);
                  }));
      assertThrows(IllegalStateException.class, () -> retained.get().with(installed));
      app.configureExtensionsDefaults(e -> e.with(installed));
      app.routes().get("/value", (request, response) -> response.text("ok"));

      try (var test = TestServer.start(app)) {
        var response = test.send(r -> r.path("/value"));
        assertEquals(200, response.statusCode());
        assertEquals("corrected", response.headers().firstValue("X-Policy").orElseThrow());
        assertTrue(response.headers().firstValue("X-Missing").isEmpty());
      }
    }
  }

  @Test
  void defaultsCanWaitForAnotherThreadToInstallAnExtensionBeforeSelectingIt() throws Exception {
    var admission =
        new AdmissionExtension((request, response) -> response.setHeader("X-Policy", "installed"));

    try (var app = new Shoostr();
        var installer = Executors.newSingleThreadExecutor()) {
      app.configureExtensionsDefaults(
          extensions -> {
            // Waiting here would deadlock if the defaults callback held the application lock.
            var installed = installer.submit(() -> app.extensions(admission));
            assertSame(app, assertDoesNotThrow(() -> installed.get(5, TimeUnit.SECONDS)));
            extensions.with(admission);
          });
      app.routes().get("/value", (request, response) -> response.text("ok"));

      try (var test = TestServer.start(app)) {
        var response =
            test.send(request -> request.path("/value"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("ok", response.body());
        assertEquals("installed", response.headers().firstValue("X-Policy").orElseThrow());
      }
    }
  }

  @Test
  void excludingInheritedAuthenticationDoesNotPermitReplacingItsOwner() throws Exception {
    var first = authenticator((request, response) -> request.principal(() -> "first"));
    var second = authenticator((request, response) -> request.principal(() -> "second"));

    try (var app = new Shoostr()) {
      app.authentication(first, second)
          .configureExtensionsDefaults(extensions -> extensions.with(first));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              app.routes()
                  .get(
                      "/identity",
                      (request, response) -> response.text("unexpected"),
                      extensions -> {
                        extensions.without(first);
                        extensions.with(second);
                      }));
      app.routes()
          .get(
              "/identity",
              (request, response) -> response.text(request.principal().orElseThrow().getName()));

      try (var test = TestServer.start(app)) {
        assertEquals(
            "first",
            test.send(request -> request.path("/identity"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void selectingAuthenticationThroughWithProtectsApplicationDefaults() throws Exception {
    var authentication =
        authenticator(
            (request, response) -> {
              if (!"Bearer valid".equals(request.header("Authorization").orElse(null))) {
                throw new UnauthorizedException("Bearer");
              }

              request.principal(() -> "alice");
            });

    try (var app = new Shoostr()) {
      app.authentication(authentication)
          .configureExtensionsDefaults(extensions -> extensions.with(authentication));
      app.routes()
          .get(
              "/identity",
              (request, response) ->
                  response.text(request.principal().map(Principal::getName).orElse("anonymous")));

      try (var test = TestServer.start(app)) {
        var missing =
            test.send(request -> request.path("/identity"), HttpResponse.BodyHandlers.ofString());
        var valid =
            test.send(
                request -> request.path("/identity").header("Authorization", "Bearer valid"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, missing.statusCode());
        assertEquals(200, valid.statusCode());
        assertEquals("alice", valid.body());
      }
    }
  }

  @Test
  void excludesOnlyTheSelectedExtensionFromAnEndpoint() throws Exception {
    var admission =
        new AdmissionExtension(
            (request, response) -> response.setHeader("X-Policy", "application"));

    try (var app = new Shoostr()) {
      app.extensions(admission)
          .configureExtensionsDefaults(extensions -> extensions.with(admission))
          .beforeRouteHandler((request, response) -> response.setHeader("X-App", "retained"));
      app.routes()
          .get(
              "/excluded",
              (request, response) -> response.text("excluded"),
              extensions -> {
                extensions.without(admission);
                extensions
                    .lifecycle()
                    .beforeRouteHandler(
                        (request, response) -> response.setHeader("X-Local", "retained"));
              })
          .get("/included", (request, response) -> response.text("included"));

      try (var test = TestServer.start(app)) {
        var excluded =
            test.send(request -> request.path("/excluded"), HttpResponse.BodyHandlers.ofString());
        var included =
            test.send(request -> request.path("/included"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, excluded.statusCode());
        assertEquals("excluded", excluded.body());
        assertTrue(excluded.headers().firstValue("X-Policy").isEmpty());
        assertEquals("retained", excluded.headers().firstValue("X-Local").orElseThrow());
        assertEquals("retained", excluded.headers().firstValue("X-App").orElseThrow());
        assertEquals("application", included.headers().firstValue("X-Policy").orElseThrow());
        assertEquals("included", included.body());
      }
    }
  }

  @Test
  void appliesApplicationExtensionDefaultsToIndependentlyRegisteredEndpoints() throws Exception {
    var admission =
        new AdmissionExtension(
            (request, response) -> response.setHeader("X-Policy", "application"));

    try (var app = new Shoostr()) {
      app.extensions(admission)
          .configureExtensionsDefaults(extensions -> extensions.with(admission));
      app.routes().get("/first", (request, response) -> response.text("first"));
      app.routes(
          routes ->
              routes.path(
                  "/nested",
                  child -> child.get("/second", (request, response) -> response.text("second"))));

      try (var test = TestServer.start(app)) {
        var first =
            test.send(request -> request.path("/first"), HttpResponse.BodyHandlers.ofString());
        var second =
            test.send(
                request -> request.path("/nested/second"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, first.statusCode());
        assertEquals("first", first.body());
        assertEquals("application", first.headers().firstValue("X-Policy").orElseThrow());
        assertEquals(200, second.statusCode());
        assertEquals("second", second.body());
        assertEquals("application", second.headers().firstValue("X-Policy").orElseThrow());
      }
    }
  }

  @Test
  void nullAdditionalExtensionsInstallsAndClosesOnlyTheRequiredFirstInstance() throws Exception {
    var events = new ArrayList<String>();
    var first = new Capability("first", events);

    try (var app = new Shoostr()) {
      assertSame(app, app.extensions(first, (Extension<?>[]) null));
      assertEquals(List.of("install-first"), events);
      assertThrows(IllegalArgumentException.class, () -> app.extensions(first));
    }

    assertEquals(List.of("install-first", "close-first"), events);
  }

  @Test
  void validatesTheWholeInstallationBatchBeforeSetupAndAllowsCorrection() throws Exception {
    var events = new ArrayList<String>();
    var first = new Capability("first", events);
    var second = new Capability("second", events);
    var auth = authenticator((ignored, ignored2) -> {});

    try (var app = new Shoostr()) {
      assertThrows(IllegalArgumentException.class, () -> app.extensions(first, first));
      assertThrows(NullPointerException.class, () -> app.extensions(first, second, null));
      assertThrows(IllegalArgumentException.class, () -> app.extensions(first, auth));
      assertTrue(events.isEmpty());
      assertSame(app, app.extensions(first, second));
      assertEquals(List.of("install-first", "install-second"), events);
      assertThrows(IllegalArgumentException.class, () -> app.extensions(second));
      assertSame(app, app.authentication(auth));

      assertThrows(IllegalArgumentException.class, () -> app.authentication(auth));
    }

    assertEquals(List.of("install-first", "install-second", "close-second", "close-first"), events);
  }

  @Test
  void selectsByIdentityAndRejectsMissingInstancesWithoutPublishingRoutes() throws Exception {
    var events = new ArrayList<String>();
    var installed = new Capability("same", events);
    var missing = new Capability("same", events);
    var retained = new AtomicReference<Extensions>();

    try (var app = new Shoostr()) {
      app.extensions(installed);
      var routes = app.routes();
      assertThrows(
          IllegalArgumentException.class,
          () -> routes.get("/route", (ignored3, ignored4) -> {}, e -> e.with(missing)));
      app.routes()
          .get(
              "/route",
              (ignored5, response) -> response.text("ok"),
              e -> {
                assertSame(e.with(installed), e.with(installed));
                retained.set(e);
              });
      var configuration = retained.get();
      assertThrows(
          IllegalStateException.class,
          () -> configuration.lifecycle().afterRouteHandler((ignored6, ignored7) -> {}));

      try (var test = TestServer.start(app)) {
        assertThrows(IllegalStateException.class, () -> app.extensions(missing));
        assertEquals(
            "ok",
            test.send(request -> request.path("/route"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void eagerProviderFailureClosesEveryClaimedResourceAndPreventsRestart() throws Exception {
    var events = new ArrayList<String>();
    var first = new Capability("first", events);
    Extension<Void> broken =
        new Extension<>() {
          @Override
          public void install(ApplicationCallbacks callbacks) {
            events.add("install-broken");
            throw new IllegalStateException("setup failed");
          }

          @Override
          public void close() throws IOException {
            events.add("close-broken");
            throw new IOException("cleanup failed");
          }
        };

    try (var app = new Shoostr()) {
      var routes = app.routes();
      var failure = assertThrows(IllegalStateException.class, () -> app.extensions(first, broken));
      assertEquals("setup failed", failure.getMessage());
      assertEquals(1, failure.getSuppressed().length);
      assertThrows(IllegalStateException.class, app::start);
      assertThrows(IllegalStateException.class, () -> routes.get("/", (ignored8, ignored9) -> {}));
    }

    assertEquals(List.of("install-first", "install-broken", "close-broken", "close-first"), events);
  }

  @Test
  void finalizationFailureClosesTheAppBeforeTraffic() throws Exception {
    var closed = new AtomicBoolean();
    Extension<Void> broken =
        new Extension<>() {
          @Override
          public void beforeStart() {
            throw new IllegalStateException("prepare failed");
          }

          @Override
          public void close() {
            closed.set(true);
          }
        };

    try (var app =
        new Shoostr()
            .modifyServer(
                nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0))) {
      app.extensions(broken);
      assertThrows(IllegalStateException.class, app::start);
      assertTrue(closed.get());
      assertThrows(IllegalStateException.class, app::port);
      assertThrows(IllegalStateException.class, app::start);
    }
  }

  @Test
  void runsLocalMatchBeforeAppMatch() throws Exception {
    var events = new LinkedBlockingQueue<String>();

    try (var app = new Shoostr()) {
      app.onRouteMatched((ignored10, ignored11) -> events.add("app"));
      app.routes()
          .get(
              "/ordered",
              (ignored12, response) -> response.text("ok"),
              e -> e.lifecycle().onRouteMatched((ignored13, ignored14) -> events.add("local")));

      try (var test = TestServer.start(app)) {
        assertEquals(
            "ok",
            test.send(request -> request.path("/ordered"), HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(List.of("local", "app"), List.copyOf(events));
      }
    }
  }

  @Test
  void runsDirectLocalAdmissionBeforeAppAdmission() throws Exception {
    var events = new LinkedBlockingQueue<String>();

    try (var app = new Shoostr()) {
      app.beforeRouteHandler((ignored15, ignored16) -> events.add("app"));
      app.routes()
          .get(
              "/ordered",
              (ignored17, response) -> response.text("ok"),
              e -> e.lifecycle().beforeRouteHandler((ignored18, ignored19) -> events.add("local")));

      try (var test = TestServer.start(app)) {
        assertEquals(
            "ok",
            test.send(request -> request.path("/ordered"), HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(List.of("local", "app"), List.copyOf(events));
      }
    }
  }

  @Test
  void runsLocalAfterHandlerBeforeAppAfterHandler() throws Exception {
    var events = new LinkedBlockingQueue<String>();

    try (var app = new Shoostr()) {
      app.afterRouteHandler((ignored20, ignored21) -> events.add("app"));
      app.routes()
          .get(
              "/ordered",
              (ignored22, response) -> response.text("ok"),
              e -> e.lifecycle().afterRouteHandler((ignored23, ignored24) -> events.add("local")));

      try (var test = TestServer.start(app)) {
        assertEquals(
            "ok",
            test.send(request -> request.path("/ordered"), HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(List.of("local", "app"), List.copyOf(events));
      }
    }
  }

  @Test
  void runsExtensionAdmissionBeforeDirectLocalAdmission() throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var admission = new AdmissionExtension((ignored25, ignored26) -> events.add("extension"));

    try (var app = new Shoostr()) {
      app.extensions(admission)
          .routes()
          .get(
              "/ordered",
              (ignored27, response) -> response.text("ok"),
              e -> {
                e.lifecycle().beforeRouteHandler((ignored28, ignored29) -> events.add("local"));
                e.with(admission);
              });

      try (var test = TestServer.start(app)) {
        assertEquals(
            "ok",
            test.send(request -> request.path("/ordered"), HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(List.of("extension", "local"), List.copyOf(events));
      }
    }
  }

  @Test
  void makesAuthenticatedPrincipalAvailableToExtensionAdmission() throws Exception {
    var auth = authenticator((request, ignored30) -> request.principal(() -> "alice"));
    var admission =
        new AdmissionExtension(
            (request, ignored31) ->
                request.attribute("identity", request.principal().orElseThrow().getName()));

    try (var app = new Shoostr()) {
      app.authentication(auth)
          .extensions(admission)
          .routes()
          .get(
              "/identity",
              (request, response) ->
                  response.text(request.attribute("identity").orElseThrow().toString()),
              e -> {
                e.with(admission);
                e.with(auth);
              });

      try (var test = TestServer.start(app)) {
        assertEquals(
            "alice",
            test.send(request -> request.path("/identity"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void selectingAuthenticationRepeatedlyAuthenticatesOnce() throws Exception {
    var calls = new AtomicInteger();
    var auth = authenticator((ignored32, ignored33) -> calls.incrementAndGet());

    try (var app = new Shoostr()) {
      app.authentication(auth)
          .routes()
          .path(
              "/",
              routes ->
                  routes.get(
                      "/identity", (ignored34, response) -> response.text("ok"), e -> e.with(auth)),
              e -> e.with(auth));

      try (var test = TestServer.start(app)) {
        assertEquals(
            "ok",
            test.send(request -> request.path("/identity"), HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(1, calls.get());
      }
    }
  }

  @Test
  void evaluatesAvailabilityAfterAppAdmissionAndBeforeTheEndpoint() throws Exception {
    var events = new LinkedBlockingQueue<String>();

    try (var app = new Shoostr()) {
      app.beforeRouteHandler((ignored35, ignored36) -> events.add("app"));
      app.routes()
          .when(
              () -> {
                events.add("availability");
                return true;
              },
              routes ->
                  routes.get(
                      "/ordered",
                      (ignored37, response) -> {
                        events.add("endpoint");
                        response.text("ok");
                      }));

      try (var test = TestServer.start(app)) {
        assertEquals(
            "ok",
            test.send(request -> request.path("/ordered"), HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(List.of("app", "availability", "endpoint"), List.copyOf(events));
      }
    }
  }

  @Test
  void makesAuthenticatedPrincipalAvailableToAvailability() throws Exception {
    var auth = authenticator((request, ignored38) -> request.principal(() -> "alice"));

    try (var app = new Shoostr()) {
      app.authentication(auth)
          .routes()
          .when(
              request -> "alice".equals(request.principal().orElseThrow().getName()),
              routes ->
                  routes.get(
                      "/identity",
                      (ignored39, response) -> response.text("ok"),
                      e -> e.with(auth)));

      try (var test = TestServer.start(app)) {
        assertEquals(
            "ok",
            test.send(request -> request.path("/identity"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void changingConditionsShortCircuitAndRenderTerminal404WithoutFallback() throws Exception {
    var enabled = new AtomicBoolean();
    var innerCalls = new AtomicInteger();
    var groupCalls = new AtomicInteger();

    try (var app = new Shoostr()) {
      app.status(404, (ignored40, response) -> response.text("app missing"));
      app.routes()
          .when(
              enabled::get,
              outer -> {
                groupCalls.incrementAndGet();
                outer.when(
                    request -> {
                      innerCalls.incrementAndGet();
                      return request.header("Accept-Language").filter("fr-FR"::equals).isPresent();
                    },
                    inner ->
                        inner.get(
                            "/users/fixed",
                            (ignored41, response) -> response.text("enabled"),
                            e ->
                                e.lifecycle()
                                    .status(
                                        404,
                                        (ignored42, response) ->
                                            response.text("locally disabled"))));
              });
      app.routes().get("/users/{id}", (ignored43, response) -> response.text("fallback"));

      try (var test = TestServer.start(app)) {
        var denied =
            test.send(
                request -> request.path("/users/fixed"), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, denied.statusCode());
        assertEquals("locally disabled", denied.body());
        assertEquals("no-store", denied.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(0, innerCalls.get());
        assertEquals(
            405,
            test.send(
                    request -> request.path("/users/fixed").method("POST"),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
        enabled.set(true);
        var admitted =
            test.send(
                request -> request.path("/users/fixed").header("Accept-Language", "fr-FR"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals("enabled", admitted.body());
        assertTrue(admitted.headers().firstValue("Cache-Control").isEmpty());
        assertEquals(1, groupCalls.get());
        assertEquals(1, innerCalls.get());
        assertEquals(
            "app missing",
            test.send(request -> request.path("/missing"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void localFailureSkipsAppGatesEndpointAndPostCallbacksAndUsesLocalRenderer() throws Exception {
    var events = new LinkedBlockingQueue<String>();

    try (var app = new Shoostr()) {
      app.beforeRouteHandler((ignored44, ignored45) -> events.add("app"));
      app.afterRouteHandler((ignored46, ignored47) -> events.add("after-app"));
      app.exception(
          IllegalArgumentException.class,
          (ignored48, ignored49, response) -> response.text("app error"));
      app.routes()
          .path(
              "/fail",
              routes -> {
                routes.exception(
                    Exception.class,
                    (ignored50, ignored51, response) -> response.text("local error"));
                routes.get(
                    (ignored52, response) -> response.text("handler"),
                    e -> {
                      e.lifecycle()
                          .beforeRouteHandler(
                              (ignored53, ignored54) -> {
                                throw new IllegalArgumentException("local");
                              });
                      e.lifecycle()
                          .afterRouteHandler((ignored55, ignored56) -> events.add("after-local"));
                    });
              });
      app.routes()
          .get(
              "/fallback",
              (ignored57, ignored58) -> {
                throw new IllegalArgumentException();
              });

      try (var test = TestServer.start(app)) {
        assertEquals(
            "local error",
            test.send(request -> request.path("/fail"), HttpResponse.BodyHandlers.ofString())
                .body());
        assertTrue(events.isEmpty());
        assertEquals(
            "app error",
            test.send(request -> request.path("/fallback"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void observesLocalTerminalCompletionWithoutGlobalObserversAndIsolatesFailures() throws Exception {
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();

    try (var app = new Shoostr()) {
      app.routes()
          .get(
              "/observed",
              (ignored59, response) -> response.text("ok"),
              e -> {
                e.lifecycle()
                    .afterRequest(
                        ignored60 -> {
                          throw new IllegalStateException("observer failed");
                        });
                e.lifecycle().afterRequest(outcomes::add);
              });

      try (var test = TestServer.start(app)) {
        assertEquals(
            200,
            test.send(request -> request.path("/observed"), HttpResponse.BodyHandlers.ofString())
                .statusCode());
        var outcome = outcomes.poll(5, TimeUnit.SECONDS);
        assertNotNull(outcome);
        assertEquals("/observed", outcome.routePattern());
        assertEquals(200, outcome.statusCode());
        assertTrue(outcomes.isEmpty());
      }
    }
  }

  @Test
  void usesAppFlushHooksBeforeMatchingAndAddsLocalHooksAfterMatching() throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var completed = new LinkedBlockingQueue<RequestOutcome>();

    try (var app = new Shoostr()) {
      app.onRequestHeaders(
          (ignored61, response) -> response.startStream("text/plain").write("early"));
      app.beforeResponseFlush((ignored62, ignored63) -> events.add("app-before"));
      app.afterResponseFlush((ignored64, ignored65) -> events.add("app-after"));
      app.afterRequest(completed::add);
      app.routes()
          .get(
              "/early",
              (ignored66, ignored67) -> {},
              e -> {
                e.lifecycle().onRouteMatched((ignored68, ignored69) -> events.add("matched"));
                e.lifecycle()
                    .beforeResponseFlush((ignored70, ignored71) -> events.add("local-before"));
                e.lifecycle()
                    .afterResponseFlush((ignored72, ignored73) -> events.add("local-after"));
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(request -> request.path("/early"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("early", result.body());
        assertNotNull(completed.poll(5, TimeUnit.SECONDS));
        assertEquals(
            List.of(
                "app-before",
                "app-after",
                "matched",
                "local-before",
                "app-before",
                "local-after",
                "app-after"),
            List.copyOf(events));
      }
    }
  }

  @Test
  void installsLocalFlushHooksBeforeRouteMatchedCallbacksCanWrite() throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var completed = new LinkedBlockingQueue<RequestOutcome>();

    try (var app = new Shoostr()) {
      app.beforeResponseFlush((ignored74, ignored75) -> events.add("app-before"));
      app.afterResponseFlush((ignored76, ignored77) -> events.add("app-after"));
      app.afterRequest(completed::add);
      app.routes()
          .get(
              "/events",
              (ignored78, ignored79) -> events.add("endpoint"),
              e -> {
                e.lifecycle()
                    .onRouteMatched(
                        (ignored80, response) -> response.startEventStream().send("early"));
                e.lifecycle()
                    .beforeResponseFlush((ignored81, ignored82) -> events.add("local-before"));
                e.lifecycle()
                    .afterResponseFlush((ignored83, ignored84) -> events.add("local-after"));
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(request -> request.path("/events"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("data: early\n\n", result.body());
        assertNotNull(completed.poll(5, TimeUnit.SECONDS));
        var order = List.copyOf(events);
        assertTrue(order.indexOf("endpoint") >= 4);
        assertEquals(
            List.of("local-before", "app-before", "local-after", "app-after"), order.subList(0, 4));
      }
    }
  }

  @Test
  void localAndAppFlushHooksRepeatInOrderAcrossEventStreamWrites() throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var completed = new LinkedBlockingQueue<RequestOutcome>();

    try (var app = new Shoostr()) {
      app.beforeResponseFlush((ignored85, ignored86) -> events.add("app-before"));
      app.afterResponseFlush((ignored87, ignored88) -> events.add("app-after"));
      app.afterRequest(
          outcome -> {
            events.add("app-terminal");
            completed.add(outcome);
          });
      app.routes()
          .sse(
              "/events",
              (ignored89, response) -> response.startEventStream().send("a").send("b"),
              e -> {
                e.lifecycle()
                    .beforeResponseFlush((ignored90, ignored91) -> events.add("local-before"));
                e.lifecycle()
                    .afterResponseFlush((ignored92, ignored93) -> events.add("local-after"));
                e.lifecycle().afterRequest(ignored94 -> events.add("local-terminal"));
              });

      try (var test = TestServer.start(app)) {
        assertEquals(
            "data: a\n\ndata: b\n\n",
            test.send(request -> request.path("/events"), HttpResponse.BodyHandlers.ofString())
                .body());
        assertNotNull(completed.poll(5, TimeUnit.SECONDS));
        var order = List.copyOf(events);
        assertTrue(order.size() >= 10);
        assertEquals(
            List.of("local-terminal", "app-terminal"),
            order.subList(order.size() - 2, order.size()));
        for (int index = 0; index < order.size() - 2; index += 4) {
          assertEquals(
              List.of("local-before", "app-before", "local-after", "app-after"),
              order.subList(index, index + 4));
        }
      }
    }
  }

  @Test
  void conditionExceptionsUseLocalErrorRenderingAndAreObserved() throws Exception {
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();

    try (var app = new Shoostr()) {
      app.routes()
          .when(
              () -> {
                throw new IllegalArgumentException("flag unavailable");
              },
              routes -> {
                routes.exception(
                    IllegalArgumentException.class,
                    (ignored95, ignored96, response) -> response.text("flag failed"));
                routes.get(
                    "/flag",
                    (ignored97, response) -> response.text("unexpected"),
                    e -> e.lifecycle().afterRequest(outcomes::add));
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(request -> request.path("/flag"), HttpResponse.BodyHandlers.ofString());
        assertEquals(500, result.statusCode());
        assertEquals("flag failed", result.body());
        var outcome = outcomes.poll(5, TimeUnit.SECONDS);
        assertNotNull(outcome);
        assertEquals("/flag", outcome.routePattern());
        assertInstanceOf(IllegalArgumentException.class, outcome.applicationFailure());
      }
    }
  }

  @Test
  @SuppressWarnings("java:S9357") // Extension has no abstract methods and cannot be a lambda.
  void rejectsProviderSideNestedAuthenticationSelectionWithoutPublishingTheEndpoint()
      throws Exception {
    var calls = new AtomicInteger();
    var auth = authenticator((ignored98, ignored99) -> calls.incrementAndGet());
    Extension<Object> nested =
        new Extension<>() {
          @Override
          public Object configure(Extensions endpoint, Object inherited) {
            endpoint.with(auth);
            return new Object();
          }
        };

    try (var app = new Shoostr()) {
      app.authentication(auth).extensions(nested);
      var routes = app.routes();
      assertThrows(
          IllegalStateException.class,
          () -> routes.get("/nested", (ignored100, ignored101) -> {}, e -> e.with(nested)));
      app.routes().get("/nested", (ignored102, response) -> response.text("retry"));

      try (var test = TestServer.start(app)) {
        assertEquals(
            "retry",
            test.send(request -> request.path("/nested"), HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(0, calls.get());
      }
    }
  }

  @Test
  void executesProvidersInFirstSelectionOrderBeforeInheritedDirectHooksWithoutDuplication()
      throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var first = callbackProvider("first", events);
    var second = callbackProvider("second", events);

    try (var app = new Shoostr()) {
      app.extensions(first, second);
      app.beforeRouteHandler((ignored103, ignored104) -> events.add("app"));
      app.routes()
          .path(
              "/outer",
              outer ->
                  outer.path(
                      "/inner",
                      inner ->
                          inner.get(
                              "/endpoint",
                              (ignored105, response) -> {
                                events.add("handler");
                                response.text("ok");
                              },
                              e -> {
                                e.with(first);
                                e.lifecycle()
                                    .beforeRouteHandler(
                                        (ignored106, ignored107) -> events.add("endpoint"));
                              }),
                      e ->
                          e.lifecycle()
                              .beforeRouteHandler((ignored108, ignored109) -> events.add("inner"))),
              e -> {
                e.lifecycle().beforeRouteHandler((ignored110, ignored111) -> events.add("outer"));
                e.with(second);
                assertSame(e.with(first), e.with(first));
              });

      try (var test = TestServer.start(app)) {
        assertEquals(
            "ok",
            test.send(
                    request -> request.path("/outer/inner/endpoint"),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            List.of("second", "first", "outer", "inner", "endpoint", "app", "handler"),
            List.copyOf(events));
      }
    }
  }

  @SuppressWarnings("java:S9357") // Extension has no abstract methods and cannot be a lambda.
  private static Extension<Object> callbackProvider(
      String name, LinkedBlockingQueue<String> events) {
    return new Extension<>() {
      @Override
      public Object configure(Extensions endpoint, Object inherited) {
        endpoint.lifecycle().beforeRouteHandler((ignored112, ignored113) -> events.add(name));
        return new Object();
      }
    };
  }

  private static AuthenticationExtension authenticator(Handler handler) {
    return new AuthenticationExtension() {
      @Override
      public void handle(Request request, Response response) throws Exception {
        handler.handle(request, response);
      }
    };
  }

  private record Capability(String name, List<String> events) implements Extension<Object> {
    private Capability {
      Objects.requireNonNull(name);
      Objects.requireNonNull(events);
    }

    @Override
    public void install(ApplicationCallbacks callbacks) {
      events.add("install-" + name);
    }

    @Override
    public Object configure(Extensions endpoint, Object inherited) {
      return new Object();
    }

    @Override
    public void close() {
      events.add("close-" + name);
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Capability capability && name.equals(capability.name);
    }

    @Override
    public int hashCode() {
      return name.hashCode();
    }
  }
}
