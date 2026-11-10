package io.github.suppierk.shoostr.micrometer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.suppierk.shoostr.RequestOutcome;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.Closeable;
import java.util.HashSet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.junit.jupiter.api.Test;

class MicrometerMetricsEqualityTest {
  @Test
  void comparesRegistryActiveTimerAndRegistrationState() {
    var first = new SimpleMeterRegistry();
    var second = new SimpleMeterRegistry();

    try {
      EqualsVerifier.forClass(MicrometerMetrics.class)
          .withPrefabValues(MeterRegistry.class, first, second)
          .withPrefabValues(
              LongTaskTimer.class,
              LongTaskTimer.builder("first").register(first),
              LongTaskTimer.builder("second").register(second))
          .withNonnullFields("registry", "active")
          .withIgnoredFields("completed")
          .suppress(Warning.NONFINAL_FIELDS)
          .verify();
    } finally {
      first.close();
      second.close();
    }
  }

  @Test
  void equalityRemainsStableAcrossActivationWhileInstancesContributeIndependently()
      throws Exception {
    var completed = new LinkedBlockingQueue<RequestOutcome>();
    var registry = new SimpleMeterRegistry();

    try {
      var app = new Shoostr().afterRequest(completed::add);

      try (Closeable appCleanup = app::close) {
        var first = new MicrometerMetrics(registry);
        var second = new MicrometerMetrics(registry);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());

        app.withExtension(first);
        assertEquals(first, second);
        app.withExtension(second);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        var values = new HashSet<MicrometerMetrics>();
        values.add(first);
        values.add(second);
        assertEquals(1, values.size());
        app.routes().get("/metrics", (request, response) -> response.text("done"));

        try (var server = TestServer.start(app)) {
          assertEquals(200, server.send(request -> request.path("/metrics")).statusCode());
          assertNotNull(completed.poll(3, TimeUnit.SECONDS));
          assertEquals(2, registry.get("http.server.requests").timer().count());
          assertEquals(
              0, registry.get("http.server.requests.active").longTaskTimer().activeTasks());
        }
      }
    } finally {
      registry.close();
    }
  }
}
