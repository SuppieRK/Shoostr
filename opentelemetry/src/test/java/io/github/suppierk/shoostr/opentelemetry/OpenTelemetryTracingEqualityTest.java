package io.github.suppierk.shoostr.opentelemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.suppierk.shoostr.RequestOutcome;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.HashSet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.junit.jupiter.api.Test;

class OpenTelemetryTracingEqualityTest {
  @Test
  void comparesAllStoredProviderState() {
    try (var firstProvider =
            SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(InMemorySpanExporter.create()))
                .build();
        var secondProvider =
            SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(InMemorySpanExporter.create()))
                .build()) {
      var first = OpenTelemetrySdk.builder().setTracerProvider(firstProvider).build();
      var second = OpenTelemetrySdk.builder().setTracerProvider(secondProvider).build();
      EqualsVerifier.forClass(OpenTelemetryTracing.class)
          .withPrefabValues(OpenTelemetry.class, first, second)
          .withPrefabValues(Tracer.class, first.getTracer("first"), second.getTracer("second"))
          .withNonnullFields("telemetry", "tracer")
          .suppress(Warning.NONFINAL_FIELDS)
          .verify();
    }
  }

  @Test
  void comparesConfigurationAndInstallationButKeepsEqualContributionsIndependent()
      throws Exception {
    var completed = new LinkedBlockingQueue<RequestOutcome>();
    var exporter = InMemorySpanExporter.create();

    try (var provider =
            SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        var app = new Shoostr().afterRequest(completed::add)) {
      var telemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
      var first = new OpenTelemetryTracing(telemetry);
      var second = new OpenTelemetryTracing(telemetry);
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
      var values = new HashSet<OpenTelemetryTracing>();
      values.add(first);
      values.add(second);
      assertEquals(1, values.size());
      app.routes().get("/tracing", (request, response) -> response.text("done"));

      try (var server = TestServer.start(app)) {
        assertEquals(200, server.send(request -> request.path("/tracing")).statusCode());
        assertNotNull(completed.poll(3, TimeUnit.SECONDS));
        assertEquals(2, exporter.getFinishedSpanItems().size());
      }
    }
  }
}
