package io.github.suppierk.shoostr;

import static io.github.suppierk.shoostr.Locks.getUnderLock;
import static io.github.suppierk.shoostr.Locks.runUnderLock;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Registration-only application callbacks supplied to one extension installation. No application,
 * router or infrastructure configuration is exposed. Retained collectors reject registration after
 * installation returns or throws.
 */
public final class ApplicationCallbacks {
  final List<Handler> headers;
  final List<Handler> matched;
  final List<Handler> before;
  final List<Handler> after;
  final List<Handler> beforeFlush;
  final List<Handler> afterFlush;
  final List<Consumer<RequestOutcome>> completed;
  final List<Function<Request, RequestObservation>> observations;

  private final ReentrantLock registrationLock;
  private boolean closed;

  /** Creates an empty collector for one installation. */
  ApplicationCallbacks() {
    this.headers = new ArrayList<>();
    this.matched = new ArrayList<>();
    this.before = new ArrayList<>();
    this.after = new ArrayList<>();
    this.beforeFlush = new ArrayList<>();
    this.afterFlush = new ArrayList<>();
    this.completed = new ArrayList<>();
    this.observations = new ArrayList<>();

    this.registrationLock = new ReentrantLock();
  }

  /**
   * Runs before matched-route processing. Registration order is preserved within this phase.
   *
   * @param handler callback
   * @return this collector
   */
  public ApplicationCallbacks onRequestHeaders(Handler handler) {
    return add(headers, handler);
  }

  /**
   * Runs after matching a route. Registration order is preserved within this phase.
   *
   * @param handler callback
   * @return this collector
   */
  public ApplicationCallbacks onRouteMatched(Handler handler) {
    return add(matched, handler);
  }

  /**
   * Runs during admission; throwing prevents endpoint execution. Registration order is preserved
   * within this phase.
   *
   * @param handler callback
   * @return this collector
   */
  public ApplicationCallbacks beforeRouteHandler(Handler handler) {
    return add(before, handler);
  }

  /**
   * Runs after successful endpoint execution. Registration order is preserved within this phase.
   *
   * @param handler callback
   * @return this collector
   */
  public ApplicationCallbacks afterRouteHandler(Handler handler) {
    return add(after, handler);
  }

  /**
   * Runs before each response flush. Registration order is preserved within this phase.
   *
   * @param handler callback
   * @return this collector
   */
  public ApplicationCallbacks beforeResponseFlush(Handler handler) {
    return add(beforeFlush, handler);
  }

  /**
   * Runs after each response flush. Registration order is preserved within this phase.
   *
   * @param handler callback
   * @return this collector
   */
  public ApplicationCallbacks afterResponseFlush(Handler handler) {
    return add(afterFlush, handler);
  }

  /**
   * Registers a terminal observer. RuntimeException failures are isolated from other observers.
   *
   * @param observer terminal observer
   * @return this collector
   */
  public ApplicationCallbacks afterRequest(Consumer<RequestOutcome> observer) {
    return add(completed, observer);
  }

  /**
   * Registers observation beginning before matched-route processing, including misses and errors.
   * RuntimeException failures retain the application's observation isolation and cleanup rules.
   *
   * @param factory observation factory
   * @return this collector
   */
  public ApplicationCallbacks observe(Function<Request, RequestObservation> factory) {
    return add(observations, factory);
  }

  /** Closes registration under the same lock used by retained writers. */
  void freeze() {
    runUnderLock(
        registrationLock,
        () -> {
          closed = true;
        });
  }

  /**
   * Adds a validated callback while holding this collector's registration lock.
   *
   * @param <T> callback type
   * @param callbacks target lifecycle phase
   * @param callback callback to register
   * @return this collector
   * @throws IllegalStateException if installation has finished already
   */
  private <T> ApplicationCallbacks add(List<T> callbacks, T callback) {
    return Objects.requireNonNull(
        getUnderLock(
            registrationLock,
            () -> {
              if (closed) {
                throw new IllegalStateException("Extension installation has finished");
              }

              callbacks.add(Objects.requireNonNull(callback));
              return this;
            }));
  }
}
