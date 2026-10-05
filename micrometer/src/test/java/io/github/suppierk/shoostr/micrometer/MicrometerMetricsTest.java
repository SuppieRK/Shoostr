package io.github.suppierk.shoostr.micrometer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.RequestOutcome;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MicrometerMetricsTest {
  @ParameterizedTest
  @CsvSource({"200, none", "404, none", "500, server"})
  @Timeout(10)
  void recordsExactTagsForExplicitResponsesOnMatchedTemplates(int status, String error)
      throws Exception {
    var registry = new SimpleMeterRegistry();
    var completed = new CompletableFuture<Void>();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      var metrics = new MicrometerMetrics(registry);
      metrics.allRequests();
      app.extensions(metrics);
      app.afterRequest(_ -> completed.complete(null));
      app.routes().get("/status/{id}", (_, response) -> response.status(status).text("explicit"));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request -> request.path("/status/private-id").timeout(Duration.ofSeconds(3)),
                HttpResponse.BodyHandlers.discarding());

        assertEquals(status, result.statusCode());
        completed.get(3, TimeUnit.SECONDS);
        assertCompletedTimerTags(registry, "/status/{id}", status, error);
        assertEquals(1, registry.find("http.server.requests").timers().size());
      }
    } finally {
      registry.close();
    }
  }

  @Test
  @Timeout(10)
  void recordsUnmatched404WithExactTagsWithoutTheRequestedPath() throws Exception {
    var registry = new SimpleMeterRegistry();
    var completed = new CompletableFuture<Void>();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      var metrics = new MicrometerMetrics(registry);
      metrics.allRequests();
      app.extensions(metrics);
      app.afterRequest(_ -> completed.complete(null));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request -> request.path("/private-missing-path").timeout(Duration.ofSeconds(3)),
                HttpResponse.BodyHandlers.discarding());

        assertEquals(404, result.statusCode());
        completed.get(3, TimeUnit.SECONDS);
        assertCompletedTimerTags(registry, "UNMATCHED", 404, "none");
        assertEquals(1, registry.find("http.server.requests").timers().size());
      }
    } finally {
      registry.close();
    }
  }

  @Test
  @Timeout(10)
  void recordsApplicationFailure500WithExactTemplateTagsWithoutFailureDetails() throws Exception {
    var registry = new SimpleMeterRegistry();
    var completed = new CompletableFuture<Void>();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      var metrics = new MicrometerMetrics(registry);
      metrics.allRequests();
      app.extensions(metrics);
      app.afterRequest(_ -> completed.complete(null));
      app.routes()
          .get(
              "/failure/{id}",
              (_, _) -> {
                throw new IllegalStateException("private failure details");
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request -> request.path("/failure/private-id").timeout(Duration.ofSeconds(3)),
                HttpResponse.BodyHandlers.discarding());

        assertEquals(500, result.statusCode());
        completed.get(3, TimeUnit.SECONDS);
        assertCompletedTimerTags(registry, "/failure/{id}", 500, "application");
        assertEquals(1, registry.find("http.server.requests").timers().size());
      }
    } finally {
      registry.close();
    }
  }

  @Test
  @Timeout(10)
  void recordsOne302RedirectTimerWithoutFollowingTheTarget() throws Exception {
    var registry = new SimpleMeterRegistry();
    var completed = new CompletableFuture<Void>();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      var metrics = new MicrometerMetrics(registry);
      metrics.allRequests();
      app.extensions(metrics);
      app.afterRequest(_ -> completed.complete(null));
      app.routes().get("/redirect/{id}", (_, response) -> response.redirect("/target"));
      app.routes().get("/target", (_, response) -> response.text("target"));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request -> request.path("/redirect/private-id").timeout(Duration.ofSeconds(3)),
                HttpResponse.BodyHandlers.discarding());

        assertEquals(302, result.statusCode());
        assertEquals("/target", result.headers().firstValue("Location").orElseThrow());
        completed.get(3, TimeUnit.SECONDS);
        assertCompletedTimerTags(registry, "/redirect/{id}", 302, "none");
        assertEquals(1, registry.find("http.server.requests").timers().size());
      }
    } finally {
      registry.close();
    }
  }

  @Test
  @Timeout(15)
  void recordsExact304TagsForAConditionalFileRequest(@TempDir Path directory) throws Exception {
    var file = directory.resolve("asset.txt");
    Files.writeString(file, "metered file: €\n");
    Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2020-01-01T00:00:00Z")));
    var registry = new SimpleMeterRegistry();
    var completed = new LinkedBlockingQueue<RequestOutcome>();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      var metrics = new MicrometerMetrics(registry);
      metrics.allRequests();
      app.extensions(metrics);
      app.afterRequest(completed::add);
      app.routes().get("/asset/{id}", (_, response) -> response.file(file, "text/plain"));

      try (var test = TestServer.start(app)) {

        var full =
            test.send(request -> request.path("/asset/private-id").timeout(Duration.ofSeconds(3)));
        assertEquals(200, full.statusCode());
        assertArrayEquals("metered file: €\n".getBytes(StandardCharsets.UTF_8), full.body());
        assertNotNull(completed.poll(3, TimeUnit.SECONDS));
        var etag = full.headers().firstValue("ETag").orElseThrow();

        var conditional =
            test.send(
                request ->
                    request
                        .path("/asset/private-id")
                        .timeout(Duration.ofSeconds(3))
                        .header("If-None-Match", etag));

        assertEquals(304, conditional.statusCode());
        assertEquals(0, conditional.body().length);
        assertNotNull(completed.poll(3, TimeUnit.SECONDS));
        assertCompletedTimerTags(registry, "/asset/{id}", 200, "none");
        assertCompletedTimerTags(registry, "/asset/{id}", 304, "none");
        assertEquals(2, registry.find("http.server.requests").timers().size());
      }
    } finally {
      registry.close();
    }
  }

  @Test
  void installationRequiresExplicitActivationAndLeavesTheRegistryBorrowed() throws Exception {
    var registry = new SimpleMeterRegistry();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      var metrics = new MicrometerMetrics(registry);
      app.extensions(metrics);
      assertThrows(IllegalStateException.class, metrics::allRequests);
      app.routes().get("/", (_, response) -> response.text("ok"));

      try (var test = TestServer.start(app)) {
        test.send(request -> request.path("/"), HttpResponse.BodyHandlers.discarding());
        assertTrue(registry.find("http.server.requests").timers().isEmpty());
        test.close();
        assertFalse(registry.isClosed());
        var active = new MicrometerMetrics(registry);
        assertSame(active, active.allRequests());
      }
    } finally {
      registry.close();
    }
  }

  @ParameterizedTest
  @CsvSource({"499, none", "500, server"})
  void classifiesExplicitResponseStatusesWithoutApplicationFailures(int status, String error)
      throws Exception {
    var registry = new SimpleMeterRegistry();
    var completed = new CompletableFuture<Void>();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.extensions(new MicrometerMetrics(registry).allRequests());
      app.afterRequest(_ -> completed.complete(null));
      app.routes().get("/explicit", (_, response) -> response.status(status).text("explicit"));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(request -> request.path("/explicit"), HttpResponse.BodyHandlers.discarding());
        assertEquals(status, result.statusCode());
        completed.get(5, TimeUnit.SECONDS);
        assertEquals(
            1,
            registry
                .get("http.server.requests")
                .tag("route", "/explicit")
                .tag("error", error)
                .timer()
                .count());
      }
    } finally {
      registry.close();
    }
  }

  @Test
  void recordsMissesApplicationErrorsAndTerminalTransportFailuresWithFiniteLabels()
      throws Exception {
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();
    var registry = new SimpleMeterRegistry();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.extensions(new MicrometerMetrics(registry).allRequests());
      app.afterRequest(outcomes::add);
      app.routes()
          .get(
              "/failure",
              (_, _) -> {
                throw new IllegalStateException("private");
              });
      app.routes().get("/transport", (_, response) -> response.text("ok"));
      app.afterResponseFlush(
          (request, _) -> {
            if (request.routePattern().filter("/transport"::equals).isPresent()) {
              throw new IOException("private");
            }
          });

      try (var test = TestServer.start(app)) {
        for (String path : new String[] {"/missing-a", "/missing-b", "/failure", "/transport"}) {
          test.send(request -> request.path(path), HttpResponse.BodyHandlers.discarding());
          var outcome = outcomes.poll(5, TimeUnit.SECONDS);
          assertNotNull(outcome);
          if ("/transport".equals(path)) {
            assertNotNull(outcome.transportFailure());
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
      }
    } finally {
      registry.close();
    }
  }

  @Test
  void leavesAnUnconfiguredRegistryUntouched() throws Exception {
    var registry = new SimpleMeterRegistry();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      try (var test = TestServer.start(app)) {
        assertEquals(
            404,
            test.send(request -> request.path("/missing"), HttpResponse.BodyHandlers.discarding())
                .statusCode());
        assertTrue(registry.getMeters().isEmpty());
      }
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
        var testRequests = Executors.newVirtualThreadPerTaskExecutor()) {
      app.extensions(new MicrometerMetrics(registry).allRequests());
      app.afterRequest(_ -> completed.complete(null));
      app.routes()
          .get(
              "/orders/{id}",
              (_, response) -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                response.text("ok");
              });

      try (var test = TestServer.start(app)) {
        var pending =
            testRequests.submit(
                () ->
                    test.send(
                        request -> request.path("/orders/private-id"),
                        HttpResponse.BodyHandlers.discarding()));

        try {
          assertTrue(entered.await(5, TimeUnit.SECONDS));
          assertEquals(
              1, registry.get("http.server.requests.active").longTaskTimer().activeTasks());
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
      }
    } finally {
      registry.close();
    }
  }

  private static void assertCompletedTimerTags(
      SimpleMeterRegistry registry, String route, int status, String error) {
    var timer =
        registry
            .get("http.server.requests")
            .tag("route", route)
            .tag("status", Integer.toString(status))
            .timer();
    assertEquals(
        Map.of("method", "GET", "route", route, "status", Integer.toString(status), "error", error),
        timer.getId().getTags().stream().collect(Collectors.toMap(Tag::getKey, Tag::getValue)));
    assertEquals(1L, timer.count());
  }
}
