package io.github.suppierk.shoostr;

import io.github.suppierk.shoostr.http.HttpMethods;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Registration-only typed extension selection and local callbacks. Groups run configuration once;
 * each child receives independently copied provider configuration. Local callbacks run before app
 * callbacks. Exceptions stop ordinary phases; terminal observers remain failure-isolated.
 */
public final class Extensions {
  private final Set<Extension<?>> installed;
  private final IdentityHashMap<Extension<?>, Selection> selections;
  private final List<Extension<?>> order;
  private final List<Handler> matched;
  private final List<Handler> before;
  private final List<Handler> after;
  private final List<Handler> beforeFlush;
  private final List<Handler> afterFlush;
  private final List<Consumer<RequestOutcome>> observers;
  private final Map<Class<? extends Exception>, ExceptionHandler<Exception>> errors;
  private final Map<Integer, Handler> statuses;
  private final List<BiConsumer<HttpMethods, String>> routeConsumers;
  private @Nullable Handler authentication;
  private boolean closed;
  private boolean provider;

  /**
   * Creates one scope, copying direct hooks and regenerating inherited provider contributions.
   *
   * @param installed identity-based app registry, guarded by Routes' lock
   * @param inherited enclosing configuration, or null
   */
  Extensions(Set<Extension<?>> installed, @Nullable Extensions inherited) {
    this.installed = installed;
    selections = new IdentityHashMap<>();
    order = new ArrayList<>();
    matched = new ArrayList<>();
    before = new ArrayList<>();
    after = new ArrayList<>();
    beforeFlush = new ArrayList<>();
    afterFlush = new ArrayList<>();
    observers = new ArrayList<>();
    errors = new LinkedHashMap<>();
    statuses = new LinkedHashMap<>();
    routeConsumers = new ArrayList<>();
    if (inherited != null) {
      append(inherited);
      for (var extension : inherited.order) {
        inherit(extension, Objects.requireNonNull(inherited.selections.get(extension)));
      }
    }
  }

  /**
   * Selects the exact installed instance; repeated selection returns the same local configurator.
   *
   * @param extension installed capability
   * @param <C> typed local configuration
   * @return this scope's configuration for the instance
   * @throws IllegalArgumentException if the instance is not installed
   * @throws IllegalStateException if this registration callback has finished or a provider attempts
   *     nested selection
   */
  @SuppressWarnings("unchecked") // Identity-keyed entries are created by this exact Extension<C>.
  public <C> C get(Extension<C> extension) {
    requireMutable();
    if (provider) {
      throw new IllegalStateException(
          "Select extensions in the route/group callback, not inside a provider");
    }

    Objects.requireNonNull(extension);
    if (!installed.contains(extension)) {
      throw new IllegalArgumentException("Install the extension before selecting it on a route");
    }

    var selection = selections.get(extension);
    if (selection == null) {
      selection = select(extension, null);
    }

    return (C) selection.configuration();
  }

  /**
   * Receives actual HTTP method and composed path template before extension beforeStart. Only
   * ordinary HTTP/SSE registrations produce facts; WebSocket handshakes and static mounts do not.
   * Never execute the serving handler here; its own route can safely be described.
   *
   * @param consumer cold registration metadata consumer
   * @return this scope
   */
  public Extensions onRoute(BiConsumer<HttpMethods, String> consumer) {
    requireMutable();
    routeConsumers.add(Objects.requireNonNull(consumer));
    return this;
  }

  /**
   * Adds a local match hook before app match hooks and known-length validation.
   *
   * @param handler local callback
   * @return this scope
   */
  public Extensions onRouteMatched(Handler handler) {
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
  public Extensions beforeRouteHandler(Handler handler) {
    return add(before, handler);
  }

  /**
   * Adds a local callback after successful endpoint return, before app callbacks.
   *
   * @param handler local callback
   * @return this scope
   */
  public Extensions afterRouteHandler(Handler handler) {
    return add(after, handler);
  }

  /**
   * Adds a local callback before each framework flush, before app callbacks.
   *
   * @param handler local callback
   * @return this scope
   */
  public Extensions beforeResponseFlush(Handler handler) {
    return add(beforeFlush, handler);
  }

  /**
   * Adds a local read-only callback after each flush, before app callbacks.
   *
   * @param handler local callback
   * @return this scope
   */
  public Extensions afterResponseFlush(Handler handler) {
    return add(afterFlush, handler);
  }

  /**
   * Adds a local terminal observer before app observers; RuntimeException does not suppress others.
   *
   * @param observer read-only terminal callback
   * @return this scope
   */
  public Extensions afterRequest(Consumer<RequestOutcome> observer) {
    requireMutable();
    observers.add(Objects.requireNonNull(observer));
    return this;
  }

  /**
   * Adds a local exception renderer. A matching local renderer wins over every app renderer; the
   * closest local superclass wins. A child scope may override an inherited exact type.
   *
   * @param type directly thrown exception family
   * @param handler error renderer
   * @param <E> exception type
   * @return this scope
   */
  public <E extends Exception> Extensions exception(
      Class<E> type, ExceptionHandler<? super E> handler) {
    requireMutable();
    Objects.requireNonNull(type);
    Objects.requireNonNull(handler);
    errors.put(
        type,
        (failure, request, response) -> handler.handle(type.cast(failure), request, response));
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
  public Extensions status(int status, Handler handler) {
    requireMutable();
    if (status < 100 || status > 599) {
      throw new IllegalArgumentException("Invalid HTTP status");
    }

    statuses.put(status, Objects.requireNonNull(handler));
    return this;
  }

  /**
   * Marks managed authentication on this scope; repeated selection does not duplicate work.
   *
   * @param handler installed authenticator
   */
  void authentication(Handler handler) {
    requireMutable();
    authentication = handler;
  }

  /**
   * Snapshots runtime behavior and queues cold facts, then closes all registration surfaces.
   *
   * @param conditions inherited runtime predicates
   * @param method actual HTTP method, or null for non-HTTP registrations
   * @param pathTemplate composed template
   * @param notifications metadata work run before listener startup
   * @return immutable runtime data, or null when no behavior is selected
   */
  @Nullable EndpointBehavior bind(
      List<Predicate<Request>> conditions,
      @Nullable HttpMethods method,
      String pathTemplate,
      List<Runnable> notifications) {
    var combined = new Extensions(installed, null);
    for (var extension : order) {
      var contribution = Objects.requireNonNull(selections.get(extension)).contribution();
      combined.append(contribution);
      contribution.closed = true;
    }
    combined.append(this);
    closed = true;
    if (method != null) {
      for (var consumer : combined.routeConsumers) {
        notifications.add(() -> consumer.accept(method, pathTemplate));
      }
    }

    if (combined.authentication == null
        && conditions.isEmpty()
        && combined.matched.isEmpty()
        && combined.before.isEmpty()
        && combined.after.isEmpty()
        && combined.beforeFlush.isEmpty()
        && combined.afterFlush.isEmpty()
        && combined.observers.isEmpty()
        && combined.errors.isEmpty()
        && combined.statuses.isEmpty()) {
      return null;
    }

    return new EndpointBehavior(
        combined.authentication,
        combined.matched,
        combined.before,
        combined.after,
        combined.beforeFlush,
        combined.afterFlush,
        combined.observers,
        combined.errors,
        combined.statuses,
        conditions);
  }

  /** Closes registration surfaces retained by a group callback. */
  void freeze() {
    closed = true;
    for (var selection : selections.values()) {
      selection.contribution().closed = true;
    }
  }

  /**
   * Copies a typed inherited provider without unchecked casts at the caller.
   *
   * @param extension inherited provider
   * @param selection inherited data
   * @param <C> provider configuration
   */
  @SuppressWarnings("unchecked") // The identity-keyed selection came from this Extension<C>.
  private <C> void inherit(Extension<C> extension, Selection selection) {
    select(extension, (C) selection.configuration());
  }

  /**
   * Creates fresh provider data and records stable selection order.
   *
   * @param extension selected provider
   * @param inherited parent data, or null
   * @param <C> configuration type
   * @return provider data and its own contributions
   */
  private <C> Selection select(Extension<C> extension, @Nullable C inherited) {
    var contribution = new Extensions(installed, null);
    contribution.provider = true;
    var selection =
        new Selection(
            Objects.requireNonNull(extension.configure(contribution, inherited)), contribution);
    selections.put(extension, selection);
    order.add(extension);
    return selection;
  }

  /**
   * Appends callbacks while preserving scope order and nearest renderer overrides.
   *
   * @param source contribution to copy
   */
  private void append(Extensions source) {
    if (source.authentication != null) {
      authentication = source.authentication;
    }

    matched.addAll(source.matched);
    before.addAll(source.before);
    after.addAll(source.after);
    beforeFlush.addAll(source.beforeFlush);
    afterFlush.addAll(source.afterFlush);
    observers.addAll(source.observers);
    errors.putAll(source.errors);
    statuses.putAll(source.statuses);
    routeConsumers.addAll(source.routeConsumers);
  }

  /**
   * Adds a non-null callback during registration.
   *
   * @param handlers target phase
   * @param handler callback
   * @return this scope
   */
  private Extensions add(List<Handler> handlers, Handler handler) {
    requireMutable();
    handlers.add(Objects.requireNonNull(handler));
    return this;
  }

  /**
   * Rejects mutation after the configuration callback has returned.
   *
   * @throws IllegalStateException if configuration has finished
   */
  void requireMutable() {
    if (closed) {
      throw new IllegalStateException("Endpoint configuration has finished");
    }
  }

  /**
   * Keeps provider state separate from direct user callbacks.
   *
   * @param configuration independently copied typed facade
   * @param contribution this provider's callbacks
   */
  private record Selection(Object configuration, Extensions contribution) {
    /**
     * Validates both parts before publishing the selection.
     *
     * @param configuration independent provider data
     * @param contribution local callbacks
     */
    private Selection {
      Objects.requireNonNull(configuration);
      Objects.requireNonNull(contribution);
    }
  }
}
