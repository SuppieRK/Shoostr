package io.github.suppierk.shoostr.example.metrics;

import io.github.suppierk.shoostr.Routes;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.nio.charset.StandardCharsets;

/** Registers request handling separately from registry construction and export configuration. */
final class MetricsRoutes {
  /** Prevents construction of the registration function. */
  private MetricsRoutes() {}

  /**
   * Registers a parameterized route with a bounded template for metric labels.
   *
   * @param routes application registration scope
   * @param registry application-owned registry, shared with the installed metrics extension
   */
  static void register(Routes routes, PrometheusMeterRegistry registry) {
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
  }
}
