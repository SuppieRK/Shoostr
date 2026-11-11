package io.github.suppierk.shoostr.mcp;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Lifecycle;
import io.github.suppierk.shoostr.RouteExtension;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import java.io.IOException;
import java.net.Socket;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import reactor.core.publisher.Sinks.Empty;
import reactor.core.publisher.Sinks.One;

class McpInitializationCleanupTest {
  private static final String INITIALIZE = initializationRequest();

  private static String initializationRequest() {
    return
        """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
          "protocolVersion":"2025-11-25","capabilities":{},
          "clientInfo":{"name":"initialization-cleanup","version":"1.0"}}}
        """;
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
            (ignoredMcpIdentity, ignoredJsonRpcMessage) -> {}),
        new McpSessionLimits(1, 1, Duration.ofMinutes(30)),
        transport ->
            McpServer.sync(
                    trackedTransport(
                        transport, closes, closed, sessionId, results, cleanup, beforeAllocation))
                .build());
  }

  private static McpStreamableServerTransportProvider trackedTransport(
      McpStreamableServerTransportProvider transport,
      AtomicInteger closes,
      CountDownLatch closed,
      AtomicReference<String> sessionId,
      Function<McpSchema.InitializeResult, Mono<McpSchema.InitializeResult>> results,
      Supplier<Mono<Void>> cleanup,
      Runnable beforeAllocation) {
    return new McpStreamableServerTransportProvider() {
      @Override
      public void setSessionFactory(McpStreamableServerSession.Factory factory) {
        transport.setSessionFactory(
            parameters -> {
              beforeAllocation.run();
              var id = UUID.randomUUID().toString();
              sessionId.set(id);
              var session = trackedSession(id, closes, closed, cleanup);
              var result = initializationResult();

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
    };
  }

  private static McpStreamableServerSession trackedSession(
      String id, AtomicInteger closes, CountDownLatch closed, Supplier<Mono<Void>> cleanup) {
    return new McpStreamableServerSession(
        id,
        McpSchema.ClientCapabilities.builder().build(),
        McpSchema.Implementation.builder("cleanup-client", "1.0").build(),
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
  }

  private static InitializeResult initializationResult() {
    return McpSchema.InitializeResult.builder(
            "2025-11-25",
            McpSchema.ServerCapabilities.builder().build(),
            McpSchema.Implementation.builder("cleanup-server", "1.0").build())
        .build();
  }

  @Test
  @Timeout(15)
  void closesTheNewSessionExactlyOnceWhenInitializationCannotFlush() throws Exception {
    var closes = new AtomicInteger();
    var closed = new CountDownLatch(1);
    var sessionId = new AtomicReference<String>();
    var mcp = trackedMcp(closes, closed, sessionId);
    var app = new Shoostr();
    app.routes(routes -> routes.path("/mcp", mcp::routes));
    var fail = new AtomicBoolean(true);
    app.beforeResponseFlush(
        (ignored, ignoredResponse) -> {
          if (fail.getAndSet(false)) {
            throw new IOException("Initialization response delivery failed");
          }
        });

    try (var test = TestServer.start(app)) {
      var response =
          sendResponseForClosesTheNewSessionExactlyOnceWhenInitializationCannotFlush(test);

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

  private HttpResponse<byte[]>
      sendResponseForClosesTheNewSessionExactlyOnceWhenInitializationCannotFlush(TestServer test)
          throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/mcp")
                .method("POST")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .body(INITIALIZE.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  @Timeout(15)
  void closesTheNewSessionWhenTheClientAbandonsInitialization() throws Exception {
    var closes = new AtomicInteger();
    var closed = new CountDownLatch(1);
    var sessionId = new AtomicReference<String>();
    var mcp = trackedMcp(closes, closed, sessionId);
    var app = new Shoostr();
    app.routes(routes -> routes.path("/mcp", mcp::routes));
    var prepared = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var completed = new CountDownLatch(1);
    var failedTransport = new AtomicBoolean();
    app.beforeResponseFlush(
        (ignoredRequest, ignoredResponse) -> {
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
        var headers = createHeadersForClosesTheNewSessionWhenTheClientAbandonsInitialization(body);
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

  private String createHeadersForClosesTheNewSessionWhenTheClientAbandonsInitialization(
      byte[] body) {
    return "POST /mcp HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
        + "Content-Type: application/json\r\nAccept: application/json, text/event-stream\r\n"
        + "Content-Length: "
        + body.length
        + "\r\n\r\n";
  }

  @Test
  @Timeout(15)
  void closesTheAllocatedSessionWhenNativeInitializationFails() throws Exception {
    var closes = new AtomicInteger();
    var closed = new CountDownLatch(1);
    var sessionId = new AtomicReference<String>();
    var mcp =
        createMcpForClosesTheAllocatedSessionWhenNativeInitializationFails(
            closes, closed, sessionId);
    var app = new Shoostr();
    app.routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var response = sendResponseForClosesTheAllocatedSessionWhenNativeInitializationFails(test);

      assertEquals(500, response.statusCode());
      assertTrue(response.headers().firstValue("Mcp-Session-Id").isEmpty());
      assertTrue(closed.await(3, TimeUnit.SECONDS));
      assertEquals(1, closes.get());
    }

    assertEquals(1, closes.get());
  }

  private Mcp<McpSyncServer> createMcpForClosesTheAllocatedSessionWhenNativeInitializationFails(
      AtomicInteger closes, CountDownLatch closed, AtomicReference<String> sessionId) {
    return trackedMcp(
        closes,
        closed,
        sessionId,
        ignoredInitializeResult -> Mono.error(new IOException("Native initialization failed")));
  }

  private HttpResponse<byte[]>
      sendResponseForClosesTheAllocatedSessionWhenNativeInitializationFails(TestServer test)
          throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/mcp")
                .method("POST")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .body(INITIALIZE.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  @Timeout(15)
  void closesTheAllocatedSessionWhenItsInitializationResultCannotBeSerialized() throws Exception {
    var closes = new AtomicInteger();
    var closed = new CountDownLatch(1);
    var sessionId = new AtomicReference<String>();
    var mcp =
        createMcpForClosesTheAllocatedSessionWhenItsInitializationResultCannotBeSerialized(
            closes, closed, sessionId);
    var app = new Shoostr();
    app.routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var response =
          sendResponseForClosesTheAllocatedSessionWhenItsInitializationResultCannotBeSerialized(
              test);

      assertEquals(500, response.statusCode());
      assertTrue(response.headers().firstValue("Mcp-Session-Id").isEmpty());
      assertTrue(closed.await(3, TimeUnit.SECONDS));
      assertEquals(1, closes.get());
    }

    assertEquals(1, closes.get());
  }

  private Mcp<McpSyncServer>
      createMcpForClosesTheAllocatedSessionWhenItsInitializationResultCannotBeSerialized(
          AtomicInteger closes, CountDownLatch closed, AtomicReference<String> sessionId) {
    return trackedMcp(
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
  }

  private HttpResponse<byte[]>
      sendResponseForClosesTheAllocatedSessionWhenItsInitializationResultCannotBeSerialized(
          TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/mcp")
                .method("POST")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .body(INITIALIZE.getBytes(StandardCharsets.UTF_8)));
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
    class ClosingSignal implements RouteExtension<Object> {
      @Override
      public Object configure(Lifecycle lifecycle, Object inherited) {
        return this;
      }

      @Override
      public void close() {
        closingExtensions.countDown();
      }
    }

    app.routes(
        routes ->
            routes
                .path("/mcp", mcp::routes)
                .get(
                    "/cleanup-witness",
                    (request, response) -> response.status(204),
                    lifecycle -> lifecycle.withExtension(new ClosingSignal())));
    var fail = new AtomicBoolean(true);
    app.beforeResponseFlush(
        (ignoredRequest, ignoredResponse) -> {
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
          submitStoppingForApplicationShutdownWaitsForAnAlreadyClaimedInitializationCleanup(
              closer, app);

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

  private Future<Object>
      submitStoppingForApplicationShutdownWaitsForAnAlreadyClaimedInitializationCleanup(
          ExecutorService closer, Shoostr app) {
    return closer.submit(
        () -> {
          app.close();

          return null;
        });
  }

  @Test
  @Timeout(25)
  void shutdownCancelsPendingInitializationAndAwaitsItsNativeCleanupOnBorrowedExecutor()
      throws Exception {
    var closes = new AtomicInteger();
    var closed = new CountDownLatch(1);
    var subscribed = new CountDownLatch(1);
    var canceled = new CountDownLatch(1);
    var held = Sinks.<McpSchema.InitializeResult>one();
    var releaseCleanup = Sinks.<Void>empty();
    var mcp =
        createMcpWithPendingCleanup(closes, closed, held, subscribed, canceled, releaseCleanup);
    var app = new Shoostr();

    try (var selected = Executors.newSingleThreadExecutor();
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      app.routes(routes -> routes.path("/mcp", group -> mcp.routes(group, selected)));

      try (var test = TestServer.start(app)) {
        final var pending = submitPendingInitialization(workers, test);

        assertTrue(subscribed.await(3, TimeUnit.SECONDS));

        var stopping = submitApplicationShutdown(workers, app);

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

  private Mcp<McpSyncServer> createMcpWithPendingCleanup(
      AtomicInteger closes,
      CountDownLatch closed,
      One<InitializeResult> held,
      CountDownLatch subscribed,
      CountDownLatch canceled,
      Empty<Void> releaseCleanup) {
    return trackedMcp(
        closes,
        closed,
        new AtomicReference<>(),
        ignoredInitializeResult ->
            held.asMono()
                .doOnSubscribe(ignoredSubscription -> subscribed.countDown())
                .doOnCancel(canceled::countDown),
        releaseCleanup::asMono);
  }

  private Future<HttpResponse<byte[]>> submitPendingInitialization(
      ExecutorService workers, TestServer test) {
    return workers.submit(
        () ->
            test.send(
                request ->
                    request
                        .path("/mcp")
                        .method("POST")
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .body(INITIALIZE.getBytes(StandardCharsets.UTF_8))));
  }

  private Future<Object> submitApplicationShutdown(ExecutorService workers, Shoostr app) {
    return workers.submit(
        () -> {
          app.close();

          return null;
        });
  }

  @Test
  @Timeout(20)
  void cancelsAndClosesNativeInitializationThatOutlivesItsVerifiedToken() throws Exception {
    var closes = new AtomicInteger();
    var closed = new CountDownLatch(1);
    var canceled = new CountDownLatch(1);
    var first = new AtomicBoolean(true);
    var mcp =
        createMcpForCancelsAndClosesNativeInitializationThatOutlivesItsVerifiedToken(
            closes, closed, first, canceled);
    var app = new Shoostr();
    app.routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var expired =
          sendExpiredRequestForCancelsAndClosesNativeInitializationThatOutlivesItsVerifiedToken(
              test);

      assertEquals(401, expired.statusCode());
      assertEquals(
          "Bearer error=\"invalid_token\"",
          expired.headers().firstValue("WWW-Authenticate").orElseThrow());
      assertTrue(expired.headers().firstValue("Mcp-Session-Id").isEmpty());
      assertTrue(canceled.await(3, TimeUnit.SECONDS));
      assertTrue(closed.await(3, TimeUnit.SECONDS));
      assertEquals(1, closes.get());

      var retry =
          sendRetryRequestForCancelsAndClosesNativeInitializationThatOutlivesItsVerifiedToken(test);

      assertEquals(
          200, retry.statusCode(), "Expired initialization must release admission capacity");
    }

    assertEquals(2, closes.get());
  }

  private Mcp<McpSyncServer>
      createMcpForCancelsAndClosesNativeInitializationThatOutlivesItsVerifiedToken(
          AtomicInteger closes,
          CountDownLatch closed,
          AtomicBoolean first,
          CountDownLatch canceled) {
    return trackedMcp(
        closes,
        closed,
        new AtomicReference<>(),
        result ->
            first.getAndSet(false)
                ? Mono.<McpSchema.InitializeResult>never().doOnCancel(canceled::countDown)
                : Mono.just(result));
  }

  private HttpResponse<byte[]>
      sendExpiredRequestForCancelsAndClosesNativeInitializationThatOutlivesItsVerifiedToken(
          TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/mcp")
                .method("POST")
                .header("X-Expiring", "true")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .body(INITIALIZE.getBytes(StandardCharsets.UTF_8)));
  }

  private HttpResponse<byte[]>
      sendRetryRequestForCancelsAndClosesNativeInitializationThatOutlivesItsVerifiedToken(
          TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/mcp")
                .method("POST")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .body(INITIALIZE.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  @Timeout(20)
  void expiresFiniteInitializationDuringFinalFlushAndReleasesItsSession() throws Exception {
    var closes = new AtomicInteger();
    var closed = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var mcp = trackedMcp(closes, closed, new AtomicReference<>());
    var app = new Shoostr();
    app.routes(routes -> routes.path("/mcp", mcp::routes));
    app.afterRouteHandler(
        (request, ignoredResponse) -> {
          if (request.header("X-Expiring").isPresent()) {
            request.attribute("finite-handler-returned", true);
          }
        });
    app.beforeResponseFlush(
        (request, ignoredResponse) -> {
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
          sendExpiredRequestForExpiresFiniteInitializationDuringFinalFlushAndReleasesItsSession(
              test);

      assertEquals(
          500, expired.statusCode(), "Later middleware follows generic interruption failure");
      assertTrue(interrupted.await(1, TimeUnit.SECONDS));
      assertTrue(closed.await(3, TimeUnit.SECONDS));
      assertEquals(1, closes.get());

      var retry =
          sendRetryRequestForExpiresFiniteInitializationDuringFinalFlushAndReleasesItsSession(test);

      assertEquals(
          200, retry.statusCode(), "Failed finite delivery must release admission capacity");
    }

    assertEquals(2, closes.get());
  }

  private HttpResponse<byte[]>
      sendExpiredRequestForExpiresFiniteInitializationDuringFinalFlushAndReleasesItsSession(
          TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/mcp")
                .method("POST")
                .header("X-Expiring", "true")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .body(INITIALIZE.getBytes(StandardCharsets.UTF_8)));
  }

  private HttpResponse<byte[]>
      sendRetryRequestForExpiresFiniteInitializationDuringFinalFlushAndReleasesItsSession(
          TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/mcp")
                .method("POST")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .body(INITIALIZE.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  @Timeout(25)
  void closesLateFactoryResultWithoutSubscribingAfterApplicationShutdown() throws Exception {
    var closes = new AtomicInteger();
    var closed = new CountDownLatch(1);
    var factoryEntered = new CountDownLatch(1);
    var releaseFactory = new CountDownLatch(1);
    var subscriptions = new AtomicInteger();
    var mcp =
        createMcpForClosesLateFactoryResultWithoutSubscribingAfterApplicationShutdown(
            closes, closed, factoryEntered, releaseFactory, subscriptions);
    var app = new Shoostr();

    try (var selected = Executors.newSingleThreadExecutor();
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      app.routes(routes -> routes.path("/mcp", group -> mcp.routes(group, selected)));

      try (var test = TestServer.start(app)) {
        final var pending =
            submitPendingForClosesLateFactoryResultWithoutSubscribingAfterApplicationShutdown(
                workers, test);

        assertTrue(factoryEntered.await(3, TimeUnit.SECONDS));

        try {
          workers
              .submit(
                  () -> {
                    try {
                      app.close();
                    } catch (IOException failure) {
                      assertInstanceOf(
                          TimeoutException.class,
                          failure.getCause(),
                          "A deliberately blocked borrowed handler can "
                              + "exhaust Jetty's drain timeout");
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

  private Mcp<McpSyncServer>
      createMcpForClosesLateFactoryResultWithoutSubscribingAfterApplicationShutdown(
          AtomicInteger closes,
          CountDownLatch closed,
          CountDownLatch factoryEntered,
          CountDownLatch releaseFactory,
          AtomicInteger subscriptions) {
    return trackedMcp(
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

          return Mono.just(result)
              .doOnSubscribe(ignoredSubscription -> subscriptions.incrementAndGet());
        });
  }

  private Future<Integer>
      submitPendingForClosesLateFactoryResultWithoutSubscribingAfterApplicationShutdown(
          ExecutorService workers, TestServer test) {
    return workers.submit(
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
          } catch (IOException ignoredFailure) {
            return 0;
          }
        });
  }

  @Test
  @Timeout(20)
  void preservesFatalFactoryErrorInsteadOfReplacingItWithAnExpiryChallenge() throws Exception {
    var fatal = new LinkageError("Native factory failed fatally");
    var observed = new AtomicReference<Throwable>();
    var completed = new CountDownLatch(1);
    var first = new AtomicBoolean(true);
    var mcp =
        createMcpForPreservesFatalFactoryErrorInsteadOfReplacingItWithAnExpiryChallenge(
            first, fatal);
    var app = new Shoostr();
    app.routes(routes -> routes.path("/mcp", mcp::routes));
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
      } catch (IOException ignoredFailure) {
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

  private Mcp<McpSyncServer>
      createMcpForPreservesFatalFactoryErrorInsteadOfReplacingItWithAnExpiryChallenge(
          AtomicBoolean first, LinkageError fatal) {
    return trackedMcp(
        new AtomicInteger(),
        new CountDownLatch(1),
        new AtomicReference<>(),
        Mono::just,
        Mono::empty,
        () -> {
          if (first.getAndSet(false)) {
            try {
              new CountDownLatch(1).await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignoredFailure) {
              throw fatal;
            }

            throw new IllegalStateException("The verified deadline must interrupt the factory");
          }
        });
  }
}
