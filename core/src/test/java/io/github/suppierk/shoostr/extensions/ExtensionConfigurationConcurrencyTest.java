package io.github.suppierk.shoostr.extensions;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Lifecycle;
import io.github.suppierk.shoostr.Routes;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.net.http.HttpResponse;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.websocket.api.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

  @ParameterizedTest
  @ValueSource(strings = {"http", "native-websocket", "managed-websocket", "inherited-provider"})
  @Timeout(10)
  void serializesConfigurationOnTheRegisteringThread(String kind) throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var attempted = new CountDownLatch(1);
    var app = new Shoostr();

    try (Closeable appCleanup = app::close;
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = submitConfiguredRegistration(executor, app, kind, entered, release);
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      var second = submitParallelRegistration(executor, app, attempted);

      try {
        assertTrue(attempted.await(3, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> second.get(150, TimeUnit.MILLISECONDS));
        assertThrows(IllegalStateException.class, app::start);
      } finally {
        release.countDown();
      }

      first.get(3, TimeUnit.SECONDS);
      second.get(3, TimeUnit.SECONDS);

      try (var test = TestServer.start(app)) {
        assertEquals(200, test.send(request -> request.path("/second")).statusCode());
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

  private TestExtension<Object> inheritedProvider(Runnable configure) {
    return new TestExtension<>() {
      @Override
      public Object configure(Lifecycle endpoint, Object inherited) {
        if (inherited != null) {
          configure.run();
        }

        return new Object();
      }
    };
  }

  private Future<Routes> submitConfiguredRegistration(
      ExecutorService executor,
      Shoostr app,
      String kind,
      CountDownLatch entered,
      CountDownLatch release) {
    return executor.submit(() -> {
      var caller = Thread.currentThread();
      return registerConfiguredEndpoint(
          app.routes(),
          kind,
          () -> {
            assertSame(caller, Thread.currentThread());
            entered.countDown();
            assertDoesNotThrow(() -> assertTrue(release.await(3, TimeUnit.SECONDS)));
          });
    });
  }

  private Routes registerConfiguredEndpoint(Routes routes, String kind, Runnable configure) {
    return switch (kind) {
      case "http" -> routes.get("/first", (request, response) -> {}, lifecycle -> configure.run());
      case "native-websocket" ->
          routes.websocket(
              "/first",
              (request, response) -> new Session.Listener.AutoDemanding() {},
              lifecycle -> configure.run());
      case "managed-websocket" -> routes.websocket("/first", callbacks -> configure.run());
      case "inherited-provider" ->
          routes.path(
              "/group",
              group -> group.get("/child", (request, response) -> {}),
              lifecycle -> lifecycle.withExtension(inheritedProvider(configure)));
      default -> throw new IllegalArgumentException(kind);
    };
  }

  private Future<Routes> submitParallelRegistration(
      ExecutorService executor, Shoostr app, CountDownLatch attempted) {
    return executor.submit(() -> {
      attempted.countDown();
      return app.routes().get("/second", (request, response) -> {});
    });
  }
}
