package io.github.suppierk.shoostr.example.metrics;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.micrometer.MicrometerMetrics;
import io.micrometer.core.instrument.MeterRegistry;

/** Measures selected endpoints without owning the application's meter registry. */
public final class RequestMetricsExample {
  /** Prevents construction of the factory. */
  private RequestMetricsExample() {}

  /**
   * Creates an unstarted application using a caller-owned meter registry.
   *
   * @param registry registry with application-selected exporters and filters
   * @return unstarted application
   */
  public static Shoostr application(MeterRegistry registry) {
    var metrics = new MicrometerMetrics(registry);
    return new Shoostr()
        // Installation makes metrics selectable; only the /api group enables observation below.
        .extensions(metrics)
        .routes(
            routes ->
                routes
                    .get("/health", (ignored, response) -> response.text("ok"))
                    .path(
                        "/api",
                        api ->
                            api.get(
                                "/items/{id}",
                                (request, response) ->
                                    response.text("item " + request.pathParam("id").orElseThrow())),
                        extensions -> extensions.lifecycle().observe(metrics)));
  }
}
