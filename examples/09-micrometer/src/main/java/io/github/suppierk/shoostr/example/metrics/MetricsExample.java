package io.github.suppierk.shoostr.example.metrics;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.micrometer.MicrometerMetrics;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Composes the metrics demonstration without a second HTTP server for metrics. */
public final class MetricsExample {
  private static final Logger LOGGER = LoggerFactory.getLogger(MetricsExample.class);

  /** Prevents construction of the launcher. */
  private MetricsExample() {}

  /**
   * Starts the app; backend addresses are described by the Compose recipe.
   *
   * <p>S1181 is suppressed because failed startup must release the owned registry even when an
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

    LOGGER.info("Metrics: http://127.0.0.1:{}/metrics", app.port());
  }

  /**
   * Returns the app with explicit registry ownership and instrumentation installation.
   *
   * <p>S2095 is suppressed because the caller owns the returned app, which owns the registry
   * through MetricsConfiguration; the metrics adapter borrows that registry. S1181 is suppressed
   * because failed assembly must also clean up after an Error before rethrowing the original
   * failure.
   *
   * @return app owned by its eventual caller
   */
  @SuppressWarnings({"java:S2095", "java:S1181"})
  public static Shoostr application() {
    var app = new Shoostr();

    try {
      // MetricsConfiguration is example-local; it creates and owns Micrometer's registry.
      var configuration = new MetricsConfiguration();
      // Register it as a core Extension so app.close() also closes the registry.
      app.extensions(configuration);
      // Shoostr's micrometer module provides this adapter; allRequests enables instrumentation.
      app.extensions(new MicrometerMetrics(configuration.registry()).allRequests());
      app.routes(
          routes -> {
            var registry = configuration.registry();
            routes.get(
                "/hello/{name}",
                (request, response) ->
                    response.text("Hello, " + request.pathParam("name").orElseThrow() + "!"));
            routes.get(
                "/metrics",
                // Micrometer produces Prometheus text; this is an ordinary Shoostr HTTP handler.
                (_, response) ->
                    response.body(
                        "text/plain; version=0.0.4; charset=utf-8",
                        registry.scrape().getBytes(StandardCharsets.UTF_8)));
          });
      return app;
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
