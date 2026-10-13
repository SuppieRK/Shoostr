package io.github.suppierk.shoostr.example.metrics;

import io.github.suppierk.shoostr.Extension;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/** Owns this example's registry; the framework metrics adapter only borrows it. */
final class MetricsConfiguration implements Extension<Void> {
  private final PrometheusMeterRegistry registry;

  /** Configures one registry eagerly, before the listener can accept traffic. */
  MetricsConfiguration() {
    registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
  }

  /**
   * Supplies the same registry to instrumentation and the scrape handler.
   *
   * @return registry owned by this configuration, never by its borrowers
   */
  PrometheusMeterRegistry registry() {
    return registry;
  }

  /** Closes the registry after request drain and borrowing-adapter cleanup. */
  @Override
  public void close() {
    registry.close();
  }
}
