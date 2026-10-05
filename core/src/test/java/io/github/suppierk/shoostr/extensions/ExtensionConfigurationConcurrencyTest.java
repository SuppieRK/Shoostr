package io.github.suppierk.shoostr.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.Extension;
import io.github.suppierk.shoostr.Extensions;
import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.websocket.api.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ExtensionConfigurationConcurrencyTest {
  @Test
  void rejectsStartupDuringEndpointConfigurationAndPublishesAfterItFinishes() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes()
          .get(
              "/configured",
              (_, response) -> response.text("registered"),
              _ -> assertThrows(IllegalStateException.class, app::start));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(request -> request.path("/configured"), HttpResponse.BodyHandlers.ofString());
        assertEquals("registered", result.body());
      }
    }
  }

  @Test
  void rejectsPublicationAfterConfigurationClosesRegistration() throws Exception {
    try (var app = new Shoostr()) {
      var routes = app.routes();
      assertThrows(
          IllegalStateException.class,
          () -> routes.get("/closed", (_, _) -> {}, _ -> routes.close()));
      assertThrows(IllegalStateException.class, app::start);
    }
  }

  @Test
  void reservesShapesDuringReentrantConfigurationAndReleasesFailedReservations() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      var routes = app.routes();
      assertThrows(
          IllegalArgumentException.class,
          () ->
              routes.get(
                  "/reserved/{id}",
                  (_, _) -> {},
                  _ -> routes.get("/reserved/{other}", (_, _) -> {})));
      app.routes().get("/reserved/{id}", (_, response) -> response.text("retry"));

      try (var test = TestServer.start(app)) {
        assertEquals(
            "retry",
            test.send(request -> request.path("/reserved/42"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  @Timeout(10)
  void configurationCanWaitForOtherThreadRegistrationAndImmediateStartupRejection()
      throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      app.routes()
          .get(
              "/first",
              (_, _) -> {},
              _ -> {
                try {
                  assertNotNull(
                      executor
                          .submit(() -> app.routes().get("/second", (_, _) -> {}))
                          .get(3, TimeUnit.SECONDS));
                  executor
                      .submit(() -> assertThrows(IllegalStateException.class, app::start))
                      .get(3, TimeUnit.SECONDS);
                } catch (Exception failure) {
                  throw new IllegalStateException(failure);
                }
              });
      app.start();
    }
  }

  @Test
  void webSocketConfigurationRejectsStartupAndReservesIndependentHandshakeShapes()
      throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      var routes = app.routes();
      routes.websocket(
          "/socket",
          (_, _) -> new Session.Listener.AutoDemanding() {},
          _ -> {
            assertThrows(IllegalStateException.class, app::start);
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    routes.websocket("/socket", (_, _) -> new Session.Listener.AutoDemanding() {}));
          });
      app.routes().get("/socket", (_, _) -> {});
      app.start();
    }
  }

  @Test
  @Timeout(10)
  @SuppressWarnings("java:S9357") // Extension has no abstract methods and cannot be a lambda.
  void inheritedProviderConfigurationRunsOutsideTheLockWithStartupGuarded() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Extension<Object> provider =
          new Extension<>() {
            @Override
            public Object configure(Extensions endpoint, Object inherited) {
              if (inherited != null) {
                try {
                  assertNotNull(
                      executor
                          .submit(() -> app.routes().get("/parallel", (_, _) -> {}))
                          .get(3, TimeUnit.SECONDS));
                  executor
                      .submit(() -> assertThrows(IllegalStateException.class, app::start))
                      .get(3, TimeUnit.SECONDS);
                } catch (Exception failure) {
                  throw new IllegalStateException(failure);
                }
              }

              return new Object();
            }
          };
      app.extensions(provider);
      app.routes().path("/group", group -> group.get("/child", (_, _) -> {}), e -> e.get(provider));
      app.start();
    }
  }
}
