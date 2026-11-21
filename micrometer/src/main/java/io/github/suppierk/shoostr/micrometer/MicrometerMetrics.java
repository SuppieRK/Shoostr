package io.github.suppierk.shoostr.micrometer;

import io.github.suppierk.shoostr.ApplicationExtension;
import io.github.suppierk.shoostr.Lifecycle;
import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.RequestObservation;
import io.github.suppierk.shoostr.RequestOutcome;
import io.github.suppierk.shoostr.RouteExtension;
import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.http.HttpStatusCodes;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Optional endpoint metrics; the application owns the supplied registry. Equality compares the
 * registry and active timer through their own equality contracts; activation does not change
 * equality or hash codes.
 */
public final class MicrometerMetrics
    implements ApplicationExtension<MicrometerMetrics>,
        RouteExtension<MicrometerMetrics>,
        Function<Request, RequestObservation> {
  private final MeterRegistry registry;
  private final LongTaskTimer active;
  private final Meter.MeterProvider<Timer> completed;

  /**
   * Creates completed count/duration and active-request instrumentation.
   *
   * @param registry application-owned registry; its filters and exporters remain configurable
   */
  public MicrometerMetrics(MeterRegistry registry) {
    this.registry = Objects.requireNonNull(registry);
    this.completed =
        Timer.builder("http.server.requests")
            .description("Terminal request count and duration")
            .withRegistry(registry);
    this.active =
        LongTaskTimer.builder("http.server.requests.active")
            .description("Requests admitted but not yet terminal")
            .register(registry);
  }

  /**
   * Compares the registry and active timer.
   *
   * @param other candidate provider
   * @return whether both providers have equal collaborators
   */
  @Override
  public boolean equals(@Nullable Object other) {
    return other instanceof MicrometerMetrics provider
        && registry.equals(provider.registry)
        && active.equals(provider.active);
  }

  /**
   * Hashes the same collaborators used by equality.
   *
   * @return hash of the current provider state
   */
  @Override
  public int hashCode() {
    return Objects.hash(registry, active);
  }

  /**
   * Starts one active sample; terminal status, template and finite error categories label its
   * timer.
   *
   * @param request admitted request, never retained
   * @return terminal sample recorder
   */
  @Override
  public RequestObservation apply(Request request) {
    final var sample = active.start();

    return outcome -> {
      try {
        recordCompleted(outcome);
      } finally {
        sample.stop();
      }
    };
  }

  /**
   * Looks up the completed timer from terminal tags and records the request duration.
   *
   * @param outcome detached terminal request outcome
   */
  private void recordCompleted(RequestOutcome outcome) {
    final var error = errorTag(outcome);

    completed
        .withTags(
            "method",
            HttpMethods.httpMethod(outcome.method()).map(HttpMethods::value).orElse("OTHER"),
            "route",
            Objects.requireNonNullElse(outcome.routePattern(), "UNMATCHED"),
            "status",
            Integer.toString(outcome.statusCode()),
            "error",
            error)
        .record(outcome.durationNanos(), TimeUnit.NANOSECONDS);
  }

  /**
   * Classifies the terminal outcome with transport failures taking precedence.
   *
   * @param outcome detached terminal request outcome
   * @return finite error tag for the completed timer
   */
  private static String errorTag(RequestOutcome outcome) {
    if (outcome.transportFailure() != null) {
      return "transport";
    }

    if (outcome.applicationFailure() != null) {
      return "application";
    }

    return outcome.statusCode() >= HttpStatusCodes.INTERNAL_SERVER_ERROR.value()
        ? "server"
        : "none";
  }

  /**
   * Contributes observation only to endpoints selected by this scope.
   *
   * @param lifecycle selected registration scope
   * @param inherited parent provider, or null
   * @return this stateless provider
   */
  @Override
  public MicrometerMetrics configure(Lifecycle lifecycle, @Nullable MicrometerMetrics inherited) {
    lifecycle.observe(this);

    return this;
  }
}
