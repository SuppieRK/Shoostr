package io.github.suppierk.shoostr.http.exceptions;

import io.github.suppierk.shoostr.http.HttpStatusCodes;
import java.io.Serial;
import org.jspecify.annotations.Nullable;

/**
 * HTTP 424: Failed Dependency.
 *
 * @see HttpStatusCodes#FAILED_DEPENDENCY
 */
public class FailedDependencyException extends HttpClientException {
  @Serial private static final long serialVersionUID = 1L;

  /** Creates this error using its standard reason phrase. */
  public FailedDependencyException() {
    this(null, null);
  }

  /**
   * Creates this error with a diagnostic message.
   *
   * @param message diagnostic message; null selects the status reason phrase
   */
  public FailedDependencyException(@Nullable String message) {
    this(message, null);
  }

  /**
   * Creates this error with its standard reason phrase and an underlying failure.
   *
   * @param cause underlying failure, or null
   */
  public FailedDependencyException(@Nullable Throwable cause) {
    this(null, cause);
  }

  /**
   * Creates this error with diagnostic information.
   *
   * @param message diagnostic message; null selects the status reason phrase
   * @param cause underlying failure, or null
   */
  public FailedDependencyException(@Nullable String message, @Nullable Throwable cause) {
    super(HttpStatusCodes.FAILED_DEPENDENCY, message, cause);
  }
}
