package io.github.suppierk.shoostr;

import io.github.suppierk.shoostr.http.HttpCharacters;
import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.http.HttpStatusCodes;
import java.io.Closeable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.server.ServerUpgradeResponse;
import org.jspecify.annotations.Nullable;

/**
 * Thread-safe route registration with shared path scopes closed when the app starts. Concurrent
 * registrations are serialized by the shared lock; literal segments take matching precedence.
 * Closing any scope ends registration for the entire app; it does not stop the running server.
 */
public final class Routes implements Closeable {
  static final String EMPTY_PATH = "";
  private final Routes root;
  private final Object lock;
  private final String prefix;
  private final int scopeDepth;
  private final ThreadLocal<Integer> activeGroupDepth;
  private final List<Predicate<Request>> conditions;
  private final @Nullable Extensions configuration;
  private final Set<Extension<?>> installed;
  private final List<Runnable> notifications;
  private boolean localObservers;
  private final List<RadixRoutes.Endpoint> registrations;
  private final List<RadixRoutes.Endpoint> websocketRegistrations;
  private final List<StaticFiles> staticFiles;
  private final Set<String> routeKeys;
  private final Set<String> websocketRouteKeys;
  private @Nullable RadixRoutes websocketRouter;
  private int activeRegistrations;
  private boolean closed;

  /** Creates the root scope, which owns registration state and the lock shared by all scopes. */
  Routes() {
    root = this;
    lock = new Object();
    prefix = EMPTY_PATH;
    scopeDepth = 0;
    activeGroupDepth = ThreadLocal.withInitial(() -> 0);
    conditions = List.of();
    configuration = null;
    installed = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    notifications = new ArrayList<>();
    registrations = new ArrayList<>();
    websocketRegistrations = new ArrayList<>();
    staticFiles = new ArrayList<>();
    routeKeys = new HashSet<>();
    websocketRouteKeys = new HashSet<>();
  }

  /**
   * Creates a path view over the root's registration state, without copying its entries.
   *
   * @param root owning root scope, not an intermediate path scope
   * @param prefix composed prefix without its optional trailing separator
   * @param conditions immutable runtime availability checks
   * @param configuration inherited extension configuration, or null
   * @param scopeDepth effective path/when ancestry, from one to ten
   */
  private Routes(
      Routes root,
      String prefix,
      List<Predicate<Request>> conditions,
      @Nullable Extensions configuration,
      int scopeDepth) {
    this.root = root;
    lock = root.lock;
    this.prefix = prefix;
    this.scopeDepth = scopeDepth;
    activeGroupDepth = root.activeGroupDepth;
    this.conditions = List.copyOf(conditions);
    this.configuration = configuration;
    installed = root.installed;
    notifications = root.notifications;
    registrations = root.registrations;
    websocketRegistrations = root.websocketRegistrations;
    staticFiles = root.staticFiles;
    routeKeys = root.routeKeys;
    websocketRouteKeys = root.websocketRouteKeys;
  }

  /**
   * Invokes a root registration callback while preventing startup until it returns.
   *
   * @param registration callback receiving this pre-created root scope
   */
  void register(Consumer<Routes> registration) {
    synchronized (lock) {
      requireMutable();
      Objects.requireNonNull(registration);
      root.activeRegistrations++;
    }

    try {
      registration.accept(this);
    } finally {
      synchronized (lock) {
        root.activeRegistrations--;
      }
    }
  }

  /**
   * Registers a nested group immediately. The callback runs without holding the registration lock;
   * concurrent groups may interleave their endpoint registrations. Startup is rejected while any
   * callback is active.
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
      String path, Consumer<Routes> registration, Consumer<Extensions> configuration) {
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
      String path, Consumer<Routes> registration, @Nullable Consumer<Extensions> configure) {
    String group;
    int previousDepth = groupDepth();
    int nextDepth = nextGroupDepth(previousDepth);
    synchronized (lock) {
      requireMutable();
      Objects.requireNonNull(registration);
      group = join(prefix, path);
      RadixRoutes.parameters(group);
      if (group.charAt(group.length() - 1) == HttpCharacters.PATH_SEPARATOR) {
        group = group.substring(0, group.length() - 1);
      }

      root.activeRegistrations++;
    }
    activeGroupDepth.set(nextDepth);
    Extensions local = null;

    try {
      local =
          configuration == null && configure == null
              ? null
              : new Extensions(installed, configuration);
      if (local != null) {
        synchronized (lock) {
          requireMutable();
        }
      }

      if (configure != null) {
        configure.accept(Objects.requireNonNull(local));
        synchronized (lock) {
          requireMutable();
        }
      }

      registration.accept(new Routes(root, group, conditions, local, nextDepth));
    } finally {
      if (local != null) {
        local.freeze();
      }

      restoreGroupDepth(previousDepth);

      synchronized (lock) {
        root.activeRegistrations--;
      }
    }

    return this;
  }

  /**
   * Reads this app's active group context for the calling thread.
   *
   * @return zero outside a group callback, otherwise effective group depth
   */
  private int groupDepth() {
    return activeGroupDepth.get();
  }

  /**
   * Combines scope ancestry with captured-root grouping before invoking callbacks.
   *
   * @param previousDepth calling thread's active grouping depth
   * @return permitted child depth
   * @throws IllegalStateException if the eleventh nested group is attempted
   */
  private int nextGroupDepth(int previousDepth) {
    int depth = Math.max(scopeDepth, previousDepth) + 1;
    if (depth > 10) {
      throw new IllegalStateException("Route groups cannot exceed 10 nested path/when scopes");
    }

    return depth;
  }

  /**
   * Restores the enclosing group, removing thread-local state at the root boundary.
   *
   * @param previousDepth context before this group's callback
   */
  private void restoreGroupDepth(int previousDepth) {
    if (previousDepth == 0) {
      activeGroupDepth.remove();
    } else {
      activeGroupDepth.set(previousDepth);
    }
  }

  /**
   * Registers GET with local extension configuration at this group's endpoint.
   *
   * @param handler endpoint handler
   * @param configuration typed local configuration
   * @return this scope
   */
  public Routes get(Handler handler, Consumer<Extensions> configuration) {
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
  public Routes get(String path, Handler handler, Consumer<Extensions> configuration) {
    return route(HttpMethods.GET, path, handler, configuration);
  }

  /**
   * Registers POST with local extension configuration at this group's endpoint.
   *
   * @param handler endpoint handler
   * @param configuration typed local configuration
   * @return this scope
   */
  public Routes post(Handler handler, Consumer<Extensions> configuration) {
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
  public Routes post(String path, Handler handler, Consumer<Extensions> configuration) {
    return route(HttpMethods.POST, path, handler, configuration);
  }

  /**
   * Registers PUT with local extension configuration at this group's endpoint.
   *
   * @param handler endpoint handler
   * @param configuration typed local configuration
   * @return this scope
   */
  public Routes put(Handler handler, Consumer<Extensions> configuration) {
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
  public Routes put(String path, Handler handler, Consumer<Extensions> configuration) {
    return route(HttpMethods.PUT, path, handler, configuration);
  }

  /**
   * Registers PATCH with local extension configuration at this group's endpoint.
   *
   * @param handler endpoint handler
   * @param configuration typed local configuration
   * @return this scope
   */
  public Routes patch(Handler handler, Consumer<Extensions> configuration) {
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
  public Routes patch(String path, Handler handler, Consumer<Extensions> configuration) {
    return route(HttpMethods.PATCH, path, handler, configuration);
  }

  /**
   * Registers DELETE with local extension configuration at this group's endpoint.
   *
   * @param handler endpoint handler
   * @param configuration typed local configuration
   * @return this scope
   */
  public Routes delete(Handler handler, Consumer<Extensions> configuration) {
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
  public Routes delete(String path, Handler handler, Consumer<Extensions> configuration) {
    return route(HttpMethods.DELETE, path, handler, configuration);
  }

  /**
   * Registers HEAD with local extension configuration at this group's endpoint.
   *
   * @param handler endpoint handler
   * @param configuration typed local configuration
   * @return this scope
   */
  public Routes head(Handler handler, Consumer<Extensions> configuration) {
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
  public Routes head(String path, Handler handler, Consumer<Extensions> configuration) {
    return route(HttpMethods.HEAD, path, handler, configuration);
  }

  /**
   * Registers OPTIONS with local extension configuration at this group's endpoint.
   *
   * @param handler endpoint handler
   * @param configuration typed local configuration
   * @return this scope
   */
  public Routes options(Handler handler, Consumer<Extensions> configuration) {
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
  public Routes options(String path, Handler handler, Consumer<Extensions> configuration) {
    return route(HttpMethods.OPTIONS, path, handler, configuration);
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
   * Registers routes whose availability is evaluated for each matched request after admission.
   * False produces terminal 404. Registration, method discovery and CORS remain unchanged.
   *
   * @param condition live application flag
   * @param registration routes under the condition
   * @return this scope
   */
  public Routes when(BooleanSupplier condition, Consumer<Routes> registration) {
    Objects.requireNonNull(condition);
    return when(_ -> condition.getAsBoolean(), registration);
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
    Routes child;
    int previousDepth = groupDepth();
    int nextDepth = nextGroupDepth(previousDepth);
    synchronized (lock) {
      requireMutable();
      Objects.requireNonNull(registration);
      var inherited = new ArrayList<>(conditions);
      inherited.add(Objects.requireNonNull(condition));
      child = new Routes(root, prefix, inherited, configuration, nextDepth);
      root.activeRegistrations++;
    }
    activeGroupDepth.set(nextDepth);

    try {
      registration.accept(child);
    } finally {
      restoreGroupDepth(previousDepth);
      synchronized (lock) {
        root.activeRegistrations--;
      }
    }

    return this;
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
    return registerSse(path, handler, null);
  }

  /**
   * Registers an event stream with typed local configuration.
   *
   * @param path relative path
   * @param handler event-stream handler
   * @param configuration endpoint configuration
   * @return this scope
   */
  public Routes sse(String path, Handler handler, Consumer<Extensions> configuration) {
    return registerSse(path, handler, Objects.requireNonNull(configuration));
  }

  /**
   * Registers an event stream at this group's endpoint with local configuration.
   *
   * @param handler event-stream handler
   * @param configuration endpoint configuration
   * @return this scope
   */
  public Routes sse(Handler handler, Consumer<Extensions> configuration) {
    return sse(EMPTY_PATH, handler, configuration);
  }

  /**
   * Keeps the event-stream contract shared by both registration forms.
   *
   * @param path relative path
   * @param handler submitted handler
   * @param configure optional local configuration
   * @return this scope
   */
  private Routes registerSse(
      String path, Handler handler, @Nullable Consumer<Extensions> configure) {
    Objects.requireNonNull(handler);
    return registerRoute(
        HttpMethods.GET,
        path,
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
    return registerWebsocket(path, listenerFactory, null);
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
      Consumer<Extensions> configuration) {
    return registerWebsocket(path, listenerFactory, Objects.requireNonNull(configuration));
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
      Consumer<Extensions> configuration) {
    return websocket(EMPTY_PATH, listenerFactory, configuration);
  }

  /**
   * Validates and publishes one configured upgrade endpoint.
   *
   * @param path relative path
   * @param listenerFactory per-connection factory
   * @param configure optional local configuration
   * @return this scope
   * @throws IllegalArgumentException if the WebSocket shape is already registered
   */
  private Routes registerWebsocket(
      String path,
      BiFunction<Request, ServerUpgradeResponse, Session.Listener> listenerFactory,
      @Nullable Consumer<Extensions> configure) {
    RadixRoutes.Endpoint endpoint;
    synchronized (lock) {
      requireMutable();
      endpoint =
          RadixRoutes.websocketEndpoint(
              join(prefix, path), (_, _) -> {}, Objects.requireNonNull(listenerFactory));
    }
    return registerEndpoint(endpoint, configure, true);
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
    return registerRoute(method, path, handler, null);
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
      HttpMethods method, String path, Handler handler, Consumer<Extensions> configuration) {
    return registerRoute(method, path, handler, Objects.requireNonNull(configuration));
  }

  /**
   * Validates collisions before configuring and publishing one endpoint.
   *
   * @param method HTTP method
   * @param path relative path
   * @param handler submitted handler
   * @param configure optional endpoint configuration
   * @return this scope
   * @throws IllegalArgumentException if the route shape already exists
   */
  private Routes registerRoute(
      HttpMethods method, String path, Handler handler, @Nullable Consumer<Extensions> configure) {
    RadixRoutes.Endpoint endpoint;
    synchronized (lock) {
      requireMutable();
      endpoint = RadixRoutes.endpoint(Objects.requireNonNull(method), join(prefix, path), handler);
    }
    return registerEndpoint(endpoint, configure, false);
  }

  /**
   * Reserves a shape before callbacks, then publishes only while registration is still open.
   * User/provider callbacks execute outside the lock, with startup rejected until they finish.
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
      RadixRoutes.Endpoint endpoint, @Nullable Consumer<Extensions> configure, boolean websocket) {
    var keys = websocket ? websocketRouteKeys : routeKeys;
    var entries = websocket ? websocketRegistrations : registrations;
    String key = websocket ? endpoint.pattern() : endpoint.method() + " " + endpoint.pattern();
    synchronized (lock) {
      requireMutable();
      if (!keys.add(key)) {
        throw new IllegalArgumentException("Duplicate route shape: " + key);
      }

      if (configuration == null && configure == null && conditions.isEmpty()) {

        entries.add(endpoint);
        return this;
      }

      root.activeRegistrations++;
    }
    List<Runnable> facts =
        configuration == null && configure == null ? List.of() : new ArrayList<>();

    try {
      var behavior =
          behavior(websocket ? null : endpoint.method(), endpoint.routePattern(), configure, facts);
      var enriched = endpoint.withBehavior(behavior);
      synchronized (lock) {
        requireMutable();
        entries.add(enriched);
        publish(behavior, facts);
      }
      return this;
    } catch (RuntimeException | Error failure) {
      synchronized (lock) {
        keys.remove(key);
      }
      throw failure;
    } finally {
      synchronized (lock) {
        root.activeRegistrations--;
      }
    }
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
    HttpMethods parsed;
    synchronized (lock) {
      requireMutable();
      Objects.requireNonNull(method);
      parsed =
          HttpMethods.httpMethod(method)
              .orElseThrow(
                  () -> new IllegalArgumentException("Unrecognized HTTP method: " + method));
    }
    return route(parsed, path, handler);
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
      String method, String path, Handler handler, Consumer<Extensions> configuration) {
    Objects.requireNonNull(method);
    return route(
        HttpMethods.httpMethod(method)
            .orElseThrow(() -> new IllegalArgumentException("Unrecognized HTTP method: " + method)),
        path,
        handler,
        configuration);
  }

  /**
   * Registers an enum method with local configuration at this group's endpoint.
   *
   * @param method HTTP method
   * @param handler endpoint handler
   * @param configuration local configuration
   * @return this scope
   */
  public Routes route(HttpMethods method, Handler handler, Consumer<Extensions> configuration) {
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
  public Routes route(String method, Handler handler, Consumer<Extensions> configuration) {
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
   * Anchors a source and configures mount callbacks outside the registration lock.
   *
   * @param path relative mount
   * @param source creates the owned static source
   * @return this scope
   */
  @SuppressWarnings(
      "java:S1181") // Close unpublished resources on Error without replacing its cause.
  private Routes staticSource(String path, Function<String, StaticFiles> source) {
    String mount;
    synchronized (lock) {
      requireMutable();
      mount = staticMount(path);
      root.activeRegistrations++;
    }
    StaticFiles files = null;

    try {
      files = source.apply(mount);
      var behavior = behavior(null, mount, null, List.of());
      files.behavior(behavior);
      synchronized (lock) {
        requireMutable();
        staticFiles.add(files);
        publish(behavior, List.of());
      }
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
    } finally {
      synchronized (lock) {
        root.activeRegistrations--;
      }
    }
  }

  /**
   * Compiles the immutable request router and ends registration before releasing the shared lock.
   * Static mounts transfer to the compiled router; other registration data is released. Active
   * callbacks are rejected before compilation, allowing startup to be retried once they finish.
   *
   * @return the completed router with literal-first endpoint matching
   * @throws IllegalStateException if registration is closed or a route registration callback is
   *     active
   */
  RadixRoutes compile() {
    synchronized (lock) {
      requireMutable();
      if (root.activeRegistrations != 0) {
        throw new IllegalStateException("Finish route registration callbacks before starting");
      }

      var router = RadixRoutes.from(registrations, staticFiles);
      root.websocketRouter =
          websocketRegistrations.isEmpty() ? null : RadixRoutes.from(websocketRegistrations);
      root.closed = true;
      registrations.clear();
      websocketRegistrations.clear();
      staticFiles.clear();
      routeKeys.clear();
      websocketRouteKeys.clear();
      installed.clear();
      return router;
    }
  }

  /**
   * Reads the compiled WebSocket router after registration has ended.
   *
   * @return separate WebSocket path router, or null when no WebSocket route was registered
   */
  @Nullable RadixRoutes websocketRouter() {
    return root.websocketRouter;
  }

  /**
   * Permanently ends registration for the root and every child scope and releases pending endpoint
   * entries and duplicate-detection keys. Repeated calls are harmless, including calls through
   * different scopes. Closing before startup prevents the app from compiling its routes; closing
   * after startup leaves the compiled router and running server intact.
   *
   * <p>Active callbacks are not awaited. Their subsequent registration attempts fail, and their
   * finally blocks still update the callback count. Clearing entries releases references but does
   * not shrink the collections' backing capacity.
   */
  @Override
  public void close() {
    synchronized (lock) {
      root.closed = true;
      registrations.clear();
      websocketRegistrations.clear();
      closeStaticFiles();
      staticFiles.clear();
      routeKeys.clear();
      websocketRouteKeys.clear();
      notifications.clear();
      installed.clear();
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
   * Validates a batch before any installation becomes visible to endpoint configuration.
   *
   * @param extensions capabilities to install
   * @throws IllegalArgumentException if an instance occurs twice or is already installed
   */
  void install(List<Extension<?>> extensions) {
    synchronized (lock) {
      requireMutable();
      var unique = Collections.newSetFromMap(new IdentityHashMap<Extension<?>, Boolean>());
      for (var extension : extensions) {
        if (!unique.add(extension) || installed.contains(extension)) {
          throw new IllegalArgumentException("An extension instance can only be installed once");
        }
      }
      installed.addAll(extensions);
    }
  }

  /** Runs cold HTTP binding notifications after compilation and before provider finalization. */
  void prepare() {
    for (var notification : List.copyOf(notifications)) {
      notification.run();
    }
    notifications.clear();
  }

  /**
   * Reports whether selected routes require terminal observation even without app observers.
   *
   * @return whether any bound endpoint has a local observer
   */
  boolean localObservers() {
    return root.localObservers;
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
      @Nullable Consumer<Extensions> configure,
      List<Runnable> facts) {
    if (configuration == null && configure == null && conditions.isEmpty()) {
      return null;
    }

    var local = new Extensions(installed, configuration);

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
   * Publishes cold notifications and terminal-observer requirements while holding the root lock.
   *
   * @param behavior immutable local behavior
   * @param facts pending HTTP metadata notifications
   */
  private void publish(@Nullable EndpointBehavior behavior, List<Runnable> facts) {
    notifications.addAll(facts);
    if (behavior != null && !behavior.observers().isEmpty()) {
      root.localObservers = true;
    }
  }

  /**
   * Normalizes a mount and rejects parameters because mounted resources have no parameter binding.
   *
   * @param path relative mount path
   * @return absolute normalized literal mount path
   * @throws IllegalArgumentException if the mount has a parameter segment
   */
  private String staticMount(String path) {
    var mounted = mount(path);
    if (!RadixRoutes.parameters(mounted).isEmpty()) {
      throw new IllegalArgumentException("Static-resource mounts cannot contain path parameters");
    }

    return mounted;
  }

  /** Closes every uncompiled static source before reporting aggregated failures. */
  private void closeStaticFiles() {
    RuntimeException failure = null;
    for (var files : staticFiles) {
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
   * Normalizes a mounted path while preserving the root mount as the single separator.
   *
   * @param path relative mount path
   * @return absolute normalized mount path
   */
  private String mount(String path) {
    var mounted = join(prefix, path);
    RadixRoutes.parameters(mounted);
    if (mounted.length() == 1) {
      return mounted;
    }

    return mounted.charAt(mounted.length() - 1) == HttpCharacters.PATH_SEPARATOR
        ? mounted.substring(0, mounted.length() - 1)
        : mounted;
  }
}
