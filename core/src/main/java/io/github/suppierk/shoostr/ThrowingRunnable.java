package io.github.suppierk.shoostr;

/**
 * Operation that propagates its declared exception type.
 *
 * @param <E> operation's exception type
 */
@FunctionalInterface
interface ThrowingRunnable<E extends Exception> {
  /**
   * Runs the operation.
   *
   * @throws E if the operation fails
   */
  void run() throws E;
}
