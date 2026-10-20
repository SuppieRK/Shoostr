package io.github.suppierk.shoostr.mcp;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Extension;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class McpInitializationCleanupTest {
  private static final String INITIALIZE =
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
        "protocolVersion":"2025-11-25","capabilities":{},
        "clientInfo":{"name":"initialization-cleanup","version":"1.0"}}}
      """;

  @Test
  @Timeout(15)
  void closesTheNewSessionExactlyOnceWhenInitializationCannotFlush() throws Exception {
    var closes = new AtomicInteger();
    var closed = new CountDownLatch(1);
    var sessionId = new AtomicReference<String>();
    var mcp = trackedMcp(closes, closed, sessionId);
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));
    var fail = new AtomicBoolean(true);
    app.beforeResponseFlush(
        (_, _) -> {
          if (fail.getAndSet(false)) {
            throw new IOException("Initialization response delivery failed");
          }
        });

    try (var test = TestServer.start(app)) {
      var response =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .method("POST")
                      .header("Content-Type", "application/json")
                      .header("Accept", "application/json, text/event-stream")
                      .body(INITIALIZE.getBytes(StandardCharsets.UTF_8)));
      assertEquals(500, response.statusCode());
      assertTrue(
          closed.await(3, TimeUnit.SECONDS), "Failed initialization must close its native session");
      assertEquals(1, closes.get());
      var lookup =
          test.send(
              request ->
                  request.path("/mcp").method("DELETE").header("Mcp-Session-Id", sessionId.get()));
      assertEquals(404, lookup.statusCode());
    }

    assertEquals(1, closes.get(), "Application shutdown must not close the failed session again");
  }

  @Test
  @Timeout(15)
  void closesTheNewSessionWhenTheClientAbandonsInitialization() throws Exception {
    var closes = new AtomicInteger();
    var closed = new CountDownLatch(1);
    var sessionId = new AtomicReference<String>();
    var mcp = trackedMcp(closes, closed, sessionId);
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));
    var prepared = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var completed = new CountDownLatch(1);
    var failedTransport = new AtomicBoolean();
    app.beforeResponseFlush(
        (_, _) -> {
          prepared.countDown();
          assertTrue(release.await(5, TimeUnit.SECONDS));
        });
    app.afterRequest(
        outcome -> {
          failedTransport.set(outcome.transportFailure() != null);
          completed.countDown();
        });

    try (var test = TestServer.start(app)) {
      try (var socket = new Socket(test.baseUri().getHost(), test.baseUri().getPort())) {
        socket.setSoLinger(true, 0);
        var body = INITIALIZE.getBytes(StandardCharsets.UTF_8);
        var headers =
            "POST /mcp HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                + "Content-Type: application/json\r\nAccept: application/json, text/event-stream\r\n"
                + "Content-Length: "
                + body.length
                + "\r\n\r\n";
        socket.getOutputStream().write(headers.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().write(body);
        socket.getOutputStream().flush();
        assertTrue(prepared.await(5, TimeUnit.SECONDS));
      } finally {
        release.countDown();
      }

      assertTrue(completed.await(5, TimeUnit.SECONDS));
      assertTrue(
          failedTransport.get(), "Exercise a failed transport, not just a closed client handle");
      assertTrue(closed.await(3, TimeUnit.SECONDS));
      assertEquals(1, closes.get());
    }

    assertEquals(1, closes.get());
  }

  @Test
  @Timeout(15)
  void closesTheAllocatedSessionWhenNativeInitializationFails() throws Exception {
    var closes = new AtomicInteger();
    var closed = new CountDownLatch(1);
    var sessionId = new AtomicReference<String>();
    var mcp =
        trackedMcp(
            closes,
            closed,
            sessionId,
            _ -> Mono.error(new IOException("Native initialization failed")));
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var response =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .method("POST")
                      .header("Content-Type", "application/json")
                      .header("Accept", "application/json, text/event-stream")
                      .body(INITIALIZE.getBytes(StandardCharsets.UTF_8)));
      assertEquals(500, response.statusCode());
      assertTrue(response.headers().firstValue("Mcp-Session-Id").isEmpty());
      assertTrue(closed.await(3, TimeUnit.SECONDS));
      assertEquals(1, closes.get());
    }

    assertEquals(1, closes.get());
  }

  @Test
  @Timeout(15)
  void closesTheAllocatedSessionWhenItsInitializationResultCannotBeSerialized() throws Exception {
    var closes = new AtomicInteger();
    var closed = new CountDownLatch(1);
    var sessionId = new AtomicReference<String>();
    var mcp =
        trackedMcp(
            closes,
            closed,
            sessionId,
            result ->
                Mono.just(
                    new McpSchema.InitializeResult(
                        result.protocolVersion(),
                        result.capabilities(),
                        result.serverInfo(),
                        null,
                        Map.of("unsupported", new Object()))));
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var response =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .method("POST")
                      .header("Content-Type", "application/json")
                      .header("Accept", "application/json, text/event-stream")
                      .body(INITIALIZE.getBytes(StandardCharsets.UTF_8)));
      assertEquals(500, response.statusCode());
      assertTrue(response.headers().firstValue("Mcp-Session-Id").isEmpty());
      assertTrue(closed.await(3, TimeUnit.SECONDS));
      assertEquals(1, closes.get());
    }

    assertEquals(1, closes.get());
  }

  @Test
  @Timeout(15)
  void applicationShutdownWaitsForAnAlreadyClaimedInitializationCleanup() throws Exception {
    var closes = new AtomicInteger();
    var closed = new CountDownLatch(1);
    var sessionId = new AtomicReference<String>();
    var release = Sinks.<Void>empty();
    var mcp = trackedMcp(closes, closed, sessionId, Mono::just, release::asMono);
    var app = new Shoostr();
    var closingExtensions = new CountDownLatch(1);
    class ClosingSignal implements Extension<Void> {
      @Override
      public void close() {
        closingExtensions.countDown();
      }
    }
    app.extensions(mcp, new ClosingSignal()).routes(routes -> routes.path("/mcp", mcp::routes));
    var fail = new AtomicBoolean(true);
    app.beforeResponseFlush(
        (_, _) -> {
          if (fail.getAndSet(false)) {
            throw new IOException("Failed initialization");
          }
        });

    try (var test = TestServer.start(app);
        var closer = Executors.newVirtualThreadPerTaskExecutor()) {
      assertEquals(
          500,
          test.send(
                  request ->
                      request
                          .path("/mcp")
                          .method("POST")
                          .header("Content-Type", "application/json")
                          .header("Accept", "application/json, text/event-stream")
                          .body(INITIALIZE.getBytes(StandardCharsets.UTF_8)))
              .statusCode());
      assertTrue(closed.await(3, TimeUnit.SECONDS));
      var stopping =
          closer.submit(
              () -> {
                app.close();
                return null;
              });

      try {
        assertTrue(
            closingExtensions.await(7, TimeUnit.SECONDS),
            "Observe extension cleanup after HTTP drain");
        await("shutdown waits for native session closure")
            .pollDelay(Duration.ZERO)
            .pollInterval(Duration.ofMillis(10))
            .during(Duration.ofMillis(500))
            .atMost(Duration.ofMillis(1500))
            .failFast("shutdown completed before native session closure", stopping::isDone)
            .until(() -> !stopping.isDone());
        assertEquals(1, closes.get());
      } finally {
        release.tryEmitEmpty();
        stopping.get(3, TimeUnit.SECONDS);
      }
    } finally {
      release.tryEmitEmpty();
    }

    assertEquals(1, closes.get());
  }

  @Test
  @Timeout(25)
  void shutdownCancelsPendingInitializationAndAwaitsItsNativeCleanupOnABorrowedExecutor()
      throws Exception {
    var closes = new AtomicInteger();
    var closed = new CountDownLatch(1);
    var subscribed = new CountDownLatch(1);
    var canceled = new CountDownLatch(1);
    var held = Sinks.<McpSchema.InitializeResult>one();
    var releaseCleanup = Sinks.<Void>empty();
    var mcp =
        trackedMcp(
            closes,
            closed,
            new AtomicReference<>(),
            _ ->
                held.asMono()
                    .doOnSubscribe(_ -> subscribed.countDown())
                    .doOnCancel(canceled::countDown),
            releaseCleanup::asMono);
    var app = new Shoostr();

    try (var selected = Executors.newSingleThreadExecutor();
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      app.extensions(mcp)
          .routes(routes -> routes.path("/mcp", group -> mcp.routes(group, selected)));

      try (var test = TestServer.start(app)) {
        var pending =
            workers.submit(
                () ->
                    test.send(
                        request ->
                            request
                                .path("/mcp")
                                .method("POST")
                                .header("Content-Type", "application/json")
                                .header("Accept", "application/json, text/event-stream")
                                .body(INITIALIZE.getBytes(StandardCharsets.UTF_8))));
        assertTrue(subscribed.await(3, TimeUnit.SECONDS));
        var stopping =
            workers.submit(
                () -> {
                  app.close();
                  return null;
                });

        try {
          assertTrue(
              canceled.await(3, TimeUnit.SECONDS),
              "Shutdown must cancel native initialization; native closes=" + closes.get());
          assertTrue(closed.await(3, TimeUnit.SECONDS));
          await("shutdown waits for pending initialization cleanup")
              .pollDelay(Duration.ZERO)
              .pollInterval(Duration.ofMillis(10))
              .during(Duration.ofMillis(500))
              .atMost(Duration.ofMillis(1500))
              .failFast("shutdown completed before initialization cleanup", stopping::isDone)
              .until(() -> !stopping.isDone());
        } finally {
          held.tryEmitError(new IOException("Release pending initialization after the test"));
          releaseCleanup.tryEmitEmpty();
          stopping.get(8, TimeUnit.SECONDS);
        }

        assertEquals("usable", selected.submit(() -> "usable").get(2, TimeUnit.SECONDS));
        assertEquals(503, pending.get(2, TimeUnit.SECONDS).statusCode());
      } finally {
        held.tryEmitError(new IOException("Release failed fixture"));
        releaseCleanup.tryEmitEmpty();
      }
    }

    assertEquals(1, closes.get());
  }

  @Test
  @Timeout(20)
  void cancelsAndClosesNativeInitializationThatOutlivesItsVerifiedToken() throws Exception {
    var closes = new AtomicInteger();
    var closed = new CountDownLatch(1);
    var canceled = new CountDownLatch(1);
    var first = new AtomicBoolean(true);
    var mcp =
        trackedMcp(
            closes,
            closed,
            new AtomicReference<>(),
            result ->
                first.getAndSet(false)
                    ? Mono.<McpSchema.InitializeResult>never().doOnCancel(canceled::countDown)
                    : Mono.just(result));
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var expired =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .method("POST")
                      .header("X-Expiring", "true")
                      .header("Content-Type", "application/json")
                      .header("Accept", "application/json, text/event-stream")
                      .body(INITIALIZE.getBytes(StandardCharsets.UTF_8)));
      assertEquals(401, expired.statusCode());
      assertEquals(
          "Bearer error=\"invalid_token\"",
          expired.headers().firstValue("WWW-Authenticate").orElseThrow());
      assertTrue(expired.headers().firstValue("Mcp-Session-Id").isEmpty());
      assertTrue(canceled.await(3, TimeUnit.SECONDS));
      assertTrue(closed.await(3, TimeUnit.SECONDS));
      assertEquals(1, closes.get());
      var retry =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .method("POST")
                      .header("Content-Type", "application/json")
                      .header("Accept", "application/json, text/event-stream")
                      .body(INITIALIZE.getBytes(StandardCharsets.UTF_8)));
      assertEquals(
          200, retry.statusCode(), "Expired initialization must release admission capacity");
    }

    assertEquals(2, closes.get());
  }

  @Test
  @Timeout(20)
  void expiresAFiniteInitializationDuringFinalFlushAndReleasesItsSession() throws Exception {
    var closes = new AtomicInteger();
    var closed = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var mcp = trackedMcp(closes, closed, new AtomicReference<>());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));
    app.afterRouteHandler(
        (request, _) -> {
          if (request.header("X-Expiring").isPresent()) {
            request.attribute("finite-handler-returned", true);
          }
        });
    app.beforeResponseFlush(
        (request, _) -> {
          if (request
              .attribute("finite-handler-returned")
              .filter(Boolean.TRUE::equals)
              .isPresent()) {
            try {
              new CountDownLatch(1).await(5, TimeUnit.SECONDS);
              throw new IOException("The finite final flush must be interrupted by expiry");
            } catch (InterruptedException failure) {
              interrupted.countDown();
              throw failure;
            }
          }
        });

    try (var test = TestServer.start(app)) {
      var expired =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .method("POST")
                      .header("X-Expiring", "true")
                      .header("Content-Type", "application/json")
                      .header("Accept", "application/json, text/event-stream")
                      .body(INITIALIZE.getBytes(StandardCharsets.UTF_8)));
      assertEquals(
          500, expired.statusCode(), "Later middleware follows generic interruption failure");
      assertTrue(interrupted.await(1, TimeUnit.SECONDS));
      assertTrue(closed.await(3, TimeUnit.SECONDS));
      assertEquals(1, closes.get());
      var retry =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .method("POST")
                      .header("Content-Type", "application/json")
                      .header("Accept", "application/json, text/event-stream")
                      .body(INITIALIZE.getBytes(StandardCharsets.UTF_8)));
      assertEquals(
          200, retry.statusCode(), "Failed finite delivery must release admission capacity");
    }

    assertEquals(2, closes.get());
  }

  @Test
  @Timeout(25)
  void closesALateFactoryResultWithoutSubscribingAfterApplicationShutdown() throws Exception {
    var closes = new AtomicInteger();
    var closed = new CountDownLatch(1);
    var factoryEntered = new CountDownLatch(1);
    var releaseFactory = new CountDownLatch(1);
    var subscriptions = new AtomicInteger();
    var mcp =
        trackedMcp(
            closes,
            closed,
            new AtomicReference<>(),
            result -> {
              factoryEntered.countDown();

              try {
                if (!releaseFactory.await(15, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("The test must release the native factory");
                }
              } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
              }

              return Mono.just(result).doOnSubscribe(_ -> subscriptions.incrementAndGet());
            });
    var app = new Shoostr();

    try (var selected = Executors.newSingleThreadExecutor();
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      app.extensions(mcp)
          .routes(routes -> routes.path("/mcp", group -> mcp.routes(group, selected)));

      try (var test = TestServer.start(app)) {
        var pending =
            workers.submit(
                () -> {
                  try {
                    return test.send(
                            request ->
                                request
                                    .path("/mcp")
                                    .method("POST")
                                    .header("Content-Type", "application/json")
                                    .header("Accept", "application/json, text/event-stream")
                                    .body(INITIALIZE.getBytes(StandardCharsets.UTF_8)))
                        .statusCode();
                  } catch (IOException _) {
                    return 0;
                  }
                });
        assertTrue(factoryEntered.await(3, TimeUnit.SECONDS));

        try {
          workers
              .submit(
                  () -> {
                    try {
                      app.close();
                    } catch (IOException failure) {
                      assertTrue(
                          failure.getCause() instanceof TimeoutException,
                          "A deliberately blocked borrowed handler can exhaust Jetty's drain timeout");
                    }

                    return null;
                  })
              .get(8, TimeUnit.SECONDS);
          assertEquals(
              0, closes.get(), "A blocked factory has not yet transferred its native handle");
        } finally {
          releaseFactory.countDown();
        }

        assertTrue(closed.await(3, TimeUnit.SECONDS));
        assertEquals("usable", selected.submit(() -> "usable").get(2, TimeUnit.SECONDS));
        assertEquals(0, subscriptions.get(), "Never subscribe to a late initialization result");
        int status = pending.get(2, TimeUnit.SECONDS);
        assertTrue(status == 0 || status == 503, "Late initialization must not report success");
      } finally {
        releaseFactory.countDown();
      }
    }

    assertEquals(1, closes.get());
  }

  @Test
  @Timeout(20)
  void preservesAFatalFactoryErrorInsteadOfReplacingItWithAnExpiryChallenge() throws Exception {
    var fatal = new LinkageError("Native factory failed fatally");
    var observed = new AtomicReference<Throwable>();
    var completed = new CountDownLatch(1);
    var first = new AtomicBoolean(true);
    var mcp =
        trackedMcp(
            new AtomicInteger(),
            new CountDownLatch(1),
            new AtomicReference<>(),
            Mono::just,
            Mono::empty,
            () -> {
              if (first.getAndSet(false)) {
                try {
                  new CountDownLatch(1).await(5, TimeUnit.SECONDS);
                } catch (InterruptedException _) {
                  throw fatal;
                }

                throw new IllegalStateException("The verified deadline must interrupt the factory");
              }
            });
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));
    app.afterRequest(
        outcome -> {
          var failure = outcome.applicationFailure();
          if (failure != null) {
            observed.set(failure);
          }

          completed.countDown();
        });

    try (var test = TestServer.start(app)) {
      try {
        test.send(
            request ->
                request
                    .path("/mcp")
                    .method("POST")
                    .header("X-Expiring", "true")
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .body(INITIALIZE.getBytes(StandardCharsets.UTF_8)));
      } catch (IOException _) {
        // Fatal failures abort the exchange rather than render an authentication challenge.
      }

      assertTrue(completed.await(3, TimeUnit.SECONDS));
      assertSame(fatal, observed.get());
      assertEquals(
          200,
          test.send(
                  request ->
                      request
                          .path("/mcp")
                          .method("POST")
                          .header("Content-Type", "application/json")
                          .header("Accept", "application/json, text/event-stream")
                          .body(INITIALIZE.getBytes(StandardCharsets.UTF_8)))
              .statusCode(),
          "A factory failure must release provisional admission capacity");
    }
  }

  private static Mcp<McpSyncServer> trackedMcp(
      AtomicInteger closes, CountDownLatch closed, AtomicReference<String> sessionId) {
    return trackedMcp(closes, closed, sessionId, Mono::just);
  }

  private static Mcp<McpSyncServer> trackedMcp(
      AtomicInteger closes,
      CountDownLatch closed,
      AtomicReference<String> sessionId,
      Function<McpSchema.InitializeResult, Mono<McpSchema.InitializeResult>> results) {
    return trackedMcp(closes, closed, sessionId, results, Mono::empty);
  }

  private static Mcp<McpSyncServer> trackedMcp(
      AtomicInteger closes,
      CountDownLatch closed,
      AtomicReference<String> sessionId,
      Function<McpSchema.InitializeResult, Mono<McpSchema.InitializeResult>> results,
      Supplier<Mono<Void>> cleanup) {
    return trackedMcp(closes, closed, sessionId, results, cleanup, () -> {});
  }

  private static Mcp<McpSyncServer> trackedMcp(
      AtomicInteger closes,
      CountDownLatch closed,
      AtomicReference<String> sessionId,
      Function<McpSchema.InitializeResult, Mono<McpSchema.InitializeResult>> results,
      Supplier<Mono<Void>> cleanup,
      Runnable beforeAllocation) {
    return Mcp.sync(
        new McpHttpSecurity(
            Set.of(),
            request ->
                new McpIdentity(
                    "alice:client",
                    McpTransportContext.EMPTY,
                    request.header("X-Expiring").isPresent() ? Instant.now().plusSeconds(1) : null),
            (_, _) -> {}),
        new McpSessionLimits(1, 1, Duration.ofMinutes(30)),
        transport ->
            McpServer.sync(
                    new McpStreamableServerTransportProvider() {
                      @Override
                      public void setSessionFactory(McpStreamableServerSession.Factory factory) {
                        transport.setSessionFactory(
                            parameters -> {
                              beforeAllocation.run();
                              var id = UUID.randomUUID().toString();
                              sessionId.set(id);
                              var session =
                                  new McpStreamableServerSession(
                                      id,
                                      McpSchema.ClientCapabilities.builder().build(),
                                      new McpSchema.Implementation("cleanup-client", "1.0"),
                                      Duration.ofSeconds(5),
                                      Map.of(),
                                      Map.of(),
                                      () ->
                                          Mono.fromRunnable(
                                                  () -> {
                                                    closes.incrementAndGet();
                                                    closed.countDown();
                                                  })
                                              .then(Mono.defer(cleanup)),
                                      McpJsonDefaults.getSchemaValidator());
                              var result =
                                  McpSchema.InitializeResult.builder(
                                          "2025-11-25",
                                          McpSchema.ServerCapabilities.builder().build(),
                                          new McpSchema.Implementation("cleanup-server", "1.0"))
                                      .build();
                              return new McpStreamableServerSession.McpStreamableServerSessionInit(
                                  session, results.apply(result));
                            });
                      }

                      @Override
                      public Mono<Void> notifyClients(String method, Object parameters) {
                        return transport.notifyClients(method, parameters);
                      }

                      @Override
                      public Mono<Void> closeGracefully() {
                        return transport.closeGracefully();
                      }

                      @Override
                      public List<String> protocolVersions() {
                        return transport.protocolVersions();
                      }
                    })
                .build());
  }
}
