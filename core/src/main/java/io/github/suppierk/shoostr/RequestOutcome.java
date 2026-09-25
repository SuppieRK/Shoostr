package io.github.suppierk.shoostr;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Read-only summary after application finalization and transport termination. Contains no live
 * request or response; callbacks may retain it. Failure references are diagnostic objects, not safe
 * response bodies or metric labels.
 *
 * @param method original wire method
 * @param routePattern composed named route template, or null if no endpoint was selected
 * @param statusCode committed final response or upgrade status, or zero if none was committed
 * @param durationNanos monotonic duration from framework admission through completion
 * @param applicationFailure initiating application failure, or null
 * @param transportFailure terminal exchange failure, or null; may share the application cause
 */
public record RequestOutcome(
    String method,
    @Nullable String routePattern,
    int statusCode,
    long durationNanos,
    @Nullable Throwable applicationFailure,
    @Nullable Throwable transportFailure) {
  /**
   * Validates the required method, final status, and elapsed duration.
   *
   * @throws NullPointerException if method is null
   * @throws IllegalArgumentException if status is neither uncommitted, an upgrade, nor a final HTTP
   *     status, or duration is negative
   */
  public RequestOutcome {
    Objects.requireNonNull(method);
    if (statusCode != 0 && statusCode != 101 && (statusCode < 200 || statusCode > 599)) {
      throw new IllegalArgumentException("Expected uncommitted, upgrade, or final HTTP status");
    }

    if (durationNanos < 0) {
      throw new IllegalArgumentException("Duration cannot be negative");
    }
  }
}
