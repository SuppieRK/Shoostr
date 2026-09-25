package io.github.suppierk.shoostr;

import io.github.suppierk.shoostr.http.HttpMethods;
import java.util.Objects;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Safe terminal summaries, registered explicitly with Shoostr.afterRequest. Disabled unless
 * registered.
 */
public final class AccessLog implements Consumer<RequestOutcome> {
  private final Consumer<String> sink;

  /**
   * Logs through System.Logger at INFO; configure its backend using the application's logging
   * setup.
   */
  public AccessLog() {
    this(line -> System.getLogger(AccessLog.class.getName()).log(System.Logger.Level.INFO, line));
  }

  /**
   * Uses an application-owned sink. It may run concurrently on completion threads and must not
   * block.
   *
   * @param sink receives a single-line summary with no raw path, headers, body or exception details
   */
  public AccessLog(Consumer<String> sink) {
    this.sink = Objects.requireNonNull(sink);
  }

  /**
   * Emits bounded method, configured route, final status, duration and failure flags.
   *
   * @param outcome terminal summary
   */
  @Override
  public void accept(RequestOutcome outcome) {
    var method = HttpMethods.httpMethod(outcome.method()).map(HttpMethods::value).orElse("OTHER");
    sink.accept(
        "method="
            + method
            + " route="
            + safe(outcome.routePattern())
            + " status="
            + outcome.statusCode()
            + " duration_ns="
            + outcome.durationNanos()
            + " application_failure="
            + (outcome.applicationFailure() != null)
            + " transport_failure="
            + (outcome.transportFailure() != null));
  }

  /**
   * Escapes separators and controls in application-configured templates to preserve one log record.
   *
   * @param value configured template, or null if no route matched
   * @return printable single-field representation
   */
  private static String safe(@Nullable String value) {
    if (value == null) {
      return "UNMATCHED";
    }

    var result = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      result.append(
          Character.isWhitespace(character) || Character.isISOControl(character) ? '_' : character);
    }
    return result.toString();
  }
}
