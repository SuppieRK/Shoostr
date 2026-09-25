package io.github.suppierk.shoostr.http.exceptions;

import io.github.suppierk.shoostr.http.HttpStatusCodes;
import java.io.Serial;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Base unchecked exception for HTTP client and server errors.
 *
 * <p>The message and cause are diagnostic information, not an HTTP response body. Response
 * rendering must explicitly choose what to expose. Normal Java stack traces and suppressed
 * exceptions are retained.
 */
public abstract class HttpException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  /** The client or server error status retained during Java serialization. */
  private final HttpStatusCodes statusCode;

  /**
   * Creates an HTTP error with diagnostic information.
   *
   * @param statusCode a client or server error status
   * @param message diagnostic message; null selects the status reason phrase
   * @param cause underlying failure, or null
   * @throws NullPointerException if statusCode is null
   * @throws IllegalArgumentException if statusCode is not a client or server error
   */
  protected HttpException(
      HttpStatusCodes statusCode, @Nullable String message, @Nullable Throwable cause) {
    super(
        message == null ? Objects.requireNonNull(statusCode, "statusCode").reasonPhrase() : message,
        cause);
    Objects.requireNonNull(statusCode, "statusCode");
    if (!statusCode.isError()) {
      throw new IllegalArgumentException("HTTP exceptions require a client or server error status");
    }

    this.statusCode = statusCode;
  }

  /**
   * Returns the HTTP error represented by this exception.
   *
   * @return the fixed client or server error status
   */
  public final HttpStatusCodes statusCode() {
    return statusCode;
  }
}
