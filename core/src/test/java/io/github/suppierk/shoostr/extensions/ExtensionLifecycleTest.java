package io.github.suppierk.shoostr.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.ApplicationCallbacks;
import io.github.suppierk.shoostr.ApplicationExtension;
import io.github.suppierk.shoostr.AuthenticationExtension;
import io.github.suppierk.shoostr.Cors;
import io.github.suppierk.shoostr.CorsPolicy;
import io.github.suppierk.shoostr.Csrf;
import io.github.suppierk.shoostr.Handler;
import io.github.suppierk.shoostr.Lifecycle;
import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.RequestOutcome;
import io.github.suppierk.shoostr.Response;
import io.github.suppierk.shoostr.RouteExtension;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.exceptions.UnauthorizedException;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
  @SuppressWarnings("java:S9357") // Extension has no abstract methods and cannot be a lambda.
  private static TestExtension<Object> callbackProvider(
      String name, LinkedBlockingQueue<String> events) {
    return new TestExtension<>() {
      @Override
      public Object configure(Lifecycle endpoint, Object inherited) {
        endpoint.beforeRouteHandler((ignoredRequest, ignoredResponse) -> events.add(name));

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

  @ParameterizedTest
  @ValueSource(strings = {"csrf", "cors"})
  void exclusionDoesNotAllowReplacingAnInheritedSecurityOwner(String policy) throws Exception {
    ApplicationExtension<?> first =
        "csrf".equals(policy)
            ? new Csrf()
            : new Cors(CorsPolicy.builder().origins("https://first.example").build());
    RouteExtension<?> second =
        "csrf".equals(policy)
            ? new Csrf()
            : new Cors(CorsPolicy.builder().origins("https://second.example").build());

    {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close) {
        app.sessions().withExtension(first);
        var routes = app.routes();

        assertThrows(
            IllegalArgumentException.class,
            () ->
                routes.get(
                    "/value",
                    (request, response) -> response.text("unexpected"),
                    lifecycle -> {
                      lifecycle.withoutExtension(first);
                      lifecycle.withExtension(second);
                    }));

        app.routes().get("/value", (request, response) -> response.text("kept"));

        try (var test = TestServer.start(app)) {
          var response =
              test.send(
                  testRequest ->
                      testRequest.path("/value").header("Origin", "https://first.example"),
                  HttpResponse.BodyHandlers.ofString());

          assertEquals(200, response.statusCode());
          assertEquals("kept", response.body());
        }
      }
    }
  }

  @Test
  void excludingInheritedAuthenticationDoesNotPermitReplacingItsOwner() throws Exception {
    var first = authenticator((request, response) -> request.principal(() -> "first"));
    var second = authenticator((request, response) -> request.principal(() -> "second"));

    {
      var app = new Shoostr();

      try (Closeable appCleanup5 = app::close) {
        app.withExtension(first);
        var routes = app.routes();

        assertThrows(
            IllegalArgumentException.class,
            () ->
                routes.get(
                    "/identity",
                    (request, response) -> response.text("unexpected"),
                    extensions -> {
                      extensions.withoutExtension(first);
                      extensions.withExtension(second);
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
  }

  @Test
  void selectingAuthenticationThroughWithProtectsApplicationDefaults() throws Exception {
    var authentication = bearerAuthentication();

    {
      var app = new Shoostr();

      try (Closeable appCleanup6 = app::close) {
        app.withExtension(authentication);
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
  }

  private AuthenticationExtension bearerAuthentication() {
    return authenticator(
        (request, response) -> {
          if (!"Bearer valid".equals(request.header("Authorization").orElse(null))) {
            throw new UnauthorizedException("Bearer");
          }

          request.principal(() -> "alice");
        });
  }

  @Test
  void excludesOnlyTheSelectedExtensionFromAnEndpoint() throws Exception {
    var admission =
        new AdmissionExtension(
            (request, response) -> response.setHeader("X-Policy", "application"));

    {
      var app = new Shoostr();

      try (Closeable appCleanup7 = app::close) {
        app.withExtension(admission)
            .beforeRouteHandler((request, response) -> response.setHeader("X-App", "retained"));
        app.routes()
            .get(
                "/excluded",
                (request, response) -> response.text("excluded"),
                extensions -> {
                  extensions.withoutExtension(admission);
                  extensions.beforeRouteHandler(
                      (request, response) -> response.setHeader("X-Local", "retained"));
                })
            .get("/included", (request, response) -> response.text("included"));

        try (var test = TestServer.start(app)) {
          var excluded =
              test.send(request -> request.path("/excluded"), HttpResponse.BodyHandlers.ofString());
          final var included =
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
  }

  @Test
  void appliesApplicationExtensionDefaultsToIndependentlyRegisteredEndpoints() throws Exception {
    var admission =
        new AdmissionExtension(
            (request, response) -> response.setHeader("X-Policy", "application"));

    {
      var app = new Shoostr();

      try (Closeable appCleanup8 = app::close) {
        app.withExtension(admission);
        app.routes().get("/first", (request, response) -> response.text("first"));
        app.routes(
            routes ->
                routes.path(
                    "/nested",
                    child -> child.get("/second", (request, response) -> response.text("second"))));

        try (var test = TestServer.start(app)) {
          var first =
              test.send(request -> request.path("/first"), HttpResponse.BodyHandlers.ofString());
          final var second =
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
  }

  @Test
  void selectsEqualInstancesIndependentlyAndFreezesTheirCollectors() throws Exception {
    var events = new ArrayList<String>();
    var installed = new Capability("same", events);
    var missing = new Capability("same", events);
    var retained = new AtomicReference<Lifecycle>();
    var retainedLifecycle = new AtomicReference<Lifecycle>();

    {
      var app = new Shoostr();

      try (Closeable appCleanup11 = app::close) {

        var routes = app.routes();
        routes.get(
            "/other",
            (ignoredRequest, ignoredResponse) -> {},
            routeLifecycle -> routeLifecycle.withExtension(missing));
        app.routes()
            .get(
                "/route",
                (ignoredRequest, response) -> response.text("ok"),
                routeLifecycle -> {
                  assertSame(
                      routeLifecycle.withExtension(installed),
                      routeLifecycle.withExtension(installed));

                  retained.set(routeLifecycle);
                  retainedLifecycle.set(routeLifecycle);
                });
        var configuration = retained.get();
        var lifecycle = retainedLifecycle.get();

        assertThrows(
            IllegalStateException.class, () -> configuration.onRoute((method, path) -> {}));
        assertThrows(
            IllegalStateException.class,
            () -> lifecycle.afterRouteHandler((ignoredRequest, ignoredResponse) -> {}));

        try (var test = TestServer.start(app)) {
          assertThrows(IllegalStateException.class, () -> app.withExtension(missing));
          assertEquals(
              "ok",
              test.send(request -> request.path("/route"), HttpResponse.BodyHandlers.ofString())
                  .body());
        }
      }
    }
  }

  @Test
  void eagerProviderFailureClosesEveryClaimedResourceAndPreventsRestart() throws Exception {
    var events = new ArrayList<String>();
    var first = new Capability("first", events);
    ApplicationExtension<Object> broken = setupFailingProvider(events);

    {
      var app = new Shoostr();

      try (Closeable appCleanup12 = app::close) {
        final var routes = app.routes();

        var failure =
            assertThrows(
                IllegalStateException.class, () -> app.withExtension(first).withExtension(broken));
        assertEquals("setup failed", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertThrows(IllegalStateException.class, app::start);
        assertThrows(
            IllegalStateException.class,
            () -> routes.get("/", (ignoredRequest, ignoredResponse) -> {}));
      }
    }

    assertEquals(List.of("install-first", "install-broken", "close-broken", "close-first"), events);
  }

  private ApplicationExtension<Object> setupFailingProvider(List<String> events) {
    return new ApplicationExtension<Object>() {
      @Override
      public Object configure(Lifecycle lifecycle, Object inherited) {
        return this;
      }

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
  }

  @Test
  void finalizationFailureClosesTheAppBeforeTraffic() throws Exception {
    var closed = new AtomicBoolean();
    ApplicationExtension<Object> broken = finalizationFailingProvider(closed);

    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup13 = app::close) {
        app.withExtension(broken);

        assertThrows(IllegalStateException.class, app::start);
        assertTrue(closed.get());
        assertThrows(IllegalStateException.class, app::port);
        assertThrows(IllegalStateException.class, app::start);
      }
    }
  }

  private ApplicationExtension<Object> finalizationFailingProvider(AtomicBoolean closed) {
    return new ApplicationExtension<Object>() {
      @Override
      public Object configure(Lifecycle lifecycle, Object inherited) {
        return this;
      }

      @Override
      public void beforeStart() {
        throw new IllegalStateException("prepare failed");
      }

      @Override
      public void close() {
        closed.set(true);
      }
    };
  }

  @Test
  void runsLocalMatchBeforeAppMatch() throws Exception {
    var events = new LinkedBlockingQueue<String>();

    {
      var app = new Shoostr();

      try (Closeable appCleanup14 = app::close) {
        app.onRouteMatched((ignoredRequest, ignoredResponse) -> events.add("app"));
        app.routes()
            .get(
                "/ordered",
                (ignoredRequest, response) -> response.text("ok"),
                lifecycle ->
                    lifecycle.onRouteMatched(
                        (ignoredRequest, ignoredResponse) -> events.add("local")));

        try (var test = TestServer.start(app)) {
          assertEquals(
              "ok",
              test.send(request -> request.path("/ordered"), HttpResponse.BodyHandlers.ofString())
                  .body());
          assertEquals(List.of("local", "app"), List.copyOf(events));
        }
      }
    }
  }

  @Test
  void runsDirectLocalAdmissionBeforeAppAdmission() throws Exception {
    var events = new LinkedBlockingQueue<String>();

    {
      var app = new Shoostr();

      try (Closeable appCleanup15 = app::close) {
        app.beforeRouteHandler((ignoredRequest, ignoredResponse) -> events.add("app"));
        app.routes()
            .get(
                "/ordered",
                (ignoredRequest, response) -> response.text("ok"),
                lifecycle ->
                    lifecycle.beforeRouteHandler(
                        (ignoredRequest, ignoredResponse) -> events.add("local")));

        try (var test = TestServer.start(app)) {
          assertEquals(
              "ok",
              test.send(request -> request.path("/ordered"), HttpResponse.BodyHandlers.ofString())
                  .body());
          assertEquals(List.of("local", "app"), List.copyOf(events));
        }
      }
    }
  }

  @Test
  void runsLocalAfterHandlerBeforeAppAfterHandler() throws Exception {
    var events = new LinkedBlockingQueue<String>();

    {
      var app = new Shoostr();

      try (Closeable appCleanup16 = app::close) {
        app.afterRouteHandler((ignoredRequest, ignoredResponse) -> events.add("app"));
        app.routes()
            .get(
                "/ordered",
                (ignoredRequest, response) -> response.text("ok"),
                lifecycle ->
                    lifecycle.afterRouteHandler(
                        (ignoredRequest, ignoredResponse) -> events.add("local")));

        try (var test = TestServer.start(app)) {
          assertEquals(
              "ok",
              test.send(request -> request.path("/ordered"), HttpResponse.BodyHandlers.ofString())
                  .body());
          assertEquals(List.of("local", "app"), List.copyOf(events));
        }
      }
    }
  }

  @Test
  void runsExtensionAdmissionBeforeDirectLocalAdmission() throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var admission =
        new AdmissionExtension((ignoredRequest, ignoredResponse) -> events.add("extension"));

    {
      var app = new Shoostr();

      try (Closeable appCleanup17 = app::close) {
        app.routes()
            .get(
                "/ordered",
                (ignoredRequest, response) -> response.text("ok"),
                lifecycle -> {
                  lifecycle.beforeRouteHandler(
                      (ignoredRequest, ignoredResponse) -> events.add("local"));
                  lifecycle.withExtension(admission);
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
  }

  @Test
  void makesAuthenticatedPrincipalAvailableToExtensionAdmission() throws Exception {
    var auth = authenticator((request, ignoredResponse) -> request.principal(() -> "alice"));
    var admission =
        new AdmissionExtension(
            (request, ignoredResponse) ->
                request.attribute("identity", request.principal().orElseThrow().getName()));

    {
      var app = new Shoostr();

      try (Closeable appCleanup18 = app::close) {
        app.routes()
            .get(
                "/identity",
                (request, response) ->
                    response.text(request.attribute("identity").orElseThrow().toString()),
                lifecycle -> {
                  lifecycle.withExtension(admission);
                  lifecycle.withExtension(auth);
                });

        try (var test = TestServer.start(app)) {
          assertEquals(
              "alice",
              test.send(request -> request.path("/identity"), HttpResponse.BodyHandlers.ofString())
                  .body());
        }
      }
    }
  }

  @Test
  void selectingAuthenticationRepeatedlyAuthenticatesOnce() throws Exception {
    var calls = new AtomicInteger();
    var auth = authenticator((ignoredRequest, ignoredResponse) -> calls.incrementAndGet());

    {
      var app = new Shoostr();

      try (Closeable appCleanup19 = app::close) {
        app.routes()
            .path(
                "/",
                routes ->
                    routes.get(
                        "/identity",
                        (ignoredRequest, response) -> response.text("ok"),
                        lifecycle -> lifecycle.withExtension(auth)),
                lifecycle -> lifecycle.withExtension(auth));

        try (var test = TestServer.start(app)) {
          assertEquals(
              "ok",
              test.send(request -> request.path("/identity"), HttpResponse.BodyHandlers.ofString())
                  .body());
          assertEquals(1, calls.get());
        }
      }
    }
  }

  @Test
  void evaluatesAvailabilityAfterAppAdmissionAndBeforeTheEndpoint() throws Exception {
    var events = new LinkedBlockingQueue<String>();

    {
      var app = new Shoostr();

      try (Closeable appCleanup20 = app::close) {
        app.beforeRouteHandler((ignoredRequest, ignoredResponse) -> events.add("app"));
        app.routes()
            .when(
                () -> {
                  events.add("availability");

                  return true;
                },
                routes ->
                    routes.get(
                        "/ordered",
                        (ignoredRequest, response) -> {
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
  }

  @Test
  void makesAuthenticatedPrincipalAvailableToAvailability() throws Exception {
    var auth = authenticator((request, ignoredResponse) -> request.principal(() -> "alice"));

    {
      var app = new Shoostr();

      try (Closeable appCleanup21 = app::close) {
        app.routes()
            .when(
                request -> "alice".equals(request.principal().orElseThrow().getName()),
                routes ->
                    routes.get(
                        "/identity",
                        (ignoredRequest, response) -> response.text("ok"),
                        lifecycle -> lifecycle.withExtension(auth)));

        try (var test = TestServer.start(app)) {
          assertEquals(
              "ok",
              test.send(request -> request.path("/identity"), HttpResponse.BodyHandlers.ofString())
                  .body());
        }
      }
    }
  }

  @Test
  void changingConditionsShortCircuitAndRenderTerminal404WithoutFallback() throws Exception {
    var enabled = new AtomicBoolean();
    var innerCalls = new AtomicInteger();
    var groupCalls = new AtomicInteger();

    {
      var app = new Shoostr();

      try (Closeable appCleanup22 = app::close) {
        app.status(404, (ignoredRequest, response) -> response.text("app missing"));
        app.routes()
            .when(
                enabled::get,
                outer -> {
                  groupCalls.incrementAndGet();
                  outer.when(
                      request -> {
                        innerCalls.incrementAndGet();

                        return request
                            .header("Accept-Language")
                            .filter("fr-FR"::equals)
                            .isPresent();
                      },
                      inner ->
                          inner.get(
                              "/users/fixed",
                              (ignoredRequest, response) -> response.text("enabled"),
                              lifecycle ->
                                  lifecycle.status(
                                      404,
                                      (ignoredRequest, response) ->
                                          response.text("locally disabled"))));
                });
        app.routes().get("/users/{id}", (ignoredRequest, response) -> response.text("fallback"));

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
  }

  @Test
  void localFailureSkipsAppGatesEndpointAndPostCallbacksAndUsesLocalRenderer() throws Exception {
    var events = new LinkedBlockingQueue<String>();

    {
      var app = new Shoostr();

      try (Closeable appCleanup23 = app::close) {
        app.beforeRouteHandler((ignoredRequest, ignoredResponse) -> events.add("app"));
        app.afterRouteHandler((ignoredRequest, ignoredResponse) -> events.add("after-app"));
        app.exception(
            IllegalArgumentException.class,
            (ignoredFailure, ignoredRequest, response) -> response.text("app error"));
        app.routes()
            .path(
                "/fail",
                routes -> {
                  routes.exception(
                      Exception.class,
                      (ignoredFailure, ignoredRequest, response) -> response.text("local error"));
                  routes.get(
                      (ignoredRequest, response) -> response.text("handler"),
                      lifecycle -> {
                        lifecycle.beforeRouteHandler(
                            (ignoredRequest, ignoredResponse) -> {
                              throw new IllegalArgumentException("local");
                            });
                        lifecycle.afterRouteHandler(
                            (ignoredRequest, ignoredResponse) -> events.add("after-local"));
                      });
                });
        app.routes()
            .get(
                "/fallback",
                (ignoredRequest, ignoredResponse) -> {
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
  }

  @Test
  void observesLocalTerminalCompletionWithoutGlobalObserversAndIsolatesFailures() throws Exception {
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();

    {
      var app = new Shoostr();

      try (Closeable appCleanup24 = app::close) {
        app.routes()
            .get(
                "/observed",
                (ignoredRequest, response) -> response.text("ok"),
                lifecycle -> {
                  lifecycle.afterRequest(
                      ignoredRequestOutcome -> {
                        throw new IllegalStateException("observer failed");
                      });
                  lifecycle.afterRequest(outcomes::add);
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
  }

  @Test
  void usesAppFlushHooksBeforeMatchingAndAddsLocalHooksAfterMatching() throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var completed = new LinkedBlockingQueue<RequestOutcome>();

    {
      var app = new Shoostr();

      try (Closeable appCleanup25 = app::close) {
        app.onRequestHeaders(
            (ignoredRequest, response) -> response.startStream("text/plain").write("early"));
        app.beforeResponseFlush((ignoredRequest, ignoredResponse) -> events.add("app-before"));
        app.afterResponseFlush((ignoredRequest, ignoredResponse) -> events.add("app-after"));
        app.afterRequest(completed::add);
        app.routes()
            .get(
                "/early",
                (ignoredRequest, ignoredResponse) -> {},
                lifecycle -> {
                  lifecycle.onRouteMatched(
                      (ignoredRequest, ignoredResponse) -> events.add("matched"));
                  lifecycle.beforeResponseFlush(
                      (ignoredRequest, ignoredResponse) -> events.add("local-before"));
                  lifecycle.afterResponseFlush(
                      (ignoredRequest, ignoredResponse) -> events.add("local-after"));
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
  }

  @Test
  void installsLocalFlushHooksBeforeRouteMatchedCallbacksCanWrite() throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var completed = new LinkedBlockingQueue<RequestOutcome>();

    {
      var app = new Shoostr();

      try (Closeable appCleanup26 = app::close) {
        app.beforeResponseFlush((ignoredRequest, ignoredResponse) -> events.add("app-before"));
        app.afterResponseFlush((ignoredRequest, ignoredResponse) -> events.add("app-after"));
        app.afterRequest(completed::add);
        app.routes()
            .get(
                "/events",
                (ignoredRequest, ignoredResponse) -> events.add("endpoint"),
                lifecycle -> {
                  lifecycle.onRouteMatched(
                      (ignoredRequest, response) -> response.startEventStream().send("early"));
                  lifecycle.beforeResponseFlush(
                      (ignoredRequest, ignoredResponse) -> events.add("local-before"));
                  lifecycle.afterResponseFlush(
                      (ignoredRequest, ignoredResponse) -> events.add("local-after"));
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
              List.of("local-before", "app-before", "local-after", "app-after"),
              order.subList(0, 4));
        }
      }
    }
  }

  @Test
  void localAndAppFlushHooksRepeatInOrderAcrossEventStreamWrites() throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var completed = new LinkedBlockingQueue<RequestOutcome>();

    {
      var app = new Shoostr();

      try (Closeable appCleanup27 = app::close) {
        app.beforeResponseFlush((ignoredRequest, ignoredResponse) -> events.add("app-before"));
        app.afterResponseFlush((ignoredRequest, ignoredResponse) -> events.add("app-after"));
        app.afterRequest(
            outcome -> {
              events.add("app-terminal");
              completed.add(outcome);
            });
        app.routes()
            .sse(
                "/events",
                (ignoredRequest, response) -> response.startEventStream().send("a").send("b"),
                lifecycle -> {
                  lifecycle.beforeResponseFlush(
                      (ignoredRequest, ignoredResponse) -> events.add("local-before"));
                  lifecycle.afterResponseFlush(
                      (ignoredRequest, ignoredResponse) -> events.add("local-after"));
                  lifecycle.afterRequest(ignoredRequestOutcome -> events.add("local-terminal"));
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
  }

  @Test
  void conditionExceptionsUseLocalErrorRenderingAndAreObserved() throws Exception {
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();

    {
      var app = new Shoostr();

      try (Closeable appCleanup28 = app::close) {
        app.routes()
            .when(
                () -> {
                  throw new IllegalArgumentException("flag unavailable");
                },
                routes -> {
                  routes.exception(
                      IllegalArgumentException.class,
                      (ignoredFailure, ignoredRequest, response) -> response.text("flag failed"));
                  routes.get(
                      "/flag",
                      (ignoredRequest, response) -> response.text("unexpected"),
                      lifecycle -> lifecycle.afterRequest(outcomes::add));
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
  }

  @Test
  @SuppressWarnings("java:S9357")
  // Extension has no abstract methods and cannot be a lambda.
  void rejectsProviderSideNestedAuthenticationSelectionWithoutPublishingTheEndpoint()
      throws Exception {
    var calls = new AtomicInteger();
    var auth = authenticator((ignoredRequest, ignoredResponse) -> calls.incrementAndGet());
    TestExtension<Object> nested = nestedAuthenticationProvider(auth);

    {
      var app = new Shoostr();

      try (Closeable appCleanup29 = app::close) {

        var routes = app.routes();

        assertThrows(
            IllegalStateException.class,
            () ->
                routes.get(
                    "/nested",
                    (ignoredRequest, ignoredResponse) -> {},
                    lifecycle -> lifecycle.withExtension(nested)));

        app.routes().get("/nested", (ignoredRequest, response) -> response.text("retry"));

        try (var test = TestServer.start(app)) {
          assertEquals(
              "retry",
              test.send(request -> request.path("/nested"), HttpResponse.BodyHandlers.ofString())
                  .body());
          assertEquals(0, calls.get());
        }
      }
    }
  }

  private TestExtension<Object> nestedAuthenticationProvider(AuthenticationExtension auth) {
    return new TestExtension<>() {
      @Override
      public Object configure(Lifecycle endpoint, Object inherited) {
        endpoint.withExtension(auth);

        return new Object();
      }
    };
  }

  @Test
  void executesProvidersInFirstSelectionOrderBeforeInheritedDirectHooksWithoutDuplication()
      throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var first = callbackProvider("first", events);
    var second = callbackProvider("second", events);

    {
      var app = new Shoostr();

      try (Closeable appCleanup30 = app::close) {

        app.beforeRouteHandler((ignoredRequest, ignoredResponse) -> events.add("app"));
        app.routes()
            .path(
                "/outer",
                outer ->
                    outer.path(
                        "/inner",
                        inner ->
                            inner.get(
                                "/endpoint",
                                (ignoredRequest, response) -> {
                                  events.add("handler");
                                  response.text("ok");
                                },
                                lifecycle -> {
                                  lifecycle.withExtension(first);
                                  lifecycle.beforeRouteHandler(
                                      (ignoredRequest, ignoredResponse) -> events.add("endpoint"));
                                }),
                        lifecycle ->
                            lifecycle.beforeRouteHandler(
                                (ignoredRequest, ignoredResponse) -> events.add("inner"))),
                lifecycle -> {
                  lifecycle.beforeRouteHandler(
                      (ignoredRequest, ignoredResponse) -> events.add("outer"));
                  lifecycle.withExtension(second);

                  assertSame(lifecycle.withExtension(first), lifecycle.withExtension(first));
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
  }

  private record Capability(String name, List<String> events) implements TestExtension<Object> {
    private Capability {
      Objects.requireNonNull(name);
      Objects.requireNonNull(events);
    }

    @Override
    public void install(ApplicationCallbacks callbacks) {
      events.add("install-" + name);
    }

    @Override
    public Object configure(Lifecycle endpoint, Object inherited) {
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
