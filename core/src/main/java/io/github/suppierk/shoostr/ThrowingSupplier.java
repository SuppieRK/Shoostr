package io.github.suppierk.shoostr;

import org.jspecify.annotations.Nullable;

/**
 * Operation that propagates its declared exception type.
 *
 * @param <T> result type of {@code get}, which may be nullable
 * @param <E> operation's exception type
 */
@FunctionalInterface
interface ThrowingSupplier<T extends @Nullable Object, E extends Exception> {
  /**
   * Gets the operation's result.
   *
   * @return the result, which may be null
   * @throws E if the operation fails
   */
  @Nullable T get() throws E;
}
