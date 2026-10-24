package io.github.suppierk.shoostr;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.GracefulHandler;
import org.eclipse.jetty.util.VirtualThreads;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(20)
class ShoostrLifecycleTest {
  @TempDir private Path temporary;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void bindsWildcardByDefaultAndPreservesExplicitLoopback(boolean loopback) throws Exception {
    var listener = new AtomicReference<ServerConnector>();

    try (var app = new Shoostr();
        var client = HttpClient.newHttpClient()) {
      app.modifyServer(
          server -> {
            var connector = assertInstanceOf(ServerConnector.class, server.getConnectors()[0]);
            connector.setPort(0);
            if (loopback) {
              connector.setHost("127.0.0.1");
            }

            listener.set(connector);
          });
      app.routes().get("/", (ignored, response) -> response.text("configured listener"));
      app.start();

      var address =
          assertInstanceOf(ServerSocketChannel.class, listener.get().getTransport())
              .socket()
              .getInetAddress();
      assertEquals(loopback, address.isLoopbackAddress());
      assertEquals(!loopback, address.isAnyLocalAddress());

      // TestServer intentionally replaces the binding, so this transport check owns its client.
      var reply =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/"))
                  .timeout(Duration.ofSeconds(3))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, reply.statusCode());
      assertEquals("configured listener", reply.body());
    }
  }

  @Test
  void servesAnotherConnectionWhileAVirtualHandlerIsBlocked() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);

    try (var app =
        new Shoostr()
            .virtualThreads()
            .modifyServer(
                nativeServerConfiguration ->
                    ((ServerConnector) nativeServerConfiguration.getConnectors()[0]).setPort(0))) {
      app.routes()
          .get(
              "/blocked",
              (ignored2, response) -> {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                  throw new IOException("test timeout");
                }

                response.text(Boolean.toString(Thread.currentThread().isVirtual()));
              });
      app.routes()
          .get(
              "/ready",
              (ignored3, response) ->
                  response.text(Boolean.toString(Thread.currentThread().isVirtual())));

      try (var test =
          TestServer.start(app, client -> client.version(HttpClient.Version.HTTP_1_1))) {
        var blocked =
            CompletableFuture.supplyAsync(
                () -> {
                  try {
                    return test.send(
                        request -> request.path("/blocked").timeout(Duration.ofSeconds(6)),
                        HttpResponse.BodyHandlers.ofString());
                  } catch (IOException | InterruptedException failure) {
                    throw new CompletionException(failure);
                  }
                });

        try {
          assertTrue(entered.await(2, TimeUnit.SECONDS));
          var ready =
              test.send(
                  request -> request.path("/ready").timeout(Duration.ofSeconds(2)),
                  HttpResponse.BodyHandlers.ofString());
          assertEquals(200, ready.statusCode());
          assertEquals("true", ready.body());
        } finally {
          release.countDown();
          assertEquals("true", blocked.get(3, TimeUnit.SECONDS).body());
        }
      }
    }
  }

  @Test
  void omitsTheServerHeaderOnTheDefaultListenerWithoutNativeOverrides() throws Exception {
    try (var app =
        new Shoostr()
            .modifyServer(
                nativeServerConfiguration ->
                    ((ServerConnector) nativeServerConfiguration.getConnectors()[0]).setPort(0))) {
      app.routes().get("/", (ignored4, response) -> response.text("default listener"));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request -> request.path("/").timeout(Duration.ofSeconds(3)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("default listener", result.body());
        assertTrue(result.headers().firstValue("Server").isEmpty());
      }
    }
  }

  @Test
  void acceptsALargerThanDefaultHeaderAfterRaisingTheNativeLimit() throws Exception {
    var value = "x".repeat(12_288);

    try (var app =
        new Shoostr()
            .modifyServer(
                nativeServerConfiguration ->
                    ((ServerConnector) nativeServerConfiguration.getConnectors()[0]).setPort(0))) {
      app.modifyHttpConfiguration(
          configuration -> {
            assertEquals(8192, configuration.getRequestHeaderSize());
            configuration.setRequestHeaderSize(16_384);
          });
      app.routes()
          .get(
              "/",
              (request, response) -> response.text(request.header("User-Agent").orElseThrow()));

      try (var test =
          TestServer.start(app, client -> client.version(HttpClient.Version.HTTP_1_1))) {
        var result =
            test.send(
                request ->
                    request.path("/").timeout(Duration.ofSeconds(3)).header("User-Agent", value),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals(HttpClient.Version.HTTP_1_1, result.version());
        assertEquals(value, result.body());
      }
    }
  }

  @Test
  void nativeConfigurationRunsInOrderAfterDefaultsAndChangesTheListener() throws Exception {
    var nativeServer = new AtomicReference<Server>();

    try (var app =
        new Shoostr()
            .modifyServer(
                nativeServerConfiguration ->
                    ((ServerConnector) nativeServerConfiguration.getConnectors()[0]).setPort(0))) {
      app.modifyHttpConfiguration(http -> http.setSendServerVersion(true));
      app.modifyHttpConfiguration(
          http -> {
            assertTrue(http.getSendServerVersion());
            http.setSendServerVersion(false);
            http.setRequestHeaderSize(512);
          });
      app.modifyServer(
          server -> {
            nativeServer.set(server);
            assertFalse(server.isStarted());
            assertEquals(5000, server.getStopTimeout());
            server.setStopTimeout(200);
          });
      app.modifyServer(
          server -> {
            assertEquals(200, server.getStopTimeout());
            var connector = (ServerConnector) server.getConnectors()[0];
            connector.setIdleTimeout(2345);
            connector.setShutdownIdleTimeout(100);
            assertEquals(
                512,
                connector
                    .getConnectionFactory(HttpConnectionFactory.class)
                    .getHttpConfiguration()
                    .getRequestHeaderSize());
          });
      app.routes().get("/", (ignored5, res) -> res.text("configured"));

      try (var test = TestServer.start(app)) {

        var response =
            test.send(request -> request.path("/"), HttpResponse.BodyHandlers.ofString());
        assertEquals("configured", response.body());
        assertTrue(response.headers().firstValue("Server").isEmpty());
        var oversized =
            test.send(
                request -> request.path("/").header("X-Large", "x".repeat(1024)),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(431, oversized.statusCode());
        assertEquals(2345, nativeServer.get().getConnectors()[0].getIdleTimeout());
      }
    }

    assertTrue(nativeServer.get().isStopped());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void nativeGracefulShutdownRejectsNewWorkAndDrainsFiniteAndStreamingResponses(boolean streaming)
      throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var nativeServer = new AtomicReference<Server>();

    try (var app =
            new Shoostr()
                .modifyServer(
                    nativeServerConfiguration ->
                        ((ServerConnector) nativeServerConfiguration.getConnectors()[0])
                            .setPort(0));
        var client = HttpClient.newHttpClient()) {
      app.modifyServer(nativeServer::set);
      app.routes()
          .get(
              "/",
              (ignored6, res) -> {
                var stream = streaming ? res.startStream("text/plain") : null;
                if (stream != null) {
                  stream.write("first");
                  stream.flush();
                }

                entered.countDown();
                release.await();
                if (stream != null) {
                  stream.write("last");
                } else {
                  res.text("complete");
                }
              });
      app.start();
      var graceful = nativeServer.get().getDescendant(GracefulHandler.class);
      assertNotNull(graceful);
      var request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/")).build();
      var pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());

      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        var drain = graceful.shutdown();
        assertFalse(drain.isDone());
        assertEquals(
            503, client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
        release.countDown();
        var response = pending.get(3, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode());
        assertEquals(streaming ? "firstlast" : "complete", response.body());
        drain.get(3, TimeUnit.SECONDS);
      } finally {
        release.countDown();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void appCloseLetsQuietFiniteAndStreamingHandlersFinishWithinDefaultGrace(boolean streaming)
      throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var stopping = new CountDownLatch(1);

    try (var app =
            new Shoostr()
                .modifyServer(
                    nativeServerConfiguration ->
                        ((ServerConnector) nativeServerConfiguration.getConnectors()[0])
                            .setPort(0));
        var client = HttpClient.newHttpClient()) {
      app.modifyServer(
          server -> {
            assertEquals(
                1000, ((ServerConnector) server.getConnectors()[0]).getShutdownIdleTimeout());
            server.addEventListener(
                new LifeCycle.Listener() {
                  @Override
                  public void lifeCycleStopping(LifeCycle event) {
                    stopping.countDown();
                  }
                });
          });
      app.routes()
          .post(
              "/",
              (ignored7, res) -> {
                var stream = streaming ? res.startStream("text/plain") : null;
                if (stream != null) {
                  stream.write("first");
                  stream.flush();
                }

                entered.countDown();
                release.await();
                if (stream != null) {
                  stream.write("last");
                } else {
                  res.text("complete");
                }
              });
      app.start();
      var request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/"))
              .POST(HttpRequest.BodyPublishers.noBody())
              .build();
      var pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());

      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        var closing =
            CompletableFuture.runAsync(
                () -> {
                  try {
                    app.close();
                  } catch (Exception failure) {
                    throw new CompletionException(failure);
                  }
                });

        try {
          assertTrue(stopping.await(3, TimeUnit.SECONDS));
          await()
              .during(Duration.ofMillis(1500))
              .atMost(Duration.ofSeconds(3))
              .until(() -> !pending.isDone() && !closing.isDone());
          release.countDown();
          assertEquals(
              streaming ? "firstlast" : "complete", pending.get(3, TimeUnit.SECONDS).body());
          closing.get(3, TimeUnit.SECONDS);
        } finally {
          release.countDown();
          closing.get(6, TimeUnit.SECONDS);
        }
      } finally {
        release.countDown();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(longs = {0, 100})
  void nativeShutdownBudgetsBoundWaitingForHandlersThatIgnoreInterrupts(long budget)
      throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);

    try (var app =
            new Shoostr()
                .modifyServer(
                    nativeServerConfiguration ->
                        ((ServerConnector) nativeServerConfiguration.getConnectors()[0])
                            .setPort(0));
        var client = HttpClient.newHttpClient()) {
      app.modifyServer(
          server -> {
            server.setStopTimeout(budget);
            // Native graceful draining and platform-worker shutdown have separate budgets.
            ((QueuedThreadPool) server.getThreadPool()).setStopTimeout(100);
            ((ServerConnector) server.getConnectors()[0]).setShutdownIdleTimeout(0);
          });
      app.routes()
          .post(
              "/",
              (ignored8, res) -> {
                entered.countDown();
                while (release.getCount() != 0) {
                  try {
                    release.await();
                  } catch (InterruptedException ignored9) {
                    interrupted.countDown();
                  }
                }

                res.text("late");
              });
      app.start();
      var request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/"))
              .POST(HttpRequest.BodyPublishers.noBody())
              .build();
      var pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());

      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        var closing =
            CompletableFuture.runAsync(
                () -> {
                  try {
                    app.close();
                  } catch (Exception failure) {
                    throw new CompletionException(failure);
                  }
                });

        try {
          var stopFailure =
              closing.handle((ignored10, failure) -> failure).get(2, TimeUnit.SECONDS);
          if (budget == 0) {
            assertNull(stopFailure);
          } else {
            var cause = assertInstanceOf(IOException.class, stopFailure.getCause());
            assertInstanceOf(TimeoutException.class, cause.getCause());
          }

          assertTrue(interrupted.await(1, TimeUnit.SECONDS));
          assertThrows(ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS));
        } finally {
          release.countDown();
          closing.handle((ignored11, failure) -> failure).get(6, TimeUnit.SECONDS);
        }
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void nativeCallbacksCannotRestartAnAppClosedDuringConfiguration() throws Exception {
    var nativeServer = new AtomicReference<Server>();

    try (var app =
        new Shoostr()
            .modifyServer(
                nativeServerConfiguration ->
                    ((ServerConnector) nativeServerConfiguration.getConnectors()[0]).setPort(0))) {
      app.modifyServer(
          server -> {
            nativeServer.set(server);
            assertDoesNotThrow(app::close);
          });

      try {
        assertThrows(IllegalStateException.class, app::start);
        assertTrue(nativeServer.get().isStopped());
      } finally {
        nativeServer.get().stop();
      }
    }
  }

  @Test
  void sessionConfigurationCannotRestartAnAppClosedDuringItsCallback() throws Exception {
    var nativeServer = new AtomicReference<Server>();
    var ownedExecutor = new AtomicReference<ExecutorService>();

    try (var app =
        new Shoostr()
            .virtualThreads()
            .modifyServer(
                nativeServerConfiguration ->
                    ((ServerConnector) nativeServerConfiguration.getConnectors()[0]).setPort(0))) {
      app.modifyServer(
          server -> {
            nativeServer.set(server);
            ownedExecutor.set(
                (ExecutorService) VirtualThreads.getVirtualThreadsExecutor(server.getThreadPool()));
          });
      app.sessions(ignored12 -> assertDoesNotThrow(app::close));

      try {
        assertThrows(IllegalStateException.class, app::start);
        assertNotNull(nativeServer.get());
        assertTrue(nativeServer.get().isStopped());
        assertTrue(nativeServer.get().getConnectors()[0].isStopped());
        assertTrue(ownedExecutor.get().isShutdown());
        assertNull(VirtualThreads.getVirtualThreadsExecutor(nativeServer.get().getThreadPool()));
        assertThrows(IllegalStateException.class, app::port);
      } finally {
        if (nativeServer.get() != null) {
          nativeServer.get().stop();
        }
      }
    }
  }

  @ParameterizedTest
  @MethodSource("invalidOwnershipChanges")
  void nativeConfigurationCannotReplaceAppOwnershipOrDispatch(Consumer<Server> change)
      throws Exception {
    var nativeServer = new AtomicReference<Server>();

    try (var app =
        new Shoostr()
            .modifyServer(
                nativeServerConfiguration ->
                    ((ServerConnector) nativeServerConfiguration.getConnectors()[0]).setPort(0))) {
      app.modifyServer(nativeServer::set);
      app.modifyServer(change);

      try {
        assertThrows(IllegalStateException.class, app::start);
        assertTrue(nativeServer.get().isStopped());
        assertThrows(IllegalStateException.class, app::start);
      } finally {
        nativeServer.get().stop();
      }
    }
  }

  private static Stream<Named<Consumer<Server>>> invalidOwnershipChanges() {
    return Stream.of(
        Named.of("root handler", server -> server.setHandler((Handler) null)),
        Named.of(
            "graceful child handler",
            server -> server.getDescendant(GracefulHandler.class).setHandler((Handler) null)),
        Named.of("connectors", server -> server.setConnectors(new Connector[0])),
        Named.of("shutdown ownership", server -> server.setStopAtShutdown(true)),
        Named.of("start ownership", server -> assertDoesNotThrow(server::start)));
  }

  @Test
  @SuppressWarnings("NullAway") // These calls verify rejection of null callbacks.
  void additionalNativeConnectorsAndHandlerWrappersShareAppRoutesAndLifecycle() throws Exception {
    var additional = new AtomicReference<ServerConnector>();

    try (var app =
            new Shoostr()
                .modifyServer(
                    nativeServerConfiguration ->
                        ((ServerConnector) nativeServerConfiguration.getConnectors()[0])
                            .setPort(0));
        var client = HttpClient.newHttpClient()) {
      assertThrows(NullPointerException.class, () -> app.modifyServer(null));
      assertThrows(NullPointerException.class, () -> app.modifyHttpConfiguration(null));
      app.modifyServer(
          server -> {
            var http =
                server
                    .getConnectors()[0]
                    .getConnectionFactory(HttpConnectionFactory.class)
                    .getHttpConfiguration();
            var extra = new ServerConnector(server, new HttpConnectionFactory(http));
            extra.setHost("127.0.0.1");
            extra.setPort(0);
            server.addConnector(extra);
            additional.set(extra);
            server.setHandler(new Handler.Wrapper(server.getHandler()));
          });
      app.routes().get("/", (ignored13, res) -> res.text("shared"));
      app.start();
      assertThrows(IllegalStateException.class, () -> app.modifyServer(ignored14 -> {}));
      assertThrows(IllegalStateException.class, () -> app.modifyHttpConfiguration(ignored15 -> {}));
      for (int port : new int[] {app.port(), additional.get().getLocalPort()}) {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).build();
        assertEquals("shared", client.send(request, HttpResponse.BodyHandlers.ofString()).body());
      }
    }

    assertTrue(additional.get().isStopped());
    assertEquals(-2, additional.get().getLocalPort());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void throwingConfigurationClosesRegistrationAndPreventsRetry(boolean http) throws Exception {
    var nativeServer = new AtomicReference<Server>();
    var ownedExecutor = new AtomicReference<ExecutorService>();
    var failure = new IllegalArgumentException("configuration failed");

    try (var app =
        new Shoostr()
            .virtualThreads()
            .modifyServer(
                nativeServerConfiguration ->
                    ((ServerConnector) nativeServerConfiguration.getConnectors()[0]).setPort(0))) {
      app.modifyServer(
          server -> {
            nativeServer.set(server);
            ownedExecutor.set(
                (ExecutorService) VirtualThreads.getVirtualThreadsExecutor(server.getThreadPool()));
          });
      if (http) {
        app.modifyHttpConfiguration(
            ignored16 -> {
              throw failure;
            });
      } else {
        app.modifyServer(
            ignored17 -> {
              throw failure;
            });
      }

      assertSame(failure, assertThrows(IllegalArgumentException.class, app::start));
      assertThrows(IllegalStateException.class, app::start);
      assertThrows(IllegalStateException.class, app::port);
      var routes = app.routes();
      assertThrows(
          IllegalStateException.class, () -> routes.get("/", (ignored18, ignored19) -> {}));
      if (!http) {
        assertTrue(nativeServer.get().isStopped());
        assertTrue(ownedExecutor.get().isShutdown());
        assertNull(VirtualThreads.getVirtualThreadsExecutor(nativeServer.get().getThreadPool()));
      }
    }
  }

  @Test
  void defaultShutdownClosesIdleKeepAliveBeforeTheGraceBudgetExpires() throws Exception {
    try (var app =
            new Shoostr()
                .modifyServer(
                    nativeServerConfiguration ->
                        ((ServerConnector) nativeServerConfiguration.getConnectors()[0])
                            .setPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().get("/", (ignored20, res) -> res.text("complete"));
      app.start();
      var request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/")).build();
      assertEquals("complete", client.send(request, HttpResponse.BodyHandlers.ofString()).body());
      var closing =
          CompletableFuture.runAsync(
              () -> {
                try {
                  app.close();
                } catch (Exception failure) {
                  throw new CompletionException(failure);
                }
              });
      closing.get(3, TimeUnit.SECONDS);
    }
  }

  @Test
  void closeBeforeStartIsIdempotentAndEndsRegistration() throws Exception {
    var app = new Shoostr();
    var scope = new AtomicReference<Routes>();
    app.routes().path("/api", scope::set);
    assertInstanceOf(Closeable.class, app);
    app.close();
    app.close();
    assertThrows(IllegalStateException.class, app::start);
    assertThrows(IllegalStateException.class, app::port);
    assertThrows(IllegalStateException.class, () -> app.modifyServer(ignored21 -> {}));
    assertThrows(IllegalStateException.class, () -> app.modifyHttpConfiguration(ignored22 -> {}));
    var routes = app.routes();
    var closedScope = scope.get();
    assertThrows(
        IllegalStateException.class, () -> routes.get("/later", (ignored23, ignored24) -> {}));
    assertThrows(IllegalStateException.class, () -> closedScope.path("/later", ignored25 -> {}));
  }

  @Test
  void closingAChildScopeEndsRegistrationAndPreventsStartup() throws Exception {
    try (var app = new Shoostr()) {
      var child = new AtomicReference<Routes>();
      var sibling = new AtomicReference<Routes>();
      app.routes().path("/api", child::set);
      app.routes().path("/other", sibling::set);

      try (Closeable registration = child.get()) {
        assertSame(child.get(), registration);
        child.get().get((ignored26, res) -> res.text("pending"));
      }

      child.get().close();
      sibling.get().close();
      app.routes().close();
      var routes = app.routes();
      var closedChild = child.get();
      var closedSibling = sibling.get();
      assertThrows(
          IllegalStateException.class, () -> routes.get("/late", (ignored27, ignored28) -> {}));
      assertThrows(
          IllegalStateException.class, () -> closedChild.get((ignored29, ignored30) -> {}));
      assertThrows(IllegalStateException.class, () -> closedSibling.path("/late", ignored31 -> {}));
      assertThrows(IllegalStateException.class, app::start);
      assertThrows(IllegalStateException.class, app::port);
    }
  }

  @Test
  void closingRoutesAfterStartupLeavesCompiledHandlersRunning() throws Exception {
    try (var app =
        new Shoostr()
            .modifyServer(
                nativeServerConfiguration ->
                    ((ServerConnector) nativeServerConfiguration.getConnectors()[0]).setPort(0))) {
      var child = new AtomicReference<Routes>();
      app.routes()
          .path(
              "/api",
              routes -> {
                child.set(routes);
                routes.get("/{id}", (req, res) -> res.text(req.pathParam("id").orElseThrow()));
              });

      try (var test = TestServer.start(app)) {
        app.routes().close();
        child.get().close();

        var response =
            test.send(
                request -> request.path("/api/42").timeout(Duration.ofSeconds(3)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("42", response.body());
        var closedChild = child.get();
        assertThrows(
            IllegalStateException.class,
            () -> closedChild.get("/later", (ignored32, ignored33) -> {}));
      }
    }
  }

  @Test
  void explicitCloseReleasesTheListenerAndPreventsRestart() throws Exception {
    int port;

    try (var app =
        new Shoostr()
            .modifyServer(
                nativeServerConfiguration ->
                    ((ServerConnector) nativeServerConfiguration.getConnectors()[0]).setPort(0))) {
      assertSame(app, app.start());
      port = app.port();
      assertThrows(IllegalStateException.class, app::start);
      app.close();
      app.close();
      assertThrows(IllegalStateException.class, app::port);
      assertThrows(IllegalStateException.class, app::start);
    }

    try (var replacement =
        new Shoostr()
            .modifyServer(
                nativeServerConfiguration ->
                    ((ServerConnector) nativeServerConfiguration.getConnectors()[0])
                        .setPort(port))) {
      replacement.start();
      assertEquals(port, replacement.port());
    }
  }

  @Test
  void failedStartupCleansUpAndCannotBeRetried() throws Exception {
    try (var occupied = new ServerSocket(0, 1, InetAddress.getAllByName("127.0.0.1")[0]);
        var app =
            new Shoostr()
                .modifyServer(
                    nativeServerConfiguration ->
                        ((ServerConnector) nativeServerConfiguration.getConnectors()[0])
                            .setPort(occupied.getLocalPort()))) {
      assertThrows(Exception.class, app::start);
      app.close();
      assertThrows(IllegalStateException.class, app::port);
      assertThrows(IllegalStateException.class, app::start);
      var routes = app.routes();
      assertThrows(IllegalStateException.class, () -> routes.path("/late", ignored34 -> {}));
    }
  }

  @Test
  void rejectionInsideAPathCallbackDoesNotPreventLaterStartup() throws Exception {
    try (var app =
        new Shoostr()
            .modifyServer(
                nativeServerConfiguration ->
                    ((ServerConnector) nativeServerConfiguration.getConnectors()[0]).setPort(0))) {
      app.routes()
          .path(
              "/api",
              routes -> {
                assertThrows(IllegalStateException.class, app::start);
                routes.get((ignored35, res) -> res.text("ready"));
              });
      app.start();
      assertTrue(app.port() > 0);
    }
  }

  @Test
  void concurrentCloseCallsCompleteWithoutDuplicatingShutdown() throws Exception {
    try (var app =
        new Shoostr()
            .modifyServer(
                nativeServerConfiguration ->
                    ((ServerConnector) nativeServerConfiguration.getConnectors()[0]).setPort(0))) {
      var before = new AtomicInteger();
      var after = new AtomicInteger();
      app.beforeClose(before::incrementAndGet);
      app.afterClose(after::incrementAndGet);
      app.start();
      var failure = new AtomicReference<Throwable>();
      Runnable close =
          () -> {
            try {
              app.close();
            } catch (Throwable thrown) {
              failure.set(thrown);
            }
          };
      var first = Thread.startVirtualThread(close);
      var second = Thread.startVirtualThread(close);
      first.join(5000);
      second.join(5000);
      assertFalse(first.isAlive());
      assertFalse(second.isAlive());
      assertNull(failure.get());
      assertEquals(1, before.get());
      assertEquals(1, after.get());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"started", "unstarted", "explicit-close"})
  void jvmShutdownCompletesApplicationCleanupExactlyOnce(String lifecycle) throws Exception {
    var marker = temporary.resolve("closed");
    var log = temporary.resolve("child.log");
    var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    var process =
        new ProcessBuilder(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                ShutdownFixture.class.getName(),
                marker.toString(),
                lifecycle)
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();

    try {
      assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Shutdown child JVM did not exit");
      assertEquals(0, process.exitValue(), Files.readString(log));
      assertEquals("closed\n", Files.readString(marker));
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
      }
    }
  }

  public static final class ShutdownFixture {
    // test-source-policy: subprocess-main; ShoostrLifecycleTest forks this JVM to verify its hook.
    public static void main(String[] args) throws Exception {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServerConfiguration ->
                      ((ServerConnector) nativeServerConfiguration.getConnectors()[0]).setPort(0));
      app.afterClose(
          () -> {
            assertThrows(IllegalStateException.class, app::port);

            try {
              Files.writeString(
                  Path.of(args[0]),
                  "closed\n",
                  StandardOpenOption.CREATE,
                  StandardOpenOption.APPEND);
            } catch (IOException failure) {
              throw new IllegalStateException(failure);
            }
          });
      if (!"unstarted".equals(args[1])) {
        app.start();
      }

      if ("explicit-close".equals(args[1])) {
        app.close();
      }

      System.exit(0);
    }
  }
}
