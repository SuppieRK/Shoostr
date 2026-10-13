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
   * @throws Exception if startup fails
   */
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
   * @return unstarted tracing application
   */
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
