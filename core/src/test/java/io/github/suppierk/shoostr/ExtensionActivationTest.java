package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ExtensionActivationTest {
  @Test
  void routeSelectionSetsUpTheInstanceBeforeServingWithoutApplicationRegistration()
      throws Exception {
    var setups = new AtomicInteger();
    var extension = new ScopedExtension(setups);
    var app = new Shoostr();

    try {
      app.routes()
          .get(
              "/selected",
              (request, response) -> response.text("ready"),
              lifecycle -> lifecycle.withExtension(extension));

      assertEquals(1, setups.get());

      try (var server = TestServer.start(app)) {
        var response =
            server.send(request -> request.path("/selected"), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("ready", response.body());
        assertEquals("selected", response.headers().firstValue("X-Extension").orElseThrow());
        assertEquals(1, setups.get());
      }
    } finally {
      app.close();
    }
  }

  @Test
  void typedDefaultsAreCopiedAndSameTypeInstancesRemainIndependent() throws Exception {
    var setups = new AtomicInteger();
    var first = new ConfigurableExtension(setups);
    var second = new ConfigurableExtension(setups);
    var app = new Shoostr();

    try {
      app.withExtension(first, defaults -> defaults.value = "application");
      app.routes()
          .get("/default", (request, response) -> response.text("ready"))
          .get(
              "/local",
              (request, response) -> response.text("ready"),
              lifecycle -> lifecycle.withExtension(first).value = "local")
          .get(
              "/private",
              (request, response) -> response.text("ready"),
              lifecycle -> {
                lifecycle.withoutExtension(first);
                lifecycle.withExtension(second).value = "private";
              });

      assertEquals(2, setups.get());

      try (var server = TestServer.start(app)) {
        assertEquals(
            "application",
            server
                .send(request -> request.path("/default"))
                .headers()
                .firstValue("X-Configuration")
                .orElseThrow());
        assertEquals(
            "local",
            server
                .send(request -> request.path("/local"))
                .headers()
                .firstValue("X-Configuration")
                .orElseThrow());
        assertEquals(
            "private",
            server
                .send(request -> request.path("/private"))
                .headers()
                .firstValue("X-Configuration")
                .orElseThrow());
        assertEquals(
            "application",
            server
                .send(request -> request.path("/default"))
                .headers()
                .firstValue("X-Configuration")
                .orElseThrow());
      }
    } finally {
      app.close();
    }
  }

  @Test
  void duplicateApplicationActivationRejectsTheSecondConfigurationWithoutRepeatingSetup()
      throws Exception {
    var setups = new AtomicInteger();
    var callbacks = new AtomicInteger();
    var extension = new ConfigurableExtension(setups);
    var app = new Shoostr();

    try {
      app.withExtension(extension);

      assertThrows(
          IllegalArgumentException.class,
          () -> app.withExtension(extension, ignored -> callbacks.incrementAndGet()));
      assertEquals(1, setups.get());
      assertEquals(0, callbacks.get());
    } finally {
      app.close();
    }
  }

  @Test
  void lateApplicationDefaultsFailBeforeAcquiringTheNewInstance() throws Exception {
    var setups = new AtomicInteger();
    var app = new Shoostr();

    try {
      app.routes().get("/ready", (request, response) -> response.text("ready"));

      assertThrows(
          IllegalStateException.class, () -> app.withExtension(new ConfigurableExtension(setups)));
      assertEquals(0, setups.get());
    } finally {
      app.close();
    }
  }

  @Test
  void applicationAuthenticationPrecedesAdmissionAndCanBeExcludedFromPublicEndpoint()
      throws Exception {
    var authentication = createAuthenticationForApplicationAuth();
    var app = new Shoostr();

    try {
      app.withExtension(authentication);
      app.routes()
          .get(
              "/private",
              (request, response) -> response.text(request.principal().orElseThrow().getName()),
              lifecycle ->
                  lifecycle.beforeRouteHandler(
                      (request, response) ->
                          response.setHeader(
                              "X-Admitted", request.principal().orElseThrow().getName())))
          .get(
              "/public",
              (request, response) -> response.text(Boolean.toString(request.principal().isEmpty())),
              lifecycle -> lifecycle.withoutExtension(authentication));

      try (var server = TestServer.start(app)) {
        var secured =
            server.send(request -> request.path("/private"), HttpResponse.BodyHandlers.ofString());

        assertEquals("alice", secured.body());
        assertEquals("alice", secured.headers().firstValue("X-Admitted").orElseThrow());
        assertEquals(
            "true",
            server
                .send(request -> request.path("/public"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    } finally {
      app.close();
    }
  }

  private AuthenticationExtension createAuthenticationForApplicationAuth() {
    return new AuthenticationExtension() {
      @Override
      public void handle(Request request, Response response) {
        request.principal(() -> "alice");
      }
    };
  }

  @Test
  void failedTypedDefaultsFreezeTheirCollectorAndCanBeCorrectedWithoutRepeatingSetup()
      throws Exception {
    var setups = new AtomicInteger();
    var retained = new AtomicReference<Lifecycle>();
    var extension = new ConfigurableExtension(setups, retained);
    var failure = new IllegalArgumentException("invalid defaults");
    var app = new Shoostr();

    try {
      assertEquals(
          failure,
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  app.withExtension(
                      extension,
                      defaults -> {
                        defaults.value = "discarded";
                        throw failure;
                      })));

      var failed = retained.get();

      assertThrows(
          IllegalStateException.class, () -> failed.beforeRouteHandler((request, response) -> {}));

      app.withExtension(extension, defaults -> defaults.value = "corrected");
      var configured = retained.get();

      assertThrows(IllegalStateException.class, () -> configured.afterRequest(ignored -> {}));

      app.routes().get("/ready", (request, response) -> response.text("ready"));

      try (var server = TestServer.start(app)) {
        assertEquals(
            "corrected",
            server
                .send(request -> request.path("/ready"))
                .headers()
                .firstValue("X-Configuration")
                .orElseThrow());
      }

      assertEquals(1, setups.get());
    } finally {
      app.close();
    }
  }

  @Test
  void typedApplicationConfigurationCanWaitForAnotherThreadsApplicationConfiguration()
      throws Exception {
    var app = new Shoostr();

    try (var worker = Executors.newSingleThreadExecutor()) {
      app.withExtension(
          new ConfigurableExtension(new AtomicInteger()),
          defaults -> {
            try {
              worker.submit(() -> app.modifyServer(ignored -> {})).get(3, TimeUnit.SECONDS);
            } catch (Exception failure) {
              throw new IllegalStateException(failure);
            }
          });
    } finally {
      app.close();
    }
  }

  @Test
  void applicationOnlyProvidersSupportTypedDefaultsAndScopedExclusion() throws Exception {
    var extension = new ApplicationOnly();
    var app = new Shoostr();

    try {
      app.withExtension(extension, defaults -> defaults.value = "application-only");
      app.routes()
          .get("/selected", (request, response) -> response.text("ready"))
          .get(
              "/excluded",
              (request, response) -> response.text("ready"),
              lifecycle -> lifecycle.withoutExtension(extension));

      try (var server = TestServer.start(app)) {
        assertEquals(
            "application-only",
            server
                .send(request -> request.path("/selected"))
                .headers()
                .firstValue("X-Application-Only")
                .orElseThrow());
        assertTrue(
            server
                .send(request -> request.path("/excluded"))
                .headers()
                .firstValue("X-Application-Only")
                .isEmpty());
        assertTrue(
            server
                .send(request -> request.path("/missing"))
                .headers()
                .firstValue("X-Application-Only")
                .isEmpty());
      }
    } finally {
      app.close();
    }
  }

  private static final class ApplicationOnly implements ApplicationExtension<Settings> {
    @Override
    public Settings configure(Lifecycle lifecycle, Settings inherited) {
      var settings = new Settings(inherited == null ? "default" : inherited.value);
      lifecycle.beforeRouteHandler(
          (request, response) -> response.setHeader("X-Application-Only", settings.value));

      return settings;
    }
  }

  private static final class Settings {
    private String value;

    private Settings(String value) {
      this.value = value;
    }
  }

  private static final class ConfigurableExtension
      implements ApplicationExtension<Settings>, RouteExtension<Settings> {
    private final AtomicInteger setups;
    private final AtomicReference<Lifecycle> retained;

    private ConfigurableExtension(AtomicInteger setups) {
      this(setups, new AtomicReference<>());
    }

    private ConfigurableExtension(AtomicInteger setups, AtomicReference<Lifecycle> retained) {
      this.setups = setups;
      this.retained = retained;
    }

    @Override
    public void install(ApplicationCallbacks callbacks) {
      setups.incrementAndGet();
    }

    @Override
    public Settings configure(Lifecycle lifecycle, Settings inherited) {
      retained.set(lifecycle);
      var settings = new Settings(inherited == null ? "default" : inherited.value);
      lifecycle.beforeRouteHandler(
          (request, response) -> response.setHeader("X-Configuration", settings.value));

      return settings;
    }
  }

  private static final class ScopedExtension implements RouteExtension<ScopedExtension> {
    private final AtomicInteger setups;

    private ScopedExtension(AtomicInteger setups) {
      this.setups = setups;
    }

    @Override
    public void install(ApplicationCallbacks callbacks) {
      setups.incrementAndGet();
    }

    @Override
    public ScopedExtension configure(Lifecycle lifecycle, ScopedExtension inherited) {
      lifecycle.beforeRouteHandler(
          (request, response) -> response.setHeader("X-Extension", "selected"));
      return this;
    }
  }
}
