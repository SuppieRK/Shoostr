package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.micrometer.MicrometerMetrics;
import io.github.suppierk.shoostr.testing.TestServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EndpointMetricsTest {
  @ParameterizedTest
  @ValueSource(strings = {"platform", "virtual", "fork-join", "wrapped-fork-join"})
  void recordsOneCompletedMetricAfterSelectedExecutorHandoff(String executorType) throws Exception {
    var owned = ConcurrentHashMap.<Thread>newKeySet();
    var handled = new CompletableFuture<Thread>();
    var finished = new CompletableFuture<Void>();
    var registry = new SimpleMeterRegistry();

    try (var workers = executor(executorType, owned);
        var app = new Shoostr(Options.defaults().withPort(0))) {
      var metrics = new MicrometerMetrics(registry);
      metrics.allRequests();
      app.extensions(metrics).afterRequest(_ -> finished.complete(null));
      app.routes()
          .get(
              "/metrics/{id}",
              workers,
              (_, response) -> {
                handled.complete(Thread.currentThread());
                response.text("ok");
              });

      try (var test = TestServer.start(app)) {
        assertEquals(200, test.send(request -> request.path("/metrics/42")).statusCode());
        assertTrue(owned.contains(handled.get(5, TimeUnit.SECONDS)));
        finished.get(5, TimeUnit.SECONDS);
        assertEquals(
            1, registry.get("http.server.requests").tag("route", "/metrics/{id}").timer().count());
      }

      assertFalse(workers.isShutdown());
    } finally {
      registry.close();
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
}
