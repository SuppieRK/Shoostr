package io.github.suppierk.shoostr.example.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.suppierk.shoostr.RequestOutcome;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.Closeable;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

class RequestMetricsExampleTest {
    @Test
    void countsSelectedRequestsUnderTheirTemplateRatherThanRawItemIdentifiers() throws Exception {
        var outcomes = new LinkedBlockingQueue<RequestOutcome>();
        var registry = new SimpleMeterRegistry();

        try {
            var app = RequestMetricsExample.application(registry).afterRequest(outcomes::offer);

            try (Closeable appCleanup = app::close;
                 var test = TestServer.start(app)) {
                assertEquals(200, test.send(request -> request
                    .pathTemplate("/api/items/{id}", path -> path
                        .pathParam("id", "41"))).statusCode());
                assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));
                assertEquals(200, test.send(request -> request
                    .pathTemplate("/api/items/{id}", path -> path
                        .pathParam("id", "42"))).statusCode());
                assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));

                var timer = registry
                    .get("http.server.requests")
                    .tags("method", "GET", "route", "/api/items/{id}", "status", "200", "error", "none")
                    .timer();

                assertEquals(2, timer.count());
                assertEquals(0, registry.get("http.server.requests.active").longTaskTimer().activeTasks());
                assertEquals(1, registry.find("http.server.requests").timers().size());
            }
        } finally {
            registry.close();
        }
    }

    @Test
    void recordsApplicationWorkWithoutMeasuringUnselectedEndpoints() throws Exception {
        var outcomes = new LinkedBlockingQueue<RequestOutcome>();
        var registry = new SimpleMeterRegistry();

        try (var test = TestServer.start(RequestMetricsExample.application(registry).afterRequest(outcomes::offer))) {
            assertEquals(200, test.send(request -> request.path("/health")).statusCode());
            assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));
            assertNull(registry.find("http.server.requests").timer());

            var timer = Timer.builder("application.work").register(registry);
            timer.record(Duration.ofMillis(1));

            assertEquals(1, timer.count());
        } finally {
            registry.close();
        }
    }

    @Test
    void keepsTheActiveSampleOpenUntilTheRequestTerminates() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var outcomes = new LinkedBlockingQueue<RequestOutcome>();
        var registry = new SimpleMeterRegistry();

        try (var clients = Executors.newVirtualThreadPerTaskExecutor()) {
            var app = createAppForKeepsTheActiveSampleOpenUntilTheRequestTerminates(registry, release, entered, outcomes);

            try (Closeable appCleanup = app::close;
                 var test = TestServer.start(app)) {
                var response = clients.submit(() -> test.send(request -> request
                    .pathTemplate("/api/items/{id}", path -> path
                        .pathParam("id", "42"))));

                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    assertEquals(1, registry.get("http.server.requests.active").longTaskTimer().activeTasks());
                    assertNull(registry.find("http.server.requests").timer());
                } finally {
                    release.countDown();
                }

                assertEquals(200, response.get(5, TimeUnit.SECONDS).statusCode());
                assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));
                assertEquals(0, registry.get("http.server.requests.active").longTaskTimer().activeTasks());
                assertEquals(1, registry.get("http.server.requests").timer().count());
            }
        } finally {
            registry.close();
        }
    }

    private Shoostr createAppForKeepsTheActiveSampleOpenUntilTheRequestTerminates(
            SimpleMeterRegistry registry,
            CountDownLatch release,
            CountDownLatch entered,
            LinkedBlockingQueue<RequestOutcome> outcomes) {
        return RequestMetricsExample.application(registry)
            .beforeClose(release::countDown)
            .beforeRouteHandler((request, response) -> {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("request was not released");
                }
            })
            .afterRequest(outcomes::offer);
    }

    @Test
    void labelsApplicationFailuresWithoutUsingTheirExceptionText() throws Exception {
        var outcomes = new LinkedBlockingQueue<RequestOutcome>();
        var registry = new SimpleMeterRegistry();
        var app = createAppForLabelsApplicationFailuresWithoutUsingTheirExceptionText(registry, outcomes);

        try (Closeable appCleanup = app::close;
             var test = TestServer.start(app)) {
            assertEquals(500, test.send(request -> request
                .pathTemplate("/api/items/{id}", path -> path
                    .pathParam("id", "42"))).statusCode());
            assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));
            assertEquals(
                1,
                registry
                    .get("http.server.requests")
                    .tags("route", "/api/items/{id}", "status", "500", "error", "application")
                    .timer()
                    .count());
            assertEquals(0, registry.get("http.server.requests.active").longTaskTimer().activeTasks());
            assertTrue(registry.getMeters().stream()
                .noneMatch(meter -> meter.getId().toString().contains("private")));
        } finally {
            registry.close();
        }
    }

    private Shoostr createAppForLabelsApplicationFailuresWithoutUsingTheirExceptionText(
        SimpleMeterRegistry registry,
        LinkedBlockingQueue<RequestOutcome> outcomes
    ) {
        return RequestMetricsExample.application(registry)
            .beforeRouteHandler((request, response) -> {
                throw new IllegalStateException("private failure");
            })
            .afterRequest(outcomes::offer);
    }
}
