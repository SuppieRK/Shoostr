package io.github.suppierk.shoostr.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.Extension;
import io.github.suppierk.shoostr.Extensions;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.websocket.api.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ExtensionConfigurationConcurrencyTest {
  @Test
  void rejectsStartupDuringEndpointConfigurationAndPublishesAfterItFinishes() throws Exception {
    try (var app = new Shoostr()) {
      app.routes()
          .get(
              "/configured",
              (ignored, response) -> response.text("registered"),
              ignored2 -> assertThrows(IllegalStateException.class, app::start));

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
          () -> routes.get("/closed", (ignored3, ignored4) -> {}, ignored5 -> routes.close()));
      assertThrows(IllegalStateException.class, app::start);
    }
  }

  @Test
  void reservesShapesDuringReentrantConfigurationAndReleasesFailedReservations() throws Exception {
    try (var app = new Shoostr()) {
      var routes = app.routes();
      assertThrows(
          IllegalArgumentException.class,
          () ->
              routes.get(
                  "/reserved/{id}",
                  (ignored6, ignored7) -> {},
                  ignored8 -> routes.get("/reserved/{other}", (ignored9, ignored10) -> {})));
      app.routes().get("/reserved/{id}", (ignored11, response) -> response.text("retry"));

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
    try (var app =
            new Shoostr()
                .modifyServer(
                    nativeServer ->
                        ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      app.routes()
          .get(
              "/first",
              (ignored12, ignored13) -> {},
              ignored14 -> {
                try {
                  assertNotNull(
                      executor
                          .submit(() -> app.routes().get("/second", (ignored15, ignored16) -> {}))
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
    try (var app =
        new Shoostr()
            .modifyServer(
                nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0))) {
      var routes = app.routes();
      routes.websocket(
          "/socket",
          (ignored17, ignored18) -> new Session.Listener.AutoDemanding() {},
          ignored19 -> {
            assertThrows(IllegalStateException.class, app::start);
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    routes.websocket(
                        "/socket",
                        (ignored20, ignored21) -> new Session.Listener.AutoDemanding() {}));
          });
      app.routes().get("/socket", (ignored22, ignored23) -> {});
      app.start();
    }
  }

  @Test
  @Timeout(10)
  @SuppressWarnings("java:S9357") // Extension has no abstract methods and cannot be a lambda.
  void inheritedProviderConfigurationRunsOutsideTheLockWithStartupGuarded() throws Exception {
    try (var app =
            new Shoostr()
                .modifyServer(
                    nativeServer ->
                        ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Extension<Object> provider =
          new Extension<>() {
            @Override
            public Object configure(Extensions endpoint, Object inherited) {
              if (inherited != null) {
                try {
                  assertNotNull(
                      executor
                          .submit(() -> app.routes().get("/parallel", (ignored24, ignored25) -> {}))
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
      app.routes()
          .path(
              "/group",
              group -> group.get("/child", (ignored26, ignored27) -> {}),
              e -> e.with(provider));
      app.start();
    }
  }
}
