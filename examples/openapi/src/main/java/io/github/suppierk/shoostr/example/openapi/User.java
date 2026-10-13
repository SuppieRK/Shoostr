package io.github.suppierk.shoostr.example.openapi;

import java.util.Objects;

/**
 * Explicit example payload, not an inferred schema or annotation-scanned model.
 *
 * @param name nonblank user name
 */
record User(String name) {
  /**
   * Establishes the payload invariant before the handler receives it.
   *
   * @param name nonblank user name
   * @throws NullPointerException if the name is missing
   * @throws IllegalArgumentException if the name is blank
   */
  public User {
    Objects.requireNonNull(name, "User name is required");
    if (name.isBlank()) {
      throw new IllegalArgumentException("User name is required");
    }
  }
}
