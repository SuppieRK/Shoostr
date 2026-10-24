package io.github.suppierk.shoostr.example.tracing;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.opentelemetry.OpenTelemetryTracing;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/** Traces selected endpoints with an application-owned SDK and worker pool. */
public final class RequestTracingExample {
  /** Prevents construction of the factory. */
  private RequestTracingExample() {}

  /**
   * Creates an unstarted application borrowing telemetry and endpoint workers.
   *
   * @param telemetry SDK with application-selected exporters and propagation
   * @param workers asynchronous caller-owned endpoint executor
   * @return unstarted application
   */
  public static Shoostr application(OpenTelemetry telemetry, ExecutorService workers) {
    Objects.requireNonNull(workers);
    var tracing = new OpenTelemetryTracing(telemetry);
    var tracer = telemetry.getTracer("example.items");
    return new Shoostr()
        // Borrow the caller's SDK; installing tracing does not enable it on every endpoint.
        .extensions(tracing)
        .routes(
            routes ->
                routes
                    .get(
                        "/health",
                        workers,
                        (ignored, response) ->
                            response.text("traced=" + Span.current().getSpanContext().isValid()))
                    .get(
                        "/api/items/{id}",
                        workers,
                        (request, response) -> {
                          var child = tracer.spanBuilder("load-item").startSpan();

                          // Make this child current for the work and end it even when output fails.

                          try (var scope = child.makeCurrent()) {
                            response.text("item " + request.pathParam("id").orElseThrow());
                          } finally {
                            child.end();
                          }
                        },
                        extensions -> extensions.with(tracing)));
  }
}
