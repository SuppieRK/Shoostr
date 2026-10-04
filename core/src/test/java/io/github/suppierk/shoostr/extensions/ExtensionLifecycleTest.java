package io.github.suppierk.shoostr.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.ApplicationCallbacks;
import io.github.suppierk.shoostr.AuthenticationExtension;
import io.github.suppierk.shoostr.Extension;
import io.github.suppierk.shoostr.Extensions;
import io.github.suppierk.shoostr.Handler;
import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.RequestOutcome;
import io.github.suppierk.shoostr.Response;
import io.github.suppierk.shoostr.Shoostr;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ExtensionLifecycleTest {
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
    var auth = authenticator((_, _) -> {});

    try (var app = new Shoostr()) {
      assertThrows(IllegalArgumentException.class, () -> app.extensions(first, first));
      assertThrows(NullPointerException.class, () -> app.extensions(first, second, null));
      assertThrows(IllegalArgumentException.class, () -> app.extensions(first, auth));
      assertTrue(events.isEmpty());
      assertSame(app, app.extensions(first, second));
      assertEquals(List.of("install-first", "install-second"), events);
      assertThrows(IllegalArgumentException.class, () -> app.extensions(second));
      assertSame(app, app.authentication(auth));
      assertThrows(
          IllegalStateException.class, () -> app.authentication(authenticator((_, _) -> {})));
    }

    assertEquals(List.of("install-first", "install-second", "close-second", "close-first"), events);
  }

  @Test
  void selectsByIdentityAndRejectsMissingInstancesWithoutPublishingRoutes() throws Exception {
    var events = new ArrayList<String>();
    var installed = new Capability("same", events);
    var missing = new Capability("same", events);
    var retained = new AtomicReference<Extensions>();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.extensions(installed);
      assertThrows(
          IllegalArgumentException.class,
          () -> app.routes().get("/route", (_, _) -> {}, e -> e.get(missing)));
      app.routes()
          .get(
              "/route",
              (_, response) -> response.text("ok"),
              e -> {
                assertSame(e.get(installed), e.get(installed));
                retained.set(e);
              });
      assertThrows(
          IllegalStateException.class, () -> retained.get().afterRouteHandler((_, _) -> {}));
      app.start();
      assertThrows(IllegalStateException.class, () -> app.extensions(missing));
      assertEquals("ok", send(client, app, "GET", "/route").body());
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
      var failure = assertThrows(IllegalStateException.class, () -> app.extensions(first, broken));
      assertEquals("setup failed", failure.getMessage());
      assertEquals(1, failure.getSuppressed().length);
      assertThrows(IllegalStateException.class, app::start);
      assertThrows(IllegalStateException.class, () -> app.routes().get("/", (_, _) -> {}));
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

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
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

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.onRouteMatched((_, _) -> events.add("app"));
      app.routes()
          .get(
              "/ordered",
              (_, response) -> response.text("ok"),
              e -> e.onRouteMatched((_, _) -> events.add("local")));
      app.start();
      assertEquals("ok", send(client, app, "GET", "/ordered").body());
      assertEquals(List.of("local", "app"), List.copyOf(events));
    }
  }

  @Test
  void runsDirectLocalAdmissionBeforeAppAdmission() throws Exception {
    var events = new LinkedBlockingQueue<String>();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.beforeRouteHandler((_, _) -> events.add("app"));
      app.routes()
          .get(
              "/ordered",
              (_, response) -> response.text("ok"),
              e -> e.beforeRouteHandler((_, _) -> events.add("local")));
      app.start();
      assertEquals("ok", send(client, app, "GET", "/ordered").body());
      assertEquals(List.of("local", "app"), List.copyOf(events));
    }
  }

  @Test
  void runsLocalAfterHandlerBeforeAppAfterHandler() throws Exception {
    var events = new LinkedBlockingQueue<String>();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.afterRouteHandler((_, _) -> events.add("app"));
      app.routes()
          .get(
              "/ordered",
              (_, response) -> response.text("ok"),
              e -> e.afterRouteHandler((_, _) -> events.add("local")));
      app.start();
      assertEquals("ok", send(client, app, "GET", "/ordered").body());
      assertEquals(List.of("local", "app"), List.copyOf(events));
    }
  }

  @Test
  void runsExtensionAdmissionBeforeDirectLocalAdmission() throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var admission = new AdmissionExtension((_, _) -> events.add("extension"));

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.extensions(admission)
          .routes()
          .get(
              "/ordered",
              (_, response) -> response.text("ok"),
              e -> {
                e.beforeRouteHandler((_, _) -> events.add("local"));
                e.get(admission);
              });
      app.start();
      assertEquals("ok", send(client, app, "GET", "/ordered").body());
      assertEquals(List.of("extension", "local"), List.copyOf(events));
    }
  }

  @Test
  void makesAuthenticatedPrincipalAvailableToExtensionAdmission() throws Exception {
    var auth = authenticator((request, _) -> request.principal(() -> "alice"));
    var admission =
        new AdmissionExtension(
            (request, _) ->
                request.attribute("identity", request.principal().orElseThrow().getName()));

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.authentication(auth)
          .extensions(admission)
          .routes()
          .get(
              "/identity",
              (request, response) ->
                  response.text(request.attribute("identity").orElseThrow().toString()),
              e -> {
                e.get(admission);
                e.get(auth).required();
              });
      app.start();
      assertEquals("alice", send(client, app, "GET", "/identity").body());
    }
  }

  @Test
  void selectingAuthenticationRepeatedlyAuthenticatesOnce() throws Exception {
    var calls = new AtomicInteger();
    var auth = authenticator((_, _) -> calls.incrementAndGet());

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.authentication(auth)
          .routes()
          .path(
              "/",
              routes ->
                  routes.get(
                      "/identity",
                      (_, response) -> response.text("ok"),
                      e -> e.get(auth).required().required()),
              e -> e.get(auth).required());
      app.start();
      assertEquals("ok", send(client, app, "GET", "/identity").body());
      assertEquals(1, calls.get());
    }
  }

  @Test
  void evaluatesAvailabilityAfterAppAdmissionAndBeforeTheEndpoint() throws Exception {
    var events = new LinkedBlockingQueue<String>();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.beforeRouteHandler((_, _) -> events.add("app"));
      app.routes()
          .when(
              () -> {
                events.add("availability");
                return true;
              },
              routes ->
                  routes.get(
                      "/ordered",
                      (_, response) -> {
                        events.add("endpoint");
                        response.text("ok");
                      }));
      app.start();
      assertEquals("ok", send(client, app, "GET", "/ordered").body());
      assertEquals(List.of("app", "availability", "endpoint"), List.copyOf(events));
    }
  }

  @Test
  void makesAuthenticatedPrincipalAvailableToAvailability() throws Exception {
    var auth = authenticator((request, _) -> request.principal(() -> "alice"));

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.authentication(auth)
          .routes()
          .when(
              request -> "alice".equals(request.principal().orElseThrow().getName()),
              routes ->
                  routes.get(
                      "/identity",
                      (_, response) -> response.text("ok"),
                      e -> e.get(auth).required()));
      app.start();
      assertEquals("ok", send(client, app, "GET", "/identity").body());
    }
  }

  @Test
  void changingConditionsShortCircuitAndRenderTerminal404WithoutFallback() throws Exception {
    var enabled = new AtomicBoolean();
    var innerCalls = new AtomicInteger();
    var groupCalls = new AtomicInteger();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.status(404, (_, response) -> response.text("app missing"));
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
                            (_, response) -> response.text("enabled"),
                            e ->
                                e.status(404, (_, response) -> response.text("locally disabled"))));
              });
      app.routes().get("/users/{id}", (_, response) -> response.text("fallback"));
      app.start();
      var denied = send(client, app, "GET", "/users/fixed");
      assertEquals(404, denied.statusCode());
      assertEquals("locally disabled", denied.body());
      assertEquals("no-store", denied.headers().firstValue("Cache-Control").orElseThrow());
      assertEquals(0, innerCalls.get());
      assertEquals(405, send(client, app, "POST", "/users/fixed").statusCode());
      enabled.set(true);
      var admitted =
          client.send(
              HttpRequest.newBuilder(uri(app, "/users/fixed"))
                  .header("Accept-Language", "fr-FR")
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals("enabled", admitted.body());
      assertTrue(admitted.headers().firstValue("Cache-Control").isEmpty());
      assertEquals(1, groupCalls.get());
      assertEquals(1, innerCalls.get());
      assertEquals("app missing", send(client, app, "GET", "/missing").body());
    }
  }

  @Test
  void localFailureSkipsAppGatesEndpointAndPostCallbacksAndUsesLocalRenderer() throws Exception {
    var events = new LinkedBlockingQueue<String>();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.beforeRouteHandler((_, _) -> events.add("app"));
      app.afterRouteHandler((_, _) -> events.add("after-app"));
      app.exception(IllegalArgumentException.class, (_, _, response) -> response.text("app error"));
      app.routes()
          .get(
              "/fail",
              (_, response) -> response.text("handler"),
              e -> {
                e.beforeRouteHandler(
                    (_, _) -> {
                      throw new IllegalArgumentException("local");
                    });
                e.afterRouteHandler((_, _) -> events.add("after-local"));
                e.exception(Exception.class, (_, _, response) -> response.text("local error"));
              });
      app.routes()
          .get(
              "/fallback",
              (_, _) -> {
                throw new IllegalArgumentException();
              });
      app.start();
      assertEquals("local error", send(client, app, "GET", "/fail").body());
      assertTrue(events.isEmpty());
      assertEquals("app error", send(client, app, "GET", "/fallback").body());
    }
  }

  @Test
  void observesLocalTerminalCompletionWithoutGlobalObserversAndIsolatesFailures() throws Exception {
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/observed",
              (_, response) -> response.text("ok"),
              e -> {
                e.afterRequest(
                    _ -> {
                      throw new IllegalStateException("observer failed");
                    });
                e.afterRequest(outcomes::add);
              });
      app.start();
      assertEquals(200, send(client, app, "GET", "/observed").statusCode());
      var outcome = outcomes.poll(5, TimeUnit.SECONDS);
      assertNotNull(outcome);
      assertEquals("/observed", outcome.routePattern());
      assertEquals(200, outcome.statusCode());
      assertTrue(outcomes.isEmpty());
    }
  }

  @Test
  void localAndAppFlushHooksRepeatInOrderAcrossEventStreamWrites() throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var completed = new LinkedBlockingQueue<RequestOutcome>();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.beforeResponseFlush((_, _) -> events.add("app-before"));
      app.afterResponseFlush((_, _) -> events.add("app-after"));
      app.afterRequest(
          outcome -> {
            events.add("app-terminal");
            completed.add(outcome);
          });
      app.routes()
          .sse(
              "/events",
              (_, response) -> response.startEventStream().send("a").send("b"),
              e -> {
                e.beforeResponseFlush((_, _) -> events.add("local-before"));
                e.afterResponseFlush((_, _) -> events.add("local-after"));
                e.afterRequest(_ -> events.add("local-terminal"));
              });
      app.start();
      assertEquals("data: a\n\ndata: b\n\n", send(client, app, "GET", "/events").body());
      assertNotNull(completed.poll(5, TimeUnit.SECONDS));
      var order = List.copyOf(events);
      assertTrue(order.size() >= 10);
      assertEquals(
          List.of("local-terminal", "app-terminal"), order.subList(order.size() - 2, order.size()));
      for (int index = 0; index < order.size() - 2; index += 4) {
        assertEquals(
            List.of("local-before", "app-before", "local-after", "app-after"),
            order.subList(index, index + 4));
      }
    }
  }

  @Test
  void conditionExceptionsUseLocalErrorRenderingAndAreObserved() throws Exception {
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .when(
              () -> {
                throw new IllegalArgumentException("flag unavailable");
              },
              routes ->
                  routes.get(
                      "/flag",
                      (_, response) -> response.text("unexpected"),
                      e -> {
                        e.exception(
                            IllegalArgumentException.class,
                            (_, _, response) -> response.text("flag failed"));
                        e.afterRequest(outcomes::add);
                      }));
      app.start();
      var result = send(client, app, "GET", "/flag");
      assertEquals(500, result.statusCode());
      assertEquals("flag failed", result.body());
      var outcome = outcomes.poll(5, TimeUnit.SECONDS);
      assertNotNull(outcome);
      assertEquals("/flag", outcome.routePattern());
      assertInstanceOf(IllegalArgumentException.class, outcome.applicationFailure());
    }
  }

  @Test
  void rejectsProviderSideNestedAuthenticationSelectionWithoutPublishingTheEndpoint()
      throws Exception {
    var calls = new AtomicInteger();
    var auth = authenticator((_, _) -> calls.incrementAndGet());
    Extension<Object> nested =
        new Extension<>() {
          @Override
          public Object configure(Extensions endpoint, Object inherited) {
            endpoint.get(auth).required();
            return new Object();
          }
        };

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.authentication(auth).extensions(nested);
      assertThrows(
          IllegalStateException.class,
          () -> app.routes().get("/nested", (_, _) -> {}, e -> e.get(nested)));
      app.routes().get("/nested", (_, response) -> response.text("retry"));
      app.start();
      assertEquals("retry", send(client, app, "GET", "/nested").body());
      assertEquals(0, calls.get());
    }
  }

  @Test
  void executesProvidersInFirstSelectionOrderBeforeInheritedDirectHooksWithoutDuplication()
      throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var first = callbackProvider("first", events);
    var second = callbackProvider("second", events);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.extensions(first, second);
      app.beforeRouteHandler((_, _) -> events.add("app"));
      app.routes()
          .path(
              "/outer",
              outer ->
                  outer.path(
                      "/inner",
                      inner ->
                          inner.get(
                              "/endpoint",
                              (_, response) -> {
                                events.add("handler");
                                response.text("ok");
                              },
                              e -> {
                                e.get(first);
                                e.beforeRouteHandler((_, _) -> events.add("endpoint"));
                              }),
                      e -> e.beforeRouteHandler((_, _) -> events.add("inner"))),
              e -> {
                e.beforeRouteHandler((_, _) -> events.add("outer"));
                e.get(second);
                assertSame(e.get(first), e.get(first));
              });
      app.start();
      assertEquals("ok", send(client, app, "GET", "/outer/inner/endpoint").body());
      assertEquals(
          List.of("second", "first", "outer", "inner", "endpoint", "app", "handler"),
          List.copyOf(events));
    }
  }

  private static Extension<Object> callbackProvider(
      String name, LinkedBlockingQueue<String> events) {
    return new Extension<>() {
      @Override
      public Object configure(Extensions endpoint, Object inherited) {
        endpoint.beforeRouteHandler((_, _) -> events.add(name));
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

  private static URI uri(Shoostr app, String path) {
    return URI.create("http://127.0.0.1:" + app.port() + path);
  }

  private static HttpResponse<String> send(
      HttpClient client, Shoostr app, String method, String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri(app, path))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());
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
