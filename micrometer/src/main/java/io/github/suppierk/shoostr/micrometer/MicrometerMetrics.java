package io.github.suppierk.shoostr.micrometer;

import io.github.suppierk.shoostr.ApplicationCallbacks;
import io.github.suppierk.shoostr.Extension;
import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.RequestObservation;
import io.github.suppierk.shoostr.http.HttpMethods;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Optional metrics registered with Shoostr.observe; the application owns the supplied registry. */
public final class MicrometerMetrics
    implements Extension<Void>, Function<Request, RequestObservation> {
  private final MeterRegistry registry;
  private final LongTaskTimer active;
  private boolean allRequests;
  private boolean installed;

  /**
   * Creates completed count/duration and active-request instrumentation.
   *
   * @param registry application-owned registry; its filters and exporters remain configurable
   */
  public MicrometerMetrics(MeterRegistry registry) {
    this.registry = Objects.requireNonNull(registry);
    active =
        LongTaskTimer.builder("http.server.requests.active")
            .description("Requests admitted but not yet terminal")
            .register(registry);
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
    var sample = active.start();
    return outcome -> {
      try {
        String error;
        if (outcome.transportFailure() != null) {
          error = "transport";
        } else if (outcome.applicationFailure() != null) {
          error = "application";
        } else if (outcome.statusCode() >= 500) {
          error = "server";
        } else {
          error = "none";
        }

        Timer.builder("http.server.requests")
            .description("Terminal request count and duration")
            .tags(
                "method",
                HttpMethods.httpMethod(outcome.method()).map(HttpMethods::value).orElse("OTHER"),
                "route",
                Objects.requireNonNullElse(outcome.routePattern(), "UNMATCHED"),
                "status",
                Integer.toString(outcome.statusCode()),
                "error",
                error)
            .register(registry)
            .record(outcome.durationNanos(), TimeUnit.NANOSECONDS);
      } finally {
        sample.stop();
      }
    };
  }

  /**
   * Enables app-wide instrumentation explicitly before extension installation.
   *
   * @return this extension
   * @throws IllegalStateException if already installed
   */
  public synchronized MicrometerMetrics allRequests() {
    if (installed) {
      throw new IllegalStateException("Configure instrumentation before installation");
    }

    allRequests = true;
    return this;
  }

  /**
   * Installs configured observation without owning borrowed dependencies.
   *
   * @param callbacks registration-only application callbacks
   */
  @Override
  public synchronized void install(ApplicationCallbacks callbacks) {
    installed = true;
    if (allRequests) {
      callbacks.observe(this);
    }
  }
}
