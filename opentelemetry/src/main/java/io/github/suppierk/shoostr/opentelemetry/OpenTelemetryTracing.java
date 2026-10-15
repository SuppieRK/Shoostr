package io.github.suppierk.shoostr.opentelemetry;

import io.github.suppierk.shoostr.ApplicationCallbacks;
import io.github.suppierk.shoostr.Extension;
import io.github.suppierk.shoostr.Extensions;
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
public final class OpenTelemetryTracing
    implements Extension<OpenTelemetryTracing.Configuration>,
        Function<Request, RequestObservation> {
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
          return carrier == null ? null : carrier.header(key).orElse(null);
        }
      };

  private final OpenTelemetry telemetry;
  private final Tracer tracer;
  private boolean allRequests;
  private boolean installed;

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
   * Creates independent route/group activation without changing application SDK configuration.
   *
   * @param endpoint registration scope receiving observations
   * @param inherited enclosing configuration, or null
   * @return this scope's configuration; selection alone does not enable tracing
   */
  @Override
  public Configuration configure(Extensions endpoint, @Nullable Configuration inherited) {
    var configuration = new Configuration(endpoint, this);
    if (inherited != null && inherited.enabled) {
      configuration.enabled();
    }

    return configuration;
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
    var spanContext = parent.with(span);
    Scope scope;

    try {
      scope = spanContext.makeCurrent();
    } catch (RuntimeException failure) {
      span.end();
      throw failure;
    }

    return new RequestObservation() {
      /**
       * Attaches the same server span without opening another span or moving its original scope.
       *
       * @return scope restoring the selected execution thread's prior context
       */
      @Override
      public AutoCloseable attach() {
        return spanContext.makeCurrent();
      }

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
            String errorType;
            if (outcome.transportFailure() != null) {
              errorType = "transport";
            } else if (outcome.statusCode() >= 500) {
              errorType = Integer.toString(outcome.statusCode());
            } else {
              errorType = "application";
            }

            span.setAttribute("error.type", errorType);
          }
        } finally {
          span.end();
        }
      }
    };
  }

  /**
   * Enables app-wide instrumentation explicitly before extension installation.
   *
   * @return this extension
   * @throws IllegalStateException if already installed
   */
  public synchronized OpenTelemetryTracing allRequests() {
    if (installed) {
      throw new IllegalStateException("Configure instrumentation before installation");
    }

    allRequests = true;
    return this;
  }

  /**
   * Installs configured observation without owning borrowed dependencies. Selective mode carries
   * admission context across every request handoff, including unselected endpoints, without
   * creating SERVER spans there.
   *
   * @param callbacks registration-only application callbacks
   */
  @Override
  public synchronized void install(ApplicationCallbacks callbacks) {
    installed = true;
    if (allRequests) {
      callbacks.observe(this);
    } else {
      callbacks.observe(_ -> admissionContext());
    }
  }

  /**
   * Captures admission context without changing it or creating a span.
   *
   * @return observation restoring the worker's exact previous context after attachment
   */
  private static RequestObservation admissionContext() {
    var captured = Context.current();
    return new RequestObservation() {
      /**
       * Replaces unrelated worker context for this invocation only.
       *
       * <p>The returned SDK scope is owned and closed by Shoostr on the attaching thread;
       * MustBeClosedChecker cannot follow that ownership transfer.
       *
       * @return scope restoring the worker's previous context on close
       */
      @Override
      @SuppressWarnings("MustBeClosedChecker")
      public AutoCloseable attach() {
        return captured.makeCurrent();
      }

      /**
       * Carries context only; there is no SERVER span to record or end.
       *
       * @param outcome terminal snapshot
       */
      @Override
      public void complete(RequestOutcome outcome) {
        // This observation carries context only and owns no span to complete.
      }
    };
  }

  /** Registration-only activation of SERVER spans on one route or group. */
  public static final class Configuration {
    private final Extensions endpoint;
    private final OpenTelemetryTracing tracing;
    private boolean enabled;

    /**
     * Creates configuration belonging to one registration scope.
     *
     * @param endpoint receiving scope
     * @param tracing installed tracing instance
     */
    private Configuration(Extensions endpoint, OpenTelemetryTracing tracing) {
      this.endpoint = Objects.requireNonNull(endpoint);
      this.tracing = Objects.requireNonNull(tracing);
    }

    /**
     * Enables SERVER spans on this route and inherited children. Repeated activation contributes
     * once; app-wide instrumentation of this instance already covers the endpoint. Application
     * spans remain controlled by the supplied SDK, including on unselected endpoints.
     *
     * @return this configuration
     * @throws IllegalArgumentException if the tracing instance is not installed
     * @throws IllegalStateException if registration has finished
     */
    public Configuration enabled() {
      endpoint.requireInstalled(tracing);
      synchronized (tracing) {
        if (!enabled && !tracing.allRequests) {
          endpoint.observe(tracing);
        }
      }
      enabled = true;
      return this;
    }
  }
}
