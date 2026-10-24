package io.github.suppierk.shoostr.example.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Extension;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;

class ShutdownExampleTest {
  @Test
  void servesTrafficBeforeFinalCleanupRuns() throws Exception {
    var disposed = new AtomicInteger();

    try (var test = TestServer.start(ShutdownExample.application(disposed::incrementAndGet))) {
      var response =
          test.send(request -> request.path("/ready"), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("ready", response.body());
      assertEquals(0, disposed.get());
    }

    assertEquals(1, disposed.get());
  }

  @Test
  void signalsWaitingRequestsBeforeDisposingTheirResources() throws Exception {
    var entered = new CountDownLatch(1);
    var disposed = new AtomicInteger();

    try (var app = ShutdownExample.application(disposed::incrementAndGet);
        var client = HttpClient.newHttpClient()) {
      app.modifyServer(server -> server.setStopTimeout(5_000));
      app.modifyServer(
          server -> {
            for (var connector : server.getConnectors()) {
              ((ServerConnector) connector).setPort(0);
            }
          });
      app.beforeRouteHandler((ignored, ignored2) -> entered.countDown());
      app.start();
      var response =
          client.sendAsync(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + app.port() + "/wait-for-shutdown"))
                  .timeout(Duration.ofSeconds(10))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      app.close();
      assertEquals(200, response.get(5, TimeUnit.SECONDS).statusCode());
      assertEquals("stopping", response.get().body());
      assertEquals(1, disposed.get());
    }
  }

  @Test
  void closesUnstartedApplicationsOnlyOnceInDocumentedOrder() throws Exception {
    var events = new ArrayList<String>();
    var app = ShutdownExample.application(() -> events.add("resource"));
    app.beforeClose(() -> events.add("signal-1"));
    app.beforeClose(() -> events.add("signal-2"));
    app.afterClose(() -> events.add("cleanup-1"));
    app.afterClose(() -> events.add("cleanup-2"));
    app.close();
    app.close();
    assertEquals(List.of("signal-1", "signal-2", "cleanup-2", "cleanup-1", "resource"), events);
    assertThrows(IllegalStateException.class, app::start);
  }

  @Test
  void releasesResourcesWhenStartupFailsAfterRouteCompilation() throws Exception {
    var disposed = new AtomicInteger();

    try (var app = ShutdownExample.application(disposed::incrementAndGet)) {
      app.extensions(
          new Extension<Void>() {
            @Override
            public void beforeStart() {
              throw new IllegalStateException("startup failed");
            }
          });
      assertThrows(IllegalStateException.class, app::start);
      assertEquals(1, disposed.get());
      assertThrows(IllegalStateException.class, app::start);
    }

    assertEquals(1, disposed.get());
  }
}
