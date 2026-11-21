package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.http.exceptions.ForbiddenException;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LifecycleRegistrationTest {
  @Test
  void startsItsOnlyObservationBeforeTheHandlerAndClosesBeforeTerminalReporting() throws Exception {
    var starts = new AtomicInteger();
    var closes = new AtomicInteger();
    var completions = new AtomicInteger();
    var closesAtCompletion = new CompletableFuture<Integer>();
    var factory = observationFactory(starts, closes, completions, closesAtCompletion);
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      var terminal = new CompletableFuture<Void>();
      app.afterRequest(outcome -> terminal.complete(null));
      app.routes()
          .get(
              "/observed",
              (request, response) -> response.text(starts.toString()),
              lifecycle -> lifecycle.observe(factory));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(request -> request.path("/observed"), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, result.statusCode());
        assertEquals("1", result.body());
        terminal.get(3, TimeUnit.SECONDS);
        assertEquals(1, starts.get());
        assertEquals(1, closes.get());
        assertEquals(1, completions.get());
        assertEquals(1, closesAtCompletion.get(3, TimeUnit.SECONDS));
      }
    }
  }

  private Function<Request, RequestObservation> observationFactory(
      AtomicInteger starts,
      AtomicInteger closes,
      AtomicInteger completions,
      CompletableFuture<Integer> closesAtCompletion) {
    return request -> {
      starts.incrementAndGet();
      return new RequestObservation() {
        @Override
        public void close() {
          closes.incrementAndGet();
        }

        @Override
        public void complete(RequestOutcome ignoredOutcome) {
          closesAtCompletion.complete(closes.get());
          completions.incrementAndGet();
        }
      };
    };
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void retriesFailedDefaultsWithoutReinstallingOrClosingOwnedInfrastructure(boolean fatal)
      throws Exception {
    var installs = new AtomicInteger();
    var closes = new AtomicInteger();
    var calls = new AtomicInteger();
    var collectors = new ArrayList<Lifecycle>();
    var provider = settingsProvider(installs, closes, calls, collectors);
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.withExtension(bearerAuthentication());
      var failure = configurationFailure(fatal);
      assertSame(
          failure,
          assertThrows(
              failure.getClass(),
              () ->
                  app.withExtension(
                      provider,
                      settings -> {
                        settings.append("discarded");
                        throw configurationExceptionToThrow(failure);
                      })));
      assertEquals(1, installs.get());
      assertEquals(0, closes.get());

      app.withExtension(provider, settings -> settings.append("corrected"));
      assertEquals(1, installs.get());
      app.routes().get("/retried", (request, response) -> response.text("ready"));
      for (var collector : collectors) {
        assertThrows(IllegalStateException.class, () -> collector.afterRequest(outcome -> {}));
      }

      try (var test = TestServer.start(app)) {
        assertEquals(403, test.send(request -> request.path("/retried")).statusCode());
        var allowed =
            test.send(
                request -> request.path("/retried").header("Authorization", "Bearer secret"),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, allowed.statusCode());
        assertEquals("ready", allowed.body());
        assertEquals(List.of("corrected"), allowed.headers().allValues("X-Settings"));
        assertEquals(1, calls.get());
        assertEquals(0, closes.get());
      }

      assertEquals(1, closes.get());
    }

    assertEquals(1, closes.get());
  }

  private ApplicationExtension<StringBuilder> settingsProvider(
      AtomicInteger installs,
      AtomicInteger closes,
      AtomicInteger calls,
      List<Lifecycle> collectors) {
    return new ApplicationExtension<>() {
      @Override
      public void install(ApplicationCallbacks ignoredCallbacks) {
        installs.incrementAndGet();
      }

      @Override
      public StringBuilder configure(Lifecycle lifecycle, StringBuilder inherited) {
        collectors.add(lifecycle);
        var settings = new StringBuilder(inherited == null ? "" : inherited.toString());
        lifecycle.beforeRouteHandler((request, response) -> {
          calls.incrementAndGet();
          response.setHeader("X-Settings", settings.toString());
        });
        return settings;
      }

      @Override
      public void close() {
        closes.incrementAndGet();
      }
    };
  }

  @Test
  void chainsLocalCallbacksOnTheSameRegistrationScope() throws Exception {
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      Handler callback = (request, response) -> {};
      app.routes()
          .get(
              "/chain",
              (request, response) -> response.text("ready"),
              lifecycle ->
                  assertSame(
                      lifecycle,
                      lifecycle
                          .onRouteMatched(callback)
                          .beforeRouteHandler(callback)
                          .afterRouteHandler(callback)
                          .beforeResponseFlush(callback)
                          .afterResponseFlush(callback)
                          .afterRequest(outcome -> {})
                          .observe(request -> outcome -> {})));

      try (var test = TestServer.start(app)) {
        assertEquals(
            "ready",
            test.send(request -> request.path("/chain"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void preservesEarlierAuthenticationDefaultsAfterLaterConfigurationFails(boolean fatal)
      throws Exception {
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.withExtension(bearerAuthentication());
      var failed = new Csrf();
      var failure = configurationFailure(fatal);
      assertSame(
          failure,
          assertThrows(
              failure.getClass(),
              () ->
                  app.withExtension(
                      failed,
                      configuration -> {
                        throw configurationExceptionToThrow(failure);
                      })));
      app.routes().get("/recovered", (request, response) -> response.text("ready"));

      try (var test = TestServer.start(app)) {
        assertEquals(403, test.send(request -> request.path("/recovered")).statusCode());
        var allowed =
            test.send(
                request -> request.path("/recovered").header("Authorization", "Bearer secret"),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, allowed.statusCode());
        assertEquals("ready", allowed.body());
      }
    }
  }

  private AuthenticationExtension bearerAuthentication() {
    return new AuthenticationExtension() {
      @Override
      public void handle(Request request, Response response) {
        if (request.header("Authorization").filter("Bearer secret"::equals).isEmpty()) {
          throw new ForbiddenException();
        }
      }
    };
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void freezesFailedProviderConfigurationAndAllowsTheSameRouteToBeRegistered(boolean fatal)
      throws Exception {
    var retained = new AtomicReference<Lifecycle>();
    var failure = configurationFailure(fatal);
    var provider = failingConfigurationProvider(retained, failure);
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      assertSame(
          failure,
          assertThrows(
              failure.getClass(),
              () ->
                  app.routes()
                      .get(
                          "/recovered",
                          (request, response) -> response.text("discarded"),
                          lifecycle -> lifecycle.withExtension(provider))));
      var closedScope = retained.get();

      assertThrows(IllegalStateException.class, () -> closedScope.afterRequest(outcome -> {}));

      app.routes().get("/recovered", (request, response) -> response.text("ready"));

      try (var test = TestServer.start(app)) {
        assertEquals(
            "ready",
            test.send(request -> request.path("/recovered"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  private RouteExtension<Object> failingConfigurationProvider(
      AtomicReference<Lifecycle> retained, Throwable failure) {
    return (lifecycle, ignoredInherited) -> {
      retained.set(lifecycle);
      throw configurationExceptionToThrow(failure);
    };
  }

  private Throwable configurationFailure(boolean fatal) {
    return fatal
        ? new LinkageError("configuration failed")
        : new IllegalStateException("configuration failed");
  }

  private RuntimeException configurationExceptionToThrow(Throwable failure) {
    if (failure instanceof Error error) {
      throw error;
    }

    return (RuntimeException) failure;
  }

  @Test
  void excludesOnlyOwnedProviderInstancesAndReturnsItsScope() throws Exception {
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      var owned = new Cors(new CorsPolicy());
      var foreign = new Cors(new CorsPolicy());
      assertEquals(owned, foreign);
      app.routes()
          .get(
              "/owned",
              (request, response) -> response.text("ready"),
              lifecycle -> {
                lifecycle.withExtension(owned);
                assertThrows(
                    IllegalArgumentException.class, () -> lifecycle.withoutExtension(foreign));
                assertSame(lifecycle, lifecycle.withoutExtension(owned));
              });

      try (var test = TestServer.start(app)) {
        assertEquals(
            "ready",
            test.send(request -> request.path("/owned"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void validatesStatusBoundsAndReturnsItsRegistrationCollector() throws Exception {
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      var retainedScope = new AtomicReference<Lifecycle>();
      Handler renderer = (request, response) -> response.text("local status");
      app.routes()
          .get(
              "/status",
              (request, response) -> response.text("ready"),
              lifecycle -> {
                retainedScope.set(lifecycle);
                assertSame(lifecycle, lifecycle.status(100, renderer));
                assertSame(lifecycle, lifecycle.status(599, renderer));
                assertThrows(IllegalArgumentException.class, () -> lifecycle.status(99, renderer));
                assertThrows(IllegalArgumentException.class, () -> lifecycle.status(600, renderer));
              });
      var frozenScope = retainedScope.get();

      assertThrows(IllegalStateException.class, () -> frozenScope.status(200, renderer));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(request -> request.path("/status"), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, result.statusCode());
        assertEquals("ready", result.body());
      }
    }
  }
}
