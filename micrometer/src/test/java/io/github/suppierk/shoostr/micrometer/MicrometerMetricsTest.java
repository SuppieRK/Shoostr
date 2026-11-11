package io.github.suppierk.shoostr.micrometer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.ApplicationExtension;
import io.github.suppierk.shoostr.Lifecycle;
import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.RequestOutcome;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.Closeable;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MicrometerMetricsTest {
  private static void assertCompletedTimerTags(
      SimpleMeterRegistry registry, String route, int status, String error) {
    var timer = createTimerForAssertCompletedTimerTags(registry, route, status);

    assertEquals(
        expectedTimerTags(route, status, error),
        timer.getId().getTags().stream().collect(Collectors.toMap(Tag::getKey, Tag::getValue)));
    assertEquals(1L, timer.count());
  }

  private static Timer createTimerForAssertCompletedTimerTags(
      SimpleMeterRegistry registry, String route, int status) {
    return registry
        .get("http.server.requests")
        .tag("route", route)
        .tag("status", Integer.toString(status))
        .timer();
  }

  private static Map<String, String> expectedTimerTags(String route, int status, String error) {
    var entries = new LinkedHashMap<String, String>();
    entries.put("method", "GET");
    entries.put("route", route);
    entries.put("status", Integer.toString(status));
    entries.put("error", error);

    return Map.copyOf(entries);
  }

  @Test
  void applicationDefaultsMeasureSelectedEndpointsAndRespectExclusionsAndRoutingMisses()
      throws Exception {
    var registry = new SimpleMeterRegistry();
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();
    var metrics = new MicrometerMetrics(registry);
    var app = new Shoostr();

    try (Closeable cleanup = app::close) {
      app.withExtension(metrics).afterRequest(outcomes::add);
      app.routes()
          .get("/selected", (request, response) -> response.text("selected"))
          .get(
              "/excluded",
              (request, response) -> response.text("excluded"),
              lifecycle -> lifecycle.withoutExtension(metrics));

      try (var server = TestServer.start(app)) {
        assertEquals(200, server.send(request -> request.path("/selected")).statusCode());
        assertNotNull(outcomes.poll(3, TimeUnit.SECONDS));
        assertEquals(200, server.send(request -> request.path("/excluded")).statusCode());
        assertNotNull(outcomes.poll(3, TimeUnit.SECONDS));
        assertEquals(404, server.send(request -> request.path("/missing")).statusCode());
        assertNotNull(outcomes.poll(3, TimeUnit.SECONDS));
        assertEquals(
            405, server.send(request -> request.path("/selected").method("POST")).statusCode());
        assertNotNull(outcomes.poll(3, TimeUnit.SECONDS));
        assertEquals(1, registry.find("http.server.requests").timers().size());
        assertEquals(
            1, registry.get("http.server.requests").tag("route", "/selected").timer().count());
        assertEquals(0, registry.get("http.server.requests.active").longTaskTimer().activeTasks());
      }
    } finally {
      registry.close();
    }
  }

  @Test
  @Timeout(15)
  void keepsRegistryOpenUntilDrainingRequestsAndExtensionCleanupFinish() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var consumed = new AtomicBoolean();
    var registry = new SimpleMeterRegistry();

    try {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close;
          var requests = Executors.newVirtualThreadPerTaskExecutor()) {
        app.afterClose(registry::close);
        app.beforeClose(release::countDown);
        app.withExtension(new MicrometerMetrics(registry))
            .withExtension(new RegistryConsumer(registry, consumed));
        app.routes()
            .get(
                "/drain",
                (ignored, response) -> {
                  entered.countDown();

                  assertTrue(release.await(5, TimeUnit.SECONDS));

                  response.text("drained");
                });

        try (var test = TestServer.start(app)) {
          var pending =
              submitPendingForKeepsRegistryOpenUntilDrainingRequestsAndExtensionCleanupFinish(
                  requests, test);

          try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            app.close();

            assertEquals(200, pending.get(5, TimeUnit.SECONDS).statusCode());
            assertTrue(consumed.get());
            assertTrue(registry.isClosed());
          } finally {
            release.countDown();
          }
        }
      }
    } finally {
      registry.close();
    }
  }

  private Future<HttpResponse<Void>>
      submitPendingForKeepsRegistryOpenUntilDrainingRequestsAndExtensionCleanupFinish(
          ExecutorService requests, TestServer test) {
    return requests.submit(
        () -> test.send(request -> request.path("/drain"), HttpResponse.BodyHandlers.discarding()));
  }

  @ParameterizedTest
  @CsvSource({"200, none", "404, none", "500, server"})
  @Timeout(10)
  void recordsExactTagsForExplicitResponsesOnMatchedTemplates(int status, String error)
      throws Exception {
    var registry = new SimpleMeterRegistry();
    var completed = new CompletableFuture<Void>();

    try {
      var app = new Shoostr();

      try (Closeable appCleanup1 = app::close) {
        var metrics = new MicrometerMetrics(registry);

        app.withExtension(metrics);
        app.afterRequest(ignoredRequestOutcome -> completed.complete(null));
        app.routes()
            .get(
                "/status/{id}",
                (ignoredRequest, response) -> response.status(status).text("explicit"));

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
      }
    } finally {
      registry.close();
    }
  }

  @Test
  @Timeout(10)
  void manualApplicationObservationRecordsUnmatched404WithoutTheRequestedPath() throws Exception {
    var registry = new SimpleMeterRegistry();
    var completed = new CompletableFuture<Void>();

    try {
      var app = new Shoostr();

      try (Closeable appCleanup2 = app::close) {
        var metrics = new MicrometerMetrics(registry);

        app.observe(metrics);
        app.afterRequest(ignoredRequestOutcome -> completed.complete(null));

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

    try {
      var app = new Shoostr();

      try (Closeable appCleanup3 = app::close) {
        var metrics = new MicrometerMetrics(registry);

        app.withExtension(metrics);
        app.afterRequest(ignoredRequestOutcome -> completed.complete(null));
        app.routes()
            .get(
                "/failure/{id}",
                (ignoredRequest, ignoredResponse) -> {
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

    try {
      var app = new Shoostr();

      try (Closeable appCleanup4 = app::close) {
        var metrics = new MicrometerMetrics(registry);

        app.withExtension(metrics);
        app.afterRequest(ignoredRequestOutcome -> completed.complete(null));
        app.routes()
            .get("/redirect/{id}", (ignoredRequest, response) -> response.redirect("/target"));
        app.routes().get("/target", (ignoredRequest, response) -> response.text("target"));

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
      }
    } finally {
      registry.close();
    }
  }

  @Test
  @Timeout(15)
  void recordsExact304TagsForConditionalFileRequest(@TempDir Path directory) throws Exception {
    var file = directory.resolve("asset.txt");
    Files.writeString(file, "metered file: €\n");
    Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2020-01-01T00:00:00Z")));
    var registry = new SimpleMeterRegistry();
    var completed = new LinkedBlockingQueue<RequestOutcome>();

    try {
      var app = new Shoostr();

      try (Closeable appCleanup5 = app::close) {
        var metrics = new MicrometerMetrics(registry);

        app.withExtension(metrics);
        app.afterRequest(completed::add);
        app.routes()
            .get("/asset/{id}", (ignoredRequest, response) -> response.file(file, "text/plain"));

        try (var test = TestServer.start(app)) {

          var full =
              test.send(
                  request -> request.path("/asset/private-id").timeout(Duration.ofSeconds(3)));

          assertEquals(200, full.statusCode());
          assertArrayEquals("metered file: €\n".getBytes(StandardCharsets.UTF_8), full.body());
          assertNotNull(completed.poll(3, TimeUnit.SECONDS));

          var etag = full.headers().firstValue("ETag").orElseThrow();

          var conditional =
              sendConditionalRequestForRecordsExact304TagsForConditionalFileRequest(test, etag);

          assertEquals(304, conditional.statusCode());
          assertEquals(0, conditional.body().length);
          assertNotNull(completed.poll(3, TimeUnit.SECONDS));
          assertCompletedTimerTags(registry, "/asset/{id}", 200, "none");
          assertCompletedTimerTags(registry, "/asset/{id}", 304, "none");
          assertEquals(2, registry.find("http.server.requests").timers().size());
        }
      }
    } finally {
      registry.close();
    }
  }

  private HttpResponse<byte[]>
      sendConditionalRequestForRecordsExact304TagsForConditionalFileRequest(
          TestServer test, String etag) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/asset/private-id")
                .timeout(Duration.ofSeconds(3))
                .header("If-None-Match", etag));
  }

  @Test
  void applicationActivationRecordsSelectedEndpointsAndLeavesTheRegistryBorrowed()
      throws Exception {
    var registry = new SimpleMeterRegistry();

    try {
      var app = new Shoostr();

      try (Closeable appCleanup6 = app::close) {
        var metrics = new MicrometerMetrics(registry);
        var completed = new CompletableFuture<Void>();
        app.withExtension(metrics).afterRequest(ignored -> completed.complete(null));
        app.routes().get("/", (ignoredRequest, response) -> response.text("ok"));

        try (var test = TestServer.start(app)) {
          test.send(request -> request.path("/"), HttpResponse.BodyHandlers.discarding());
          completed.get(3, TimeUnit.SECONDS);

          assertEquals(1, registry.get("http.server.requests").timer().count());

          test.close();

          assertFalse(registry.isClosed());
        }
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

    try {
      var app = new Shoostr();

      try (Closeable appCleanup7 = app::close) {
        app.withExtension(new MicrometerMetrics(registry));
        app.afterRequest(ignoredRequestOutcome -> completed.complete(null));
        app.routes()
            .get(
                "/explicit",
                (ignoredRequest, response) -> response.status(status).text("explicit"));

        try (var test = TestServer.start(app)) {
          var result =
              test.send(
                  request -> request.path("/explicit"), HttpResponse.BodyHandlers.discarding());

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

    try {
      var app = new Shoostr();

      try (Closeable appCleanup8 = app::close) {
        app.observe(new MicrometerMetrics(registry));
        app.afterRequest(outcomes::add);
        app.routes()
            .get(
                "/failure",
                (ignoredRequest, ignoredResponse) -> {
                  throw new IllegalStateException("private");
                });
        app.routes().get("/transport", (ignoredRequest, response) -> response.text("ok"));
        app.afterResponseFlush(
            (request, ignoredResponse) -> {
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
          assertEquals(
              0, registry.get("http.server.requests.active").longTaskTimer().activeTasks());
          assertEquals(3, registry.find("http.server.requests").timers().size());
        }
      }
    } finally {
      registry.close();
    }
  }

  @Test
  void leavesAnUnconfiguredRegistryUntouched() throws Exception {
    var registry = new SimpleMeterRegistry();

    try {
      var app = new Shoostr();

      try (Closeable appCleanup9 = app::close) {
        try (var test = TestServer.start(app)) {
          assertEquals(
              404,
              test.send(request -> request.path("/missing"), HttpResponse.BodyHandlers.discarding())
                  .statusCode());
          assertTrue(registry.getMeters().isEmpty());
        }
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

    try {
      var app = new Shoostr();

      try (Closeable appCleanup10 = app::close;
          var testRequests = Executors.newVirtualThreadPerTaskExecutor()) {
        app.withExtension(new MicrometerMetrics(registry));
        app.afterRequest(ignoredRequestOutcome -> completed.complete(null));
        app.routes()
            .get(
                "/orders/{id}",
                (ignoredRequest, response) -> {
                  entered.countDown();

                  assertTrue(release.await(5, TimeUnit.SECONDS));

                  response.text("ok");
                });

        try (var test = TestServer.start(app)) {
          var pending =
              submitPendingForCountsActiveAndCompletedRequestsUsingRouteTemplates(
                  testRequests, test);

          try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(
                1, registry.get("http.server.requests.active").longTaskTimer().activeTasks());
          } finally {
            release.countDown();
          }

          assertEquals(200, pending.get(5, TimeUnit.SECONDS).statusCode());

          completed.get(5, TimeUnit.SECONDS);

          assertEquals(
              0, registry.get("http.server.requests.active").longTaskTimer().activeTasks());

          var timer = createTimerForCountsActiveAndCompletedRequestsUsingRouteTemplates(registry);

          assertEquals(1, timer.count());
          assertTrue(timer.totalTime(TimeUnit.NANOSECONDS) > 0);
          assertEquals("none", timer.getId().getTag("error"));
        }
      }
    } finally {
      registry.close();
    }
  }

  private Future<HttpResponse<Void>>
      submitPendingForCountsActiveAndCompletedRequestsUsingRouteTemplates(
          ExecutorService testRequests, TestServer test) {
    return testRequests.submit(
        () ->
            test.send(
                request -> request.path("/orders/private-id"),
                HttpResponse.BodyHandlers.discarding()));
  }

  private Timer createTimerForCountsActiveAndCompletedRequestsUsingRouteTemplates(
      SimpleMeterRegistry registry) {
    return registry
        .get("http.server.requests")
        .tag("route", "/orders/{id}")
        .tag("method", "GET")
        .timer();
  }

  @Test
  @Timeout(20)
  void sharesOneObserverAcrossConcurrentFilteredMeterIdsWithoutEagerCompletedMeters()
      throws Exception {
    var entered = new CountDownLatch(8);
    var release = new CountDownLatch(1);
    var terminal = new CountDownLatch(24);
    var registry = new SimpleMeterRegistry();

    var configuredOptions = new Options();
    configuredOptions.setVirtualThreads(true);

    try {
      var app = new Shoostr(configuredOptions);

      try (Closeable appCleanup11 = app::close;
          var clients = Executors.newVirtualThreadPerTaskExecutor()) {
        registry
            .config()
            .meterFilter(MeterFilter.commonTags(List.of(Tag.of("deployment", "test"))));
        app.observe(new MicrometerMetrics(registry));
        app.afterRequest(outcome -> terminal.countDown());
        app.routes()
            .get(
                "/items/{id}",
                (request, response) -> {
                  entered.countDown();
                  if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("Request gate timed out");
                  }

                  response.text("done");
                })
            .post(
                "/items/{id}",
                (request, response) -> {
                  entered.countDown();
                  if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("Request gate timed out");
                  }

                  response.status(503).text("busy");
                });

        assertTrue(registry.find("http.server.requests").timers().isEmpty());

        try (var server = TestServer.start(app)) {
          assertTrue(registry.find("http.server.requests").timers().isEmpty());

          var pending = new ArrayList<Future<?>>();
          for (int index = 0; index < 24; index++) {
            final int id = index;
            pending.add(
                clients.submit(
                    () -> {
                      int kind = id % 3;
                      var result = sendResultForFilteredMeters(server, kind, id);

                      assertEquals(kind == 0 ? 200 : kind == 1 ? 503 : 404, result.statusCode());

                      return null;
                    }));
          }

          try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
          } finally {
            release.countDown();
          }

          for (var request : pending) {
            request.get(5, TimeUnit.SECONDS);
          }

          assertTrue(terminal.await(3, TimeUnit.SECONDS));
          assertEquals(3, registry.find("http.server.requests").timers().size());

          for (var timer : registry.find("http.server.requests").timers()) {
            assertEquals(8, timer.count());
            assertEquals("Terminal request count and duration", timer.getId().getDescription());
            assertEquals("test", timer.getId().getTag("deployment"));
            assertEquals(5, timer.getId().getTags().size());
          }

          assertEquals(
              8,
              registry
                  .get("http.server.requests")
                  .tags("method", "GET", "route", "/items/{id}", "status", "200", "error", "none")
                  .timer()
                  .count());
          assertEquals(
              8,
              registry
                  .get("http.server.requests")
                  .tags(
                      "method", "POST", "route", "/items/{id}", "status", "503", "error", "server")
                  .timer()
                  .count());
          assertEquals(
              8,
              registry
                  .get("http.server.requests")
                  .tags("method", "GET", "route", "UNMATCHED", "status", "404", "error", "none")
                  .timer()
                  .count());
          assertEquals(
              0, registry.get("http.server.requests.active").longTaskTimer().activeTasks());
        }

        assertFalse(registry.isClosed());
      }
    } finally {
      release.countDown();
      registry.close();
    }
  }

  private HttpResponse<Void> sendResultForFilteredMeters(TestServer server, int kind, int id)
      throws IOException, InterruptedException {
    return server.send(
        request ->
            request
                .method(kind == 1 ? "POST" : "GET")
                .path((kind == 2 ? "/missing/" : "/items/") + "private-" + id)
                .timeout(Duration.ofSeconds(3)),
        HttpResponse.BodyHandlers.discarding());
  }

  @Test
  @Timeout(10)
  void releasesTheActiveSampleWhenCompletedMeterRegistrationFails() throws Exception {
    var registry = new SimpleMeterRegistry();
    var completed = new CountDownLatch(1);
    registry
        .config()
        .meterFilter(
            new MeterFilter() {
              @Override
              public MeterFilterReply accept(Meter.Id id) {
                if ("http.server.requests".equals(id.getName())) {
                  throw new IllegalStateException("Completed registration failed");
                }

                return MeterFilterReply.NEUTRAL;
              }
            });

    try {
      var app = new Shoostr();

      try (Closeable appCleanup12 = app::close) {
        app.withExtension(new MicrometerMetrics(registry));
        app.afterRequest(outcome -> completed.countDown());
        app.routes().get("/metrics", (request, response) -> response.text("done"));

        try (var server = TestServer.start(app)) {
          var response =
              server.send(
                  request -> request.path("/metrics"), HttpResponse.BodyHandlers.ofString());

          assertEquals(200, response.statusCode());
          assertEquals("done", response.body());
          assertTrue(completed.await(3, TimeUnit.SECONDS));
          assertTrue(registry.find("http.server.requests").timers().isEmpty());
          assertEquals(
              0, registry.get("http.server.requests.active").longTaskTimer().activeTasks());
        }

        assertFalse(registry.isClosed());
      }
    } finally {
      registry.close();
    }
  }

  private record RegistryConsumer(SimpleMeterRegistry registry, AtomicBoolean consumed)
      implements ApplicationExtension<Object> {

    private RegistryConsumer {
      Objects.requireNonNull(registry);
      Objects.requireNonNull(consumed);
    }

    @Override
    public Object configure(Lifecycle lifecycle, Object inherited) {
      return this;
    }

    @Override
    public void close() {
      assertFalse(registry.isClosed());
      assertEquals(1, registry.get("http.server.requests").tag("route", "/drain").timer().count());

      consumed.set(true);
    }
  }
}
