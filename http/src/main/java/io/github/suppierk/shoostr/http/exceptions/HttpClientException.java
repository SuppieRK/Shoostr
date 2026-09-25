package io.github.suppierk.shoostr.http.exceptions;

import io.github.suppierk.shoostr.http.HttpStatusCodes;
import java.io.Serial;
import org.jspecify.annotations.Nullable;

/** Base unchecked exception for HTTP client errors (4xx). */
public abstract class HttpClientException extends HttpException {
  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates a client error with diagnostic information.
   *
   * @param statusCode a client error status
   * @param message diagnostic message; null selects the status reason phrase
   * @param cause underlying failure, or null
   * @throws NullPointerException if statusCode is null
   * @throws IllegalArgumentException if statusCode is not a client error
   */
  protected HttpClientException(
      HttpStatusCodes statusCode, @Nullable String message, @Nullable Throwable cause) {
    super(statusCode, message, cause);
    if (!statusCode.isClientError()) {
      throw new IllegalArgumentException("HTTP client exceptions require a client error status");
    }
  }
}
