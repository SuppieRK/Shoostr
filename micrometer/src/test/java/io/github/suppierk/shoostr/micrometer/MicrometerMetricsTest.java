package io.github.suppierk.shoostr.micrometer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.RequestOutcome;
import io.github.suppierk.shoostr.Shoostr;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MicrometerMetricsTest {
  @Test
  void recordsMissesApplicationErrorsAndTerminalTransportFailuresWithFiniteLabels()
      throws Exception {
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();
    var registry = new SimpleMeterRegistry();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.observe(new MicrometerMetrics(registry));
      app.afterRequest(outcomes::add);
      app.routes()
          .get(
              "/failure",
              (request, response) -> {
                throw new IllegalStateException("private");
              });
      app.routes().get("/transport", (request, response) -> response.text("ok"));
      app.afterResponseFlush(
          (request, response) -> {
            if ("/transport".equals(request.routePattern())) {
              throw new IOException("private");
            }
          });
      app.start();
      for (String path : new String[] {"/missing-a", "/missing-b", "/failure", "/transport"}) {
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.discarding());
        var outcome = outcomes.poll(5, TimeUnit.SECONDS);
        assertTrue(outcome != null);
        if ("/transport".equals(path)) {
          assertTrue(outcome.transportFailure() != null);
        }
      }
      assertEquals(
          2, registry.get("http.server.requests").tag("route", "UNMATCHED").timer().count());
      assertEquals(
          1, registry.get("http.server.requests").tag("error", "application").timer().count());
      assertEquals(
          1, registry.get("http.server.requests").tag("error", "transport").timer().count());
      assertEquals(0, registry.get("http.server.requests.active").longTaskTimer().activeTasks());
      assertEquals(3, registry.find("http.server.requests").timers().size());
    } finally {
      registry.close();
    }
  }

  @Test
  void leavesAnUnconfiguredRegistryUntouched() throws Exception {
    var registry = new SimpleMeterRegistry();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.start();
      assertEquals(
          404,
          client
              .send(
                  HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/missing"))
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.discarding())
              .statusCode());
      assertTrue(registry.getMeters().isEmpty());
    } finally {
      registry.close();
    }
  }

  @Test
  void countsActiveAndCompletedRequestsUsingRouteTemplates() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var completed = new CompletableFuture<Void>();

    var registry = new SimpleMeterRegistry();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.observe(new MicrometerMetrics(registry));
      app.afterRequest(outcome -> completed.complete(null));
      app.routes()
          .get(
              "/orders/{id}",
              (request, response) -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                response.text("ok");
              });
      app.start();
      var pending =
          client.sendAsync(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + app.port() + "/orders/private-id"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.discarding());

      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals(1, registry.get("http.server.requests.active").longTaskTimer().activeTasks());
      } finally {
        release.countDown();
      }

      assertEquals(200, pending.get(5, TimeUnit.SECONDS).statusCode());
      completed.get(5, TimeUnit.SECONDS);
      assertEquals(0, registry.get("http.server.requests.active").longTaskTimer().activeTasks());
      var timer =
          registry
              .get("http.server.requests")
              .tag("route", "/orders/{id}")
              .tag("method", "GET")
              .timer();
      assertEquals(1, timer.count());
      assertTrue(timer.totalTime(TimeUnit.NANOSECONDS) > 0);
      assertEquals("none", timer.getId().getTag("error"));
    } finally {
      registry.close();
    }
  }
}
