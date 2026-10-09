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

  /**
   * Explicitly attaches retained observation context after a selected-lifecycle submission. Called
   * on the execution thread, or on the original admission thread for error rendering if submission
   * is rejected, after the original invocation scope's {@link #close()} and before terminal {@link
   * #complete(RequestOutcome)}. The framework closes the returned scope on this same attaching
   * thread, including failure paths. Neither attachment nor its cleanup completes the observation.
   * The default propagates no thread-local state; implementations must retain only the context
   * needed for attachment, not live request/response objects.
   *
   * @return scope restoring the attaching thread's previous context on close
   */
  default AutoCloseable attach() {
    return () -> {};
  }

  /**
   * Restores the original invocation thread's context. With a handoff this precedes {@link
   * #attach()}; without a handoff this follows invocation. It does not signify transport completion
   * or release context still needed by attachment or terminal recording.
   */
  @Override
  default void close() {}
}
