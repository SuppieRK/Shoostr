package io.github.suppierk.shoostr.example.metrics;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.micrometer.MicrometerMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
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
   * @param args unused command-line arguments
   * @throws Exception if startup fails
   */
  public static void main(String[] args) throws Exception {
    var app = application().start();
    LOGGER.info("Metrics: http://127.0.0.1:{}/metrics", app.port());
  }

  /**
   * Returns the app with explicit registry ownership and instrumentation installation.
   *
   * <p>The app closes the registry through its afterClose callback. The metrics adapter borrows the
   * registry.
   *
   * @return app owned by its eventual caller
   */
  public static Shoostr application() {
    var app = new Shoostr();

    // Micrometer's Prometheus integration creates the registry; Shoostr does not configure it.
    var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    // Core's afterClose callback releases it after request drain and extension cleanup.
    app.afterClose(registry::close);
    // Shoostr's micrometer module provides this adapter; allRequests enables instrumentation.
    app.extensions(new MicrometerMetrics(registry).allRequests());
    app.routes(
        routes -> {
          routes.get(
              "/hello/{name}",
              (request, response) ->
                  response.text("Hello, " + request.pathParam("name").orElseThrow() + "!"));
          routes.get(
              "/metrics",
              // Micrometer produces Prometheus text; this is an ordinary Shoostr HTTP handler.
              (ignored, response) ->
                  response.body(
                      "text/plain; version=0.0.4; charset=utf-8",
                      registry.scrape().getBytes(StandardCharsets.UTF_8)));
        });
    return app;
  }
}
