package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.suppierk.shoostr.micrometer.MicrometerMetrics;
import io.github.suppierk.shoostr.testing.TestServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.Test;

class EndpointMetricsTest {
  @Test
  void recordsOneCompletedMetricAfterPlatformHandoff() throws Exception {
    var finished = new CompletableFuture<Void>();
    var transport = new QueuedThreadPool(16, 8);
    transport.setReservedThreads(0);
    var execution =
        new ExecutionSettings(
            transport, true, Executors.newFixedThreadPool(1), Set.of("/metrics/{id}"));
    var registry = new SimpleMeterRegistry();

    try (var app = new Shoostr(Options.defaults().withPort(0), execution)) {
      var metrics = new MicrometerMetrics(registry);
      metrics.allRequests();
      app.extensions(metrics).afterRequest(_ -> finished.complete(null));
      app.routes().get("/metrics/{id}", (_, response) -> response.text("ok"));

      try (var test = TestServer.start(app)) {
        assertEquals(200, test.send(request -> request.path("/metrics/42")).statusCode());
        finished.get(5, TimeUnit.SECONDS);
        assertEquals(
            1, registry.get("http.server.requests").tag("route", "/metrics/{id}").timer().count());
      }
    } finally {
      registry.close();
    }
  }
}
