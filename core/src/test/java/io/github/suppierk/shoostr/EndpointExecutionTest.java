package io.github.suppierk.shoostr;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class EndpointExecutionTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void cancelsQueuedSelectedRequestWithoutInvokingEndpoint(boolean wrapped) throws Exception {
    var workers =
        wrapped
            ? new ScheduledThreadPoolExecutor(1)
            : new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    var occupied = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    workers.execute(
        () -> {
          occupied.countDown();

          try {
            release.await();
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
          }
        });
    assertTrue(occupied.await(5, TimeUnit.SECONDS));
    var invoked = new AtomicBoolean();
    var completed = new CompletableFuture<RequestOutcome>();
    var completions = new AtomicInteger();
    var transport = new QueuedThreadPool(16, 8);
    transport.setReservedThreads(0);
    var execution = new ExecutionSettings(transport, true, workers, Set.of("/queued"));

    try (var app = new Shoostr(Options.defaults().withPort(0), execution);
        var driver = Executors.newVirtualThreadPerTaskExecutor()) {
      app.modifyServer(server -> server.setStopTimeout(0));
      app.routes()
          .get(
              "/queued",
              (_, response) -> {
                invoked.set(true);
                response.text("wrong");
              },
              extensions ->
                  extensions.afterRequest(
                      outcome -> {
                        completions.incrementAndGet();
                        completed.complete(outcome);
                      }));

      try (var test = TestServer.start(app)) {
        var sent = driver.submit(() -> test.send(request -> request.path("/queued")));
        await().atMost(Duration.ofSeconds(5)).until(() -> workers.getQueue().size() == 1);
        app.close();
        var outcome = completed.get(5, TimeUnit.SECONDS);
        assertEquals("/queued", outcome.routePattern());
        assertTrue(outcome.applicationFailure() instanceof RejectedExecutionException);
        assertFalse(invoked.get());
        assertEquals(1, completions.get());

        try {
          sent.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException expected) {
          assertTrue(expected.getCause() instanceof IOException);
        }
      }
    } finally {
      release.countDown();
      workers.shutdownNow();
    }
  }

  @Test
  void rejectsWrappedCallerRunsEvenWhenAdmissionThreadIsPlatform() throws Exception {
    var occupied = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var pool =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            new ThreadPoolExecutor.CallerRunsPolicy());
    pool.execute(
        () -> {
          occupied.countDown();

          try {
            release.await();
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
          }
        });
    assertTrue(occupied.await(5, TimeUnit.SECONDS));
    var invoked = new AtomicBoolean();
    var transport = new QueuedThreadPool(16, 8);
    transport.setReservedThreads(0);
    var execution =
        new ExecutionSettings(
            transport, false, Executors.unconfigurableExecutorService(pool), Set.of("/inline"));

    try (var app = new Shoostr(Options.defaults().withPort(0), execution)) {
      app.exception(
          RejectedExecutionException.class,
          (_, _, response) -> response.status(503).text("rejected"));
      app.routes()
          .get(
              "/inline",
              (_, response) -> {
                invoked.set(true);
                response.text("wrong");
              });

      try (var test = TestServer.start(app)) {
        assertEquals(503, test.send(request -> request.path("/inline")).statusCode());
        assertFalse(invoked.get());
      }
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void keepsRequestLiveUntilInterruptedPlatformHandlerUnwinds() throws Exception {
    var entered = new CountDownLatch(1);
    var unwind = new CompletableFuture<String>();
    var completed = new CompletableFuture<RequestOutcome>();
    var transport = new QueuedThreadPool(16, 8);
    transport.setReservedThreads(0);
    var execution =
        new ExecutionSettings(transport, true, Executors.newFixedThreadPool(1), Set.of("/active"));

    try (var app = new Shoostr(Options.defaults().withPort(0), execution);
        var driver = Executors.newVirtualThreadPerTaskExecutor()) {
      app.modifyServer(server -> server.setStopTimeout(0));
      app.afterRequest(completed::complete);
      app.routes()
          .get(
              "/active",
              (request, _) -> {
                entered.countDown();

                try {
                  new CountDownLatch(1).await();
                } catch (InterruptedException failure) {
                  unwind.complete(request.routePattern().orElseThrow());
                  throw failure;
                }
              });

      try (var test = TestServer.start(app)) {
        var sent = driver.submit(() -> test.send(request -> request.path("/active")));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        app.close();
        assertEquals("/active", unwind.get(5, TimeUnit.SECONDS));
        assertTrue(
            completed.get(5, TimeUnit.SECONDS).applicationFailure()
                instanceof InterruptedException);

        try {
          sent.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException expected) {
          assertTrue(expected.getCause() instanceof IOException);
        }
      }
    }
  }

  @Test
  void rendersUnavailableSelectedEndpointLocallyWithoutInvokingHandler() throws Exception {
    var invoked = new AtomicBoolean();
    var transport = new QueuedThreadPool(16, 8);
    transport.setReservedThreads(0);
    var execution =
        new ExecutionSettings(transport, true, Executors.newFixedThreadPool(1), Set.of("/hidden"));

    try (var app = new Shoostr(Options.defaults().withPort(0), execution)) {
      app.routes()
          .when(
              () -> false,
              routes ->
                  routes.get(
                      "/hidden",
                      (_, _) -> invoked.set(true),
                      extensions ->
                          extensions.status(
                              404,
                              (_, response) ->
                                  response.text("local:" + Thread.currentThread().isVirtual()))));

      try (var test = TestServer.start(app)) {
        var response =
            test.send(request -> request.path("/hidden"), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode());
        assertEquals("local:false", response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertFalse(invoked.get());
      }
    }
  }

  @Test
  void shutsDownAcceptedPlatformPoolWhenStartupFails() throws Exception {
    var workers = Executors.newFixedThreadPool(1);
    var execution = new ExecutionSettings(new QueuedThreadPool(16, 8), true, workers, Set.of());

    try (var app = new Shoostr(Options.defaults().withPort(0), execution)) {
      app.extensions(
          new Extension<Void>() {
            @Override
            public void beforeStart() {
              throw new IllegalStateException("startup failed");
            }
          });
      assertThrows(IllegalStateException.class, app::start);
      assertTrue(workers.isShutdown());
    }
  }

  @Test
  void doesNotTakeOwnershipOfRejectedCallerRunsPool() {
    try (var workers =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            new ThreadPoolExecutor.CallerRunsPolicy())) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new ExecutionSettings(
                  new QueuedThreadPool(16, 8), true, workers, Set.of("/selected")));
      assertFalse(workers.isShutdown());
    }
  }

  @Test
  void preservesFrameworkAttributesAcrossSequentialHandoff() throws Exception {
    var transport = new QueuedThreadPool(16, 8);
    transport.setReservedThreads(0);
    var execution =
        new ExecutionSettings(
            transport, true, Executors.newFixedThreadPool(1), Set.of("/attributes"));

    try (var app = new Shoostr(Options.defaults().withPort(0), execution)) {
      app.onRequestHeaders((request, _) -> request.attribute("value", "original"));
      app.routes()
          .get(
              "/attributes",
              (request, response) ->
                  response.text(request.attribute("value").orElseThrow().toString()));

      try (var test = TestServer.start(app)) {
        assertEquals(
            "original",
            test.send(request -> request.path("/attributes"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void doesNotCopyArbitraryAdmissionThreadLocal() throws Exception {
    var context = new ThreadLocal<String>();
    var transport = new QueuedThreadPool(16, 8);
    transport.setReservedThreads(0);
    var execution =
        new ExecutionSettings(transport, true, Executors.newFixedThreadPool(1), Set.of("/context"));

    try (var app = new Shoostr(Options.defaults().withPort(0), execution)) {
      app.onRequestHeaders((_, _) -> context.set("admission-only"));
      app.routes()
          .get(
              "/context",
              (_, response) -> response.text(context.get() == null ? "absent" : "copied"));

      try (var test = TestServer.start(app)) {
        assertEquals(
            "absent",
            test.send(request -> request.path("/context"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void retainsLocalExceptionRendererWhenPlatformSubmissionIsRejected() throws Exception {
    var invoked = new AtomicBoolean();
    var workers = Executors.newFixedThreadPool(1);
    var transport = new QueuedThreadPool(16, 8);
    transport.setReservedThreads(0);
    var execution = new ExecutionSettings(transport, true, workers, Set.of("/rejected"));

    try (var app = new Shoostr(Options.defaults().withPort(0), execution)) {
      app.exception(
          RejectedExecutionException.class, (_, _, response) -> response.status(503).text("app"));
      app.routes()
          .get(
              "/rejected",
              (_, response) -> {
                invoked.set(true);
                response.text("endpoint");
              },
              extensions ->
                  extensions.exception(
                      RejectedExecutionException.class,
                      (_, _, response) -> response.status(503).text("local")));

      try (var test = TestServer.start(app)) {
        workers.shutdown();
        var response =
            test.send(request -> request.path("/rejected"), HttpResponse.BodyHandlers.ofString());
        assertEquals(503, response.statusCode());
        assertEquals("local", response.body());
        assertFalse(invoked.get());
      }
    }
  }

  @Test
  void shutsDownSuppliedPlatformPoolWhenClosedBeforeStartup() throws Exception {
    var workers = Executors.newFixedThreadPool(1);
    var transport = new QueuedThreadPool(16, 8);
    var execution = new ExecutionSettings(transport, true, workers, Set.of());
    var app = new Shoostr(Options.defaults().withPort(0), execution);
    app.close();
    app.close();
    assertTrue(workers.isShutdown());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("callbackPhases")
  void runsLocalBeforeAppCallbackOnSelectedThread(
      String phase,
      BiConsumer<Extensions, Handler> localRegistration,
      BiConsumer<Shoostr, Handler> appRegistration)
      throws Exception {
    var events = new ArrayList<String>();
    var finished = new CompletableFuture<Void>();
    var transport = new QueuedThreadPool(16, 8);
    transport.setReservedThreads(0);
    var execution =
        new ExecutionSettings(
            transport, true, Executors.newFixedThreadPool(1), Set.of("/callbacks"));

    try (var app = new Shoostr(Options.defaults().withPort(0), execution)) {
      appRegistration.accept(
          app, (_, _) -> events.add("app:" + Thread.currentThread().isVirtual()));
      app.afterRequest(_ -> finished.complete(null));
      app.routes()
          .get(
              "/callbacks",
              (_, response) -> response.text("ok"),
              extensions ->
                  localRegistration.accept(
                      extensions,
                      (_, _) -> events.add("local:" + Thread.currentThread().isVirtual())));

      try (var test = TestServer.start(app)) {
        var response =
            test.send(request -> request.path("/callbacks"), HttpResponse.BodyHandlers.ofString());
        assertEquals("ok", response.body());
        finished.get(5, TimeUnit.SECONDS);
        assertEquals(List.of("local:false", "app:false"), events, phase);
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"A,true:true", "B,true:true", "C,false:false", "D,true:false"})
  void usesDeclaredAdmissionAndMatchedThreadKinds(String model, String expected) throws Exception {
    ExecutionSettings execution = null;
    if (!"A".equals(model)) {
      var transport = new QueuedThreadPool(16, 8);
      transport.setReservedThreads(0);
      execution =
          new ExecutionSettings(
              transport,
              !"C".equals(model),
              "D".equals(model) ? Executors.newFixedThreadPool(1) : null,
              "D".equals(model) ? Set.of("/thread-kind") : Set.of());
    }

    try (var app = new Shoostr(Options.defaults().withPort(0), execution)) {
      app.onRequestHeaders(
          (request, _) -> request.attribute("admission", Thread.currentThread().isVirtual()));
      app.routes()
          .get(
              "/thread-kind",
              (request, response) ->
                  response.text(
                      request.attribute("admission").orElseThrow()
                          + ":"
                          + Thread.currentThread().isVirtual()));

      try (var test = TestServer.start(app)) {
        var response =
            test.send(
                request -> request.path("/thread-kind"), HttpResponse.BodyHandlers.ofString());
        assertEquals(expected, response.body());
      }
    }
  }

  @Test
  void keepsSseHandlerAndStreamWritesOnSelectedPlatformThread() throws Exception {
    Handler endpoint =
        (_, response) ->
            response.startEventStream().send(Boolean.toString(Thread.currentThread().isVirtual()));
    var transport = new QueuedThreadPool(16, 8);
    transport.setReservedThreads(0);
    var workers = Executors.newFixedThreadPool(1);
    var execution = new ExecutionSettings(transport, true, workers, Set.of("/events"));

    try (var app = new Shoostr(Options.defaults().withPort(0), execution)) {
      app.routes().sse("/events", endpoint);

      try (var test = TestServer.start(app)) {
        var response =
            test.send(request -> request.path("/events"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("data: false\n\n", response.body());
      }
    }
  }

  @Test
  void restoresInstrumentationScopesOnTheirOwningThreads() throws Exception {
    var context = new ThreadLocal<String>();
    var events = Collections.synchronizedList(new ArrayList<String>());
    var completed = new CompletableFuture<RequestOutcome>();
    var admissionRestored = new AtomicBoolean();
    var workerRestored = new AtomicBoolean();
    Handler endpoint = (_, response) -> response.text(Objects.requireNonNull(context.get()));
    var transport = new QueuedThreadPool(16, 8);
    transport.setReservedThreads(0);
    var workers = Executors.newFixedThreadPool(1);
    var execution = new ExecutionSettings(transport, true, workers, Set.of("/scoped"));

    try (var app = new Shoostr(Options.defaults().withPort(0), execution)) {
      app.observe(
          _ -> {
            var admission = Thread.currentThread();
            context.set("server-context");
            return new RequestObservation() {
              @Override
              public AutoCloseable attach() {
                var owner = Thread.currentThread();
                context.set("server-context");
                events.add("worker-attach");
                return () -> {
                  context.remove();
                  workerRestored.set(Thread.currentThread() == owner && context.get() == null);
                  events.add("worker-close");
                };
              }

              @Override
              public void close() {
                context.remove();
                admissionRestored.set(Thread.currentThread() == admission && context.get() == null);
                events.add("admission-close");
              }

              @Override
              public void complete(RequestOutcome outcome) {
                events.add("complete");
                completed.complete(outcome);
              }
            };
          });
      app.routes().get("/scoped", endpoint);

      try (var test = TestServer.start(app)) {
        var response =
            test.send(request -> request.path("/scoped"), HttpResponse.BodyHandlers.ofString());
        assertEquals("server-context", response.body());
        assertEquals(200, completed.get(5, TimeUnit.SECONDS).statusCode());
        assertTrue(admissionRestored.get());
        assertTrue(workerRestored.get());
        assertEquals(
            List.of("admission-close", "worker-attach", "worker-close", "complete"), events);
      }
    }
  }

  @Test
  void handlesSelectedEndpointOnPlatformThread() throws Exception {
    Handler endpoint =
        (_, response) -> response.text(Boolean.toString(Thread.currentThread().isVirtual()));
    var transport = new QueuedThreadPool(16, 8);
    transport.setReservedThreads(0);
    var workers =
        Executors.newFixedThreadPool(
            2, Thread.ofPlatform().inheritInheritableThreadLocals(false).factory());
    var execution = new ExecutionSettings(transport, true, workers, Set.of("/selected"));

    try (var app = new Shoostr(Options.defaults().withPort(0), execution)) {
      app.routes().get("/selected", endpoint);

      try (var test = TestServer.start(app)) {
        var response =
            test.send(request -> request.path("/selected"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("false", response.body());
      }
    }
  }

  private static Stream<Arguments> callbackPhases() {
    return Stream.of(
        Arguments.of(
            "matched",
            (BiConsumer<Extensions, Handler>) Extensions::onRouteMatched,
            (BiConsumer<Shoostr, Handler>) Shoostr::onRouteMatched),
        Arguments.of(
            "before",
            (BiConsumer<Extensions, Handler>) Extensions::beforeRouteHandler,
            (BiConsumer<Shoostr, Handler>) Shoostr::beforeRouteHandler),
        Arguments.of(
            "after",
            (BiConsumer<Extensions, Handler>) Extensions::afterRouteHandler,
            (BiConsumer<Shoostr, Handler>) Shoostr::afterRouteHandler),
        Arguments.of(
            "before-flush",
            (BiConsumer<Extensions, Handler>) Extensions::beforeResponseFlush,
            (BiConsumer<Shoostr, Handler>) Shoostr::beforeResponseFlush),
        Arguments.of(
            "after-flush",
            (BiConsumer<Extensions, Handler>) Extensions::afterResponseFlush,
            (BiConsumer<Shoostr, Handler>) Shoostr::afterResponseFlush));
  }
}
