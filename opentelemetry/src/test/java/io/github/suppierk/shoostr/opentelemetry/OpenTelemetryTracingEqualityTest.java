package io.github.suppierk.shoostr.opentelemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.suppierk.shoostr.RequestOutcome;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.OpenTelemetrySdkBuilder;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.io.Closeable;
import java.util.HashSet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.junit.jupiter.api.Test;

class OpenTelemetryTracingEqualityTest {
  @Test
  void comparesTelemetryProviderAndTracer() {
    try (var firstProvider = openTracerProvider();
        var secondProvider = openTracerProvider()) {
      var first = createTelemetry(firstProvider);
      var second = createTelemetry(secondProvider);

      EqualsVerifier.forClass(OpenTelemetryTracing.class)
          .withPrefabValues(OpenTelemetry.class, first, second)
          .withPrefabValues(Tracer.class, first.getTracer("first"), second.getTracer("second"))
          .withNonnullFields("telemetry", "tracer")
          .withIgnoredFields("registrationLock")
          .suppress(Warning.NONFINAL_FIELDS)
          .verify();
    }
  }

  @Test
  void equalityRemainsStableAcrossActivationWhileInstancesContributeIndependently()
      throws Exception {
    var completed = new LinkedBlockingQueue<RequestOutcome>();
    var exporter = InMemorySpanExporter.create();

    try (var provider = openTracerProvider(exporter)) {
      var app = new Shoostr().afterRequest(completed::add);

      try (Closeable appCleanup = app::close) {
        var telemetry = createTelemetry(provider);
        var first = new OpenTelemetryTracing(telemetry);
        var second = new OpenTelemetryTracing(telemetry);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());

        app.withExtension(first);

        assertEquals(first, second);

        app.withExtension(second);

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

  private SdkTracerProvider openTracerProvider() {
    return tracerProviderProperties().build();
  }

  private SdkTracerProvider openTracerProvider(InMemorySpanExporter exporter) {
    return tracerProviderProperties(exporter).build();
  }

  private SdkTracerProviderBuilder tracerProviderProperties() {
    return SdkTracerProvider.builder()
        .addSpanProcessor(SimpleSpanProcessor.create(InMemorySpanExporter.create()));
  }

  private SdkTracerProviderBuilder tracerProviderProperties(InMemorySpanExporter exporter) {
    return SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter));
  }

  private OpenTelemetrySdk createTelemetry(SdkTracerProvider provider) {
    return telemetryProperties(provider).build();
  }

  private OpenTelemetrySdkBuilder telemetryProperties(SdkTracerProvider provider) {
    return OpenTelemetrySdk.builder().setTracerProvider(provider);
  }
}
