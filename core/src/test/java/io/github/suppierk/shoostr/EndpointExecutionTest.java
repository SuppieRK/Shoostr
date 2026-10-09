package io.github.suppierk.shoostr;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
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
          } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
          }
        });
    assertTrue(occupied.await(5, TimeUnit.SECONDS));
    var invoked = new AtomicBoolean();
    var completed = new CompletableFuture<RequestOutcome>();
    var completions = new AtomicInteger();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var driver = Executors.newVirtualThreadPerTaskExecutor()) {
      app.modifyServer(server -> server.setStopTimeout(0));
      app.routes()
          .get(
              "/queued",
              workers,
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
        assertInstanceOf(RejectedExecutionException.class, outcome.applicationFailure());
        assertFalse(invoked.get());
        assertEquals(1, completions.get());
        assertFalse(workers.isShutdown());
        release.countDown();
        assertEquals("usable", workers.submit(() -> "usable").get(5, TimeUnit.SECONDS));
        assertFalse(invoked.get());
        assertEquals(1, completions.get());

        try {
          sent.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException expected) {
          assertInstanceOf(IOException.class, expected.getCause());
        }
      }
    } finally {
      release.countDown();
      workers.shutdownNow();
    }
  }

  @Test
  void rejectsWrappedCallerRunsWithoutInvokingTheEndpoint() throws Exception {
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
          } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
          }
        });
    assertTrue(occupied.await(5, TimeUnit.SECONDS));
    var invoked = new AtomicBoolean();
    var workers = Executors.unconfigurableExecutorService(pool);

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.exception(
          RejectedExecutionException.class,
          (_, _, response) -> response.status(503).text("rejected"));
      app.routes()
          .get(
              "/inline",
              workers,
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
  void rendersUnavailableSelectedEndpointLocallyWithoutInvokingHandler() throws Exception {
    var invoked = new AtomicBoolean();
    var owned = ConcurrentHashMap.<Thread>newKeySet();
    var workers = executor("platform", owned);

    try (workers;
        var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes()
          .when(
              () -> false,
              routes ->
                  routes.get(
                      "/hidden",
                      workers,
                      (_, _) -> invoked.set(true),
                      extensions ->
                          extensions.status(
                              404,
                              (_, response) ->
                                  response.text(
                                      "local:" + owned.contains(Thread.currentThread())))));

      try (var test = TestServer.start(app)) {
        var response =
            test.send(request -> request.path("/hidden"), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode());
        assertEquals("local:true", response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertFalse(invoked.get());
      }
    }
  }

  @Test
  void preservesFrameworkAttributesAcrossSequentialHandoff() throws Exception {
    var workers = Executors.newFixedThreadPool(1);

    try (workers;
        var app = new Shoostr(Options.defaults().withPort(0))) {
      app.onRequestHeaders((request, _) -> request.attribute("value", "original"));
      app.routes()
          .get(
              "/attributes",
              workers,
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
    var workers = Executors.newFixedThreadPool(1);

    try (workers;
        var app = new Shoostr(Options.defaults().withPort(0))) {
      app.onRequestHeaders((_, _) -> context.set("admission-only"));
      app.routes()
          .get(
              "/context",
              workers,
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

    try (workers;
        var app = new Shoostr(Options.defaults().withPort(0))) {
      app.exception(
          RejectedExecutionException.class, (_, _, response) -> response.status(503).text("app"));
      app.routes()
          .get(
              "/rejected",
              workers,
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

  @ParameterizedTest(name = "{0}")
  @MethodSource("callbackPhases")
  void runsLocalBeforeAppCallbackOnSelectedThread(
      String phase,
      String executorType,
      BiConsumer<Extensions, Handler> localRegistration,
      BiConsumer<Shoostr, Handler> appRegistration)
      throws Exception {
    var events = new ArrayList<String>();
    var callbackThreads = new ArrayList<Thread>();
    var owned = ConcurrentHashMap.<Thread>newKeySet();
    var finished = new CompletableFuture<Void>();
    var workers = executor(executorType, owned);

    try (workers;
        var app = new Shoostr(Options.defaults().withPort(0))) {
      appRegistration.accept(
          app,
          (_, _) -> {
            callbackThreads.add(Thread.currentThread());
            events.add("app");
          });
      app.afterRequest(_ -> finished.complete(null));
      app.routes()
          .get(
              "/callbacks",
              workers,
              (_, response) -> {
                callbackThreads.add(Thread.currentThread());
                response.text("ok");
              },
              extensions ->
                  localRegistration.accept(
                      extensions,
                      (_, _) -> {
                        callbackThreads.add(Thread.currentThread());
                        events.add("local");
                      }));

      try (var test = TestServer.start(app)) {
        var response =
            test.send(request -> request.path("/callbacks"), HttpResponse.BodyHandlers.ofString());
        assertEquals("ok", response.body());
        finished.get(5, TimeUnit.SECONDS);
        assertEquals(List.of("local", "app"), events, phase);
        assertEquals(3, callbackThreads.size());
        assertTrue(owned.containsAll(callbackThreads), executorType);
      }
    }
  }

  @Test
  void keepsSseHandlerAndStreamWritesOnSelectedPlatformThread() throws Exception {
    var owned = ConcurrentHashMap.<Thread>newKeySet();
    Handler endpoint =
        (_, response) ->
            response
                .startEventStream()
                .send(Boolean.toString(owned.contains(Thread.currentThread())));
    var workers = executor("platform", owned);

    try (workers;
        var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes().sse("/events", workers, endpoint);

      try (var test = TestServer.start(app)) {
        var response =
            test.send(request -> request.path("/events"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("data: true\n\n", response.body());
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
    var workers = Executors.newFixedThreadPool(1);

    try (workers;
        var app = new Shoostr(Options.defaults().withPort(0))) {
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
      app.routes().get("/scoped", workers, endpoint);

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
    var owned = ConcurrentHashMap.<Thread>newKeySet();
    Handler endpoint =
        (_, response) -> response.text(Boolean.toString(owned.contains(Thread.currentThread())));
    var workers = executor("platform", owned);

    try (workers;
        var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes().get("/selected", workers, endpoint);

      try (var test = TestServer.start(app)) {
        var response =
            test.send(request -> request.path("/selected"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("true", response.body());
      }
    }
  }

  private static ExecutorService executor(String type, Set<Thread> owned) {
    ThreadFactory factory =
        task -> {
          var thread =
              "virtual".equals(type)
                  ? Thread.ofVirtual().unstarted(task)
                  : Thread.ofPlatform().unstarted(task);
          owned.add(thread);
          return thread;
        };
    return switch (type) {
      case "platform" -> Executors.newFixedThreadPool(1, factory);
      case "virtual" -> Executors.newThreadPerTaskExecutor(factory);
      case "fork-join" -> forkJoin(1, owned);
      case "wrapped-fork-join" -> Executors.unconfigurableExecutorService(forkJoin(1, owned));
      default -> throw new IllegalArgumentException(type);
    };
  }

  private static ForkJoinPool forkJoin(int parallelism, Set<Thread> owned) {
    return new ForkJoinPool(
        parallelism,
        pool -> {
          var worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
          owned.add(worker);
          return worker;
        },
        null,
        false);
  }

  private static Stream<Arguments> callbackPhases() {
    return Stream.of("platform", "virtual", "fork-join", "wrapped-fork-join")
        .flatMap(
            executorType ->
                Stream.of(
                    Arguments.of(
                        "matched",
                        executorType,
                        (BiConsumer<Extensions, Handler>) Extensions::onRouteMatched,
                        (BiConsumer<Shoostr, Handler>) Shoostr::onRouteMatched),
                    Arguments.of(
                        "before",
                        executorType,
                        (BiConsumer<Extensions, Handler>) Extensions::beforeRouteHandler,
                        (BiConsumer<Shoostr, Handler>) Shoostr::beforeRouteHandler),
                    Arguments.of(
                        "after",
                        executorType,
                        (BiConsumer<Extensions, Handler>) Extensions::afterRouteHandler,
                        (BiConsumer<Shoostr, Handler>) Shoostr::afterRouteHandler),
                    Arguments.of(
                        "before-flush",
                        executorType,
                        (BiConsumer<Extensions, Handler>) Extensions::beforeResponseFlush,
                        (BiConsumer<Shoostr, Handler>) Shoostr::beforeResponseFlush),
                    Arguments.of(
                        "after-flush",
                        executorType,
                        (BiConsumer<Extensions, Handler>) Extensions::afterResponseFlush,
                        (BiConsumer<Shoostr, Handler>) Shoostr::afterResponseFlush)));
  }
}
