package io.github.suppierk.shoostr;

import io.github.suppierk.shoostr.http.HttpCharacters;
import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.http.HttpStatusCodes;
import java.io.Closeable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
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
  private final List<Handler> policies;
  private final List<RadixRoutes.Endpoint> registrations;
  private final List<RadixRoutes.Endpoint> websocketRegistrations;
  private final List<StaticFiles> staticFiles;
  private final Set<String> routeKeys;
  private final Set<String> websocketRouteKeys;
  private @Nullable RadixRoutes websocketRouter;
  private int registrationDepth;
  private boolean closed;

  /** Creates the root scope, which owns registration state and the lock shared by all scopes. */
  Routes() {
    root = this;
    lock = new Object();
    prefix = EMPTY_PATH;
    policies = List.of();
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
   * @param policies immutable admission policies inherited by this scope
   */
  private Routes(Routes root, String prefix, List<Handler> policies) {
    this.root = root;
    lock = root.lock;
    this.prefix = prefix;
    this.policies = List.copyOf(policies);
    registrations = root.registrations;
    websocketRegistrations = root.websocketRegistrations;
    staticFiles = root.staticFiles;
    routeKeys = root.routeKeys;
    websocketRouteKeys = root.websocketRouteKeys;
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
    Routes scope;
    synchronized (lock) {
      requireMutable();
      Objects.requireNonNull(registration);
      String group = join(prefix, path);
      RadixRoutes.parameters(group);
      if (group.charAt(group.length() - 1) == HttpCharacters.PATH_SEPARATOR) {
        group = group.substring(0, group.length() - 1);
      }

      scope = new Routes(root, group, policies);
      root.registrationDepth++;
    }

    try {
      registration.accept(scope);
    } finally {
      synchronized (lock) {
        root.registrationDepth--;
      }
    }

    return this;
  }

  /**
   * Registers routes with an additional admission policy. Nested scopes inherit policies in
   * outer-to-inner order; siblings remain unchanged. Throw to reject access. Policies run after
   * application-wide gates and before the endpoint, and cannot start a response stream.
   *
   * @param policy reusable admission callback, invoked independently for each matching request
   * @param registration routes to protect, including nested path groups
   * @return this enclosing scope
   * @throws IllegalStateException if registration has ended
   */
  public Routes protect(Handler policy, Consumer<Routes> registration) {
    Routes scope;
    synchronized (lock) {
      requireMutable();
      Objects.requireNonNull(registration);
      var inherited = new ArrayList<>(policies);
      inherited.add(Objects.requireNonNull(policy));
      scope = new Routes(root, prefix, inherited);
      root.registrationDepth++;
    }

    try {
      registration.accept(scope);
    } finally {
      synchronized (lock) {
        root.registrationDepth--;
      }
    }

    return this;
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
    Objects.requireNonNull(handler);
    return route(
        HttpMethods.GET,
        path,
        (request, response) -> {
          response.requireEventStream();
          handler.handle(request, response);
          if (response.status() != HttpStatusCodes.NO_CONTENT.value() && !response.isCommitted()) {
            throw new IllegalStateException("SSE route must start an event stream or return 204");
          }
        });
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
    synchronized (lock) {
      requireMutable();
      Objects.requireNonNull(listenerFactory);
      var endpoint =
          RadixRoutes.websocketEndpoint(
              join(prefix, path), protectedHandler((_, _) -> {}, policies), listenerFactory);
      if (!websocketRouteKeys.add(endpoint.pattern())) {
        throw new IllegalArgumentException("Duplicate WebSocket route shape: " + path);
      }

      websocketRegistrations.add(endpoint);
      return this;
    }
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
   * Registers an explicit HTTP method beneath this group's prefix.
   *
   * @param method registered HTTP method
   * @param path relative endpoint path; empty selects the group endpoint
   * @param handler request/response handler
   * @return this scope
   * @throws IllegalArgumentException if the route is invalid or already registered
   * @throws IllegalStateException if route registration has ended
   */
  public Routes route(HttpMethods method, String path, Handler handler) {
    synchronized (lock) {
      requireMutable();
      Objects.requireNonNull(method);
      var endpoint =
          RadixRoutes.endpoint(method, join(prefix, path), protectedHandler(handler, policies));
      if (!routeKeys.add(method + " " + endpoint.pattern())) {
        throw new IllegalArgumentException("Duplicate route shape: " + method + " " + path);
      }

      registrations.add(endpoint);
      return this;
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
    synchronized (lock) {
      requireMutable();
      Objects.requireNonNull(method);
      return route(
          HttpMethods.httpMethod(method)
              .orElseThrow(
                  () -> new IllegalArgumentException("Unrecognized HTTP method: " + method)),
          path,
          handler);
    }
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
    synchronized (lock) {
      requireMutable();
      var mount = staticMount(path);
      staticFiles.add(new StaticFiles(mount, directory, policies, Objects.requireNonNull(options)));
      return this;
    }
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
    synchronized (lock) {
      requireMutable();
      staticFiles.add(
          new StaticFiles(staticMount(path), directory, policies, Objects.requireNonNull(options)));
      return this;
    }
  }

  /**
   * Compiles the immutable request router and ends registration before releasing the shared lock.
   * Static mounts transfer to the compiled router; other registration data is released. Active
   * callbacks are rejected before compilation, allowing startup to be retried once they finish.
   *
   * @return the completed router with literal-first endpoint matching
   * @throws IllegalStateException if registration is closed or a path callback is active
   */
  RadixRoutes compile() {
    synchronized (lock) {
      requireMutable();
      if (root.registrationDepth != 0) {
        throw new IllegalStateException("Finish path group registration before starting");
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
   * Captures this scope's immutable policies around a registered endpoint.
   *
   * @param handler endpoint reached only after every policy succeeds
   * @param policies immutable admission policies captured during registration
   * @return original handler for public routes, otherwise a guarded handler
   */
  static Handler protectedHandler(Handler handler, List<Handler> policies) {
    Objects.requireNonNull(handler);
    if (policies.isEmpty()) {
      return handler;
    }

    return (request, response) -> {
      response.gating(true);

      try {
        for (var policy : policies) {
          policy.handle(request, response);
        }
      } finally {
        response.gating(false);
      }

      handler.handle(request, response);
    };
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
