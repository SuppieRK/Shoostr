package io.github.suppierk.shoostr.opentelemetry;

import io.github.suppierk.shoostr.ApplicationCallbacks;
import io.github.suppierk.shoostr.ApplicationExtension;
import io.github.suppierk.shoostr.Lifecycle;
import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.RequestObservation;
import io.github.suppierk.shoostr.RequestOutcome;
import io.github.suppierk.shoostr.RouteExtension;
import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.http.HttpStatusCodes;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Optional SERVER spans with application-owned SDK, exporters and propagation configuration.
 * Equality compares the telemetry provider and tracer through their own equality contracts;
 * activation does not change equality or hash codes.
 */
public final class OpenTelemetryTracing
    implements ApplicationExtension<OpenTelemetryTracing>,
        RouteExtension<OpenTelemetryTracing>,
        Function<Request, RequestObservation> {
  private static final TextMapGetter<Request> HEADERS = headerGetter();

  private final OpenTelemetry telemetry;
  private final Tracer tracer;
  private final ReentrantLock registrationLock;

  /**
   * Uses an explicit provider without installing globals or owning its lifetime.
   *
   * @param telemetry application-configured tracing and propagation
   */
  public OpenTelemetryTracing(OpenTelemetry telemetry) {
    this.telemetry = Objects.requireNonNull(telemetry);
    this.tracer = telemetry.getTracer("io.github.suppierk.shoostr");
    this.registrationLock = new ReentrantLock();
  }

  /**
   * Adapts immutable request headers to OpenTelemetry context propagation.
   *
   * @return request-header propagation reader
   */
  private static TextMapGetter<Request> headerGetter() {
    return new TextMapGetter<>() {
      /**
       * Retains header enumeration for propagators that use the deprecated upstream method.
       *
       * <p>Deprecation is suppressed because this upstream callback still serves propagators that
       * enumerate request headers.
       *
       * @param carrier request providing the header names
       * @return incoming header names
       */
      @Override
      @SuppressWarnings("deprecation")
      public Iterable<String> keys(Request carrier) {
        return carrier.headerMap().keySet();
      }

      /** {@inheritDoc} */
      @Override
      public @Nullable String get(@Nullable Request carrier, String key) {
        return carrier == null ? null : carrier.header(key).orElse(null);
      }
    };
  }

  /**
   * Captures admission context without changing it or creating a span.
   *
   * @return observation restoring the worker's exact previous context after attachment
   */
  private static RequestObservation admissionContext() {
    final var captured = Context.current();

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

  /**
   * Compares all stored collaborators.
   *
   * @param other candidate provider
   * @return whether both providers have equal stored state
   */
  @Override
  public boolean equals(@Nullable Object other) {
    return other instanceof OpenTelemetryTracing provider
        && telemetry.equals(provider.telemetry)
        && tracer.equals(provider.tracer);
  }

  /**
   * Hashes the same collaborators used by equality.
   *
   * @return hash of the current provider state
   */
  @Override
  public int hashCode() {
    return Objects.hash(telemetry, tracer);
  }

  /**
   * Contributes selected SERVER spans without changing application SDK configuration.
   *
   * @param endpoint registration collector receiving this selection's observations
   * @param inherited installed instance selected by the parent, or null
   * @return this installed provider, which has no mutable scope-local settings
   */
  @Override
  public OpenTelemetryTracing configure(
      Lifecycle endpoint, @Nullable OpenTelemetryTracing inherited) {
    registrationLock.lock();

    try {
      endpoint.observe(this);

      return this;
    } finally {
      registrationLock.unlock();
    }
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
    final var parent =
        telemetry.getPropagators().getTextMapPropagator().extract(Context.root(), request, HEADERS);
    final var method =
        HttpMethods.httpMethod(request.method()).map(HttpMethods::value).orElse("_OTHER");
    final var span = startSpan(method, parent);
    final var spanContext = parent.with(span);
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
          recordTerminalAttributes(span, method, outcome);
        } finally {
          span.end();
        }
      }
    };
  }

  /**
   * Records route, response status and any error on the server span at completion.
   *
   * @param span server span for the exchange
   * @param method HTTP method token used in the span name
   * @param outcome detached terminal request outcome
   */
  private static void recordTerminalAttributes(Span span, String method, RequestOutcome outcome) {
    final var route = outcome.routePattern();
    if (route != null) {
      span.updateName(method + " " + route);
      span.setAttribute("http.route", route);
    }

    final var status = outcome.statusCode();
    if (status != 0) {
      span.setAttribute("http.response.status_code", status);
    }

    final var errorType = errorType(outcome);
    if (errorType != null) {
      span.setStatus(StatusCode.ERROR);
      span.setAttribute("error.type", errorType);
    }
  }

  /**
   * Classifies terminal errors in transport, server status and application order.
   *
   * @param outcome detached terminal request outcome
   * @return error type, or null when the span status and error tag should be left untouched
   */
  private static @Nullable String errorType(RequestOutcome outcome) {
    if (outcome.transportFailure() != null) {
      return "transport";
    }

    final var status = outcome.statusCode();
    if (status >= HttpStatusCodes.INTERNAL_SERVER_ERROR.value()) {
      return Integer.toString(status);
    }

    return status == 0 && outcome.applicationFailure() != null ? "application" : null;
  }

  /**
   * Starts a server span with the incoming method and propagated parent context.
   *
   * @param method HTTP method token
   * @param parent propagated parent context
   * @return span completed by the request observation
   */
  private Span startSpan(String method, Context parent) {
    return tracer
        .spanBuilder(method)
        .setSpanKind(SpanKind.SERVER)
        .setParent(parent)
        .setAttribute("http.request.method", method)
        .startSpan();
  }

  /**
   * Installs configured observation without owning borrowed dependencies. Selective mode carries
   * admission context across every request handoff, including unselected endpoints, without
   * creating SERVER spans there.
   *
   * @param callbacks registration-only application callbacks
   */
  @Override
  public void install(ApplicationCallbacks callbacks) {
    registrationLock.lock();

    try {
      callbacks.observe(ignored -> admissionContext());
    } finally {
      registrationLock.unlock();
    }
  }
}
