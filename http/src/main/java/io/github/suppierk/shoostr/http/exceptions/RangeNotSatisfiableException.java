package io.github.suppierk.shoostr.http.exceptions;

import io.github.suppierk.shoostr.http.HttpStatusCodes;
import java.io.Serial;
import org.jspecify.annotations.Nullable;

/**
 * HTTP 416: Range Not Satisfiable.
 *
 * @see HttpStatusCodes#RANGE_NOT_SATISFIABLE
 */
public class RangeNotSatisfiableException extends HttpClientException {
  @Serial private static final long serialVersionUID = 1L;

  /** Creates this error using its standard reason phrase. */
  public RangeNotSatisfiableException() {
    this(null, null);
  }

  /**
   * Creates this error with a diagnostic message.
   *
   * @param message diagnostic message; null selects the status reason phrase
   */
  public RangeNotSatisfiableException(@Nullable String message) {
    this(message, null);
  }

  /**
   * Creates this error with its standard reason phrase and an underlying failure.
   *
   * @param cause underlying failure, or null
   */
  public RangeNotSatisfiableException(@Nullable Throwable cause) {
    this(null, cause);
  }

  /**
   * Creates this error with diagnostic information.
   *
   * @param message diagnostic message; null selects the status reason phrase
   * @param cause underlying failure, or null
   */
  public RangeNotSatisfiableException(@Nullable String message, @Nullable Throwable cause) {
    super(HttpStatusCodes.RANGE_NOT_SATISFIABLE, message, cause);
  }
}
