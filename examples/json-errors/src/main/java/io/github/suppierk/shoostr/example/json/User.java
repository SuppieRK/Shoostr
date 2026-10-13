package io.github.suppierk.shoostr.example.json;

import java.util.Objects;

/**
 * One explicit payload model, independent of the HTTP listener and JSON library.
 *
 * @param name required nonblank name
 */
public record User(String name) {
  /**
   * Establishes the model invariant immediately.
   *
   * @param name required nonblank name
   * @throws NullPointerException if the name is absent
   * @throws IllegalArgumentException if the name is blank
   */
  public User {
    Objects.requireNonNull(name);
    if (name.isBlank()) {
      throw new IllegalArgumentException("A user name is required");
    }
  }
}
