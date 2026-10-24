package io.github.suppierk.shoostr.example.tracing;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.opentelemetry.OpenTelemetryTracing;
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
   * @param args unused command-line arguments
   * @throws Exception if startup fails
   */
  public static void main(String[] args) throws Exception {
    var app = application().start();
    LOGGER.info("Tracing: http://127.0.0.1:{}/hello/Ada", app.port());
  }

  /**
   * Builds an unstarted app for explicit caller-owned lifecycle.
   *
   * <p>The app closes the SDK through its afterClose callback. The tracing adapter borrows the SDK.
   *
   * @return unstarted tracing application
   */
  public static Shoostr application() {
    var app = new Shoostr();

    // TelemetryConfiguration is example-local; it creates and owns the OpenTelemetry SDK.
    var configuration = new TelemetryConfiguration();
    // Core's afterClose callback flushes and closes the SDK after adapter cleanup.
    app.afterClose(configuration::close);
    // The SDK's Tracer creates application spans; this service borrows it for internal calls.
    var greetings = new GreetingService(configuration.sdk().getTracer("shoostr-tracing-example"));
    // Shoostr's opentelemetry module provides the adapter; installation alone leaves tracing off.
    var tracing = new OpenTelemetryTracing(configuration.sdk());
    app.extensions(tracing);
    return app.routes(
        routes -> {
          // This endpoint remains untraced; extension selection belongs to individual
          // routes/groups.
          routes.get("/health", (ignored, response) -> response.text("ok"));
          routes.get(
              "/hello/{name}",
              (request, response) ->
                  response.text(greetings.greet(request.pathParam("name").orElseThrow())),
              extensions -> extensions.with(tracing));
        });
  }
}
