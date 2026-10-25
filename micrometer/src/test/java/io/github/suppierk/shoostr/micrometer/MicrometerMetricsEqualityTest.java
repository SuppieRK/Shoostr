package io.github.suppierk.shoostr.micrometer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.suppierk.shoostr.RequestOutcome;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashSet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.junit.jupiter.api.Test;

class MicrometerMetricsEqualityTest {
  @Test
  void comparesAllStoredProviderState() {
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
          .suppress(Warning.NONFINAL_FIELDS)
          .verify();
    } finally {
      first.close();
      second.close();
    }
  }

  @Test
  void comparesConfigurationAndInstallationButKeepsEqualContributionsIndependent()
      throws Exception {
    var completed = new LinkedBlockingQueue<RequestOutcome>();
    var registry = new SimpleMeterRegistry();

    try (var app = new Shoostr().afterRequest(completed::add)) {
      var first = new MicrometerMetrics(registry);
      var second = new MicrometerMetrics(registry);
      assertEquals(first, second);
      assertEquals(first.hashCode(), second.hashCode());
      first.allRequests();
      assertNotEquals(first, second);
      second.allRequests();
      assertEquals(first, second);
      app.extensions(first);
      assertNotEquals(first, second);
      app.extensions(second);
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
        assertEquals(0, registry.get("http.server.requests.active").longTaskTimer().activeTasks());
      }
    } finally {
      registry.close();
    }
  }
}
