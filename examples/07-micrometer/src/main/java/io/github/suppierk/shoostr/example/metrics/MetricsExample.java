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

    LOGGER.info("Metrics: http://127.0.0.1:{}/metrics", app.port());
  }

  /**
   * Returns the app with explicit registry ownership and instrumentation installation.
   *
   * @return app owned by its eventual caller
   */
  public static Shoostr application() {
    var app = new Shoostr();

    try {
      var configuration = new MetricsConfiguration();
      app.extensions(configuration);
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
