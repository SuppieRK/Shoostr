package io.github.suppierk.shoostr.http.exceptions;

import java.io.Serial;
import java.util.Objects;

/** HTTP 401 with an application-configured WWW-Authenticate challenge. */
public final class AuthenticationRequiredException extends UnauthorizedException {
  @Serial private static final long serialVersionUID = 1L;

  /** Challenge emitted in the HTTP {@code WWW-Authenticate} field. */
  private final String challenge;

  /**
   * Requests authentication using a configured HTTP authentication challenge.
   *
   * @param challenge complete WWW-Authenticate field value, such as Bearer or Basic realm="api"
   * @throws IllegalArgumentException if the challenge is empty or contains unsafe field characters
   */
  public AuthenticationRequiredException(String challenge) {
    var value = Objects.requireNonNull(challenge);
    if (value.isBlank()
        || value.chars().anyMatch(character -> character < 0x20 || character > 0x7e)) {
      throw new IllegalArgumentException("Invalid authentication challenge");
    }

    this.challenge = value;
  }

  /**
   * Returns the challenge required when this failure is rendered as HTTP 401.
   *
   * @return application-configured field value, never credentials
   */
  public String challenge() {
    return challenge;
  }
}
