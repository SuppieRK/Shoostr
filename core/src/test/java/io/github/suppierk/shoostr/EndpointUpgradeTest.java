package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Closeable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentHashMap.KeySetView;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.server.ServerUpgradeResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EndpointUpgradeTest {
  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void createsWebSocketListenerOnSelectedHttpUpgradeThread(boolean pathless, boolean configured)
      throws Exception {
    var owned = ConcurrentHashMap.<Thread>newKeySet();
    var selected = new CompletableFuture<Thread>();

    try (var workers = openWorkersForCreatesWebSocketListenerOnSelectedHttpUpgradeThread(owned)) {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup = app::close) {
        var configuredThread = new CompletableFuture<Thread>();
        BiFunction<Request, ServerUpgradeResponse, Session.Listener> factory =
            (ignored, ignoredServerUpgradeResponse) -> {
              selected.complete(Thread.currentThread());

              return new Session.Listener.AutoDemanding() {};
            };
        Consumer<Lifecycle> configuration =
            extensions ->
                extensions.beforeRouteHandler((ignoredRequest, ignoredResponse) ->
                    configuredThread.complete(Thread.currentThread()));
        if (pathless) {
          app.routes()
              .path(
                  "/socket",
                  routes -> {
                    if (configured) {
                      routes.websocket(workers, factory, configuration);
                    } else {
                      routes.websocket(workers, factory);
                    }
                  });
        } else if (configured) {
          app.routes().websocket("/socket", workers, factory, configuration);
        } else {
          app.routes().websocket("/socket", workers, factory);
        }

        app.start();

        try (var client = HttpClient.newHttpClient()) {
          var socket =
              createSocketForCreatesWebSocketListenerOnSelectedHttpUpgradeThread(client, app);

          try {
            var thread = selected.get(5, TimeUnit.SECONDS);

            assertTrue(owned.contains(thread));
            assertEquals(configured, configuredThread.isDone());

            if (configured) {
              assertSame(thread, configuredThread.get(5, TimeUnit.SECONDS));
            }
          } finally {
            socket.abort();
          }
        }
      }
    }
  }

  private ExecutorService openWorkersForCreatesWebSocketListenerOnSelectedHttpUpgradeThread(
      KeySetView<Thread, Boolean> owned) {
    return Executors.newFixedThreadPool(
        1,
        task -> {
          var thread = Thread.ofPlatform().unstarted(task);
          owned.add(thread);

          return thread;
        });
  }

  private WebSocket createSocketForCreatesWebSocketListenerOnSelectedHttpUpgradeThread(
      HttpClient client, Shoostr app)
      throws InterruptedException, ExecutionException, TimeoutException {
    return client
        .newWebSocketBuilder()
        .buildAsync(
            URI.create("ws://127.0.0.1:" + app.port() + "/socket"), new WebSocket.Listener() {})
        .get(5, TimeUnit.SECONDS);
  }
}
