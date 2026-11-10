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
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Registration-only typed extension selection and local callbacks. Groups run configuration once;
 * each child receives independently copied provider configuration. Local callbacks run before app
 * callbacks. Exceptions stop ordinary phases; terminal observers remain failure-isolated.
 */
public final class Lifecycle {
  private final Set<Extension> installed;
  private final IdentityHashMap<Extension, Selection<?>> selections;
  private final Consumer<Extension> initialize;
  private final List<Extension> order;
  final List<Handler> matched;
  final List<Handler> before;
  final List<Handler> after;
  final List<Handler> beforeFlush;
  final List<Handler> afterFlush;
  final List<Consumer<RequestOutcome>> observers;
  final List<Function<Request, RequestObservation>> instrumentation;
  final Map<Integer, Handler> statuses;

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
   * @param installed identity-based completed infrastructure registry
   * @param initialize application-owned eager infrastructure setup
   * @param inherited enclosing configuration, or null
   */
  Lifecycle(
      Set<Extension> installed, Consumer<Extension> initialize, @Nullable Lifecycle inherited) {
    this.installed = installed;
    this.initialize = initialize;
    selections = new IdentityHashMap<>();
    order = new ArrayList<>();
    matched = new ArrayList<>();
    before = new ArrayList<>();
    after = new ArrayList<>();
    beforeFlush = new ArrayList<>();
    afterFlush = new ArrayList<>();
    observers = new ArrayList<>();
    instrumentation = new ArrayList<>();
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
   * Selects an exact instance and eagerly establishes its application-owned infrastructure.
   * Repeated selection in this scope returns the same typed configuration. Descendants receive
   * independently copied configuration. Provider contributions run before direct scope callbacks.
   *
   * @param extension capability selected for this scope
   * @param <C> typed provider configuration
   * @return this scope's provider configuration
   * @throws IllegalStateException if configuration has ended or a provider selects another provider
   */
  @SuppressWarnings("unchecked") // An exact instance has one configuration type across its roles.
  public <C> C withExtension(RouteExtension<C> extension) {
    requireSelection();
    Objects.requireNonNull(extension);
    initialize.accept(extension);
    var selection = selections.get(extension);
    if (selection == null) {
      selection = select(extension, extension::configure, null);
    } else if (!selection.included()) {
      selection = selection.included(true);
      selections.put(extension, selection);
    }

    return (C) selection.configuration();
  }

  /**
   * Suppresses the selected instance's contribution in this scope without releasing its resources.
   *
   * @param extension application-owned instance excluded from this scope
   * @return this scope
   * @throws IllegalArgumentException if the instance is not owned by this application
   * @throws IllegalStateException if configuration has ended or this is a provider contribution
   */
  public Lifecycle withoutExtension(Extension extension) {
    requireSelection();
    Objects.requireNonNull(extension);
    if (!installed.contains(extension)) {
      throw new IllegalArgumentException("The extension is not owned by this application");
    }

    var selected = selections.get(extension);
    if (selected != null && selected.included()) {
      selections.put(extension, selected.included(false));
    }

    return this;
  }

  /**
   * Activates one application's typed default contribution before route registration begins.
   *
   * @param extension application capability
   * @param configure typed default customization
   * @param <C> provider configuration
   * @throws IllegalArgumentException if this exact instance already supplies application defaults
   */
  <C> void activate(ApplicationExtension<C> extension, Consumer<? super C> configure) {
    requireSelection();
    Objects.requireNonNull(extension);
    Objects.requireNonNull(configure);
    if (selections.containsKey(extension)) {
      throw new IllegalArgumentException(
          "An extension instance can only be activated once on the application");
    }

    initialize.accept(extension);
    var selected = select(extension, extension::configure, null);

    try {
      configure.accept(selected.configuration());
    } catch (RuntimeException | Error failure) {
      selections.remove(extension);
      removeIdentity(extension);
      throw failure;
    } finally {
      selected.contribution().freeze();
    }
  }

  /**
   * Rejects nested provider selection and retained configuration mutations.
   *
   * @throws IllegalStateException if this collector is a provider contribution
   */
  private void requireSelection() {
    requireMutable();
    if (provider) {
      throw new IllegalStateException(
          "Select extensions in the route/group callback, not inside a provider");
    }
  }

  /**
   * Removes a failed selection without relying on provider equality.
   *
   * @param extension failed instance
   */
  @SuppressWarnings("ReferenceEquality")
  private void removeIdentity(Extension extension) {
    order.removeIf(candidate -> candidate == extension);
  }

  /**
   * Receives actual HTTP method and composed path template before extension beforeStart. Only
   * ordinary HTTP/SSE registrations produce facts; WebSocket handshakes and static mounts do not.
   * Never execute the serving handler here; its own route can safely be described.
   *
   * @param consumer cold registration metadata consumer
   * @return this scope
   */
  public Lifecycle onRoute(BiConsumer<HttpMethods, String> consumer) {
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
  public Lifecycle onRoute(Consumer<RouteInfo> consumer) {
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
    requireMutable();
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
    requireMutable();
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
   * <p>ReferenceEquality is suppressed because authentication selection requires the same instance.
   *
   * @param handler installed authenticator
   * @throws IllegalArgumentException if a different authenticator is already required
   */
  @SuppressWarnings("ReferenceEquality")
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
   * <p>ReferenceEquality is suppressed because selection requires the same installed instance, even
   * when two extensions trust the same origins.
   *
   * @param owner selected protection
   * @throws IllegalArgumentException if a different CSRF owner is already selected
   */
  @SuppressWarnings("ReferenceEquality")
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
   * <p>ReferenceEquality is suppressed because selection requires the same installed instance, even
   * when two extensions have equal policies.
   *
   * @param owner selected sharing policy
   * @param policy resolved immutable permissions
   * @throws IllegalArgumentException if a different CORS owner is already selected
   */
  @SuppressWarnings("ReferenceEquality")
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
        && combined.matched.isEmpty()
        && combined.before.isEmpty()
        && combined.after.isEmpty()
        && combined.beforeFlush.isEmpty()
        && combined.afterFlush.isEmpty()
        && combined.observers.isEmpty()
        && combined.instrumentation.isEmpty()
        && combined.statuses.isEmpty()) {
      return null;
    }

    return new EndpointBehavior(
        combined.authentication,
        combined.csrf,
        combined.corsPolicy,
        combined.matched,
        combined.before,
        combined.after,
        combined.beforeFlush,
        combined.afterFlush,
        combined.observers,
        combined.instrumentation,
        Map.of(),
        combined.statuses,
        conditions);
  }

  /**
   * Copies provider contributions before direct callbacks without closing the source scope.
   *
   * @return independent combined collector
   */
  private Lifecycle combined() {
    var combined = new Lifecycle(installed, initialize, null);
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
   * Regenerates a provider contribution from its independently inherited typed configuration.
   *
   * @param extension inherited instance
   * @param selection inherited data
   * @param <C> configuration type
   */
  private <C> void inherit(Extension extension, Selection<C> selection) {
    var inherited = select(extension, selection.configure(), selection.configuration());
    if (!selection.included()) {
      selections.put(extension, inherited.included(false));
    }
  }

  /**
   * Creates typed provider data while isolating its hooks from direct scope callbacks.
   *
   * @param extension selected exact instance
   * @param configure provider's configuration operation
   * @param inherited parent settings, or null for first selection
   * @param <C> configuration type
   * @return independent settings and contribution
   */
  @SuppressWarnings("java:S1181") // Freeze retained provider collectors after any setup failure.
  private <C> Selection<C> select(
      Extension extension, BiFunction<Lifecycle, @Nullable C, C> configure, @Nullable C inherited) {
    var contribution = new Lifecycle(installed, initialize, null);
    contribution.provider = true;

    try {
      var selection =
          new Selection<>(
              Objects.requireNonNull(configure.apply(contribution, inherited)),
              contribution,
              true,
              configure);
      var owners = new Lifecycle(installed, initialize, null);
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
  private void append(Lifecycle source) {
    reserve(source);
    appendCallbacks(source);
    routeConsumers.addAll(source.routeConsumers);
  }

  /**
   * Preserves selected owners even while their scoped contributions are excluded.
   *
   * @param source configuration whose ownership constraints remain in force
   */
  private void reserve(Lifecycle source) {
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
   * Copies inherited callbacks and nearest status renderers.
   *
   * @param source callbacks to append
   */
  private void appendCallbacks(Lifecycle source) {
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
    requireMutable();
    handlers.add(Objects.requireNonNull(handler));
    return this;
  }

  /**
   * Keeps typed provider settings and their configuration operation together.
   *
   * @param configuration independent settings
   * @param contribution isolated callbacks
   * @param included whether the contribution is active
   * @param configure operation used to regenerate child settings
   * @param <C> configuration type
   */
  private record Selection<C>(
      C configuration,
      Lifecycle contribution,
      boolean included,
      BiFunction<Lifecycle, @Nullable C, C> configure) {
    /**
     * Validates the settings, contribution and operation retained for inheritance.
     *
     * @param configuration independent settings
     * @param contribution isolated callbacks
     * @param included whether the contribution is active
     * @param configure operation used to regenerate child settings
     */
    private Selection {
      Objects.requireNonNull(configuration);
      Objects.requireNonNull(contribution);
      Objects.requireNonNull(configure);
    }

    /**
     * Retains the configuration while changing its inclusion in a descendant scope.
     *
     * @param value whether callbacks should contribute
     * @return selection with the requested inclusion
     */
    private Selection<C> included(boolean value) {
      return new Selection<>(configuration, contribution, value, configure);
    }
  }
}
