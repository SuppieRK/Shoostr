package io.github.suppierk.shoostr;

/**
 * Per-request instrumentation. The framework closes the invocation scope on its owning thread, then
 * reports completion once application cleanup and transport termination have both finished.
 * Implementations must not retain live request/response objects or block completion threads.
 */
@FunctionalInterface
public interface RequestObservation extends AutoCloseable {
  /**
   * Records the terminal result, potentially on a transport thread, after {@link #close()}.
   *
   * @param outcome immutable terminal summary
   */
  void complete(RequestOutcome outcome);

  /** Restores invocation-local context; this does not signify transport completion. */
  @Override
  default void close() {}
}
