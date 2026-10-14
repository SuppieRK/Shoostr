package io.github.suppierk.shoostr.example.tracing;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.opentelemetry.OpenTelemetryTracing;
import io.opentelemetry.api.trace.Span;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Wires request handling independently from tracing configuration. */
public final class TelemetryExample {
  private static final Logger LOGGER = LoggerFactory.getLogger(TelemetryExample.class);

  /** Prevents construction of the launcher. */
  private TelemetryExample() {}

  /**
   * Starts the standalone tracing example.
   *
   * <p>S1181 is suppressed because failed startup must release the owned telemetry SDK even when an
   * Error escapes; cleanup runs before the original failure is rethrown.
   *
   * @throws Exception if startup fails
   */
  @SuppressWarnings("java:S1181")
  static void main() throws Exception {
    var app = application();

    try {
      app.start();
    } catch (Exception | Error failure) {
      try {
        app.close();
      } catch (IOException cleanup) {
        failure.addSuppressed(cleanup);
      }

      throw failure;
    }

    LOGGER.info("Tracing: http://127.0.0.1:{}/hello/Ada", app.port());
  }

  /**
   * Builds an unstarted app for explicit caller-owned lifecycle.
   *
   * <p>S2095 is suppressed because the caller owns the returned app, which owns the SDK through
   * TelemetryConfiguration; the tracing adapter borrows that SDK. S1181 is suppressed because
   * failed assembly must also clean up after an Error before rethrowing the original failure.
   *
   * @return unstarted tracing application
   */
  @SuppressWarnings({"java:S2095", "java:S1181"})
  public static Shoostr application() {
    var app = new Shoostr();

    try {
      // TelemetryConfiguration is example-local; it creates and owns the OpenTelemetry SDK.
      var configuration = new TelemetryConfiguration();
      // Register it as a core Extension so app.close() also flushes and closes the SDK.
      app.extensions(configuration);
      // Shoostr's opentelemetry module provides the adapter; allRequests enables request tracing.
      app.extensions(new OpenTelemetryTracing(configuration.sdk()).allRequests());
      return app.routes(
          routes ->
              routes.get(
                  "/hello/{name}",
                  (request, response) -> {
                    // OpenTelemetry's Span.current() exposes the server span created by the
                    // adapter.
                    response.setHeader(
                        HttpHeaders.of("X-Trace-Id").value(),
                        Span.current().getSpanContext().getTraceId());
                    response.text("Hello, " + request.pathParam("name").orElseThrow() + "!");
                  }));
    } catch (RuntimeException | Error failure) {
      try {
        app.close();
      } catch (IOException cleanup) {
        failure.addSuppressed(cleanup);
      }

      throw failure;
    }
  }
}
