package io.github.suppierk.shoostr;

import static io.github.suppierk.shoostr.Locks.getUnderLock;
import static io.github.suppierk.shoostr.Locks.runUnderLock;

import io.github.suppierk.shoostr.http.HttpCharacters;
import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.http.HttpStatusCodes;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.server.ServerUpgradeResponse;
import org.jspecify.annotations.Nullable;

/**
 * Thread-safe route registration with shared path scopes closed when the app starts. Registration
 * and configuration callbacks run synchronously on the calling thread under one shared lock.
 * Concurrent registration operations are serialized, including their callbacks. Callbacks must not
 * wait for another thread to register routes or close the same application. Literal segments take
 * matching precedence. Registration resources belong to the application; scopes have no separate
 * cleanup operation. Endpoint overloads accept caller-owned executors for the matched lifecycle.
 * Executors must run accepted tasks asynchronously or throw on rejection, never silently discard or
 * run on the caller. Registration rejects shutdown executors and inspectable thread pools using the
 * standard caller-runs, discard or discard-oldest policy classes. Custom policies (including
 * subclasses) and executor wrappers must honor the same contract. Shoostr never shuts these
 * executors down or interrupts their running workers. The caller must keep them usable until the
 * app has closed and its running handlers have finished. Default registrations use the
 * application's native execution mode: platform threads by default, with {@link
 * Options#setVirtualThreads(Boolean)} for virtual execution. Request observation context is
 * explicitly attached; arbitrary thread-local state and application-created child tasks are not
 * propagated.
 */
public final class Routes {
  static final String EMPTY_PATH = "";

  private static final String UNRECOGNIZED_HTTP_METHOD = "Unrecognized HTTP method: ";

  private final Routes root;
  private final Consumer<Extension> initialize;
  private final @Nullable Routes parent;
  private final ReentrantLock lock;
  private final String prefix;
  private final int scopeDepth;
  private final List<Predicate<Request>> conditions;
  private final Set<Extension> installed;
  private final List<Runnable> notifications;
  private final List<RadixRoutes.Endpoint> registrations;
  private final List<RadixRoutes.Endpoint> websocketRegistrations;
  private final List<StaticFiles> staticFiles;
  private final Map<Class<? extends Exception>, ExceptionHandler<Exception>> errors;
  private final IdentityHashMap<RadixRoutes.Endpoint, Routes> endpointScopes;
  private final IdentityHashMap<StaticFiles, Routes> staticScopes;
  private final Set<String> routeKeys;
  private final Set<String> websocketRouteKeys;

  private @Nullable Lifecycle configuration;
  private boolean localObservers;
  private @Nullable RadixRoutes websocketRouter;
  private int activeGroupDepth;
  private boolean registrationStarted;
  private boolean configuringDefaults;
  private boolean closed;

  /**
   * Creates the root scope with application-owned extension setup and shared registration state.
   *
   * @param initialize eagerly establishes an exact extension instance's infrastructure
   */
  Routes(Consumer<Extension> initialize) {
    this.initialize = Objects.requireNonNull(initialize);
    this.root = this;
    this.parent = null;
    this.lock = new ReentrantLock();
    this.prefix = EMPTY_PATH;
    this.scopeDepth = 0;
    this.conditions = List.of();
    this.configuration = null;
    this.installed = Collections.newSetFromMap(new IdentityHashMap<>());
    this.notifications = new ArrayList<>();
    this.registrations = new ArrayList<>();
    this.websocketRegistrations = new ArrayList<>();
    this.staticFiles = new ArrayList<>();
    this.errors = new LinkedHashMap<>();
    this.endpointScopes = new IdentityHashMap<>();
    this.staticScopes = new IdentityHashMap<>();
    this.routeKeys = new HashSet<>();
    this.websocketRouteKeys = new HashSet<>();
  }

  /**
   * Creates a path view over the root's registration state, without copying its entries.
   *
   * @param parent enclosing registration scope
   * @param prefix composed prefix without its optional trailing separator
   * @param conditions immutable runtime availability checks
   * @param configuration inherited extension configuration, or null
   * @param scopeDepth effective path/when ancestry, from one to ten
   */
  private Routes(
      Routes parent,
      String prefix,
      List<Predicate<Request>> conditions,
      @Nullable Lifecycle configuration,
      int scopeDepth) {
    this.parent = parent;
    this.initialize = parent.initialize;
    this.root = parent.root;
    this.lock = root.lock;
    this.prefix = prefix;
    this.scopeDepth = scopeDepth;
    this.conditions = List.copyOf(conditions);
    this.configuration = configuration;
    this.installed = root.installed;
    this.notifications = root.notifications;
    this.registrations = root.registrations;
    this.websocketRegistrations = root.websocketRegistrations;
    this.staticFiles = root.staticFiles;
    this.errors = new LinkedHashMap<>();
    this.endpointScopes = root.endpointScopes;
    this.staticScopes = root.staticScopes;
    this.routeKeys = root.routeKeys;
    this.websocketRouteKeys = root.websocketRouteKeys;
  }

  /**
   * Composes a relative endpoint or group path without allowing a leading slash to escape scope.
   * Empty paths select the current group, or the root slash when no prefix exists.
   *
   * @param prefix enclosing group prefix
   * @param path relative path, optionally beginning with a separator
   * @return composed absolute path; trailing separators remain significant
   */
  private static String join(String prefix, String path) {
    Objects.requireNonNull(path);
    if (path.isEmpty()) {
      return prefix.isEmpty() ? HttpCharacters.PATH_SEPARATOR_STRING : prefix;
    }

    return prefix
        + (path.charAt(0) == HttpCharacters.PATH_SEPARATOR
            ? path
            : HttpCharacters.PATH_SEPARATOR + path);
  }

  /**
   * Invokes a root registration callback synchronously while holding the shared lock.
   *
   * @param registration callback receiving this pre-created root scope
   */
  void register(Consumer<Routes> registration) {
    runUnderLock(
        lock,
        () -> {
          beginRegistration();
          Objects.requireNonNull(registration);
          registration.accept(this);
        });
  }

  /**
   * Activates one typed application default before endpoint registration.
   *
   * @param extension exact application instance
   * @param configure typed customization callback
   * @param <C> provider configuration
   * @throws IllegalStateException if route registration or another defaults callback has begun
   */
  <C> void activate(ApplicationExtension<C> extension, Consumer<? super C> configure) {
    runUnderLock(
        lock,
        () -> {
          requireMutable();
          if (root.configuringDefaults || root.registrationStarted) {
            throw new IllegalStateException(
                "Configure application defaults before registering routes");
          }

          root.configuringDefaults = true;

          try {
            final var defaults =
                configuration == null ? new Lifecycle(installed, initialize, null) : configuration;
            defaults.activate(extension, configure);
            requireMutable();
            configuration = defaults;
          } finally {
            root.configuringDefaults = false;
          }
        });
  }

  /**
   * Registers an exception renderer for this entire scope and its descendants, including endpoints
   * registered earlier. A child replaces an inherited mapping for the same class; the most-specific
   * matching class in the resulting scope map wins over app-wide renderers. Repeated registration
   * in this scope replaces its previous mapping. This method configures the scope, not the last
   * endpoint returned by a fluent registration. No installed extension is required.
   *
   * @param type directly thrown exception family
   * @param handler renderer receiving the original failure
   * @param <E> exception type
   * @return this scope
   * @throws NullPointerException if either argument is null
   * @throws IllegalStateException if startup or closure has ended registration
   * @throws IllegalArgumentException if the callback requires a typed registration view
   */
  public <E extends Exception> Routes exception(
      Class<E> type, ExceptionHandler<? super E> handler) {
    registerException(type, handler, false);
    return this;
  }

  /**
   * Registers a renderer in the shared scope map after checking the registration view.
   *
   * @param type directly thrown exception family
   * @param handler renderer receiving the original failure
   * @param codecBacked whether the caller registered through the typed view
   * @param <E> exception type
   */
  private <E extends Exception> void registerException(
      Class<E> type, ExceptionHandler<? super E> handler, boolean codecBacked) {
    runUnderLock(
        lock,
        () -> {
          requireMutable();
          Objects.requireNonNull(type);
          Objects.requireNonNull(handler);
          if (!codecBacked && handler instanceof ExceptionHandler.Typed<?>) {
            throw new IllegalArgumentException(
                "Register typed exception handlers through Routes.Typed");
          }

          errors.put(
              type,
              (failure, request, response) ->
                  handler.handle(type.cast(failure), request, response));
        });
  }

  /**
   * Registers a nested group synchronously on the calling thread while holding the shared lock.
   * Concurrent registrations wait until the entire group callback finishes. Starting the
   * application from the callback is rejected.
   *
   * @param path relative prefix; a leading slash does not escape this scope
   * @param registration callback, including reusable method references
   * @return this scope
   */
  public Routes path(String path, Consumer<Routes> registration) {
    return pathScope(path, registration, null);
  }

  /**
   * Configures a path group once, with independently inherited extension settings for its routes.
   *
   * @param path relative prefix
   * @param registration child route definitions
   * @param configuration typed group configuration
   * @return this scope
   */
  public Routes path(
      String path, Consumer<Routes> registration, Consumer<Lifecycle> configuration) {
    return pathScope(path, registration, Objects.requireNonNull(configuration));
  }

  /**
   * Creates a group and protects its entire configuration/registration interval from startup.
   *
   * @param path relative prefix
   * @param registration route definitions
   * @param configure optional group configuration
   * @return this scope
   */
  @SuppressWarnings(
      "java:S2093") // Closing a borrowed scope would close the root's shared registration.
  private Routes pathScope(
      String path, Consumer<Routes> registration, @Nullable Consumer<Lifecycle> configure) {
    return Objects.requireNonNull(
        getUnderLock(
            lock,
            () -> {
              final var previousDepth = root.activeGroupDepth;
              final var nextDepth = nextGroupDepth(previousDepth);

              beginRegistration();
              Objects.requireNonNull(registration);

              var group = join(prefix, path);
              RadixRoutes.parameters(group);
              if (group.charAt(group.length() - 1) == HttpCharacters.PATH_SEPARATOR) {
                group = group.substring(0, group.length() - 1);
              }

              root.activeGroupDepth = nextDepth;

              try {
                final var local = configureGroup(configure);
                final var child = new Routes(this, group, conditions, local, nextDepth);
                registration.accept(child);
              } finally {
                root.activeGroupDepth = previousDepth;
              }

              return this;
            }));
  }

  /**
   * Configures and freezes a group collector while the caller holds the shared lock.
   *
   * @param configure optional group configuration callback
   * @return frozen collector, or null when neither inherited nor explicit configuration exists
   * @throws IllegalStateException if inherited or explicit configuration closes registration
   */
  private @Nullable Lifecycle configureGroup(@Nullable Consumer<Lifecycle> configure) {
    if (configuration == null && configure == null) {
      return null;
    }

    final var local = new Lifecycle(installed, initialize, configuration);

    try {
      requireMutable();
      if (configure != null) {
        configure.accept(local);
        requireMutable();
      }

      return local;
    } finally {
      local.freeze();
    }
  }

  /**
   * Registers routes whose availability is evaluated for each matched request after admission.
   * False produces terminal 404. Registration, method discovery and CORS remain unchanged.
   *
   * @param condition live application flag
   * @param registration routes under the condition
   * @return this scope
   */
  public Routes when(BooleanSupplier condition, Consumer<Routes> registration) {
    Objects.requireNonNull(condition);

    return when(ignored -> condition.getAsBoolean(), registration);
  }

  /**
   * Registers a request-dependent condition, including authenticated principal decisions. Nested
   * scopes short-circuit in outer-to-inner order; false does not fall through to another route.
   *
   * @param condition live request decision
   * @param registration conditional routes, registered once
   * @return this scope
   */
  public Routes when(Predicate<Request> condition, Consumer<Routes> registration) {
    return Objects.requireNonNull(
        getUnderLock(
            lock,
            () -> {
              final var previousDepth = root.activeGroupDepth;
              final var nextDepth = nextGroupDepth(previousDepth);

              beginRegistration();
              Objects.requireNonNull(registration);

              final var inherited = new ArrayList<>(conditions);
              inherited.add(Objects.requireNonNull(condition));
              final var child = new Routes(this, prefix, inherited, configuration, nextDepth);

              root.activeGroupDepth = nextDepth;

              try {
                registration.accept(child);
              } finally {
                root.activeGroupDepth = previousDepth;
              }

              return this;
            }));
  }

  /**
   * Combines scope ancestry with captured-root grouping before invoking callbacks.
   *
   * @param previousDepth calling thread's active grouping depth
   * @return permitted child depth
   * @throws IllegalStateException if the eleventh nested group is attempted
   */
  private int nextGroupDepth(int previousDepth) {
    final var depth = Math.max(scopeDepth, previousDepth) + 1;
    if (depth > 10) {
      throw new IllegalStateException("Route groups cannot exceed 10 nested path/when scopes");
    }

    return depth;
  }

  /**
   * Registers GET on a caller-owned executor beneath this scope.
   *
   * @param path relative endpoint path
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @return this scope
   */
  public Routes get(String path, ExecutorService executor, Handler handler) {
    return registerRoute(HttpMethods.GET, path, Objects.requireNonNull(executor), handler, null);
  }

  /**
   * Registers GET on a caller-owned executor beneath this scope.
   *
   * @param path relative endpoint path
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public Routes get(
      String path, ExecutorService executor, Handler handler, Consumer<Lifecycle> configuration) {
    return registerRoute(
        HttpMethods.GET,
        path,
        Objects.requireNonNull(executor),
        handler,
        Objects.requireNonNull(configuration));
  }

  /**
   * Registers GET on a caller-owned executor at this scope's endpoint.
   *
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @return this scope
   */
  public Routes get(ExecutorService executor, Handler handler) {
    return get(EMPTY_PATH, executor, handler);
  }

  /**
   * Registers GET on a caller-owned executor at this scope's endpoint.
   *
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public Routes get(ExecutorService executor, Handler handler, Consumer<Lifecycle> configuration) {
    return get(EMPTY_PATH, executor, handler, configuration);
  }

  /**
   * Registers GET with local extension configuration at this group's endpoint.
   *
   * @param handler endpoint handler
   * @param configuration typed local configuration
   * @return this scope
   */
  public Routes get(Handler handler, Consumer<Lifecycle> configuration) {
    return route(HttpMethods.GET, EMPTY_PATH, handler, configuration);
  }

  /**
   * Registers GET with local extension configuration beneath this scope.
   *
   * @param path relative path
   * @param handler endpoint handler
   * @param configuration typed local configuration
   * @return this scope
   */
  public Routes get(String path, Handler handler, Consumer<Lifecycle> configuration) {
    return route(HttpMethods.GET, path, handler, configuration);
  }

  /**
   * Registers GET at this group's own endpoint.
   *
   * @param handler request/response handler
   * @return this scope
   */
  public Routes get(Handler handler) {
    return route(HttpMethods.GET, EMPTY_PATH, handler);
  }

  /**
   * Registers GET beneath this group's prefix.
   *
   * @param path relative endpoint path; empty selects the group endpoint
   * @param handler request/response handler
   * @return this scope
   */
  public Routes get(String path, Handler handler) {
    return route(HttpMethods.GET, path, handler);
  }

  /**
   * Registers POST on a caller-owned executor beneath this scope.
   *
   * @param path relative endpoint path
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @return this scope
   */
  public Routes post(String path, ExecutorService executor, Handler handler) {
    return registerRoute(HttpMethods.POST, path, Objects.requireNonNull(executor), handler, null);
  }

  /**
   * Registers POST on a caller-owned executor beneath this scope.
   *
   * @param path relative endpoint path
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public Routes post(
      String path, ExecutorService executor, Handler handler, Consumer<Lifecycle> configuration) {
    return registerRoute(
        HttpMethods.POST,
        path,
        Objects.requireNonNull(executor),
        handler,
        Objects.requireNonNull(configuration));
  }

  /**
   * Registers POST on a caller-owned executor at this scope's endpoint.
   *
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @return this scope
   */
  public Routes post(ExecutorService executor, Handler handler) {
    return post(EMPTY_PATH, executor, handler);
  }

  /**
   * Registers POST on a caller-owned executor at this scope's endpoint.
   *
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public Routes post(ExecutorService executor, Handler handler, Consumer<Lifecycle> configuration) {
    return post(EMPTY_PATH, executor, handler, configuration);
  }

  /**
   * Registers POST with local extension configuration at this group's endpoint.
   *
   * @param handler endpoint handler
   * @param configuration typed local configuration
   * @return this scope
   */
  public Routes post(Handler handler, Consumer<Lifecycle> configuration) {
    return route(HttpMethods.POST, EMPTY_PATH, handler, configuration);
  }

  /**
   * Registers POST with local extension configuration beneath this scope.
   *
   * @param path relative path
   * @param handler endpoint handler
   * @param configuration typed local configuration
   * @return this scope
   */
  public Routes post(String path, Handler handler, Consumer<Lifecycle> configuration) {
    return route(HttpMethods.POST, path, handler, configuration);
  }

  /**
   * Registers POST at this group's own endpoint.
   *
   * @param handler request/response handler
   * @return this scope
   */
  public Routes post(Handler handler) {
    return route(HttpMethods.POST, EMPTY_PATH, handler);
  }

  /**
   * Registers POST beneath this group's prefix.
   *
   * @param path relative endpoint path; empty selects the group endpoint
   * @param handler request/response handler
   * @return this scope
   */
  public Routes post(String path, Handler handler) {
    return route(HttpMethods.POST, path, handler);
  }

  /**
   * Registers PUT on a caller-owned executor beneath this scope.
   *
   * @param path relative endpoint path
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @return this scope
   */
  public Routes put(String path, ExecutorService executor, Handler handler) {
    return registerRoute(HttpMethods.PUT, path, Objects.requireNonNull(executor), handler, null);
  }

  /**
   * Registers PUT on a caller-owned executor beneath this scope.
   *
   * @param path relative endpoint path
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public Routes put(
      String path, ExecutorService executor, Handler handler, Consumer<Lifecycle> configuration) {
    return registerRoute(
        HttpMethods.PUT,
        path,
        Objects.requireNonNull(executor),
        handler,
        Objects.requireNonNull(configuration));
  }

  /**
   * Registers PUT on a caller-owned executor at this scope's endpoint.
   *
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @return this scope
   */
  public Routes put(ExecutorService executor, Handler handler) {
    return put(EMPTY_PATH, executor, handler);
  }

  /**
   * Registers PUT on a caller-owned executor at this scope's endpoint.
   *
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public Routes put(ExecutorService executor, Handler handler, Consumer<Lifecycle> configuration) {
    return put(EMPTY_PATH, executor, handler, configuration);
  }

  /**
   * Registers PUT with local extension configuration at this group's endpoint.
   *
   * @param handler endpoint handler
   * @param configuration typed local configuration
   * @return this scope
   */
  public Routes put(Handler handler, Consumer<Lifecycle> configuration) {
    return route(HttpMethods.PUT, EMPTY_PATH, handler, configuration);
  }

  /**
   * Registers PUT with local extension configuration beneath this scope.
   *
   * @param path relative path
   * @param handler endpoint handler
   * @param configuration typed local configuration
   * @return this scope
   */
  public Routes put(String path, Handler handler, Consumer<Lifecycle> configuration) {
    return route(HttpMethods.PUT, path, handler, configuration);
  }

  /**
   * Registers PUT at this group's own endpoint.
   *
   * @param handler request/response handler
   * @return this scope
   */
  public Routes put(Handler handler) {
    return route(HttpMethods.PUT, EMPTY_PATH, handler);
  }

  /**
   * Registers PUT beneath this group's prefix.
   *
   * @param path relative endpoint path; empty selects the group endpoint
   * @param handler request/response handler
   * @return this scope
   */
  public Routes put(String path, Handler handler) {
    return route(HttpMethods.PUT, path, handler);
  }

  /**
   * Registers PATCH on a caller-owned executor beneath this scope.
   *
   * @param path relative endpoint path
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @return this scope
   */
  public Routes patch(String path, ExecutorService executor, Handler handler) {
    return registerRoute(HttpMethods.PATCH, path, Objects.requireNonNull(executor), handler, null);
  }

  /**
   * Registers PATCH on a caller-owned executor beneath this scope.
   *
   * @param path relative endpoint path
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public Routes patch(
      String path, ExecutorService executor, Handler handler, Consumer<Lifecycle> configuration) {
    return registerRoute(
        HttpMethods.PATCH,
        path,
        Objects.requireNonNull(executor),
        handler,
        Objects.requireNonNull(configuration));
  }

  /**
   * Registers PATCH on a caller-owned executor at this scope's endpoint.
   *
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @return this scope
   */
  public Routes patch(ExecutorService executor, Handler handler) {
    return patch(EMPTY_PATH, executor, handler);
  }

  /**
   * Registers PATCH on a caller-owned executor at this scope's endpoint.
   *
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public Routes patch(
      ExecutorService executor, Handler handler, Consumer<Lifecycle> configuration) {
    return patch(EMPTY_PATH, executor, handler, configuration);
  }

  /**
   * Registers PATCH with local extension configuration at this group's endpoint.
   *
   * @param handler endpoint handler
   * @param configuration typed local configuration
   * @return this scope
   */
  public Routes patch(Handler handler, Consumer<Lifecycle> configuration) {
    return route(HttpMethods.PATCH, EMPTY_PATH, handler, configuration);
  }

  /**
   * Registers PATCH with local extension configuration beneath this scope.
   *
   * @param path relative path
   * @param handler endpoint handler
   * @param configuration typed local configuration
   * @return this scope
   */
  public Routes patch(String path, Handler handler, Consumer<Lifecycle> configuration) {
    return route(HttpMethods.PATCH, path, handler, configuration);
  }

  /**
   * Registers PATCH at this group's own endpoint.
   *
   * @param handler request/response handler
   * @return this scope
   */
  public Routes patch(Handler handler) {
    return route(HttpMethods.PATCH, EMPTY_PATH, handler);
  }

  /**
   * Registers PATCH beneath this group's prefix.
   *
   * @param path relative endpoint path; empty selects the group endpoint
   * @param handler request/response handler
   * @return this scope
   */
  public Routes patch(String path, Handler handler) {
    return route(HttpMethods.PATCH, path, handler);
  }

  /**
   * Registers DELETE on a caller-owned executor beneath this scope.
   *
   * @param path relative endpoint path
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @return this scope
   */
  public Routes delete(String path, ExecutorService executor, Handler handler) {
    return registerRoute(HttpMethods.DELETE, path, Objects.requireNonNull(executor), handler, null);
  }

  /**
   * Registers DELETE on a caller-owned executor beneath this scope.
   *
   * @param path relative endpoint path
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public Routes delete(
      String path, ExecutorService executor, Handler handler, Consumer<Lifecycle> configuration) {
    return registerRoute(
        HttpMethods.DELETE,
        path,
        Objects.requireNonNull(executor),
        handler,
        Objects.requireNonNull(configuration));
  }

  /**
   * Registers DELETE on a caller-owned executor at this scope's endpoint.
   *
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @return this scope
   */
  public Routes delete(ExecutorService executor, Handler handler) {
    return delete(EMPTY_PATH, executor, handler);
  }

  /**
   * Registers DELETE on a caller-owned executor at this scope's endpoint.
   *
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public Routes delete(
      ExecutorService executor, Handler handler, Consumer<Lifecycle> configuration) {
    return delete(EMPTY_PATH, executor, handler, configuration);
  }

  /**
   * Registers DELETE with local extension configuration at this group's endpoint.
   *
   * @param handler endpoint handler
   * @param configuration typed local configuration
   * @return this scope
   */
  public Routes delete(Handler handler, Consumer<Lifecycle> configuration) {
    return route(HttpMethods.DELETE, EMPTY_PATH, handler, configuration);
  }

  /**
   * Registers DELETE with local extension configuration beneath this scope.
   *
   * @param path relative path
   * @param handler endpoint handler
   * @param configuration typed local configuration
   * @return this scope
   */
  public Routes delete(String path, Handler handler, Consumer<Lifecycle> configuration) {
    return route(HttpMethods.DELETE, path, handler, configuration);
  }

  /**
   * Registers DELETE at this group's own endpoint.
   *
   * @param handler request/response handler
   * @return this scope
   */
  public Routes delete(Handler handler) {
    return route(HttpMethods.DELETE, EMPTY_PATH, handler);
  }

  /**
   * Registers DELETE beneath this group's prefix.
   *
   * @param path relative endpoint path; empty selects the group endpoint
   * @param handler request/response handler
   * @return this scope
   */
  public Routes delete(String path, Handler handler) {
    return route(HttpMethods.DELETE, path, handler);
  }

  /**
   * Registers HEAD on a caller-owned executor beneath this scope.
   *
   * @param path relative endpoint path
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @return this scope
   */
  public Routes head(String path, ExecutorService executor, Handler handler) {
    return registerRoute(HttpMethods.HEAD, path, Objects.requireNonNull(executor), handler, null);
  }

  /**
   * Registers HEAD on a caller-owned executor beneath this scope.
   *
   * @param path relative endpoint path
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public Routes head(
      String path, ExecutorService executor, Handler handler, Consumer<Lifecycle> configuration) {
    return registerRoute(
        HttpMethods.HEAD,
        path,
        Objects.requireNonNull(executor),
        handler,
        Objects.requireNonNull(configuration));
  }

  /**
   * Registers HEAD on a caller-owned executor at this scope's endpoint.
   *
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @return this scope
   */
  public Routes head(ExecutorService executor, Handler handler) {
    return head(EMPTY_PATH, executor, handler);
  }

  /**
   * Registers HEAD on a caller-owned executor at this scope's endpoint.
   *
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public Routes head(ExecutorService executor, Handler handler, Consumer<Lifecycle> configuration) {
    return head(EMPTY_PATH, executor, handler, configuration);
  }

  /**
   * Registers HEAD with local extension configuration at this group's endpoint.
   *
   * @param handler endpoint handler
   * @param configuration typed local configuration
   * @return this scope
   */
  public Routes head(Handler handler, Consumer<Lifecycle> configuration) {
    return route(HttpMethods.HEAD, EMPTY_PATH, handler, configuration);
  }

  /**
   * Registers HEAD with local extension configuration beneath this scope.
   *
   * @param path relative path
   * @param handler endpoint handler
   * @param configuration typed local configuration
   * @return this scope
   */
  public Routes head(String path, Handler handler, Consumer<Lifecycle> configuration) {
    return route(HttpMethods.HEAD, path, handler, configuration);
  }

  /**
   * Registers HEAD at this group's own endpoint.
   *
   * @param handler request/response handler
   * @return this scope
   */
  public Routes head(Handler handler) {
    return route(HttpMethods.HEAD, EMPTY_PATH, handler);
  }

  /**
   * Registers HEAD beneath this group's prefix.
   *
   * @param path relative endpoint path; empty selects the group endpoint
   * @param handler request/response handler
   * @return this scope
   */
  public Routes head(String path, Handler handler) {
    return route(HttpMethods.HEAD, path, handler);
  }

  /**
   * Registers OPTIONS on a caller-owned executor beneath this scope.
   *
   * @param path relative endpoint path
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @return this scope
   */
  public Routes options(String path, ExecutorService executor, Handler handler) {
    return registerRoute(
        HttpMethods.OPTIONS, path, Objects.requireNonNull(executor), handler, null);
  }

  /**
   * Registers OPTIONS on a caller-owned executor beneath this scope.
   *
   * @param path relative endpoint path
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public Routes options(
      String path, ExecutorService executor, Handler handler, Consumer<Lifecycle> configuration) {
    return registerRoute(
        HttpMethods.OPTIONS,
        path,
        Objects.requireNonNull(executor),
        handler,
        Objects.requireNonNull(configuration));
  }

  /**
   * Registers OPTIONS on a caller-owned executor at this scope's endpoint.
   *
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @return this scope
   */
  public Routes options(ExecutorService executor, Handler handler) {
    return options(EMPTY_PATH, executor, handler);
  }

  /**
   * Registers OPTIONS on a caller-owned executor at this scope's endpoint.
   *
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public Routes options(
      ExecutorService executor, Handler handler, Consumer<Lifecycle> configuration) {
    return options(EMPTY_PATH, executor, handler, configuration);
  }

  /**
   * Registers OPTIONS with local extension configuration at this group's endpoint.
   *
   * @param handler endpoint handler
   * @param configuration typed local configuration
   * @return this scope
   */
  public Routes options(Handler handler, Consumer<Lifecycle> configuration) {
    return route(HttpMethods.OPTIONS, EMPTY_PATH, handler, configuration);
  }

  /**
   * Registers OPTIONS with local extension configuration beneath this scope.
   *
   * @param path relative path
   * @param handler endpoint handler
   * @param configuration typed local configuration
   * @return this scope
   */
  public Routes options(String path, Handler handler, Consumer<Lifecycle> configuration) {
    return route(HttpMethods.OPTIONS, path, handler, configuration);
  }

  /**
   * Registers OPTIONS at this group's own endpoint.
   *
   * @param handler request/response handler
   * @return this scope
   */
  public Routes options(Handler handler) {
    return route(HttpMethods.OPTIONS, EMPTY_PATH, handler);
  }

  /**
   * Registers OPTIONS beneath this group's prefix.
   *
   * @param path relative endpoint path; empty selects the group endpoint
   * @param handler request/response handler
   * @return this scope
   */
  public Routes options(String path, Handler handler) {
    return route(HttpMethods.OPTIONS, path, handler);
  }

  /**
   * Registers an event stream on a caller-owned executor beneath this scope.
   *
   * @param path relative endpoint path
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @return this scope
   */
  public Routes sse(String path, ExecutorService executor, Handler handler) {
    return registerSse(path, Objects.requireNonNull(executor), handler, null);
  }

  /**
   * Registers an event stream on a caller-owned executor beneath this scope.
   *
   * @param path relative endpoint path
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public Routes sse(
      String path, ExecutorService executor, Handler handler, Consumer<Lifecycle> configuration) {
    return registerSse(
        path, Objects.requireNonNull(executor), handler, Objects.requireNonNull(configuration));
  }

  /**
   * Registers an event stream on a caller-owned executor at this scope's endpoint.
   *
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @return this scope
   */
  public Routes sse(ExecutorService executor, Handler handler) {
    return sse(EMPTY_PATH, executor, handler);
  }

  /**
   * Registers an event stream on a caller-owned executor at this scope's endpoint.
   *
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public Routes sse(ExecutorService executor, Handler handler, Consumer<Lifecycle> configuration) {
    return sse(EMPTY_PATH, executor, handler, configuration);
  }

  /**
   * Registers a server-sent event GET route at this group's own endpoint.
   *
   * @param handler request/response handler that starts an event stream
   * @return this scope
   */
  public Routes sse(Handler handler) {
    return sse(EMPTY_PATH, handler);
  }

  /**
   * Registers a server-sent event GET route beneath this group's prefix.
   *
   * @param path relative endpoint path; empty selects the group endpoint
   * @param handler request/response handler that starts an event stream
   * @return this scope
   */
  public Routes sse(String path, Handler handler) {
    return registerSse(path, null, handler, null);
  }

  /**
   * Registers an event stream with typed local configuration.
   *
   * @param path relative path
   * @param handler event-stream handler
   * @param configuration endpoint configuration
   * @return this scope
   */
  public Routes sse(String path, Handler handler, Consumer<Lifecycle> configuration) {
    return registerSse(path, null, handler, Objects.requireNonNull(configuration));
  }

  /**
   * Registers an event stream at this group's endpoint with local configuration.
   *
   * @param handler event-stream handler
   * @param configuration endpoint configuration
   * @return this scope
   */
  public Routes sse(Handler handler, Consumer<Lifecycle> configuration) {
    return sse(EMPTY_PATH, handler, configuration);
  }

  /**
   * Keeps the event-stream contract shared by both registration forms.
   *
   * @param path relative path
   * @param executor borrowed executor, or null for default execution
   * @param handler submitted handler
   * @param configure optional local configuration
   * @return this scope
   */
  private Routes registerSse(
      String path,
      @Nullable ExecutorService executor,
      Handler handler,
      @Nullable Consumer<Lifecycle> configure) {
    Objects.requireNonNull(handler);

    return registerRoute(
        HttpMethods.GET,
        path,
        executor,
        (request, response) -> {
          response.requireEventStream();
          handler.handle(request, response);
          if (response.status() != HttpStatusCodes.NO_CONTENT.value() && !response.isCommitted()) {
            throw new IllegalStateException("SSE route must start an event stream or return 204");
          }
        },
        configure);
  }

  /**
   * Registers a WebSocket handshake on a caller-owned executor beneath this scope. Later listener
   * callbacks retain their native execution contract.
   *
   * @param path relative endpoint path
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param listenerFactory per-connection listener factory
   * @return this scope
   */
  public Routes websocket(
      String path,
      ExecutorService executor,
      BiFunction<Request, ServerUpgradeResponse, Session.Listener> listenerFactory) {
    return registerWebsocket(path, Objects.requireNonNull(executor), listenerFactory, null);
  }

  /**
   * Registers a WebSocket handshake on a caller-owned executor beneath this scope. Later listener
   * callbacks retain their native execution contract.
   *
   * @param path relative endpoint path
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param listenerFactory per-connection listener factory
   * @param configuration local extension configuration
   * @return this scope
   */
  public Routes websocket(
      String path,
      ExecutorService executor,
      BiFunction<Request, ServerUpgradeResponse, Session.Listener> listenerFactory,
      Consumer<Lifecycle> configuration) {
    return registerWebsocket(
        path,
        Objects.requireNonNull(executor),
        listenerFactory,
        Objects.requireNonNull(configuration));
  }

  /**
   * Registers a WebSocket handshake on a caller-owned executor at this scope's endpoint. Later
   * listener callbacks retain their native execution contract.
   *
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param listenerFactory per-connection listener factory
   * @return this scope
   */
  public Routes websocket(
      ExecutorService executor,
      BiFunction<Request, ServerUpgradeResponse, Session.Listener> listenerFactory) {
    return websocket(EMPTY_PATH, executor, listenerFactory);
  }

  /**
   * Registers a WebSocket handshake on a caller-owned executor at this scope's endpoint. Later
   * listener callbacks retain their native execution contract.
   *
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param listenerFactory per-connection listener factory
   * @param configuration local extension configuration
   * @return this scope
   */
  public Routes websocket(
      ExecutorService executor,
      BiFunction<Request, ServerUpgradeResponse, Session.Listener> listenerFactory,
      Consumer<Lifecycle> configuration) {
    return websocket(EMPTY_PATH, executor, listenerFactory, configuration);
  }

  /**
   * Registers a WebSocket endpoint at this group's own path.
   *
   * @param listenerFactory one listener per accepted connection, created during the handshake
   * @return this scope
   */
  public Routes websocket(
      BiFunction<Request, ServerUpgradeResponse, Session.Listener> listenerFactory) {
    return websocket(EMPTY_PATH, listenerFactory);
  }

  /**
   * Registers a WebSocket endpoint beneath this group's prefix. The factory runs after route
   * admission, may configure the upgrade response, and must return a fresh listener. It must not
   * retain the HTTP request or upgrade response after the handshake.
   *
   * @param path relative endpoint path; empty selects the group endpoint
   * @param listenerFactory one listener per accepted connection
   * @return this scope
   * @throws IllegalArgumentException if the path duplicates an existing WebSocket route shape
   */
  public Routes websocket(
      String path, BiFunction<Request, ServerUpgradeResponse, Session.Listener> listenerFactory) {
    return registerWebsocket(path, null, listenerFactory, null);
  }

  /**
   * Registers handshake-local extension configuration. Hooks apply to HTTP admission only.
   *
   * @param path relative path
   * @param listenerFactory per-connection factory
   * @param configuration local handshake configuration
   * @return this scope
   */
  public Routes websocket(
      String path,
      BiFunction<Request, ServerUpgradeResponse, Session.Listener> listenerFactory,
      Consumer<Lifecycle> configuration) {
    return registerWebsocket(path, null, listenerFactory, Objects.requireNonNull(configuration));
  }

  /**
   * Registers a configured handshake at this group's own endpoint.
   *
   * @param listenerFactory per-connection factory
   * @param configuration local handshake configuration
   * @return this scope
   */
  public Routes websocket(
      BiFunction<Request, ServerUpgradeResponse, Session.Listener> listenerFactory,
      Consumer<Lifecycle> configuration) {
    return websocket(EMPTY_PATH, listenerFactory, configuration);
  }

  /**
   * Registers complete-message callbacks configured once, running sequentially per connection on
   * the application's configured execution facility. Existing native factories retain their own
   * execution.
   *
   * @param path relative endpoint path
   * @param configuration eager callback configuration
   * @return this scope
   */
  public Routes websocket(String path, Consumer<WebSocket> configuration) {
    return registerManagedWebsocket(path, null, configuration, null);
  }

  /**
   * Registers managed callbacks at this group's own endpoint.
   *
   * @param configuration eager callback configuration
   * @return this scope
   */
  public Routes websocket(Consumer<WebSocket> configuration) {
    return websocket(EMPTY_PATH, configuration);
  }

  /**
   * Registers managed callbacks with local extensions applying to HTTP admission only.
   *
   * @param path relative endpoint path
   * @param configuration eager callback configuration
   * @param extensions local handshake extension configuration
   * @return this scope
   */
  public Routes websocket(
      String path, Consumer<WebSocket> configuration, Consumer<Lifecycle> extensions) {
    return registerManagedWebsocket(path, null, configuration, Objects.requireNonNull(extensions));
  }

  /**
   * Registers managed callbacks and handshake extensions at this group's own endpoint.
   *
   * @param configuration eager callback configuration
   * @param extensions local handshake extension configuration
   * @return this scope
   */
  public Routes websocket(Consumer<WebSocket> configuration, Consumer<Lifecycle> extensions) {
    return websocket(EMPTY_PATH, configuration, extensions);
  }

  /**
   * Runs the matched HTTP handshake on a borrowed executor. All subsequent WebSocket callbacks run
   * using the application's execution mode; executor thread-local bindings are not propagated.
   *
   * @param path relative endpoint path
   * @param executor asynchronous borrowed handshake executor
   * @param configuration eager callback configuration
   * @return this scope
   */
  public Routes websocket(
      String path, ExecutorService executor, Consumer<WebSocket> configuration) {
    return registerManagedWebsocket(path, Objects.requireNonNull(executor), configuration, null);
  }

  /**
   * Selects a borrowed HTTP handshake executor and local extensions. Subsequent WebSocket callbacks
   * use the application's execution mode, without propagating arbitrary executor-local bindings.
   *
   * @param path relative endpoint path
   * @param executor asynchronous borrowed handshake executor
   * @param configuration eager callback configuration
   * @param extensions local handshake extension configuration
   * @return this scope
   */
  public Routes websocket(
      String path,
      ExecutorService executor,
      Consumer<WebSocket> configuration,
      Consumer<Lifecycle> extensions) {
    return registerManagedWebsocket(
        path, Objects.requireNonNull(executor), configuration, Objects.requireNonNull(extensions));
  }

  /**
   * Runs this group's HTTP handshake on a borrowed executor, not its managed WebSocket callbacks.
   * Arbitrary executor-local bindings are not propagated after upgrade.
   *
   * @param executor asynchronous borrowed handshake executor
   * @param configuration eager callback configuration
   * @return this scope
   */
  public Routes websocket(ExecutorService executor, Consumer<WebSocket> configuration) {
    return websocket(EMPTY_PATH, executor, configuration);
  }

  /**
   * Configures this group's handshake on a borrowed executor with local HTTP extensions. Subsequent
   * WebSocket callbacks use the application's execution mode without executor-local bindings.
   *
   * @param executor asynchronous borrowed handshake executor
   * @param configuration eager callback configuration
   * @param extensions local handshake extension configuration
   * @return this scope
   */
  public Routes websocket(
      ExecutorService executor, Consumer<WebSocket> configuration, Consumer<Lifecycle> extensions) {
    return websocket(EMPTY_PATH, executor, configuration, extensions);
  }

  /**
   * Validates and publishes one configured upgrade endpoint.
   *
   * @param path relative path
   * @param executor borrowed handshake executor, or null for default execution
   * @param listenerFactory per-connection factory
   * @param configure optional local configuration
   * @return this scope
   * @throws IllegalArgumentException if the WebSocket shape is already registered
   */
  private Routes registerWebsocket(
      String path,
      @Nullable ExecutorService executor,
      BiFunction<Request, ServerUpgradeResponse, Session.Listener> listenerFactory,
      @Nullable Consumer<Lifecycle> configure) {
    Objects.requireNonNull(listenerFactory);
    return Objects.requireNonNull(
        getUnderLock(
            lock,
            () -> {
              final var endpoint = createWebsocketEndpoint(path, executor, listenerFactory);
              return registerEndpoint(endpoint, configure, true);
            }));
  }

  /**
   * Constructs a native WebSocket endpoint while the caller holds the shared registration lock.
   *
   * @param path relative endpoint path
   * @param executor optional borrowed handshake executor
   * @param listenerFactory validated per-connection factory
   * @return validated endpoint awaiting reservation and configuration
   */
  private RadixRoutes.Endpoint createWebsocketEndpoint(
      String path,
      @Nullable ExecutorService executor,
      BiFunction<Request, ServerUpgradeResponse, Session.Listener> listenerFactory) {
    requireMutable();
    return RadixRoutes.websocketEndpoint(
            join(prefix, path),
            (ignoredRequest, ignoredResponse) -> {},
            (request, response, ignoredWebSocketRuntime) ->
                Objects.requireNonNull(listenerFactory).apply(request, response))
        .withExecutor(executor);
  }

  /**
   * Reserves the route before configuring its managed callbacks and extensions.
   *
   * @param path relative endpoint path
   * @param executor optional borrowed handshake executor
   * @param configuration eager WebSocket callback configuration
   * @param extensions optional local handshake extensions
   * @return this scope
   */
  private Routes registerManagedWebsocket(
      String path,
      @Nullable ExecutorService executor,
      Consumer<WebSocket> configuration,
      @Nullable Consumer<Lifecycle> extensions) {
    return registerManagedWebsocket(path, executor, new WebSocket(), configuration, extensions);
  }

  /**
   * Shares route reservation and publication between ordinary and codec-enabled callbacks.
   *
   * @param path relative endpoint path
   * @param executor optional borrowed handshake executor
   * @param callbacks framework-owned callback configuration
   * @param configuration eager callback registration
   * @param extensions optional local handshake extensions
   * @return this scope
   */
  Routes registerManagedWebsocket(
      String path,
      @Nullable ExecutorService executor,
      WebSocket callbacks,
      Consumer<WebSocket> configuration,
      @Nullable Consumer<Lifecycle> extensions) {
    Objects.requireNonNull(configuration);
    Objects.requireNonNull(callbacks);
    return Objects.requireNonNull(
        getUnderLock(
            lock,
            () -> {
              final var endpoint = createManagedWebsocketEndpoint(path, executor, callbacks);
              return registerEndpoint(
                  endpoint,
                  local -> {
                    configuration.accept(callbacks);
                    callbacks.freeze();
                    if (extensions != null) {
                      extensions.accept(local);
                    }
                  },
                  true);
            }));
  }

  /**
   * Constructs a managed WebSocket endpoint while the caller holds the shared registration lock.
   *
   * @param path relative endpoint path
   * @param executor optional borrowed handshake executor
   * @param callbacks validated callback configuration
   * @return validated endpoint awaiting reservation and configuration
   */
  private RadixRoutes.Endpoint createManagedWebsocketEndpoint(
      String path, @Nullable ExecutorService executor, WebSocket callbacks) {
    requireMutable();
    return RadixRoutes.websocketEndpoint(
            join(prefix, path),
            callbacks::admit,
            (request, ignoredServerUpgradeResponse, runtime) -> {
              request.response().requireWebSocketUpgrade();
              return new ManagedWebSocket(callbacks, request, runtime);
            })
        .withExecutor(executor);
  }

  /**
   * Registers an HTTP endpoint on a caller-owned executor beneath this scope.
   *
   * @param method HTTP method
   * @param path relative endpoint path
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @return this scope
   */
  public Routes route(HttpMethods method, String path, ExecutorService executor, Handler handler) {
    return registerRoute(method, path, Objects.requireNonNull(executor), handler, null);
  }

  /**
   * Registers an HTTP endpoint on a caller-owned executor beneath this scope.
   *
   * @param method HTTP method
   * @param path relative endpoint path
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public Routes route(
      HttpMethods method,
      String path,
      ExecutorService executor,
      Handler handler,
      Consumer<Lifecycle> configuration) {
    return registerRoute(
        method,
        path,
        Objects.requireNonNull(executor),
        handler,
        Objects.requireNonNull(configuration));
  }

  /**
   * Registers an HTTP endpoint on a caller-owned executor at this scope's endpoint.
   *
   * @param method HTTP method
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @return this scope
   */
  public Routes route(HttpMethods method, ExecutorService executor, Handler handler) {
    return route(method, EMPTY_PATH, executor, handler);
  }

  /**
   * Registers an HTTP endpoint on a caller-owned executor at this scope's endpoint.
   *
   * @param method HTTP method
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public Routes route(
      HttpMethods method,
      ExecutorService executor,
      Handler handler,
      Consumer<Lifecycle> configuration) {
    return route(method, EMPTY_PATH, executor, handler, configuration);
  }

  /**
   * Registers an HTTP endpoint on a caller-owned executor beneath this scope.
   *
   * @param method recognized case-sensitive wire method
   * @param path relative endpoint path
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @return this scope
   */
  public Routes route(String method, String path, ExecutorService executor, Handler handler) {
    return route(parseMethod(method), path, executor, handler);
  }

  /**
   * Registers an HTTP endpoint on a caller-owned executor beneath this scope.
   *
   * @param method recognized case-sensitive wire method
   * @param path relative endpoint path
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public Routes route(
      String method,
      String path,
      ExecutorService executor,
      Handler handler,
      Consumer<Lifecycle> configuration) {
    return route(parseMethod(method), path, executor, handler, configuration);
  }

  /**
   * Registers an HTTP endpoint on a caller-owned executor at this scope's endpoint.
   *
   * @param method recognized case-sensitive wire method
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @return this scope
   */
  public Routes route(String method, ExecutorService executor, Handler handler) {
    return route(method, EMPTY_PATH, executor, handler);
  }

  /**
   * Registers an HTTP endpoint on a caller-owned executor at this scope's endpoint.
   *
   * @param method recognized case-sensitive wire method
   * @param executor asynchronous borrowed executor; see this class's execution contract
   * @param handler matched endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public Routes route(
      String method, ExecutorService executor, Handler handler, Consumer<Lifecycle> configuration) {
    return route(method, EMPTY_PATH, executor, handler, configuration);
  }

  /**
   * Registers an explicit HTTP method beneath this group's prefix. Whole-segment patterns may use
   * {@code {name}}, terminal {@code {*tail}}, or a constrained parameter such as {@code
   * {id:[0-9]+}}. Constraints use one ASCII character class with optional simple repetition.
   * Matching prefers literals, constraints, plain parameters, then catch-alls.
   *
   * @param method registered HTTP method
   * @param path relative endpoint path; empty selects the group endpoint
   * @param handler request/response handler
   * @return this scope
   * @throws IllegalArgumentException if the route is invalid or already registered
   * @throws IllegalStateException if route registration has ended
   */
  public Routes route(HttpMethods method, String path, Handler handler) {
    return registerRoute(method, path, null, handler, null);
  }

  /**
   * Registers an HTTP endpoint with typed instance configuration and local lifecycle callbacks.
   *
   * @param method HTTP method
   * @param path relative path
   * @param handler submitted endpoint handler
   * @param configuration local endpoint configuration
   * @return this scope
   */
  public Routes route(
      HttpMethods method, String path, Handler handler, Consumer<Lifecycle> configuration) {
    return registerRoute(method, path, null, handler, Objects.requireNonNull(configuration));
  }

  /**
   * Registers a case-sensitive wire method beneath this group's prefix.
   *
   * @param method registered wire token, such as GET or PROPFIND; no normalization is performed
   * @param path relative endpoint path; empty selects the group endpoint
   * @param handler request/response handler
   * @return this scope
   * @throws NullPointerException if method is null
   * @throws IllegalArgumentException if the method is unrecognized or the route is invalid
   * @throws IllegalStateException if route registration has ended
   */
  public Routes route(String method, String path, Handler handler) {
    return Objects.requireNonNull(
        getUnderLock(
            lock,
            () -> {
              requireMutable();
              final var endpoint = createEndpoint(parseMethod(method), path, null, handler);
              return registerEndpoint(endpoint, null, false);
            }));
  }

  /**
   * Registers a wire method with local endpoint configuration.
   *
   * @param method recognized case-sensitive method
   * @param path relative path
   * @param handler submitted endpoint
   * @param configuration local configuration
   * @return this scope
   * @throws IllegalArgumentException if the method is unrecognized
   */
  public Routes route(
      String method, String path, Handler handler, Consumer<Lifecycle> configuration) {
    return route(parseMethod(method), path, handler, configuration);
  }

  /**
   * Registers an enum method with local configuration at this group's endpoint.
   *
   * @param method HTTP method
   * @param handler endpoint handler
   * @param configuration local configuration
   * @return this scope
   */
  public Routes route(HttpMethods method, Handler handler, Consumer<Lifecycle> configuration) {
    return route(method, EMPTY_PATH, handler, configuration);
  }

  /**
   * Registers a wire method with local configuration at this group's endpoint.
   *
   * @param method recognized case-sensitive method
   * @param handler endpoint handler
   * @param configuration local configuration
   * @return this scope
   */
  public Routes route(String method, Handler handler, Consumer<Lifecycle> configuration) {
    return route(method, EMPTY_PATH, handler, configuration);
  }

  /**
   * Registers an explicit HTTP method at this group's own endpoint.
   *
   * @param method registered HTTP method
   * @param handler request/response handler
   * @return this scope
   */
  public Routes route(HttpMethods method, Handler handler) {
    return route(method, EMPTY_PATH, handler);
  }

  /**
   * Registers a case-sensitive wire method at this group's own endpoint.
   *
   * @param method registered wire token, without trimming or normalization
   * @param handler request/response handler
   * @return this scope
   */
  public Routes route(String method, Handler handler) {
    return route(method, EMPTY_PATH, handler);
  }

  /**
   * Parses a case-sensitive wire method without checking registration state.
   *
   * @param method wire method token
   * @return recognized HTTP method
   * @throws NullPointerException if the method is null
   * @throws IllegalArgumentException if the method is unrecognized
   */
  private static HttpMethods parseMethod(String method) {
    return HttpMethods.httpMethod(Objects.requireNonNull(method))
        .orElseThrow(() -> new IllegalArgumentException(UNRECOGNIZED_HTTP_METHOD + method));
  }

  /**
   * Validates collisions before configuring and publishing one endpoint.
   *
   * @param method HTTP method
   * @param path relative path
   * @param executor borrowed executor, or null for default execution
   * @param handler submitted handler
   * @param configure optional endpoint configuration
   * @return this scope
   * @throws IllegalArgumentException if the route shape already exists
   */
  private Routes registerRoute(
      HttpMethods method,
      String path,
      @Nullable ExecutorService executor,
      Handler handler,
      @Nullable Consumer<Lifecycle> configure) {
    return Objects.requireNonNull(
        getUnderLock(
            lock,
            () -> {
              final var endpoint = createEndpoint(method, path, executor, handler);
              return registerEndpoint(endpoint, configure, false);
            }));
  }

  /**
   * Constructs an HTTP endpoint while the caller holds the shared registration lock.
   *
   * @param method registered HTTP method
   * @param path relative endpoint path
   * @param executor optional borrowed handler executor
   * @param handler submitted endpoint handler
   * @return validated endpoint awaiting reservation and configuration
   */
  private RadixRoutes.Endpoint createEndpoint(
      HttpMethods method, String path, @Nullable ExecutorService executor, Handler handler) {
    requireMutable();
    return RadixRoutes.endpoint(Objects.requireNonNull(method), join(prefix, path), handler)
        .withExecutor(executor);
  }

  /**
   * Reserves a shape before callbacks, then publishes only while registration is still open. The
   * caller holds the shared lock throughout configuration and publication.
   *
   * @param endpoint validated endpoint
   * @param configure optional local configuration
   * @param websocket whether this belongs to the independent handshake table
   * @return this scope
   * @throws IllegalArgumentException if the shape is already registered or being configured
   */
  @SuppressWarnings(
      "java:S1181") // Roll back the reserved shape even when configuration throws Error.
  private Routes registerEndpoint(
      RadixRoutes.Endpoint endpoint, @Nullable Consumer<Lifecycle> configure, boolean websocket) {
    final var keys = websocket ? websocketRouteKeys : routeKeys;
    final var entries = websocket ? websocketRegistrations : registrations;
    final var key = websocket ? endpoint.pattern() : endpoint.method() + " " + endpoint.pattern();

    beginRegistration();
    if (!keys.add(key)) {
      throw new IllegalArgumentException("Duplicate route shape: " + key);
    }

    if (configuration == null && configure == null && conditions.isEmpty()) {
      entries.add(endpoint);
      endpointScopes.put(endpoint, this);
      return this;
    }

    final var facts =
        configuration == null && configure == null
            ? List.<Runnable>of()
            : new ArrayList<Runnable>();

    try {
      final var behavior =
          behavior(websocket ? null : endpoint.method(), endpoint.routePattern(), configure, facts);
      final var enriched = endpoint.withBehavior(behavior);

      requireMutable();
      entries.add(enriched);
      endpointScopes.put(enriched, this);
      publish(behavior, facts);
      return this;
    } catch (RuntimeException | Error failure) {
      keys.remove(key);
      throw failure;
    }
  }

  /**
   * Copies a scope and freezes all request data without retaining the extension map in the router.
   *
   * @param method HTTP method, or null for handshakes/mounts
   * @param template actual composed registration path
   * @param configure optional local configuration
   * @param facts pending metadata notifications, published only on successful registration
   * @return behavior, or null for a route without local runtime behavior
   */
  private @Nullable EndpointBehavior behavior(
      @Nullable HttpMethods method,
      String template,
      @Nullable Consumer<Lifecycle> configure,
      List<Runnable> facts) {
    if (configuration == null && configure == null && conditions.isEmpty()) {
      return null;
    }

    final var local = new Lifecycle(installed, initialize, configuration);

    try {
      if (configure != null) {
        configure.accept(local);
      }

      return local.bind(conditions, method, template, facts);
    } finally {
      local.freeze();
    }
  }

  /**
   * Publishes cold notifications and completion requirements while holding the root lock.
   *
   * @param behavior immutable local behavior
   * @param facts pending HTTP metadata notifications
   */
  private void publish(@Nullable EndpointBehavior behavior, List<Runnable> facts) {
    notifications.addAll(facts);
    if (behavior != null
        && (!behavior.observers().isEmpty() || !behavior.instrumentation().isEmpty())) {
      root.localObservers = true;
    }
  }

  /**
   * Mounts a filesystem directory below this scope. Explicit endpoint routes always take
   * precedence, and only existing regular GET or HEAD resources are selected.
   *
   * @param path relative mounted path
   * @param directory source directory
   * @return this scope
   * @throws IllegalArgumentException if the source is not a directory or the mount is invalid
   * @throws IllegalStateException if route registration has ended
   */
  public Routes staticFiles(String path, Path directory) {
    return staticFiles(path, directory, StaticOptions.defaults());
  }

  /**
   * Mounts a filesystem directory with optional welcome-file and SPA fallback behavior. Explicit
   * endpoints retain precedence over this mount.
   *
   * @param path relative mounted path
   * @param directory source directory
   * @param options immutable static-resource options
   * @return this scope
   * @throws IllegalArgumentException if the source or mount is invalid
   * @throws IllegalStateException if route registration has ended
   */
  public Routes staticFiles(String path, Path directory, StaticOptions options) {
    return staticSource(
        path, mount -> new StaticFiles(mount, directory, Objects.requireNonNull(options)));
  }

  /**
   * Mounts a classpath directory below this scope. Explicit endpoint routes always take precedence,
   * and only existing regular GET or HEAD resources are selected.
   *
   * @param path relative mounted path
   * @param directory classpath directory
   * @return this scope
   * @throws IllegalArgumentException if the source is not a readable directory or the mount is
   *     invalid
   * @throws IllegalStateException if route registration has ended
   */
  public Routes classpathResources(String path, String directory) {
    return classpathResources(path, directory, StaticOptions.defaults());
  }

  /**
   * Mounts a classpath directory with optional welcome-file and SPA fallback behavior. Explicit
   * endpoints retain precedence over this mount.
   *
   * @param path relative mounted path
   * @param directory classpath source directory
   * @param options immutable static-resource options
   * @return this scope
   * @throws IllegalArgumentException if the source or mount is invalid
   * @throws IllegalStateException if route registration has ended
   */
  public Routes classpathResources(String path, String directory, StaticOptions options) {
    return staticSource(
        path, mount -> new StaticFiles(mount, directory, Objects.requireNonNull(options)));
  }

  /**
   * Anchors, configures and publishes a static source while holding the shared lock.
   *
   * @param path relative mount
   * @param source creates the owned static source
   * @return this scope
   */
  @SuppressWarnings(
      "java:S1181") // Close unpublished resources on Error without replacing its cause.
  private Routes staticSource(String path, Function<String, StaticFiles> source) {
    return Objects.requireNonNull(
        getUnderLock(
            lock,
            () -> {
              beginRegistration();
              final var mount = staticMount(path);
              StaticFiles files = null;

              try {
                files = source.apply(mount);
                final var behavior = behavior(null, mount, null, List.of());
                files.behavior(behavior);
                requireMutable();
                staticFiles.add(files);
                staticScopes.put(files, this);
                publish(behavior, List.of());
                return this;
              } catch (RuntimeException | Error failure) {
                if (files != null) {
                  try {
                    files.close();
                  } catch (RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                  }
                }

                throw failure;
              }
            }));
  }

  /**
   * Normalizes a mount and rejects parameters because mounted resources have no parameter binding.
   *
   * @param path relative mount path
   * @return absolute normalized literal mount path
   * @throws IllegalArgumentException if the mount has a parameter segment
   */
  private String staticMount(String path) {
    final var mounted = join(prefix, path);
    if (!RadixRoutes.parameters(mounted).isEmpty()) {
      throw new IllegalArgumentException("Static-resource mounts cannot contain path parameters");
    }

    if (mounted.length() == 1) {
      return mounted;
    }

    return mounted.charAt(mounted.length() - 1) == HttpCharacters.PATH_SEPARATOR
        ? mounted.substring(0, mounted.length() - 1)
        : mounted;
  }

  /**
   * Compiles the immutable request router and ends registration before releasing the shared lock.
   * Static mounts transfer to the compiled router; other registration data is released. Compilation
   * rejects reentrant admission and an occupied registration lock, allowing startup to be retried
   * once registration finishes.
   *
   * @param sessions whether native application sessions are configured
   * @return the completed router with literal-first endpoint matching
   * @throws IllegalStateException if registration is closed or its shared lock is already held
   */
  RadixRoutes compile(boolean sessions) {
    if (lock.isHeldByCurrentThread() || !lock.tryLock()) {
      throw new IllegalStateException("Finish route registration callbacks before starting");
    }

    try {
      requireMutable();

      if (!sessions
          && (registrations.stream()
                  .anyMatch(
                      endpoint -> endpoint.behavior() != null && endpoint.behavior().csrf() != null)
              || staticFiles.stream().anyMatch(StaticFiles::requiresSessions))) {
        throw new IllegalStateException("CSRF protection requires application sessions");
      }

      final var resolved =
          new IdentityHashMap<
              Routes, Map<Class<? extends Exception>, ExceptionHandler<Exception>>>();
      final var endpoints =
          registrations.stream().map(endpoint -> finalizeEndpoint(endpoint, resolved)).toList();
      final var handshakes =
          websocketRegistrations.stream()
              .map(endpoint -> finalizeEndpoint(endpoint, resolved))
              .toList();
      for (final var files : staticFiles) {
        files.exceptions(
            Objects.requireNonNull(staticScopes.get(files)).exceptionMappings(resolved));
      }

      final var router = RadixRoutes.from(endpoints, staticFiles);
      root.websocketRouter = handshakes.isEmpty() ? null : RadixRoutes.from(handshakes);
      root.closed = true;
      registrations.clear();
      websocketRegistrations.clear();
      staticFiles.clear();
      endpointScopes.clear();
      staticScopes.clear();
      routeKeys.clear();
      websocketRouteKeys.clear();
      installed.clear();

      return router;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Freezes scope exception behavior without retaining registration ancestry in the router.
   *
   * @param endpoint registered binding
   * @param resolved startup-only cache of inherited maps
   * @return binding with its completed immutable behavior
   */
  private RadixRoutes.Endpoint finalizeEndpoint(
      RadixRoutes.Endpoint endpoint,
      IdentityHashMap<Routes, Map<Class<? extends Exception>, ExceptionHandler<Exception>>>
          resolved) {
    var scope = Objects.requireNonNull(endpointScopes.get(endpoint));

    return endpoint.withBehavior(
        EndpointBehavior.withErrors(endpoint.behavior(), scope.exceptionMappings(resolved)));
  }

  /**
   * Resolves this scope once, with child exact-type replacements independent of registration order.
   *
   * @param resolved startup-only cache
   * @return immutable mappings inherited through registration scopes, not URL prefixes
   */
  private Map<Class<? extends Exception>, ExceptionHandler<Exception>> exceptionMappings(
      IdentityHashMap<Routes, Map<Class<? extends Exception>, ExceptionHandler<Exception>>>
          resolved) {
    final var found = resolved.get(this);
    if (found != null) {
      return found;
    }

    final var inherited =
        parent == null
            ? Map.<Class<? extends Exception>, ExceptionHandler<Exception>>of()
            : parent.exceptionMappings(resolved);
    if (errors.isEmpty()) {
      resolved.put(this, inherited);

      return inherited;
    }

    final var combined = new LinkedHashMap<>(inherited);
    combined.putAll(errors);

    final var frozen = Map.copyOf(combined);
    resolved.put(this, frozen);

    return frozen;
  }

  /**
   * Reads the compiled WebSocket router after registration has ended.
   *
   * @return separate WebSocket path router, or null when no WebSocket route was registered
   */
  @Nullable RadixRoutes websocketRouter() {
    return root.websocketRouter;
  }

  /** Ends registration across all scopes without disposing pending registration resources. */
  void endRegistration() {
    runUnderLock(lock, () -> root.closed = true);
  }

  /**
   * Permanently ends registration for the root and every child scope and releases pending endpoint
   * entries and duplicate-detection keys. The application calls this during cleanup, after ending
   * registration or transferring static resources to the compiled router. Repeated disposal is
   * harmless.
   *
   * <p>Concurrent registration callbacks finish before disposal acquires the shared lock. Clearing
   * entries releases references but does not shrink the collections' backing capacity.
   */
  void disposeRegistration() {
    runUnderLock(
        lock,
        () -> {
          root.closed = true;
          registrations.clear();
          websocketRegistrations.clear();

          try {
            closeStaticFiles();
          } finally {
            staticFiles.clear();
            endpointScopes.clear();
            staticScopes.clear();
            routeKeys.clear();
            websocketRouteKeys.clear();
            notifications.clear();
            installed.clear();
          }
        });
  }

  /** Closes every uncompiled static source before reporting aggregated failures. */
  private void closeStaticFiles() {
    RuntimeException failure = null;
    for (final var files : staticFiles) {
      try {
        files.close();
      } catch (RuntimeException closeFailure) {
        if (failure == null) {
          failure = closeFailure;
        } else {
          failure.addSuppressed(closeFailure);
        }
      }
    }

    if (failure != null) {
      throw failure;
    }
  }

  /**
   * Checks the root's registration state while the caller holds the shared lock.
   *
   * @throws IllegalStateException if startup or closure has ended registration
   */
  private void requireMutable() {
    if (root.closed) {
      throw new IllegalStateException("Register routes before starting or closing the app");
    }
  }

  /**
   * Starts registration while excluding an unfinished application-default callback.
   *
   * @throws IllegalStateException if registration has closed or defaults are still being configured
   */
  private void beginRegistration() {
    requireMutable();
    if (root.configuringDefaults) {
      throw new IllegalStateException("Finish configuring extension defaults before registration");
    }

    root.registrationStarted = true;
  }

  /**
   * Reads completed infrastructure state by exact identity.
   *
   * @param extension selected instance
   * @return whether setup completed
   */
  boolean installed(Extension extension) {
    return Objects.requireNonNull(getUnderLock(lock, () -> installed.contains(extension)));
  }

  /**
   * Checks registration before invoking ownership or setup callbacks.
   *
   * @param extension exact capability
   * @throws IllegalArgumentException if the instance is already installed
   */
  void validateInstallation(Extension extension) {
    runUnderLock(
        lock,
        () -> {
          requireMutable();
          if (installed.contains(extension)) {
            throw new IllegalArgumentException("An extension instance can only be installed once");
          }
        });
  }

  /**
   * Publishes completed infrastructure after rechecking registration.
   *
   * @param extension claimed and configured capability
   */
  void install(Extension extension) {
    runUnderLock(
        lock,
        () -> {
          validateInstallation(extension);
          installed.add(extension);
        });
  }

  /** Runs cold HTTP binding notifications after compilation and before provider finalization. */
  void prepare() {
    for (final var notification : List.copyOf(notifications)) {
      notification.run();
    }
    notifications.clear();
  }

  /**
   * Reports whether selected routes require terminal observation even without app observers.
   *
   * @return whether any bound endpoint has a terminal observer or observation factory
   */
  boolean localObservers() {
    return root.localObservers;
  }

  /**
   * Codec-backed registration sharing the existing router, group-depth limits, availability,
   * extension configuration and caller-owned executor contract. No runtime route view is allocated.
   * Use ordinary Routes for native WebSocket listeners and static resources. Registration resources
   * belong to the application; route scopes have no separate cleanup operation.
   */
  public static final class Typed {
    private final Routes routes;
    private final Codec codec;

    /**
     * Wraps registration only; request/response state is not wrapped or copied.
     *
     * @param routes existing registration scope
     * @param codec configured application conversion
     */
    Typed(Routes routes, Codec codec) {
      this.routes = Objects.requireNonNull(routes);
      this.codec = Objects.requireNonNull(codec);
    }

    /**
     * Adapts only handler static types; both callbacks see the same exchange objects.
     *
     * @param handler typed endpoint handler
     * @return ordinary registration handler
     */
    private static Handler adapt(Handler.Typed handler) {
      Objects.requireNonNull(handler);

      return (request, response) ->
          handler.handle((Request.Typed) request, (Response.Typed) response);
    }

    /**
     * Registers an existing exception handler for this scope and its descendants, including earlier
     * endpoints. Statically typed exception lambdas can use the codec-backed overload.
     *
     * @param type directly thrown exception family
     * @param handler renderer receiving the original failure
     * @param <E> exception type
     * @return this scope
     */
    public <E extends Exception> Typed exception(
        Class<E> type, ExceptionHandler<? super E> handler) {
      routes.registerException(type, handler, true);

      return this;
    }

    /**
     * Registers a renderer with codec-backed request and response helpers for this scope and its
     * descendants, including earlier endpoints. Uses the same scope inheritance and
     * local-before-app selection as ordinary route exception handlers.
     *
     * @param type directly thrown exception family
     * @param handler renderer receiving the original failure and exchange
     * @param <E> exception type
     * @return this scope
     */
    public <E extends Exception> Typed exception(
        Class<E> type, ExceptionHandler.Typed<? super E> handler) {
      return exception(type, (ExceptionHandler<? super E>) handler);
    }

    /**
     * Registers a nested typed group under the same depth and lifetime guards as ordinary routes.
     *
     * @param path relative prefix
     * @param registration child endpoints
     * @return this scope
     */
    public Typed path(String path, Consumer<Typed> registration) {
      Objects.requireNonNull(registration);

      routes.path(path, child -> registration.accept(new Typed(child, codec)));

      return this;
    }

    /**
     * Registers a typed group with independently inherited extension configuration.
     *
     * @param path relative prefix
     * @param registration child endpoints
     * @param configuration group extension configuration
     * @return this scope
     */
    public Typed path(
        String path, Consumer<Typed> registration, Consumer<Lifecycle> configuration) {
      Objects.requireNonNull(registration);

      routes.path(path, child -> registration.accept(new Typed(child, codec)), configuration);

      return this;
    }

    /**
     * Evaluates availability at request time, retaining the existing concealed-404 behavior.
     *
     * @param condition shared runtime availability
     * @param registration conditional endpoints
     * @return this scope
     */
    public Typed when(BooleanSupplier condition, Consumer<Typed> registration) {
      Objects.requireNonNull(registration);

      routes.when(condition, child -> registration.accept(new Typed(child, codec)));

      return this;
    }

    /**
     * Evaluates request-dependent availability on the endpoint's matched executor.
     *
     * @param condition runtime request policy
     * @param registration conditional endpoints
     * @return this scope
     */
    public Typed when(Predicate<Request> condition, Consumer<Typed> registration) {
      Objects.requireNonNull(registration);

      routes.when(condition, child -> registration.accept(new Typed(child, codec)));

      return this;
    }

    /**
     * Registers a typed GET endpoint through the existing registration scope.
     *
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed get(Handler.Typed handler, Consumer<Lifecycle> configuration) {
      return get(EMPTY_PATH, handler, configuration);
    }

    /**
     * Registers a typed GET endpoint through the existing registration scope.
     *
     * @param path relative endpoint path
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed get(String path, Handler.Typed handler, Consumer<Lifecycle> configuration) {
      routes.get(path, adapt(handler), configuration);

      return this;
    }

    /**
     * Registers a typed GET endpoint through the existing registration scope.
     *
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed get(Handler.Typed handler) {
      return get(EMPTY_PATH, handler);
    }

    /**
     * Registers a typed GET endpoint through the existing registration scope.
     *
     * @param path relative endpoint path
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed get(String path, Handler.Typed handler) {
      routes.get(path, adapt(handler));

      return this;
    }

    /**
     * Registers a typed GET endpoint through the existing registration scope.
     *
     * @param path relative endpoint path
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed get(String path, ExecutorService executor, Handler.Typed handler) {
      routes.get(path, executor, adapt(handler));

      return this;
    }

    /**
     * Registers a typed GET endpoint through the existing registration scope.
     *
     * @param path relative endpoint path
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed get(
        String path,
        ExecutorService executor,
        Handler.Typed handler,
        Consumer<Lifecycle> configuration) {
      routes.get(path, executor, adapt(handler), configuration);

      return this;
    }

    /**
     * Registers a typed GET endpoint through the existing registration scope.
     *
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed get(ExecutorService executor, Handler.Typed handler) {
      return get(EMPTY_PATH, executor, handler);
    }

    /**
     * Registers a typed GET endpoint through the existing registration scope.
     *
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed get(
        ExecutorService executor, Handler.Typed handler, Consumer<Lifecycle> configuration) {
      return get(EMPTY_PATH, executor, handler, configuration);
    }

    /**
     * Registers a typed POST endpoint through the existing registration scope.
     *
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed post(Handler.Typed handler, Consumer<Lifecycle> configuration) {
      return post(EMPTY_PATH, handler, configuration);
    }

    /**
     * Registers a typed POST endpoint through the existing registration scope.
     *
     * @param path relative endpoint path
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed post(String path, Handler.Typed handler, Consumer<Lifecycle> configuration) {
      routes.post(path, adapt(handler), configuration);

      return this;
    }

    /**
     * Registers a typed POST endpoint through the existing registration scope.
     *
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed post(Handler.Typed handler) {
      return post(EMPTY_PATH, handler);
    }

    /**
     * Registers a typed POST endpoint through the existing registration scope.
     *
     * @param path relative endpoint path
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed post(String path, Handler.Typed handler) {
      routes.post(path, adapt(handler));

      return this;
    }

    /**
     * Registers a typed POST endpoint through the existing registration scope.
     *
     * @param path relative endpoint path
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed post(String path, ExecutorService executor, Handler.Typed handler) {
      routes.post(path, executor, adapt(handler));

      return this;
    }

    /**
     * Registers a typed POST endpoint through the existing registration scope.
     *
     * @param path relative endpoint path
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed post(
        String path,
        ExecutorService executor,
        Handler.Typed handler,
        Consumer<Lifecycle> configuration) {
      routes.post(path, executor, adapt(handler), configuration);

      return this;
    }

    /**
     * Registers a typed POST endpoint through the existing registration scope.
     *
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed post(ExecutorService executor, Handler.Typed handler) {
      return post(EMPTY_PATH, executor, handler);
    }

    /**
     * Registers a typed POST endpoint through the existing registration scope.
     *
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed post(
        ExecutorService executor, Handler.Typed handler, Consumer<Lifecycle> configuration) {
      return post(EMPTY_PATH, executor, handler, configuration);
    }

    /**
     * Registers a typed PUT endpoint through the existing registration scope.
     *
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed put(Handler.Typed handler, Consumer<Lifecycle> configuration) {
      return put(EMPTY_PATH, handler, configuration);
    }

    /**
     * Registers a typed PUT endpoint through the existing registration scope.
     *
     * @param path relative endpoint path
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed put(String path, Handler.Typed handler, Consumer<Lifecycle> configuration) {
      routes.put(path, adapt(handler), configuration);

      return this;
    }

    /**
     * Registers a typed PUT endpoint through the existing registration scope.
     *
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed put(Handler.Typed handler) {
      return put(EMPTY_PATH, handler);
    }

    /**
     * Registers a typed PUT endpoint through the existing registration scope.
     *
     * @param path relative endpoint path
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed put(String path, Handler.Typed handler) {
      routes.put(path, adapt(handler));

      return this;
    }

    /**
     * Registers a typed PUT endpoint through the existing registration scope.
     *
     * @param path relative endpoint path
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed put(String path, ExecutorService executor, Handler.Typed handler) {
      routes.put(path, executor, adapt(handler));

      return this;
    }

    /**
     * Registers a typed PUT endpoint through the existing registration scope.
     *
     * @param path relative endpoint path
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed put(
        String path,
        ExecutorService executor,
        Handler.Typed handler,
        Consumer<Lifecycle> configuration) {
      routes.put(path, executor, adapt(handler), configuration);

      return this;
    }

    /**
     * Registers a typed PUT endpoint through the existing registration scope.
     *
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed put(ExecutorService executor, Handler.Typed handler) {
      return put(EMPTY_PATH, executor, handler);
    }

    /**
     * Registers a typed PUT endpoint through the existing registration scope.
     *
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed put(
        ExecutorService executor, Handler.Typed handler, Consumer<Lifecycle> configuration) {
      return put(EMPTY_PATH, executor, handler, configuration);
    }

    /**
     * Registers a typed PATCH endpoint through the existing registration scope.
     *
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed patch(Handler.Typed handler, Consumer<Lifecycle> configuration) {
      return patch(EMPTY_PATH, handler, configuration);
    }

    /**
     * Registers a typed PATCH endpoint through the existing registration scope.
     *
     * @param path relative endpoint path
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed patch(String path, Handler.Typed handler, Consumer<Lifecycle> configuration) {
      routes.patch(path, adapt(handler), configuration);

      return this;
    }

    /**
     * Registers a typed PATCH endpoint through the existing registration scope.
     *
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed patch(Handler.Typed handler) {
      return patch(EMPTY_PATH, handler);
    }

    /**
     * Registers a typed PATCH endpoint through the existing registration scope.
     *
     * @param path relative endpoint path
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed patch(String path, Handler.Typed handler) {
      routes.patch(path, adapt(handler));

      return this;
    }

    /**
     * Registers a typed PATCH endpoint through the existing registration scope.
     *
     * @param path relative endpoint path
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed patch(String path, ExecutorService executor, Handler.Typed handler) {
      routes.patch(path, executor, adapt(handler));

      return this;
    }

    /**
     * Registers a typed PATCH endpoint through the existing registration scope.
     *
     * @param path relative endpoint path
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed patch(
        String path,
        ExecutorService executor,
        Handler.Typed handler,
        Consumer<Lifecycle> configuration) {
      routes.patch(path, executor, adapt(handler), configuration);

      return this;
    }

    /**
     * Registers a typed PATCH endpoint through the existing registration scope.
     *
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed patch(ExecutorService executor, Handler.Typed handler) {
      return patch(EMPTY_PATH, executor, handler);
    }

    /**
     * Registers a typed PATCH endpoint through the existing registration scope.
     *
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed patch(
        ExecutorService executor, Handler.Typed handler, Consumer<Lifecycle> configuration) {
      return patch(EMPTY_PATH, executor, handler, configuration);
    }

    /**
     * Registers a typed DELETE endpoint through the existing registration scope.
     *
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed delete(Handler.Typed handler, Consumer<Lifecycle> configuration) {
      return delete(EMPTY_PATH, handler, configuration);
    }

    /**
     * Registers a typed DELETE endpoint through the existing registration scope.
     *
     * @param path relative endpoint path
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed delete(String path, Handler.Typed handler, Consumer<Lifecycle> configuration) {
      routes.delete(path, adapt(handler), configuration);

      return this;
    }

    /**
     * Registers a typed DELETE endpoint through the existing registration scope.
     *
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed delete(Handler.Typed handler) {
      return delete(EMPTY_PATH, handler);
    }

    /**
     * Registers a typed DELETE endpoint through the existing registration scope.
     *
     * @param path relative endpoint path
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed delete(String path, Handler.Typed handler) {
      routes.delete(path, adapt(handler));

      return this;
    }

    /**
     * Registers a typed DELETE endpoint through the existing registration scope.
     *
     * @param path relative endpoint path
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed delete(String path, ExecutorService executor, Handler.Typed handler) {
      routes.delete(path, executor, adapt(handler));

      return this;
    }

    /**
     * Registers a typed DELETE endpoint through the existing registration scope.
     *
     * @param path relative endpoint path
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed delete(
        String path,
        ExecutorService executor,
        Handler.Typed handler,
        Consumer<Lifecycle> configuration) {
      routes.delete(path, executor, adapt(handler), configuration);

      return this;
    }

    /**
     * Registers a typed DELETE endpoint through the existing registration scope.
     *
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed delete(ExecutorService executor, Handler.Typed handler) {
      return delete(EMPTY_PATH, executor, handler);
    }

    /**
     * Registers a typed DELETE endpoint through the existing registration scope.
     *
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed delete(
        ExecutorService executor, Handler.Typed handler, Consumer<Lifecycle> configuration) {
      return delete(EMPTY_PATH, executor, handler, configuration);
    }

    /**
     * Registers a typed HEAD endpoint through the existing registration scope.
     *
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed head(Handler.Typed handler, Consumer<Lifecycle> configuration) {
      return head(EMPTY_PATH, handler, configuration);
    }

    /**
     * Registers a typed HEAD endpoint through the existing registration scope.
     *
     * @param path relative endpoint path
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed head(String path, Handler.Typed handler, Consumer<Lifecycle> configuration) {
      routes.head(path, adapt(handler), configuration);

      return this;
    }

    /**
     * Registers a typed HEAD endpoint through the existing registration scope.
     *
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed head(Handler.Typed handler) {
      return head(EMPTY_PATH, handler);
    }

    /**
     * Registers a typed HEAD endpoint through the existing registration scope.
     *
     * @param path relative endpoint path
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed head(String path, Handler.Typed handler) {
      routes.head(path, adapt(handler));

      return this;
    }

    /**
     * Registers a typed HEAD endpoint through the existing registration scope.
     *
     * @param path relative endpoint path
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed head(String path, ExecutorService executor, Handler.Typed handler) {
      routes.head(path, executor, adapt(handler));

      return this;
    }

    /**
     * Registers a typed HEAD endpoint through the existing registration scope.
     *
     * @param path relative endpoint path
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed head(
        String path,
        ExecutorService executor,
        Handler.Typed handler,
        Consumer<Lifecycle> configuration) {
      routes.head(path, executor, adapt(handler), configuration);

      return this;
    }

    /**
     * Registers a typed HEAD endpoint through the existing registration scope.
     *
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed head(ExecutorService executor, Handler.Typed handler) {
      return head(EMPTY_PATH, executor, handler);
    }

    /**
     * Registers a typed HEAD endpoint through the existing registration scope.
     *
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed head(
        ExecutorService executor, Handler.Typed handler, Consumer<Lifecycle> configuration) {
      return head(EMPTY_PATH, executor, handler, configuration);
    }

    /**
     * Registers a typed OPTIONS endpoint through the existing registration scope.
     *
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed options(Handler.Typed handler, Consumer<Lifecycle> configuration) {
      return options(EMPTY_PATH, handler, configuration);
    }

    /**
     * Registers a typed OPTIONS endpoint through the existing registration scope.
     *
     * @param path relative endpoint path
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed options(String path, Handler.Typed handler, Consumer<Lifecycle> configuration) {
      routes.options(path, adapt(handler), configuration);

      return this;
    }

    /**
     * Registers a typed OPTIONS endpoint through the existing registration scope.
     *
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed options(Handler.Typed handler) {
      return options(EMPTY_PATH, handler);
    }

    /**
     * Registers a typed OPTIONS endpoint through the existing registration scope.
     *
     * @param path relative endpoint path
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed options(String path, Handler.Typed handler) {
      routes.options(path, adapt(handler));

      return this;
    }

    /**
     * Registers a typed OPTIONS endpoint through the existing registration scope.
     *
     * @param path relative endpoint path
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed options(String path, ExecutorService executor, Handler.Typed handler) {
      routes.options(path, executor, adapt(handler));

      return this;
    }

    /**
     * Registers a typed OPTIONS endpoint through the existing registration scope.
     *
     * @param path relative endpoint path
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed options(
        String path,
        ExecutorService executor,
        Handler.Typed handler,
        Consumer<Lifecycle> configuration) {
      routes.options(path, executor, adapt(handler), configuration);

      return this;
    }

    /**
     * Registers a typed OPTIONS endpoint through the existing registration scope.
     *
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed options(ExecutorService executor, Handler.Typed handler) {
      return options(EMPTY_PATH, executor, handler);
    }

    /**
     * Registers a typed OPTIONS endpoint through the existing registration scope.
     *
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed options(
        ExecutorService executor, Handler.Typed handler, Consumer<Lifecycle> configuration) {
      return options(EMPTY_PATH, executor, handler, configuration);
    }

    /**
     * Registers a typed HTTP endpoint through the existing registration scope.
     *
     * @param method recognized HTTP method
     * @param path relative endpoint path
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed route(HttpMethods method, String path, Handler.Typed handler) {
      routes.route(method, path, adapt(handler));

      return this;
    }

    /**
     * Registers a typed HTTP endpoint through the existing registration scope.
     *
     * @param method recognized HTTP method
     * @param path relative endpoint path
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed route(
        HttpMethods method, String path, Handler.Typed handler, Consumer<Lifecycle> configuration) {
      routes.route(method, path, adapt(handler), configuration);

      return this;
    }

    /**
     * Registers a typed HTTP endpoint through the existing registration scope.
     *
     * @param method recognized HTTP method
     * @param path relative endpoint path
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed route(String method, String path, Handler.Typed handler) {
      routes.route(method, path, adapt(handler));

      return this;
    }

    /**
     * Registers a typed HTTP endpoint through the existing registration scope.
     *
     * @param method recognized HTTP method
     * @param path relative endpoint path
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed route(
        String method, String path, Handler.Typed handler, Consumer<Lifecycle> configuration) {
      routes.route(method, path, adapt(handler), configuration);

      return this;
    }

    /**
     * Registers a typed HTTP endpoint through the existing registration scope.
     *
     * @param method recognized HTTP method
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed route(
        HttpMethods method, Handler.Typed handler, Consumer<Lifecycle> configuration) {
      return route(method, EMPTY_PATH, handler, configuration);
    }

    /**
     * Registers a typed HTTP endpoint through the existing registration scope.
     *
     * @param method recognized HTTP method
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed route(String method, Handler.Typed handler, Consumer<Lifecycle> configuration) {
      return route(method, EMPTY_PATH, handler, configuration);
    }

    /**
     * Registers a typed HTTP endpoint through the existing registration scope.
     *
     * @param method recognized HTTP method
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed route(HttpMethods method, Handler.Typed handler) {
      return route(method, EMPTY_PATH, handler);
    }

    /**
     * Registers a typed HTTP endpoint through the existing registration scope.
     *
     * @param method recognized HTTP method
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed route(String method, Handler.Typed handler) {
      return route(method, EMPTY_PATH, handler);
    }

    /**
     * Registers a typed HTTP endpoint through the existing registration scope.
     *
     * @param method recognized HTTP method
     * @param path relative endpoint path
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed route(
        HttpMethods method, String path, ExecutorService executor, Handler.Typed handler) {
      routes.route(method, path, executor, adapt(handler));

      return this;
    }

    /**
     * Registers a typed HTTP endpoint through the existing registration scope.
     *
     * @param method recognized HTTP method
     * @param path relative endpoint path
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed route(
        HttpMethods method,
        String path,
        ExecutorService executor,
        Handler.Typed handler,
        Consumer<Lifecycle> configuration) {
      routes.route(method, path, executor, adapt(handler), configuration);

      return this;
    }

    /**
     * Registers a typed HTTP endpoint through the existing registration scope.
     *
     * @param method recognized HTTP method
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed route(HttpMethods method, ExecutorService executor, Handler.Typed handler) {
      return route(method, EMPTY_PATH, executor, handler);
    }

    /**
     * Registers a typed HTTP endpoint through the existing registration scope.
     *
     * @param method recognized HTTP method
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed route(
        HttpMethods method,
        ExecutorService executor,
        Handler.Typed handler,
        Consumer<Lifecycle> configuration) {
      return route(method, EMPTY_PATH, executor, handler, configuration);
    }

    /**
     * Registers a typed HTTP endpoint through the existing registration scope.
     *
     * @param method recognized HTTP method
     * @param path relative endpoint path
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed route(
        String method, String path, ExecutorService executor, Handler.Typed handler) {
      routes.route(method, path, executor, adapt(handler));

      return this;
    }

    /**
     * Registers a typed HTTP endpoint through the existing registration scope.
     *
     * @param method recognized HTTP method
     * @param path relative endpoint path
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed route(
        String method,
        String path,
        ExecutorService executor,
        Handler.Typed handler,
        Consumer<Lifecycle> configuration) {
      routes.route(method, path, executor, adapt(handler), configuration);

      return this;
    }

    /**
     * Registers a typed HTTP endpoint through the existing registration scope.
     *
     * @param method recognized HTTP method
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @return this scope
     */
    public Typed route(String method, ExecutorService executor, Handler.Typed handler) {
      return route(method, EMPTY_PATH, executor, handler);
    }

    /**
     * Registers a typed HTTP endpoint through the existing registration scope.
     *
     * @param method recognized HTTP method
     * @param executor caller-owned asynchronous executor for the matched lifecycle
     * @param handler codec-backed endpoint handler
     * @param configuration local extension configuration
     * @return this scope
     */
    public Typed route(
        String method,
        ExecutorService executor,
        Handler.Typed handler,
        Consumer<Lifecycle> configuration) {
      return route(method, EMPTY_PATH, executor, handler, configuration);
    }

    /**
     * Registers a codec-enabled managed WebSocket. Object handlers receive binary messages;
     * ordinary text and binary callbacks remain available.
     *
     * @param path relative endpoint path
     * @param configuration eager callback configuration
     * @return this scope
     */
    public Typed websocket(String path, Consumer<WebSocket.Typed> configuration) {
      return registerWebsocket(path, null, configuration, null);
    }

    /**
     * Registers an object-capable WebSocket at this group's endpoint.
     *
     * @param configuration eager callback configuration
     * @return this scope
     */
    public Typed websocket(Consumer<WebSocket.Typed> configuration) {
      return websocket("", configuration);
    }

    /**
     * Registers an object-capable WebSocket with local handshake extensions.
     *
     * @param path relative endpoint path
     * @param configuration eager callback configuration
     * @param extensions local handshake extensions
     * @return this scope
     */
    public Typed websocket(
        String path, Consumer<WebSocket.Typed> configuration, Consumer<Lifecycle> extensions) {
      return registerWebsocket(path, null, configuration, Objects.requireNonNull(extensions));
    }

    /**
     * Registers this group's WebSocket with local handshake extensions.
     *
     * @param configuration eager callback configuration
     * @param extensions local handshake extensions
     * @return this scope
     */
    public Typed websocket(
        Consumer<WebSocket.Typed> configuration, Consumer<Lifecycle> extensions) {
      return websocket("", configuration, extensions);
    }

    /**
     * Selects a borrowed HTTP handshake executor. Managed callbacks use the application's native
     * execution mode: platform threads by default, with {@link Options#setVirtualThreads(Boolean)}
     * for virtual execution.
     *
     * @param path relative endpoint path
     * @param executor caller-owned handshake executor
     * @param configuration eager callback configuration
     * @return this scope
     */
    public Typed websocket(
        String path, ExecutorService executor, Consumer<WebSocket.Typed> configuration) {
      return registerWebsocket(path, Objects.requireNonNull(executor), configuration, null);
    }

    /**
     * Selects a borrowed handshake executor and local extensions.
     *
     * @param path relative endpoint path
     * @param executor caller-owned handshake executor
     * @param configuration eager callback configuration
     * @param extensions local handshake extensions
     * @return this scope
     */
    public Typed websocket(
        String path,
        ExecutorService executor,
        Consumer<WebSocket.Typed> configuration,
        Consumer<Lifecycle> extensions) {
      return registerWebsocket(
          path,
          Objects.requireNonNull(executor),
          configuration,
          Objects.requireNonNull(extensions));
    }

    /**
     * Registers this group's WebSocket using a borrowed handshake executor.
     *
     * @param executor caller-owned handshake executor
     * @param configuration eager callback configuration
     * @return this scope
     */
    public Typed websocket(ExecutorService executor, Consumer<WebSocket.Typed> configuration) {
      return websocket("", executor, configuration);
    }

    /**
     * Registers this group's WebSocket with a borrowed executor and local extensions.
     *
     * @param executor caller-owned handshake executor
     * @param configuration eager callback configuration
     * @param extensions local handshake extensions
     * @return this scope
     */
    public Typed websocket(
        ExecutorService executor,
        Consumer<WebSocket.Typed> configuration,
        Consumer<Lifecycle> extensions) {
      return websocket("", executor, configuration, extensions);
    }

    /**
     * Shares managed registration without changing eager callback ordering or scope ownership.
     *
     * @param path relative endpoint path
     * @param executor optional borrowed handshake executor
     * @param configuration eager callback configuration
     * @param extensions optional local handshake extensions
     * @return this scope
     */
    private Typed registerWebsocket(
        String path,
        @Nullable ExecutorService executor,
        Consumer<WebSocket.Typed> configuration,
        @Nullable Consumer<Lifecycle> extensions) {
      Objects.requireNonNull(configuration);

      var callbacks = new WebSocket.Typed(codec);

      routes.registerManagedWebsocket(
          path, executor, callbacks, ignored -> configuration.accept(callbacks), extensions);

      return this;
    }

    /**
     * Registers an SSE endpoint with codec-enabled request, response and event output.
     *
     * @param path relative endpoint path
     * @param handler handler-scoped event producer
     * @return this scope
     */
    public Typed sse(String path, Handler.Typed handler) {
      routes.sse(path, adapt(handler));

      return this;
    }

    /**
     * Registers SSE at this group's endpoint.
     *
     * @param handler handler-scoped event producer
     * @return this scope
     */
    public Typed sse(Handler.Typed handler) {
      return sse(EMPTY_PATH, handler);
    }

    /**
     * Registers SSE with local extension configuration.
     *
     * @param path relative endpoint path
     * @param handler handler-scoped event producer
     * @param configuration local extensions
     * @return this scope
     */
    public Typed sse(String path, Handler.Typed handler, Consumer<Lifecycle> configuration) {
      routes.sse(path, adapt(handler), configuration);

      return this;
    }

    /**
     * Registers this group's SSE endpoint with local extensions.
     *
     * @param handler handler-scoped event producer
     * @param configuration local extensions
     * @return this scope
     */
    public Typed sse(Handler.Typed handler, Consumer<Lifecycle> configuration) {
      return sse(EMPTY_PATH, handler, configuration);
    }

    /**
     * Registers SSE on a caller-owned executor without changing handler-scoped ownership.
     *
     * @param path relative endpoint path
     * @param executor borrowed handler executor
     * @param handler handler-scoped event producer
     * @return this scope
     */
    public Typed sse(String path, ExecutorService executor, Handler.Typed handler) {
      routes.sse(path, executor, adapt(handler));

      return this;
    }

    /**
     * Registers SSE on a caller-owned executor with local extensions.
     *
     * @param path relative endpoint path
     * @param executor borrowed handler executor
     * @param handler handler-scoped event producer
     * @param configuration local extensions
     * @return this scope
     */
    public Typed sse(
        String path,
        ExecutorService executor,
        Handler.Typed handler,
        Consumer<Lifecycle> configuration) {
      routes.sse(path, executor, adapt(handler), configuration);

      return this;
    }

    /**
     * Registers this group's SSE endpoint on a caller-owned executor.
     *
     * @param executor borrowed handler executor
     * @param handler handler-scoped event producer
     * @return this scope
     */
    public Typed sse(ExecutorService executor, Handler.Typed handler) {
      return sse(EMPTY_PATH, executor, handler);
    }

    /**
     * Registers this group's SSE endpoint with a borrowed executor and local extensions.
     *
     * @param executor borrowed handler executor
     * @param handler handler-scoped event producer
     * @param configuration local extensions
     * @return this scope
     */
    public Typed sse(
        ExecutorService executor, Handler.Typed handler, Consumer<Lifecycle> configuration) {
      return sse(EMPTY_PATH, executor, handler, configuration);
    }
  }
}
