package io.github.suppierk.shoostr.example.tracing;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.opentelemetry.OpenTelemetryTracing;
import io.opentelemetry.api.trace.Span;
import java.io.IOException;

/** Wires request handling independently from tracing configuration. */
public final class TelemetryExample {
  /** Prevents construction of the launcher. */
  private TelemetryExample() {}

  /**
   * Starts the standalone tracing example.
   *
   * @throws Exception if startup fails
   */
  static void main() throws Exception {
    var app = application(Options.defaults());

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
  }

  /**
   * Builds an unstarted app for explicit caller-owned lifecycle.
   *
   * @param options listener settings
   * @return unstarted tracing application
   */
  public static Shoostr application(Options options) {
    var app = new Shoostr(options);

    try {
      var configuration = new TelemetryConfiguration();
      app.extensions(configuration);
      app.extensions(new OpenTelemetryTracing(configuration.sdk()).allRequests());
      return app.routes(
          routes ->
              routes.get(
                  "/hello/{name}",
                  (request, response) -> {
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
