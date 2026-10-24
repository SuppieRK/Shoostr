package io.github.suppierk.shoostr;

import io.github.suppierk.shoostr.http.HttpMethods;
import java.util.ArrayList;
import java.util.IdentityHashMap;
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
  private final Lifecycle lifecycle;
  private final List<Consumer<RouteInfo>> routeConsumers;
  private @Nullable Handler authentication;
  private @Nullable Csrf csrf;
  private @Nullable Cors cors;
  private @Nullable CorsPolicy corsPolicy;
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
    lifecycle = new Lifecycle(this);
    routeConsumers = new ArrayList<>();
    if (inherited != null) {
      append(inherited);
      for (var extension : inherited.order) {
        inherit(extension, Objects.requireNonNull(inherited.selections.get(extension)));
      }
    }
  }

  /**
   * Verifies that this application's registry contains the exact extension instance. This check
   * neither selects route configuration nor contributes request-time behavior. Installation in this
   * app does not establish exclusive ownership of the extension.
   *
   * @param extension installed capability
   * @return this scope
   * @throws NullPointerException if the extension is null
   * @throws IllegalArgumentException if the instance is not installed in this application
   * @throws IllegalStateException if this registration callback has finished
   */
  public Extensions requireInstalled(Extension<?> extension) {
    requireMutable();
    Objects.requireNonNull(extension);
    if (!installed.contains(extension)) {
      throw new IllegalArgumentException("Install the extension before using it on a route");
    }

    return this;
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
  public <C> C with(Extension<C> extension) {
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
    } else if (!selection.included()) {
      selection = new Selection(selection.configuration(), selection.contribution(), true);
      selections.put(extension, selection);
    }

    return (C) selection.configuration();
  }

  /**
   * Excludes an installed instance's scoped contributions without disposing its resources.
   *
   * @param extension installed extension instance
   * @return this scope
   * @throws IllegalArgumentException if the instance is not installed
   * @throws IllegalStateException if the scope is frozen or a provider attempts nested selection
   */
  public Extensions without(Extension<?> extension) {
    requireMutable();
    if (provider) {
      throw new IllegalStateException(
          "Select extensions in the route/group callback, not inside a provider");
    }

    requireInstalled(extension);
    var selected = selections.get(extension);
    if (selected != null && selected.included()) {
      selections.put(
          extension, new Selection(selected.configuration(), selected.contribution(), false));
    }

    return this;
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
    Objects.requireNonNull(consumer);
    return onRoute(info -> consumer.accept(info.method(), info.pathTemplate()));
  }

  /**
   * Receives final HTTP/SSE registration facts, including inherited availability, before startup.
   * Conditions are reported, never evaluated. This hook adds no request-time behavior. WebSocket
   * handshakes and static mounts do not produce HTTP facts.
   *
   * @param consumer cold registration metadata consumer
   * @return this scope
   */
  public Extensions onRoute(Consumer<RouteInfo> consumer) {
    requireMutable();
    routeConsumers.add(Objects.requireNonNull(consumer));
    return this;
  }

  /**
   * Returns this scope's built-in request lifecycle configuration.
   *
   * @return the independently owned registration collector
   */
  public Lifecycle lifecycle() {
    requireMutable();
    return lifecycle;
  }

  /**
   * Marks managed authentication on this scope; repeated selection does not duplicate work.
   *
   * @param handler installed authenticator
   * @throws IllegalArgumentException if a different authenticator is already required
   */
  void authentication(Handler handler) {
    requireMutable();
    if (authentication != null && authentication != handler) {
      throw new IllegalArgumentException("An endpoint may require only one distinct authenticator");
    }

    authentication = handler;
  }

  /**
   * Selects the immutable CSRF owner.
   *
   * @param owner selected protection
   * @throws IllegalArgumentException if a different CSRF owner is already selected
   */
  void csrf(Csrf owner) {
    requireMutable();
    if (csrf != null && csrf != owner) {
      throw new IllegalArgumentException("An endpoint may select only one Csrf instance");
    }

    csrf = owner;
  }

  /**
   * Selects the immutable CORS owner.
   *
   * @param owner selected sharing policy
   * @param policy resolved immutable permissions
   * @throws IllegalArgumentException if a different CORS owner is already selected
   */
  void cors(Cors owner, CorsPolicy policy) {
    requireMutable();
    if (cors != null && cors != owner) {
      throw new IllegalArgumentException("An endpoint may select only one Cors instance");
    }

    cors = owner;
    corsPolicy = Objects.requireNonNull(policy);
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
    var combined = combined();
    freeze();
    if (method != null && !combined.routeConsumers.isEmpty()) {
      var info = new RouteInfo(method, pathTemplate, !conditions.isEmpty());
      for (var consumer : combined.routeConsumers) {
        notifications.add(() -> consumer.accept(info));
      }
    }

    if (combined.cors == null
        && combined.csrf == null
        && combined.authentication == null
        && conditions.isEmpty()
        && combined.lifecycle.matched.isEmpty()
        && combined.lifecycle.before.isEmpty()
        && combined.lifecycle.after.isEmpty()
        && combined.lifecycle.beforeFlush.isEmpty()
        && combined.lifecycle.afterFlush.isEmpty()
        && combined.lifecycle.observers.isEmpty()
        && combined.lifecycle.instrumentation.isEmpty()
        && combined.lifecycle.statuses.isEmpty()) {
      return null;
    }

    return new EndpointBehavior(
        combined.authentication,
        combined.csrf,
        combined.corsPolicy,
        combined.lifecycle.matched,
        combined.lifecycle.before,
        combined.lifecycle.after,
        combined.lifecycle.beforeFlush,
        combined.lifecycle.afterFlush,
        combined.lifecycle.observers,
        combined.lifecycle.instrumentation,
        Map.of(),
        combined.lifecycle.statuses,
        conditions);
  }

  /**
   * Copies provider contributions before direct callbacks without closing the source scope.
   *
   * @return independent combined collector
   */
  private Extensions combined() {
    var combined = new Extensions(installed, null);
    for (var extension : order) {
      var selected = Objects.requireNonNull(selections.get(extension));
      if (selected.included()) {
        combined.append(selected.contribution());
      }
    }
    combined.append(this);
    return combined;
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
    var inherited = select(extension, (C) selection.configuration());
    if (!selection.included()) {
      selections.put(
          extension, new Selection(inherited.configuration(), inherited.contribution(), false));
    }
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

    try {
      var selection =
          new Selection(
              Objects.requireNonNull(extension.configure(contribution, inherited)),
              contribution,
              true);
      var owners = new Extensions(installed, null);
      owners.reserve(this);
      for (var previous : selections.values()) {
        owners.reserve(previous.contribution());
      }
      owners.reserve(contribution);
      selections.put(extension, selection);
      order.add(extension);
      return selection;
    } catch (RuntimeException | Error failure) {
      contribution.freeze();
      throw failure;
    }
  }

  /**
   * Appends callbacks while preserving scope order and nearest renderer overrides.
   *
   * @param source contribution to copy
   */
  private void append(Extensions source) {
    reserve(source);
    lifecycle.append(source.lifecycle);
    routeConsumers.addAll(source.routeConsumers);
  }

  /**
   * Preserves selected owners even while their scoped contributions are excluded.
   *
   * @param source configuration whose ownership constraints remain in force
   */
  private void reserve(Extensions source) {
    if (source.authentication != null) {
      authentication(source.authentication);
    }

    if (source.csrf != null) {
      csrf(source.csrf);
    }

    if (source.cors != null) {
      cors(source.cors, Objects.requireNonNull(source.corsPolicy));
    }
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
   * @param included whether this scope applies the contribution
   */
  private record Selection(Object configuration, Extensions contribution, boolean included) {
    /**
     * Validates required values before publishing the selection.
     *
     * @param configuration independent provider data
     * @param contribution local callbacks
     * @param included whether this scope applies the contribution
     */
    private Selection {
      Objects.requireNonNull(configuration);
      Objects.requireNonNull(contribution);
    }
  }
}
