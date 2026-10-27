package io.github.suppierk.shoostr.example.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class StreamBulkheadExampleTest {
  @Test
  void boundsOpenStreamsWhileHealthProgressesBeyondTheNativeWorkerBudget() throws Exception {
    var release = new CountDownLatch(1);
    var completed = new CountDownLatch(40);
    var bulkhead =
        Bulkhead.of(
            "streams",
            BulkheadConfig.custom().maxConcurrentCalls(40).maxWaitDuration(Duration.ZERO).build());
    bulkhead.getEventPublisher().onCallFinished(event -> completed.countDown());
    var app =
        StreamBulkheadExample.application(
            bulkhead,
            () -> {
              if (!release.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Fixture was not released");
              }

              return "done";
            });
    app.modifyServer(server -> ((QueuedThreadPool) server.getThreadPool()).setMaxThreads(32));
    var streams = new ArrayList<HttpResponse<InputStream>>();

    try (var test = TestServer.start(app)) {
      try {
        for (int index = 0; index < 40; index++) {
          var response =
              test.send(
                  request -> request.path("/events").timeout(Duration.ofSeconds(2)),
                  HttpResponse.BodyHandlers.ofInputStream());
          streams.add(response);
          assertEquals(200, response.statusCode());
        }
        var rejected =
            test.send(
                request -> request.path("/events").timeout(Duration.ofSeconds(2)),
                HttpResponse.BodyHandlers.ofInputStream());

        try (var body = rejected.body()) {
          assertEquals(503, rejected.statusCode());
        }

        var health =
            test.send(
                request -> request.path("/health").timeout(Duration.ofSeconds(2)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, health.statusCode());
        assertEquals("healthy", health.body());
      } finally {
        release.countDown();
        for (var response : streams) {
          try (var body = response.body()) {
            assertTrue(
                new String(body.readAllBytes(), StandardCharsets.UTF_8).contains("data: ready"));
          }
        }
      }

      assertTrue(completed.await(3, TimeUnit.SECONDS));
      var recovered =
          test.send(request -> request.path("/events"), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, recovered.statusCode());
      assertTrue(recovered.body().contains("data: done"));
    }
  }

  @Test
  void retainsThePermitThroughTheFrameworkFinalWrite() throws Exception {
    var finalWrite = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var completed = new CountDownLatch(1);
    var bulkhead =
        Bulkhead.of(
            "final-write",
            BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build());
    bulkhead.getEventPublisher().onCallFinished(event -> completed.countDown());
    var app = StreamBulkheadExample.application(bulkhead, () -> "done");
    app.afterRouteHandler(
        (request, response) -> {
          if (request.header("X-Hold-Final").isPresent()) {
            request.attribute("handler-returned", true);
          }
        });
    app.beforeResponseFlush(
        (request, response) -> {
          if (request.attribute("handler-returned").isPresent()) {
            finalWrite.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
              throw new IllegalStateException("Final write was not released");
            }
          }
        });

    try (var test = TestServer.start(app)) {
      var active =
          test.send(
              request -> request.path("/events").header("X-Hold-Final", "true"),
              HttpResponse.BodyHandlers.ofInputStream());

      try (var body = active.body()) {
        try {
          assertEquals(200, active.statusCode());
          assertTrue(finalWrite.await(2, TimeUnit.SECONDS));
          assertEquals(
              503,
              test.send(request -> request.path("/events").timeout(Duration.ofSeconds(2)))
                  .statusCode());
        } finally {
          release.countDown();
        }

        assertTrue(new String(body.readAllBytes(), StandardCharsets.UTF_8).contains("data: done"));
      }

      assertTrue(completed.await(2, TimeUnit.SECONDS));
      assertEquals(200, test.send(request -> request.path("/events")).statusCode());
    } finally {
      release.countDown();
    }
  }

  @Test
  void releasesAdmissionAfterAProducerFailureAbortsCommittedOutput() throws Exception {
    var completed = new CountDownLatch(1);
    var calls = new AtomicInteger();
    var bulkhead =
        Bulkhead.of(
            "failure",
            BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build());
    bulkhead.getEventPublisher().onCallFinished(event -> completed.countDown());
    var app =
        StreamBulkheadExample.application(
            bulkhead,
            () -> {
              if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("Producer failed");
              }

              return "recovered";
            });

    try (var test = TestServer.start(app)) {
      var failed =
          test.send(request -> request.path("/events"), HttpResponse.BodyHandlers.ofInputStream());

      try (var body = failed.body()) {
        assertEquals(200, failed.statusCode());

        try {
          body.readAllBytes();
        } catch (IOException expected) {
          // A failure after commitment aborts the existing response.
        }
      }

      assertTrue(completed.await(2, TimeUnit.SECONDS));
      var recovered =
          test.send(request -> request.path("/events"), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, recovered.statusCode());
      assertTrue(recovered.body().contains("data: recovered"));
    }
  }

  @Test
  void retainsAdmissionAfterDisconnectUntilTheProducerActuallyReturns() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var completed = new CountDownLatch(1);
    var bulkhead =
        Bulkhead.of(
            "disconnect",
            BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build());
    bulkhead.getEventPublisher().onCallFinished(event -> completed.countDown());
    var app =
        StreamBulkheadExample.application(
            bulkhead,
            () -> {
              entered.countDown();
              if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Producer was not released");
              }

              return "done";
            });

    try (var test = TestServer.start(app)) {
      var active =
          test.send(request -> request.path("/events"), HttpResponse.BodyHandlers.ofInputStream());

      try {
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        active.body().close();
        assertEquals(0, bulkhead.getMetrics().getAvailableConcurrentCalls());
      } finally {
        active.body().close();
        release.countDown();
      }

      assertTrue(completed.await(2, TimeUnit.SECONDS));
      assertEquals(200, test.send(request -> request.path("/events")).statusCode());
    } finally {
      release.countDown();
    }
  }

  @Test
  void releasesAdmissionWhenApplicationShutdownStopsItsProducer() throws Exception {
    var entered = new CountDownLatch(1);
    var completed = new CountDownLatch(1);
    var stop = new CountDownLatch(1);
    var bulkhead =
        Bulkhead.of(
            "shutdown",
            BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build());
    bulkhead.getEventPublisher().onCallFinished(event -> completed.countDown());
    var app =
        StreamBulkheadExample.application(
                bulkhead,
                () -> {
                  entered.countDown();
                  stop.await();
                  return "stopped";
                })
            .beforeClose(stop::countDown);

    try (var test = TestServer.start(app)) {
      var active =
          test.send(request -> request.path("/events"), HttpResponse.BodyHandlers.ofInputStream());

      try (var body = active.body()) {
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        test.close();
        assertTrue(completed.await(3, TimeUnit.SECONDS));
        assertEquals(1, bulkhead.getMetrics().getAvailableConcurrentCalls());
      }
    }
  }
}
