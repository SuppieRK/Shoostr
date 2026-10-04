package io.github.suppierk.shoostr;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
  private boolean closed;

  /** Creates an empty collector for one installation. */
  ApplicationCallbacks() {
    headers = new ArrayList<>();
    matched = new ArrayList<>();
    before = new ArrayList<>();
    after = new ArrayList<>();
    beforeFlush = new ArrayList<>();
    afterFlush = new ArrayList<>();
    completed = new ArrayList<>();
    observations = new ArrayList<>();
  }

  /**
   * Runs before route lookup. Registration order is preserved within this phase.
   *
   * @param handler callback
   * @return this collector
   */
  public synchronized ApplicationCallbacks onRequestHeaders(Handler handler) {
    return add(headers, handler);
  }

  /**
   * Runs after matching a route. Registration order is preserved within this phase.
   *
   * @param handler callback
   * @return this collector
   */
  public synchronized ApplicationCallbacks onRouteMatched(Handler handler) {
    return add(matched, handler);
  }

  /**
   * Runs during admission; throwing prevents endpoint execution. Registration order is preserved
   * within this phase.
   *
   * @param handler callback
   * @return this collector
   */
  public synchronized ApplicationCallbacks beforeRouteHandler(Handler handler) {
    return add(before, handler);
  }

  /**
   * Runs after successful endpoint execution. Registration order is preserved within this phase.
   *
   * @param handler callback
   * @return this collector
   */
  public synchronized ApplicationCallbacks afterRouteHandler(Handler handler) {
    return add(after, handler);
  }

  /**
   * Runs before each response flush. Registration order is preserved within this phase.
   *
   * @param handler callback
   * @return this collector
   */
  public synchronized ApplicationCallbacks beforeResponseFlush(Handler handler) {
    return add(beforeFlush, handler);
  }

  /**
   * Runs after each response flush. Registration order is preserved within this phase.
   *
   * @param handler callback
   * @return this collector
   */
  public synchronized ApplicationCallbacks afterResponseFlush(Handler handler) {
    return add(afterFlush, handler);
  }

  /**
   * Registers a terminal observer. RuntimeException failures are isolated from other observers.
   *
   * @param observer terminal observer
   * @return this collector
   */
  public synchronized ApplicationCallbacks afterRequest(Consumer<RequestOutcome> observer) {
    requireMutable();
    completed.add(Objects.requireNonNull(observer));
    return this;
  }

  /**
   * Registers observation beginning before lookup, including misses and errors. RuntimeException
   * failures retain the application's observation isolation and cleanup rules.
   *
   * @param factory observation factory
   * @return this collector
   */
  public synchronized ApplicationCallbacks observe(Function<Request, RequestObservation> factory) {
    requireMutable();
    observations.add(Objects.requireNonNull(factory));
    return this;
  }

  /** Closes registration under the same monitor used by retained writers. */
  synchronized void freeze() {
    closed = true;
  }

  /**
   * Adds a validated callback while the caller holds this collector's monitor.
   *
   * @param handlers target lifecycle phase
   * @param handler callback
   * @return this collector
   */
  private ApplicationCallbacks add(List<Handler> handlers, Handler handler) {
    requireMutable();
    handlers.add(Objects.requireNonNull(handler));
    return this;
  }

  /**
   * Rejects registration after installation finishes.
   *
   * @throws IllegalStateException if installation has returned or thrown
   */
  private void requireMutable() {
    if (closed) {
      throw new IllegalStateException("Extension installation has finished");
    }
  }
}
