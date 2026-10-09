package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.Session;
import org.junit.jupiter.api.Test;

class EndpointUpgradeTest {
  @Test
  void createsWebSocketListenerOnSelectedHttpUpgradeThread() throws Exception {
    var selected = new CompletableFuture<Boolean>();
    var transport = new QueuedThreadPool(16, 8);
    transport.setReservedThreads(0);
    var execution =
        new ExecutionSettings(transport, true, Executors.newFixedThreadPool(1), Set.of("/socket"));

    try (var app = new Shoostr(Options.defaults().withPort(0), execution)) {
      app.routes()
          .websocket(
              "/socket",
              (_, _) -> {
                selected.complete(Thread.currentThread().isVirtual());
                return new Session.Listener.AutoDemanding() {};
              });

      app.start();

      try (var client = HttpClient.newHttpClient()) {
        var socket =
            client
                .newWebSocketBuilder()
                .buildAsync(
                    URI.create("ws://127.0.0.1:" + app.port() + "/socket"),
                    new WebSocket.Listener() {})
                .get(5, TimeUnit.SECONDS);

        try {
          assertFalse(selected.get(5, TimeUnit.SECONDS));
        } finally {
          socket.abort();
        }
      }
    }
  }
}
