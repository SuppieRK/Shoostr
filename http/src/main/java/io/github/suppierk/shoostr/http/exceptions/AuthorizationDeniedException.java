package io.github.suppierk.shoostr.http.exceptions;

import java.io.Serial;
import java.util.Objects;

/**
 * HTTP 403 with a required WWW-Authenticate challenge, independent of the authentication provider.
 */
@SuppressWarnings("java:S110") // Preserve the established typed HTTP exception hierarchy.
public final class AuthorizationDeniedException extends ForbiddenException {
  @Serial private static final long serialVersionUID = 1L;

  private final String challenge;

  /**
   * Rejects authorization with a complete HTTP authentication challenge.
   *
   * @param challenge complete WWW-Authenticate field value, never credentials
   * @throws NullPointerException if the challenge is null
   * @throws IllegalArgumentException if the challenge is blank or contains unsafe field characters
   */
  public AuthorizationDeniedException(String challenge) {
    var value = Objects.requireNonNull(challenge);
    if (value.isBlank()
        || value.chars().anyMatch(character -> character < 0x20 || character > 0x7e)) {
      throw new IllegalArgumentException("Invalid authorization challenge");
    }

    this.challenge = value;
  }

  /**
   * Returns the challenge required when this failure is rendered as HTTP 403.
   *
   * @return complete WWW-Authenticate field value
   */
  public String challenge() {
    return challenge;
  }
}
