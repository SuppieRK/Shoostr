package io.github.suppierk.shoostr.example.tracing;

import io.github.suppierk.shoostr.Routes;
import io.github.suppierk.shoostr.http.HttpHeaders;
import io.opentelemetry.api.trace.Span;

/** Registers the small observable HTTP surface. */
final class TelemetryRoutes {
  /** Prevents construction of the registration function. */
  private TelemetryRoutes() {}

  /**
   * Returns the current server trace identifier with the greeting.
   *
   * @param routes application registration scope
   */
  static void register(Routes routes) {
    routes.get(
        "/hello/{name}",
        (request, response) -> {
          response.setHeader(
              HttpHeaders.of("X-Trace-Id").value(), Span.current().getSpanContext().getTraceId());
          response.text("Hello, " + request.pathParam("name").orElseThrow() + "!");
        });
  }
}
