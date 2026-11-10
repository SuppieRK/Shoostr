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
import java.util.List;
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

    {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close;
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
            sendReplyRequestForBindsWildcardByDefaultAndPreservesExplicitLoopback(client, app);

        assertEquals(200, reply.statusCode());
        assertEquals("configured listener", reply.body());
      }
    }
  }

  private HttpResponse<String>
      sendReplyRequestForBindsWildcardByDefaultAndPreservesExplicitLoopback(
          HttpClient client, Shoostr app) throws IOException, InterruptedException {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/"))
            .timeout(Duration.ofSeconds(3))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void servesAnotherConnectionWhileAVirtualHandlerIsBlocked() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);

    var configuredOptions = new Options();
    configuredOptions.setVirtualThreads(true);

    {
      var app =
          new Shoostr(configuredOptions)
              .modifyServer(
                  nativeServerConfiguration ->
                      ((ServerConnector) nativeServerConfiguration.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup1 = app::close) {
        app.routes()
            .get(
                "/blocked",
                (ignoredRequest, response) -> {
                  entered.countDown();
                  if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("test timeout");
                  }

                  response.text(Boolean.toString(Thread.currentThread().isVirtual()));
                });
        app.routes()
            .get(
                "/ready",
                (ignoredRequest, response) ->
                    response.text(Boolean.toString(Thread.currentThread().isVirtual())));

        try (var test =
            TestServer.start(app, client -> client.version(HttpClient.Version.HTTP_1_1))) {
          var blocked = createBlockedForServesAnotherConnectionWhileAVirtualHandlerIsBlocked(test);

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
  }

  private CompletableFuture<HttpResponse<String>>
      createBlockedForServesAnotherConnectionWhileAVirtualHandlerIsBlocked(TestServer test) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return test.send(
                request -> request.path("/blocked").timeout(Duration.ofSeconds(6)),
                HttpResponse.BodyHandlers.ofString());
          } catch (IOException | InterruptedException failure) {
            throw new CompletionException(failure);
          }
        });
  }

  @Test
  void omitsTheServerHeaderOnTheDefaultListenerWithoutNativeOverrides() throws Exception {
    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServerConfiguration ->
                      ((ServerConnector) nativeServerConfiguration.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup2 = app::close) {
        app.routes().get("/", (ignoredRequest, response) -> response.text("default listener"));

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
  }

  @Test
  void acceptsALargerThanDefaultHeaderAfterRaisingTheNativeLimit() throws Exception {
    var value = "x".repeat(12_288);

    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServerConfiguration ->
                      ((ServerConnector) nativeServerConfiguration.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup3 = app::close) {
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
  }

  @Test
  void nativeConfigurationRunsInOrderAfterDefaultsAndChangesTheListener() throws Exception {
    var nativeServer = new AtomicReference<Server>();

    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServerConfiguration ->
                      ((ServerConnector) nativeServerConfiguration.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup4 = app::close) {
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
        app.routes().get("/", (ignoredRequest, routeResponse) -> routeResponse.text("configured"));

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

    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServerConfiguration ->
                      ((ServerConnector) nativeServerConfiguration.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup5 = app::close;
          var client = HttpClient.newHttpClient()) {
        app.modifyServer(nativeServer::set);
        app.routes()
            .get(
                "/",
                (ignoredRequest, routeResponse) -> {
                  var stream = streaming ? routeResponse.startStream("text/plain") : null;
                  if (stream != null) {
                    stream.write("first");
                    stream.flush();
                  }

                  entered.countDown();
                  release.await();
                  if (stream != null) {
                    stream.write("last");
                  } else {
                    routeResponse.text("complete");
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
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void appCloseLetsQuietFiniteAndStreamingHandlersFinishWithinDefaultGrace(boolean streaming)
      throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var stopping = new CountDownLatch(1);

    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServerConfiguration ->
                      ((ServerConnector) nativeServerConfiguration.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup6 = app::close;
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
                (ignoredRequest, response) -> {
                  var stream = streaming ? response.startStream("text/plain") : null;
                  if (stream != null) {
                    stream.write("first");
                    stream.flush();
                  }

                  entered.countDown();
                  release.await();
                  if (stream != null) {
                    stream.write("last");
                  } else {
                    response.text("complete");
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
              createClosingForAppCloseLetsQuietFiniteAndStreamingHandlersFinishWithinDefaultGrace(
                  app);

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
  }

  private CompletableFuture<Void>
      createClosingForAppCloseLetsQuietFiniteAndStreamingHandlersFinishWithinDefaultGrace(
          Shoostr app) {
    return CompletableFuture.runAsync(
        () -> {
          try {
            app.close();
          } catch (Exception failure) {
            throw new CompletionException(failure);
          }
        });
  }

  @ParameterizedTest
  @ValueSource(longs = {0, 100})
  void nativeShutdownBudgetsBoundWaitingForHandlersThatIgnoreInterrupts(long budget)
      throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);

    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServerConfiguration ->
                      ((ServerConnector) nativeServerConfiguration.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup7 = app::close;
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
                (ignoredRequest, response) -> {
                  entered.countDown();
                  while (release.getCount() != 0) {
                    try {
                      release.await();
                    } catch (InterruptedException ignoredFailure) {
                      interrupted.countDown();
                    }
                  }

                  response.text("late");
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
              createClosingForNativeShutdownBudgetsBoundWaitingForHandlersThatIgnoreInterrupts(app);

          try {
            var stopFailure =
                closing.handle((ignoredVoid, failure) -> failure).get(2, TimeUnit.SECONDS);
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
            closing.handle((ignoredVoid, failure) -> failure).get(6, TimeUnit.SECONDS);
          }
        } finally {
          release.countDown();
        }
      }
    }
  }

  private CompletableFuture<Void>
      createClosingForNativeShutdownBudgetsBoundWaitingForHandlersThatIgnoreInterrupts(
          Shoostr app) {
    return CompletableFuture.runAsync(
        () -> {
          try {
            app.close();
          } catch (Exception failure) {
            throw new CompletionException(failure);
          }
        });
  }

  @Test
  void nativeCallbacksCannotRestartAnAppClosedDuringConfiguration() throws Exception {
    var nativeServer = new AtomicReference<Server>();

    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServerConfiguration ->
                      ((ServerConnector) nativeServerConfiguration.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup8 = app::close) {
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
  }

  @Test
  void sessionConfigurationCannotRestartAnAppClosedDuringItsCallback() throws Exception {
    var nativeServer = new AtomicReference<Server>();
    var ownedExecutor = new AtomicReference<ExecutorService>();

    var configuredOptions = new Options();
    configuredOptions.setVirtualThreads(true);

    {
      var app =
          new Shoostr(configuredOptions)
              .modifyServer(
                  nativeServerConfiguration ->
                      ((ServerConnector) nativeServerConfiguration.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup9 = app::close) {
        app.modifyServer(
            server -> {
              nativeServer.set(server);
              ownedExecutor.set(
                  (ExecutorService)
                      VirtualThreads.getVirtualThreadsExecutor(server.getThreadPool()));
            });
        app.sessions(ignoredSessionHandler -> assertDoesNotThrow(app::close));

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
  }

  @ParameterizedTest
  @MethodSource("invalidOwnershipChanges")
  void nativeConfigurationCannotReplaceAppOwnershipOrDispatch(Consumer<Server> change)
      throws Exception {
    var nativeServer = new AtomicReference<Server>();

    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServerConfiguration ->
                      ((ServerConnector) nativeServerConfiguration.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup10 = app::close) {
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
  void rejectsHandlerReplacementEvenWhenItsEqualsClaimsTheOwnedHandler() throws Exception {
    {
      var app = createAppForRejectsHandlerReplacementEvenWhenItsEqualsClaimsTheOwnedHandler();

      try (Closeable appCleanup11 = app::close) {
        var failure = assertThrows(IllegalStateException.class, app::start);
        assertEquals(
            "Jetty configuration must preserve Shoostr lifecycle, handler and default connector",
            failure.getMessage());
      }
    }
  }

  private Shoostr createAppForRejectsHandlerReplacementEvenWhenItsEqualsClaimsTheOwnedHandler() {
    return new Shoostr()
        .modifyServer(
            server -> {
              ((ServerConnector) server.getConnectors()[0]).setPort(0);
              var graceful = server.getDescendant(GracefulHandler.class);

              var owned = assertInstanceOf(Handler.class, graceful.getHandler());

              graceful.setHandler(
                  new Handler.Wrapper(owned) {
                    @Override
                    public boolean equals(Object other) {
                      return owned.equals(other)
                          || (other instanceof Handler.Wrapper wrapper
                              && owned.equals(wrapper.getHandler()));
                    }

                    @Override
                    public int hashCode() {
                      return owned.hashCode();
                    }
                  });
            });
  }

  @Test
  @SuppressWarnings("NullAway") // These calls verify rejection of null callbacks.
  void additionalNativeConnectorsAndHandlerWrappersShareAppRoutesAndLifecycle() throws Exception {
    var additional = new AtomicReference<ServerConnector>();

    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServerConfiguration ->
                      ((ServerConnector) nativeServerConfiguration.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup12 = app::close;
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
        app.routes().get("/", (ignoredRequest, response) -> response.text("shared"));
        app.start();

        assertThrows(IllegalStateException.class, () -> app.modifyServer(ignoredServer -> {}));
        assertThrows(
            IllegalStateException.class,
            () -> app.modifyHttpConfiguration(ignoredHttpConfiguration -> {}));

        for (int port : new int[] {app.port(), additional.get().getLocalPort()}) {
          var request =
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).build();

          assertEquals("shared", client.send(request, HttpResponse.BodyHandlers.ofString()).body());
        }
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

    var configuredOptions = new Options();
    configuredOptions.setVirtualThreads(true);

    {
      var app =
          new Shoostr(configuredOptions)
              .modifyServer(
                  nativeServerConfiguration ->
                      ((ServerConnector) nativeServerConfiguration.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup13 = app::close) {
        app.modifyServer(
            server -> {
              nativeServer.set(server);
              ownedExecutor.set(
                  (ExecutorService)
                      VirtualThreads.getVirtualThreadsExecutor(server.getThreadPool()));
            });
        if (http) {
          app.modifyHttpConfiguration(
              ignoredHttpConfiguration -> {
                throw failure;
              });
        } else {
          app.modifyServer(
              ignoredServer -> {
                throw failure;
              });
        }

        assertSame(failure, assertThrows(IllegalArgumentException.class, app::start));
        assertThrows(IllegalStateException.class, app::start);
        assertThrows(IllegalStateException.class, app::port);

        var routes = app.routes();

        assertThrows(
            IllegalStateException.class,
            () -> routes.get("/", (ignoredRequest, ignoredResponse) -> {}));

        if (!http) {
          assertTrue(nativeServer.get().isStopped());
          assertTrue(ownedExecutor.get().isShutdown());
          assertNull(VirtualThreads.getVirtualThreadsExecutor(nativeServer.get().getThreadPool()));
        }
      }
    }
  }

  @Test
  void defaultShutdownClosesIdleKeepAliveBeforeTheGraceBudgetExpires() throws Exception {
    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServerConfiguration ->
                      ((ServerConnector) nativeServerConfiguration.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup14 = app::close;
          var client = HttpClient.newHttpClient()) {
        app.routes().get("/", (ignoredRequest, response) -> response.text("complete"));
        app.start();
        var request =
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/")).build();

        assertEquals("complete", client.send(request, HttpResponse.BodyHandlers.ofString()).body());

        var closing =
            createClosingForDefaultShutdownClosesIdleKeepAliveBeforeTheGraceBudgetExpires(app);
        closing.get(3, TimeUnit.SECONDS);
      }
    }
  }

  private CompletableFuture<Void>
      createClosingForDefaultShutdownClosesIdleKeepAliveBeforeTheGraceBudgetExpires(Shoostr app) {
    return CompletableFuture.runAsync(
        () -> {
          try {
            app.close();
          } catch (Exception failure) {
            throw new CompletionException(failure);
          }
        });
  }

  @Test
  void closeBeforeStartIsIdempotentAndEndsRegistration() throws Exception {
    var app = new Shoostr();
    var scope = new AtomicReference<Routes>();
    app.routes().path("/api", scope::set);

    assertFalse(
        List.of(Shoostr.class, Routes.class, TypedRoutes.class).stream()
            .anyMatch(AutoCloseable.class::isAssignableFrom));

    app.close();
    app.close();

    assertThrows(IllegalStateException.class, app::start);
    assertThrows(IllegalStateException.class, app::port);
    assertThrows(IllegalStateException.class, () -> app.modifyServer(ignoredServer -> {}));
    assertThrows(
        IllegalStateException.class,
        () -> app.modifyHttpConfiguration(ignoredHttpConfiguration -> {}));

    var routes = app.routes();
    var closedScope = scope.get();

    assertThrows(
        IllegalStateException.class,
        () -> routes.get("/later", (ignoredRequest, ignoredResponse) -> {}));
    assertThrows(
        IllegalStateException.class, () -> closedScope.path("/later", ignoredRoutes -> {}));
  }

  @Test
  void explicitCloseReleasesTheListenerAndPreventsRestart() throws Exception {
    int port;

    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServerConfiguration ->
                      ((ServerConnector) nativeServerConfiguration.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup17 = app::close) {
        assertSame(app, app.start());

        port = app.port();

        assertThrows(IllegalStateException.class, app::start);

        app.close();
        app.close();

        assertThrows(IllegalStateException.class, app::port);
        assertThrows(IllegalStateException.class, app::start);
      }
    }

    {
      var replacement =
          createReplacementForExplicitCloseReleasesTheListenerAndPreventsRestart(port);

      try (Closeable replacementCleanup = replacement::close) {
        replacement.start();

        assertEquals(port, replacement.port());
      }
    }
  }

  private Shoostr createReplacementForExplicitCloseReleasesTheListenerAndPreventsRestart(int port) {
    return new Shoostr()
        .modifyServer(
            nativeServerConfiguration ->
                ((ServerConnector) nativeServerConfiguration.getConnectors()[0]).setPort(port));
  }

  @Test
  void failedStartupCleansUpAndCannotBeRetried() throws Exception {
    try (var occupied = new ServerSocket(0, 1, InetAddress.getAllByName("127.0.0.1")[0])) {
      var app = createAppForFailedStartupCleansUpAndCannotBeRetried(occupied);

      try (Closeable appCleanup18 = app::close) {
        assertThrows(Exception.class, app::start);

        app.close();

        assertThrows(IllegalStateException.class, app::port);
        assertThrows(IllegalStateException.class, app::start);

        var routes = app.routes();

        assertThrows(IllegalStateException.class, () -> routes.path("/late", ignoredRoutes -> {}));
      }
    }
  }

  private Shoostr createAppForFailedStartupCleansUpAndCannotBeRetried(ServerSocket occupied) {
    return new Shoostr()
        .modifyServer(
            nativeServerConfiguration ->
                ((ServerConnector) nativeServerConfiguration.getConnectors()[0])
                    .setPort(occupied.getLocalPort()));
  }

  @Test
  void rejectionInsideAPathCallbackDoesNotPreventLaterStartup() throws Exception {
    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServerConfiguration ->
                      ((ServerConnector) nativeServerConfiguration.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup19 = app::close) {
        app.routes()
            .path(
                "/api",
                routes -> {
                  assertThrows(IllegalStateException.class, app::start);

                  routes.get((ignoredRequest, response) -> response.text("ready"));
                });
        app.start();

        assertTrue(app.port() > 0);
      }
    }
  }

  @Test
  void concurrentCloseCallsCompleteWithoutDuplicatingShutdown() throws Exception {
    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServerConfiguration ->
                      ((ServerConnector) nativeServerConfiguration.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup20 = app::close) {
        var before = new AtomicInteger();
        var after = new AtomicInteger();
        app.beforeClose(before::incrementAndGet);
        app.afterClose(after::incrementAndGet);
        app.start();
        var failure = new AtomicReference<Throwable>();
        Runnable close =
            createCloseForConcurrentCloseCallsCompleteWithoutDuplicatingShutdown(app, failure);
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
  }

  private Runnable createCloseForConcurrentCloseCallsCompleteWithoutDuplicatingShutdown(
      Shoostr app, AtomicReference<Throwable> failure) throws IOException {
    return () -> {
      try {
        app.close();
      } catch (Throwable thrown) {
        failure.set(thrown);
      }
    };
  }

  @ParameterizedTest
  @ValueSource(strings = {"started", "unstarted", "explicit-close"})
  void jvmShutdownCompletesApplicationCleanupExactlyOnce(String lifecycle) throws Exception {
    var marker = temporary.resolve("closed");
    var log = temporary.resolve("child.log");
    var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    var process =
        createProcessForJvmShutdownCompletesApplicationCleanupExactlyOnce(
            java, marker, lifecycle, log);

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

  private Process createProcessForJvmShutdownCompletesApplicationCleanupExactlyOnce(
      String java, Path marker, String lifecycle, Path log) throws IOException {
    return new ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            ShutdownFixture.class.getName(),
            marker.toString(),
            lifecycle)
        .redirectErrorStream(true)
        .redirectOutput(log.toFile())
        .start();
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
