package io.github.suppierk.shoostr.example.postgres;

import java.util.Objects;

/**
 * One persisted resource with invariants established at construction.
 *
 * @param id positive database identity
 * @param text nonblank note text
 */
public record Note(long id, String text) {
  /**
   * Rejects an invalid database identity or missing content.
   *
   * @param id positive database identity
   * @param text nonblank note text
   * @throws IllegalArgumentException if the identity is nonpositive or text blank
   * @throws NullPointerException if text is absent
   */
  public Note {
    Objects.requireNonNull(text);
    if (id <= 0 || text.isBlank()) {
      throw new IllegalArgumentException("A note needs a positive identity and nonblank text");
    }
  }
}
