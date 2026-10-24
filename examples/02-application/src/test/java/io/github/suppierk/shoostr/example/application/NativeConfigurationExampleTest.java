package io.github.suppierk.shoostr.example.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.Test;

class NativeConfigurationExampleTest {
  @Test
  void appliesNativeSettingsDuringStartupAndRejectsLateChanges() throws Exception {
    var stopTimeout = new AtomicLong(-1);
    var callbacks = new ArrayList<String>();
    var listener = new AtomicReference<ServerConnector>();
    var threads = new AtomicLong(-1);

    try (var app = NativeConfigurationExample.application(0);
        var client =
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build()) {
      app.modifyServer(
          server -> {
            callbacks.add("server");
            stopTimeout.set(server.getStopTimeout());
            listener.set((ServerConnector) server.getConnectors()[0]);
            threads.set(((QueuedThreadPool) server.getThreadPool()).getMaxThreads());
          });
      app.modifyHttpConfiguration(http -> callbacks.add("http"));
      assertEquals(List.of(), callbacks);
      assertEquals(-1, stopTimeout.get());

      app.start();
      assertEquals(List.of("http", "server"), callbacks);
      assertEquals(1000, stopTimeout.get());
      assertEquals(32, threads.get());
      assertEquals("127.0.0.1", listener.get().getHost());
      assertEquals(10_000, listener.get().getIdleTimeout());
      assertEquals(app.port(), listener.get().getLocalPort());
      var health = URI.create("http://127.0.0.1:" + app.port() + "/health");

      var response =
          client.send(
              HttpRequest.newBuilder(health).timeout(Duration.ofSeconds(3)).build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("ready", response.body());
      assertEquals(HttpClient.Version.HTTP_1_1, response.version());

      var rejected =
          client.send(
              HttpRequest.newBuilder(health)
                  .timeout(Duration.ofSeconds(3))
                  .header("X-Padding", "x".repeat(4096))
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      assertEquals(431, rejected.statusCode());
      assertThrows(IllegalStateException.class, () -> app.modifyHttpConfiguration(ignored -> {}));
      assertThrows(IllegalStateException.class, () -> app.modifyServer(ignored2 -> {}));
      app.close();
      assertThrows(IllegalStateException.class, () -> app.modifyHttpConfiguration(ignored3 -> {}));
      assertThrows(IllegalStateException.class, () -> app.modifyServer(ignored4 -> {}));
    }
  }
}
