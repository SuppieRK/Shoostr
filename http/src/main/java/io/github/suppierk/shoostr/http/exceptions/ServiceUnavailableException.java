package io.github.suppierk.shoostr.http.exceptions;

import io.github.suppierk.shoostr.http.HttpStatusCodes;
import java.io.Serial;
import org.jspecify.annotations.Nullable;

/**
 * HTTP 503: Service Unavailable.
 *
 * <p>S110 is suppressed due to the need to maintain exceptions hierarchy.
 *
 * @see HttpStatusCodes#SERVICE_UNAVAILABLE
 */
@SuppressWarnings("squid:S110")
public class ServiceUnavailableException extends HttpServerException {
  @Serial private static final long serialVersionUID = 1L;

  /** Creates this error using its standard reason phrase. */
  public ServiceUnavailableException() {
    this(null, null);
  }

  /**
   * Creates this error with a diagnostic message.
   *
   * @param message diagnostic message; null selects the status reason phrase
   */
  public ServiceUnavailableException(@Nullable String message) {
    this(message, null);
  }

  /**
   * Creates this error with its standard reason phrase and an underlying failure.
   *
   * @param cause underlying failure, or null
   */
  public ServiceUnavailableException(@Nullable Throwable cause) {
    this(null, cause);
  }

  /**
   * Creates this error with diagnostic information.
   *
   * @param message diagnostic message; null selects the status reason phrase
   * @param cause underlying failure, or null
   */
  public ServiceUnavailableException(@Nullable String message, @Nullable Throwable cause) {
    super(HttpStatusCodes.SERVICE_UNAVAILABLE, message, cause);
  }
}
