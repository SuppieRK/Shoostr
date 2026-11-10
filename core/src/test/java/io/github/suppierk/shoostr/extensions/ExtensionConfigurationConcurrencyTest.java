package io.github.suppierk.shoostr.extensions;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.Lifecycle;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.net.http.HttpResponse;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.websocket.api.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ExtensionConfigurationConcurrencyTest {
  @Test
  void rejectsStartupDuringEndpointConfigurationAndPublishesAfterItFinishes() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close) {
        app.routes()
            .get(
                "/configured",
                (ignored, response) -> response.text("registered"),
                ignoredLifecycle -> assertThrows(IllegalStateException.class, app::start));

        try (var test = TestServer.start(app)) {
          var result =
              test.send(
                  request -> request.path("/configured"), HttpResponse.BodyHandlers.ofString());

          assertEquals("registered", result.body());
        }
      }
    }
  }

  @Test
  void rejectsPublicationAfterConfigurationClosesRegistration() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup1 = app::close) {
        var routes = app.routes();

        assertThrows(
            IllegalStateException.class,
            () ->
                routes.get(
                    "/closed",
                    (ignoredRequest, ignoredResponse) -> {},
                    ignoredLifecycle -> assertDoesNotThrow(app::close)));
        assertThrows(IllegalStateException.class, app::start);
      }
    }
  }

  @Test
  void reservesShapesDuringReentrantConfigurationAndReleasesFailedReservations() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup2 = app::close) {
        var routes = app.routes();

        assertThrows(
            IllegalArgumentException.class,
            () ->
                routes.get(
                    "/reserved/{id}",
                    (ignoredRequest, ignoredResponse) -> {},
                    ignoredLifecycle ->
                        routes.get("/reserved/{other}", (ignoredRequest, ignoredResponse) -> {})));

        app.routes().get("/reserved/{id}", (ignoredRequest, response) -> response.text("retry"));

        try (var test = TestServer.start(app)) {
          assertEquals(
              "retry",
              test.send(
                      request -> request.path("/reserved/42"), HttpResponse.BodyHandlers.ofString())
                  .body());
        }
      }
    }
  }

  @Test
  @Timeout(10)
  void configurationCanWaitForOtherThreadRegistrationAndImmediateStartupRejection()
      throws Exception {
    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup3 = app::close;
          var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        app.routes()
            .get(
                "/first",
                (ignoredRequest, ignoredResponse) -> {},
                ignoredLifecycle -> {
                  try {
                    assertNotNull(
                        executor
                            .submit(
                                () ->
                                    app.routes()
                                        .get("/second", (ignoredRequest, ignoredResponse) -> {}))
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
  }

  @Test
  void webSocketConfigurationRejectsStartupAndReservesIndependentHandshakeShapes()
      throws Exception {
    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup4 = app::close) {
        var routes = app.routes();
        routes.websocket(
            "/socket",
            (ignoredRequest, ignoredServerUpgradeResponse) ->
                new Session.Listener.AutoDemanding() {},
            ignoredLifecycle -> {
              assertThrows(IllegalStateException.class, app::start);
              assertThrows(
                  IllegalArgumentException.class,
                  () ->
                      routes.websocket(
                          "/socket",
                          (ignoredRequest, ignoredServerUpgradeResponse) ->
                              new Session.Listener.AutoDemanding() {}));
            });
        app.routes().get("/socket", (ignoredRequest, ignoredResponse) -> {});
        app.start();
      }
    }
  }

  @Test
  @Timeout(10)
  @SuppressWarnings("java:S9357") // Extension has no abstract methods and cannot be a lambda.
  void inheritedProviderConfigurationRunsOutsideTheLockWithStartupGuarded() throws Exception {
    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup5 = app::close;
          var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        TestExtension<Object> provider = blockingInheritedProvider(executor, app);

        app.routes()
            .path(
                "/group",
                group -> group.get("/child", (ignoredRequest, ignoredResponse) -> {}),
                lifecycle -> lifecycle.withExtension(provider));
        app.start();
      }
    }
  }

  private TestExtension<Object> blockingInheritedProvider(ExecutorService executor, Shoostr app) {
    return new TestExtension<>() {
      @Override
      public Object configure(Lifecycle endpoint, Object inherited) {
        if (inherited != null) {
          try {
            assertNotNull(
                executor
                    .submit(
                        () ->
                            app.routes().get("/parallel", (ignoredRequest, ignoredResponse) -> {}))
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
  }
}
