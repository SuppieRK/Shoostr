package io.github.suppierk.shoostr.opentelemetry;

import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.RequestObservation;
import io.github.suppierk.shoostr.RequestOutcome;
import io.github.suppierk.shoostr.http.HttpMethods;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Optional SERVER spans with application-owned SDK, exporters and propagation configuration. */
public final class OpenTelemetryTracing implements Function<Request, RequestObservation> {
  private static final TextMapGetter<Request> HEADERS =
      new TextMapGetter<>() {
        /** {@inheritDoc} */
        @Override
        public Iterable<String> keys(Request carrier) {
          return carrier.headerMap().keySet();
        }

        /** {@inheritDoc} */
        @Override
        public @Nullable String get(@Nullable Request carrier, String key) {
          return carrier == null ? null : carrier.header(key);
        }
      };

  private final OpenTelemetry telemetry;
  private final Tracer tracer;

  /**
   * Uses an explicit provider without installing globals or owning its lifetime.
   *
   * @param telemetry application-configured tracing and propagation
   */
  public OpenTelemetryTracing(OpenTelemetry telemetry) {
    this.telemetry = Objects.requireNonNull(telemetry);
    tracer = telemetry.getTracer("io.github.suppierk.shoostr");
  }

  /**
   * Extracts a remote parent and scopes the server span across application invocation.
   *
   * @param request admission metadata, never retained
   * @return invocation scope and terminal span recorder
   */
  @Override
  @SuppressWarnings(
      "MustBeClosedChecker") // Shoostr closes the returned observation on this thread.
  public RequestObservation apply(Request request) {
    var parent =
        telemetry.getPropagators().getTextMapPropagator().extract(Context.root(), request, HEADERS);
    var method = HttpMethods.httpMethod(request.method()).map(HttpMethods::value).orElse("_OTHER");
    var span =
        tracer
            .spanBuilder(method)
            .setSpanKind(SpanKind.SERVER)
            .setParent(parent)
            .setAttribute("http.request.method", method)
            .startSpan();
    Scope scope;

    try {
      scope = parent.with(span).makeCurrent();
    } catch (RuntimeException failure) {
      span.end();
      throw failure;
    }

    return new RequestObservation() {
      /** Restores the caller's thread context before any terminal observer notification. */
      @Override
      public void close() {
        scope.close();
      }

      /**
       * Adds safe terminal attributes and ends the span once the exchange has terminated.
       *
       * @param outcome terminal snapshot
       */
      @Override
      public void complete(RequestOutcome outcome) {
        try {
          if (outcome.routePattern() != null) {
            span.updateName(method + " " + outcome.routePattern());
            span.setAttribute("http.route", outcome.routePattern());
          }

          if (outcome.statusCode() != 0) {
            span.setAttribute("http.response.status_code", outcome.statusCode());
          }

          if (outcome.transportFailure() != null
              || outcome.statusCode() >= 500
              || (outcome.statusCode() == 0 && outcome.applicationFailure() != null)) {
            span.setStatus(StatusCode.ERROR);
            span.setAttribute(
                "error.type",
                outcome.transportFailure() != null
                    ? "transport"
                    : outcome.statusCode() >= 500
                        ? Integer.toString(outcome.statusCode())
                        : "application");
          }
        } finally {
          span.end();
        }
      }
    };
  }
}
