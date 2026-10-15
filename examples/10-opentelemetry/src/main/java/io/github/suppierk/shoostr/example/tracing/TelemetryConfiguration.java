package io.github.suppierk.shoostr.example.tracing;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.time.Duration;

/** Owns the SDK and exporter; Shoostr's tracing adapter only borrows them. */
final class TelemetryConfiguration implements AutoCloseable {
  private final OpenTelemetrySdk sdk;

  /** Configures OTLP export and propagation eagerly without installing a global SDK. */
  TelemetryConfiguration() {
    var endpoint =
        System.getenv()
            .getOrDefault("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "http://127.0.0.1:4318/v1/traces");
    // OpenTelemetry's OTLP HTTP exporter sends spans to the Compose trace backend.
    var exporter =
        OtlpHttpSpanExporter.builder()
            .setEndpoint(endpoint)
            .setTimeout(Duration.ofSeconds(2))
            .build();
    // OpenTelemetry batches finished spans and tags them with this example's service name.
    var provider =
        SdkTracerProvider.builder()
            .setResource(
                Resource.getDefault()
                    .merge(
                        Resource.create(
                            Attributes.builder()
                                .put("service.name", "shoostr-tracing-example")
                                .build())))
            .addSpanProcessor(
                BatchSpanProcessor.builder(exporter)
                    .setScheduleDelay(Duration.ofMillis(100))
                    .build())
            .build();
    // Build an app-local SDK; W3C propagation reads and writes standard trace-context headers.
    sdk =
        OpenTelemetrySdk.builder()
            .setTracerProvider(provider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build();
  }

  /**
   * Supplies application-owned telemetry to the borrowing adapter.
   *
   * @return SDK owned by this configuration
   */
  OpenTelemetrySdk sdk() {
    return sdk;
  }

  /** Flushes and closes tracing after request drain and adapter cleanup. */
  @Override
  public void close() {
    sdk.close();
  }
}
