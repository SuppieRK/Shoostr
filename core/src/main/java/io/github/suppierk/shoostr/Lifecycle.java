package io.github.suppierk.shoostr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Registration-only request lifecycle for a route or group. Each scope owns its collector;
 * inherited callbacks are copied and run before application callbacks. Retained collectors reject
 * changes after their registration callback returns.
 */
public final class Lifecycle {
  private final Extensions owner;
  final List<Handler> matched;
  final List<Handler> before;
  final List<Handler> after;
  final List<Handler> beforeFlush;
  final List<Handler> afterFlush;
  final List<Consumer<RequestOutcome>> observers;
  final List<Function<Request, RequestObservation>> instrumentation;
  final Map<Integer, Handler> statuses;

  /**
   * Creates independently owned callback state.
   *
   * @param owner scope controlling mutation
   */
  Lifecycle(Extensions owner) {
    this.owner = Objects.requireNonNull(owner);
    matched = new ArrayList<>();
    before = new ArrayList<>();
    after = new ArrayList<>();
    beforeFlush = new ArrayList<>();
    afterFlush = new ArrayList<>();
    observers = new ArrayList<>();
    instrumentation = new ArrayList<>();
    statuses = new LinkedHashMap<>();
  }

  /**
   * Adds a local match hook before app match hooks and known-length validation.
   *
   * @param handler local callback
   * @return this scope
   */
  public Lifecycle onRouteMatched(Handler handler) {
    return add(matched, handler);
  }

  /**
   * Adds an admission callback after managed authentication and before app gates. Selected
   * extension contributions run in inherited/first-selection order, followed by direct local
   * callbacks. Throw to reject; response streaming is unavailable during admission.
   *
   * @param handler local gate
   * @return this scope
   */
  public Lifecycle beforeRouteHandler(Handler handler) {
    return add(before, handler);
  }

  /**
   * Adds a local callback after successful endpoint return, before app callbacks.
   *
   * @param handler local callback
   * @return this scope
   */
  public Lifecycle afterRouteHandler(Handler handler) {
    return add(after, handler);
  }

  /**
   * Adds a local callback before each framework flush, before app callbacks.
   *
   * @param handler local callback
   * @return this scope
   */
  public Lifecycle beforeResponseFlush(Handler handler) {
    return add(beforeFlush, handler);
  }

  /**
   * Adds a local read-only callback after each flush, before app callbacks.
   *
   * @param handler local callback
   * @return this scope
   */
  public Lifecycle afterResponseFlush(Handler handler) {
    return add(afterFlush, handler);
  }

  /**
   * Adds a local terminal observer before app observers; RuntimeException does not suppress others.
   *
   * @param observer read-only terminal callback
   * @return this scope
   */
  public Lifecycle afterRequest(Consumer<RequestOutcome> observer) {
    owner.requireMutable();
    observers.add(Objects.requireNonNull(observer));
    return this;
  }

  /**
   * Adds matched-request instrumentation before executor handoff and local match hooks. The same
   * factory instance contributes once per endpoint, including inherited selections. Scopes follow
   * the application instrumentation's handoff, cleanup and failure-isolation contract. Unmatched
   * requests and pre-routing short circuits do not invoke local factories.
   *
   * <p>ReferenceEquality is suppressed because only the same factory instance is deduplicated.
   *
   * @param factory creates one observation without retaining live input
   * @return this scope
   */
  @SuppressWarnings("ReferenceEquality")
  public Lifecycle observe(Function<Request, RequestObservation> factory) {
    owner.requireMutable();
    Objects.requireNonNull(factory);
    if (instrumentation.stream().noneMatch(existing -> existing == factory)) {
      instrumentation.add(factory);
    }

    return this;
  }

  /**
   * Adds a generated-status renderer; a local match suppresses the app renderer.
   *
   * @param status HTTP status from 100 through 599
   * @param handler renderer
   * @return this scope
   * @throws IllegalArgumentException if status is outside the HTTP range
   */
  public Lifecycle status(int status, Handler handler) {
    owner.requireMutable();
    if (status < 100 || status > 599) {
      throw new IllegalArgumentException("Invalid HTTP status");
    }

    statuses.put(status, Objects.requireNonNull(handler));
    return this;
  }

  /**
   * Copies inherited callbacks and nearest status renderers.
   *
   * @param source callbacks to append
   */
  void append(Lifecycle source) {
    matched.addAll(source.matched);
    before.addAll(source.before);
    after.addAll(source.after);
    beforeFlush.addAll(source.beforeFlush);
    afterFlush.addAll(source.afterFlush);
    observers.addAll(source.observers);
    source.instrumentation.forEach(this::observe);
    statuses.putAll(source.statuses);
  }

  /**
   * Adds a non-null callback during registration.
   *
   * @param handlers target phase
   * @param handler callback
   * @return this scope
   */
  private Lifecycle add(List<Handler> handlers, Handler handler) {
    owner.requireMutable();
    handlers.add(Objects.requireNonNull(handler));
    return this;
  }
}
