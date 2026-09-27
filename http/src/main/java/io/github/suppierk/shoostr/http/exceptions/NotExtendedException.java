package io.github.suppierk.shoostr.http.exceptions;

import io.github.suppierk.shoostr.http.HttpStatusCodes;
import java.io.Serial;
import org.jspecify.annotations.Nullable;

/**
 * HTTP 510: Not Extended.
 *
 * <p>S110 is suppressed due to the need to maintain exceptions hierarchy.
 *
 * <p>S1133 is suppressed due to the need to keep this status for any legacy system or another
 * system that might want to use this status, clearly indicating that it should be replaced.
 *
 * @see HttpStatusCodes#NOT_EXTENDED
 * @deprecated This status is obsoleted in the IANA registry.
 */
@SuppressWarnings({"squid:S110", "squid:S1133"})
@Deprecated(since = "0.1.0")
public class NotExtendedException extends HttpServerException {
  @Serial private static final long serialVersionUID = 1L;

  /** Creates this error using its standard reason phrase. */
  public NotExtendedException() {
    this(null, null);
  }

  /**
   * Creates this error with a diagnostic message.
   *
   * @param message diagnostic message; null selects the status reason phrase
   */
  public NotExtendedException(@Nullable String message) {
    this(message, null);
  }

  /**
   * Creates this error with its standard reason phrase and an underlying failure.
   *
   * @param cause underlying failure, or null
   */
  public NotExtendedException(@Nullable Throwable cause) {
    this(null, cause);
  }

  /**
   * Creates this error with diagnostic information.
   *
   * @param message diagnostic message; null selects the status reason phrase
   * @param cause underlying failure, or null
   */
  public NotExtendedException(@Nullable String message, @Nullable Throwable cause) {
    super(HttpStatusCodes.NOT_EXTENDED, message, cause);
  }
}
