package io.github.suppierk.shoostr.http.exceptions;

import io.github.suppierk.shoostr.http.HttpStatusCodes;
import java.io.Serial;
import org.jspecify.annotations.Nullable;

/**
 * HTTP 410: Gone.
 *
 * @see HttpStatusCodes#GONE
 */
public class GoneException extends HttpClientException {
  @Serial private static final long serialVersionUID = 1L;

  /** Creates this error using its standard reason phrase. */
  public GoneException() {
    this(null, null);
  }

  /**
   * Creates this error with a diagnostic message.
   *
   * @param message diagnostic message; null selects the status reason phrase
   */
  public GoneException(@Nullable String message) {
    this(message, null);
  }

  /**
   * Creates this error with its standard reason phrase and an underlying failure.
   *
   * @param cause underlying failure, or null
   */
  public GoneException(@Nullable Throwable cause) {
    this(null, cause);
  }

  /**
   * Creates this error with diagnostic information.
   *
   * @param message diagnostic message; null selects the status reason phrase
   * @param cause underlying failure, or null
   */
  public GoneException(@Nullable String message, @Nullable Throwable cause) {
    super(HttpStatusCodes.GONE, message, cause);
  }
}
