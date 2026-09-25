package io.github.suppierk.shoostr.http.exceptions;

import io.github.suppierk.shoostr.http.HttpStatusCodes;
import java.io.Serial;
import org.jspecify.annotations.Nullable;

/** Base unchecked exception for HTTP server errors (5xx). */
public abstract class HttpServerException extends HttpException {
  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates a server error with diagnostic information.
   *
   * @param statusCode a server error status
   * @param message diagnostic message; null selects the status reason phrase
   * @param cause underlying failure, or null
   * @throws NullPointerException if statusCode is null
   * @throws IllegalArgumentException if statusCode is not a server error
   */
  protected HttpServerException(
      HttpStatusCodes statusCode, @Nullable String message, @Nullable Throwable cause) {
    if (!statusCode.isServerError()) {
      throw new IllegalArgumentException("HTTP server exceptions require a server error status");
    }

    super(statusCode, message, cause);
  }
}
