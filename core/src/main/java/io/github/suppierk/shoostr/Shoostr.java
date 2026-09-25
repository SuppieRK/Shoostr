package io.github.suppierk.shoostr;

import io.github.suppierk.shoostr.http.ForwardedHeaders;
import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.http.HttpStatusCodes;
import io.github.suppierk.shoostr.http.exceptions.AuthenticationRequiredException;
import io.github.suppierk.shoostr.http.exceptions.ContentTooLargeException;
import io.github.suppierk.shoostr.http.exceptions.HttpException;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.compression.server.CompressionConfig;
import org.eclipse.jetty.compression.server.CompressionHandler;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.QuotedQualityCSV;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.GracefulHandler;
import org.eclipse.jetty.session.SessionHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.server.ServerWebSocketContainer;
import org.jspecify.annotations.Nullable;

/** HTTP application with explicit startup and automatic JVM shutdown cleanup. */
public final class Shoostr implements Closeable {
  private static final long DEFAULT_STOP_TIMEOUT_MILLIS = 5000;
  private static final int DEFAULT_WEBSOCKET_MAX_OUTGOING_FRAMES = 32;

  private final Options options;
  private final Routes routes;
  private final Map<Class<? extends Exception>, ExceptionHandler<Exception>> exceptionHandlers;
  private final Map<Integer, Handler> statusHandlers;
  private final List<Handler> requestHeaderHandlers;
  private final List<Handler> routeMatchedHandlers;
  private final List<Handler> beforeHandlers;
  private final List<Handler> afterRouteHandlers;
  private final List<Handler> beforeFlushHandlers;
  private final List<Handler> afterFlushHandlers;
  private final List<Consumer<RequestOutcome>> afterHandlers;
  private final List<Function<Request, RequestObservation>> observationFactories;
  private final List<Consumer<Server>> serverConfigurations;
  private final List<Consumer<HttpConfiguration>> httpConfigurations;
  private @Nullable TrustedProxy trustedProxy;
  private @Nullable CorsPolicy corsPolicy;
  private @Nullable Consumer<SessionHandler> sessionConfiguration;
  private @Nullable Consumer<SslContextFactory.Server> tlsConfiguration;
  private boolean http2;
  private boolean compression;
  private @Nullable Server server;
  private @Nullable ServerConnector connector;
  private @Nullable RadixRoutes router;
  private @Nullable RadixRoutes websocketRouter;
  private @Nullable ExecutorService virtualThreads;
  private @Nullable Thread shutdownHook;
  private boolean started;
  private boolean closed;

  /** Creates an application with the default bind address and resource limits. */
  public Shoostr() {
    this(Options.defaults());
  }

  /**
   * Creates an application with explicit server limits.
   *
   * @param options server configuration
   */
  public Shoostr(Options options) {
    this.options = Objects.requireNonNull(options);
    routes = new Routes();
    exceptionHandlers = new HashMap<>();
    statusHandlers = new HashMap<>();
    requestHeaderHandlers = new ArrayList<>();
    routeMatchedHandlers = new ArrayList<>();
    beforeHandlers = new ArrayList<>();
    afterRouteHandlers = new ArrayList<>();
    beforeFlushHandlers = new ArrayList<>();
    afterFlushHandlers = new ArrayList<>();
    afterHandlers = new ArrayList<>();
    observationFactories = new ArrayList<>();
    serverConfigurations = new ArrayList<>();
    httpConfigurations = new ArrayList<>();
  }

  /**
   * Registers native Jetty configuration before startup. Callbacks run in registration order after
   * HTTP configuration, the default connector and application handler have been installed. Native
   * settings override defaults. Shoostr owns startup and shutdown; callbacks must preserve its
   * handler and default connector and must not start or stop the server or enable Jetty's separate
   * JVM hook. Wrapping the installed handler is supported. Additional connectors are owned by
   * Shoostr; {@link #port()} continues to identify the default connector.
   *
   * @param configuration callback for the unstarted server, including native connectors and pool
   * @return this application
   * @throws IllegalStateException if startup has begun or the app is closed
   * @throws NullPointerException if configuration is null
   */
  public synchronized Shoostr modifyServer(Consumer<Server> configuration) {
    if (started || closed) {
      throw new IllegalStateException("Jetty must be configured before startup");
    }

    serverConfigurations.add(Objects.requireNonNull(configuration));
    return this;
  }

  /**
   * Registers native HTTP configuration for the default connector before startup. Callbacks run in
   * registration order after HTTP defaults and before all {@link #modifyServer(Consumer)}
   * callbacks. Further connectors can reuse this configuration through their native connection
   * factories.
   *
   * @param configuration callback for native parsing, header, output and compliance settings
   * @return this application
   * @throws IllegalStateException if startup has begun or the app is closed
   * @throws NullPointerException if configuration is null
   */
  public synchronized Shoostr modifyHttpConfiguration(Consumer<HttpConfiguration> configuration) {
    if (started || closed) {
      throw new IllegalStateException("HTTP must be configured before startup");
    }

    httpConfigurations.add(Objects.requireNonNull(configuration));
    return this;
  }

  /**
   * Enables TLS on the default listener. The callback configures Jetty's native, unstarted TLS
   * context, including its key store. Shoostr owns the context and connector lifecycle. Configure
   * before startup; the default listener remains HTTP/1.1 unless HTTP/2 is also enabled.
   *
   * @param configuration callback supplying the server key material and optional native TLS
   *     settings
   * @return this application
   * @throws IllegalStateException if startup has begun, Shoostr is closed, or TLS is already
   *     enabled
   * @throws NullPointerException if configuration is null
   */
  public synchronized Shoostr tls(Consumer<SslContextFactory.Server> configuration) {
    if (started || closed || tlsConfiguration != null) {
      throw new IllegalStateException("TLS must be configured once before startup");
    }

    tlsConfiguration = Objects.requireNonNull(configuration);
    return this;
  }

  /**
   * Enables HTTP/2 on the default listener. With TLS, ALPN negotiates HTTP/2 and retains HTTP/1.1
   * fallback; without TLS, the listener supports clear-text HTTP/1.1 and h2c. Configure before
   * startup. Native Jetty settings remain available through the configuration callbacks.
   *
   * @return this application
   * @throws IllegalStateException if startup has begun, Shoostr is closed, or HTTP/2 is already
   *     enabled
   */
  public synchronized Shoostr http2() {
    if (started || closed || http2) {
      throw new IllegalStateException("HTTP/2 must be configured once before startup");
    }

    http2 = true;
    return this;
  }

  /**
   * Enables Jetty gzip response compression on eligible GET and POST responses. Request-body
   * decompression remains disabled, so the existing request-size limit applies to wire bytes.
   * Individual responses may opt out with {@link Response#disableCompression()}.
   *
   * @return this application
   * @throws IllegalStateException if startup has begun, Shoostr is closed, or compression is
   *     enabled
   */
  public synchronized Shoostr compression() {
    if (started || closed || compression) {
      throw new IllegalStateException("Compression must be configured once before startup");
    }

    compression = true;
    return this;
  }

  /**
   * Returns the pre-created root registration scope.
   *
   * @return the same routes instance on every call; register before starting the app
   */
  public Routes routes() {
    return routes;
  }

  /**
   * Enables RFC7239 forwarding from explicitly trusted IP peers. Configure before startup. The
   * shared predicate must be thread-safe and nonblocking; it must identify actual proxy addresses.
   * Proxies must remove untrusted input or append truthful forwarding entries.
   *
   * @param trusted predicate evaluated against the physical peer and preceding proxy addresses
   * @return this application
   * @throws IllegalStateException if startup has begun, the app is closed, or already configured
   * @throws NullPointerException if trusted is null
   */
  public synchronized Shoostr trustedProxies(Predicate<InetAddress> trusted) {
    return trustedProxies(trusted, ForwardedHeaders.RFC7239);
  }

  /**
   * Enables one explicit forwarding header family from trusted IP peers. Configure before startup.
   * The shared predicate must be thread-safe and nonblocking; it must identify actual proxy
   * addresses. The selected family exclusively controls effective metadata.
   *
   * @param trusted predicate evaluated against the physical peer and preceding proxy addresses
   * @param headers accepted forwarding header family
   * @return this application
   * @throws IllegalStateException if startup has begun, the app is closed, or already configured
   * @throws NullPointerException if trusted or headers is null
   */
  public synchronized Shoostr trustedProxies(
      Predicate<InetAddress> trusted, ForwardedHeaders headers) {
    if (started || closed || trustedProxy != null) {
      throw new IllegalStateException("Proxy trust must be configured once before startup");
    }

    trustedProxy =
        new TrustedProxy(Objects.requireNonNull(trusted), Objects.requireNonNull(headers));
    return this;
  }

  /**
   * Registers an ordered application-wide gate for matched routes before startup. Setting finite
   * output does not skip the endpoint; throw an exception to reject a request. Gates cannot start
   * streams or close output. They run after the known request-size check.
   *
   * @param handler shared callback invoked on the route's thread
   * @return this application
   * @throws IllegalStateException if startup has begun or the app is closed
   * @throws NullPointerException if handler is null
   */
  public synchronized Shoostr beforeRouteHandler(Handler handler) {
    if (started || closed) {
      throw new IllegalStateException("Route gates must be registered before startup");
    }

    beforeHandlers.add(Objects.requireNonNull(handler));
    return this;
  }

  /**
   * Registers an admission callback after request headers are available and before route lookup.
   *
   * @param handler callback on the request thread
   * @return this application
   */
  public synchronized Shoostr onRequestHeaders(Handler handler) {
    return register(requestHeaderHandlers, handler, "Request-header hooks");
  }

  /**
   * Registers a callback after a route has matched and before route gates run.
   *
   * @param handler callback on the request thread
   * @return this application
   */
  public synchronized Shoostr onRouteMatched(Handler handler) {
    return register(routeMatchedHandlers, handler, "Route-match hooks");
  }

  /**
   * Registers a callback after a matched route handler returns successfully.
   *
   * @param handler callback on the request thread
   * @return this application
   */
  public synchronized Shoostr afterRouteHandler(Handler handler) {
    return register(afterRouteHandlers, handler, "Post-route hooks");
  }

  /**
   * Registers a callback immediately before each framework-owned response flush.
   *
   * @param handler callback on the request thread
   * @return this application
   */
  public synchronized Shoostr beforeResponseFlush(Handler handler) {
    return register(beforeFlushHandlers, handler, "Before-flush hooks");
  }

  /**
   * Registers a read-only callback after each committed framework-owned response flush.
   *
   * @param handler callback on the request thread
   * @return this application
   */
  public synchronized Shoostr afterResponseFlush(Handler handler) {
    return register(afterFlushHandlers, handler, "After-flush hooks");
  }

  /**
   * Enables an immutable application-wide CORS policy before startup. Recognized preflights run
   * header admission, then finish without route authentication or business handlers. Actual
   * requests retain normal authentication. CORS is response-sharing permission, not CSRF
   * protection.
   *
   * @param policy explicit cross-origin permissions
   * @return this application
   * @throws IllegalStateException if startup has begun, the app is closed or CORS is already set
   * @throws NullPointerException if policy is null
   */
  public synchronized Shoostr cors(CorsPolicy policy) {
    if (started || closed || corsPolicy != null) {
      throw new IllegalStateException("CORS must be configured once before startup");
    }

    corsPolicy = Objects.requireNonNull(policy);
    return this;
  }

  /**
   * Enables Jetty's in-memory HTTP sessions with its default configuration.
   *
   * @return this application
   * @throws IllegalStateException if startup has begun, the app is closed, or sessions are enabled
   */
  public synchronized Shoostr sessions() {
    return sessions(_ -> {});
  }

  /**
   * Enables Jetty HTTP sessions and lets the application configure its session handler after native
   * server callbacks and before startup, including cookie settings and a Jetty session cache and
   * data store.
   *
   * @param configuration callback for the unstarted session handler
   * @return this application
   * @throws IllegalStateException if startup has begun, the app is closed, or sessions are enabled
   * @throws NullPointerException if configuration is null
   */
  public synchronized Shoostr sessions(Consumer<SessionHandler> configuration) {
    if (started || closed || sessionConfiguration != null) {
      throw new IllegalStateException("Sessions must be configured once before startup");
    }

    sessionConfiguration = Objects.requireNonNull(configuration);
    return this;
  }

  /**
   * Registers instrumentation before startup. Factories run at admission before application hooks,
   * including unmatched requests. Returned scopes close in reverse order on the invocation thread;
   * completion follows transport termination. Runtime failures are logged without changing
   * responses. A factory that fails must clean up any resources it acquired before returning.
   *
   * @param factory creates independent instrumentation for each request
   * @return this application
   * @throws IllegalStateException if startup has begun or the app is closed
   */
  public synchronized Shoostr observe(Function<Request, RequestObservation> factory) {
    if (started || closed) {
      throw new IllegalStateException("Instrumentation must be registered before startup");
    }

    observationFactories.add(Objects.requireNonNull(factory));
    return this;
  }

  /**
   * Registers an ordered read-only observer of terminal requests before startup. Callbacks may run
   * on transport threads and must not block. They receive no live request or response.
   * RuntimeException failures are logged and later observers still run. Requests rejected by the
   * transport before reaching this application are outside this observer's scope.
   *
   * @param observer shared callback receiving one completion summary per admitted exchange
   * @return this application
   * @throws IllegalStateException if startup has begun or the app is closed
   * @throws NullPointerException if observer is null
   */
  public synchronized Shoostr afterRequest(Consumer<RequestOutcome> observer) {
    if (started || closed) {
      throw new IllegalStateException("Completion observers must be registered before startup");
    }

    afterHandlers.add(Objects.requireNonNull(observer));
    return this;
  }

  /**
   * Registers an application-wide safety net before startup. Business logic should handle
   * recoverable failures directly; these handlers do not have route or group scope.
   *
   * @param type directly thrown exception class
   * @param handler callback receiving the live request and a clean error response
   * @param <E> exception family
   * @return this application
   * @throws IllegalStateException if startup has begun or the application is closed
   * @throws IllegalArgumentException if this exact class already has a registered callback
   * @throws NullPointerException if type or handler is null
   */
  public synchronized <E extends Exception> Shoostr exception(
      Class<E> type, ExceptionHandler<? super E> handler) {
    if (started || closed) {
      throw new IllegalStateException("Exception handlers must be registered before startup");
    }

    Objects.requireNonNull(type);
    Objects.requireNonNull(handler);
    if (exceptionHandlers.containsKey(type)) {
      throw new IllegalArgumentException(
          "An exception handler is already registered for " + type.getName());
    }

    exceptionHandlers.put(
        type,
        (failure, request, response) -> handler.handle(type.cast(failure), request, response));
    return this;
  }

  /**
   * Registers one application-wide renderer for a router-generated HTTP status before startup. The
   * renderer applies only before response commitment and does not catch application failures.
   *
   * @param status generated status code to customize
   * @param handler callback receiving the live request and response
   * @return this application
   * @throws IllegalStateException if startup has begun or the app is closed
   * @throws IllegalArgumentException if this status already has a renderer
   * @throws NullPointerException if handler is null
   */
  public synchronized Shoostr status(int status, Handler handler) {
    if (started || closed) {
      throw new IllegalStateException("Status handlers must be registered before startup");
    }

    if (statusHandlers.putIfAbsent(status, Objects.requireNonNull(handler)) != null) {
      throw new IllegalArgumentException("A status handler is already registered for " + status);
    }

    return this;
  }

  /**
   * Compiles and closes route registration, starts the HTTP listener, and registers a JVM shutdown
   * hook. Failure after compilation closes acquired resources and prevents restart. Rejected
   * compilation leaves registration open for retry once the problem is corrected.
   *
   * @return this application
   * @throws Exception if startup or shutdown-hook registration fails
   * @throws IllegalStateException if started, closed, or any path registration callback is active
   */
  @SuppressWarnings(
      "java:S1181") // Startup cleanup must also run when Jetty or a callback throws Error.
  public synchronized Shoostr start() throws Exception {
    if (started || closed) {
      throw new IllegalStateException("Shoostr can only start once and cannot start after close");
    }

    router = routes.compile();
    websocketRouter = routes.websocketRouter();

    try {
      var dispatch =
          new DispatchConfiguration(
              Objects.requireNonNull(router),
              exceptionHandlers,
              statusHandlers,
              beforeHandlers,
              requestHeaderHandlers,
              routeMatchedHandlers,
              afterRouteHandlers,
              beforeFlushHandlers,
              afterFlushHandlers,
              afterHandlers,
              observationFactories);
      observationFactories.clear();
      afterHandlers.clear();
      beforeHandlers.clear();
      requestHeaderHandlers.clear();
      routeMatchedHandlers.clear();
      afterRouteHandlers.clear();
      beforeFlushHandlers.clear();
      afterFlushHandlers.clear();
      exceptionHandlers.clear();
      statusHandlers.clear();
      started = true;

      startServer(dispatch);
      var boundConnector = Objects.requireNonNull(connector);
      shutdownHook = new Thread(this::shutdown, "web-shutdown-" + boundConnector.getLocalPort());
      Runtime.getRuntime().addShutdownHook(shutdownHook);
    } catch (Exception | Error failure) {
      try {
        close();
      } catch (IOException cleanup) {
        failure.addSuppressed(cleanup);
      }

      throw failure;
    }

    return this;
  }

  /**
   * Returns the bound port, including an ephemeral port selected by the OS.
   *
   * @return listening port
   * @throws IllegalStateException if the application is not running
   */
  public synchronized int port() {
    if (closed || connector == null || server == null || !server.isStarted()) {
      throw new IllegalStateException("Shoostr is not running");
    }

    return connector.getLocalPort();
  }

  /**
   * Drains and stops Jetty using its configured native timeouts, then interrupts remaining owned
   * virtual-thread tasks and removes the shutdown hook. Does not wait for application code that
   * ignores interruption. Repeated calls are harmless, including a call before startup.
   *
   * @throws IOException if resources cannot be closed
   */
  @Override
  public synchronized void close() throws IOException {
    if (closed) {
      return;
    }

    closed = true;
    exceptionHandlers.clear();
    statusHandlers.clear();
    requestHeaderHandlers.clear();
    routeMatchedHandlers.clear();
    beforeHandlers.clear();
    afterRouteHandlers.clear();
    beforeFlushHandlers.clear();
    afterFlushHandlers.clear();
    afterHandlers.clear();
    routes.close();
    serverConfigurations.clear();
    httpConfigurations.clear();
    sessionConfiguration = null;
    tlsConfiguration = null;
    IOException failure = null;

    try {
      if (server != null) {
        server.stop();
      }
    } catch (Exception stopFailure) {
      if (stopFailure instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }

      failure = new IOException("Could not stop HTTP server", stopFailure);
    } finally {
      if (router != null) {
        try {
          router.close();
        } catch (RuntimeException closeFailure) {
          failure = appendCloseFailure(failure, "Could not close static resources", closeFailure);
        } finally {
          router = null;
        }
      }

      if (virtualThreads != null) {
        try {
          virtualThreads.shutdownNow();
        } catch (RuntimeException executorFailure) {
          failure = appendCloseFailure(failure, "Could not close HTTP executor", executorFailure);
        }
      }

      removeShutdownHook();
    }

    if (failure != null) {
      throw failure;
    }
  }

  /**
   * Adds one independent shutdown failure without skipping later cleanup.
   *
   * @param current previously observed cleanup failure, or null
   * @param message context for the first failure
   * @param additional subsequent cleanup failure
   * @return aggregated failure
   */
  private static IOException appendCloseFailure(
      @Nullable IOException current, String message, RuntimeException additional) {
    if (current == null) {
      return new IOException(message, additional);
    }

    current.addSuppressed(additional);
    return current;
  }

  /**
   * Creates the configured transport and virtual-thread executor, installs dispatch, and binds the
   * listener. Fields retain each acquired resource so start's failure path can clean it up.
   *
   * @param dispatch immutable registration snapshot used by all request handlers
   * @throws Exception if transport initialization or listener binding fails
   * @throws IllegalStateException if configuration closes Shoostr or changes its owned wiring
   */
  private void startServer(DispatchConfiguration dispatch) throws Exception {
    virtualThreads = Executors.newVirtualThreadPerTaskExecutor();
    var pool = new QueuedThreadPool();
    pool.setReservedThreads(0);
    pool.setVirtualThreadsExecutor(virtualThreads);
    server = new Server(pool);
    server.setStopTimeout(DEFAULT_STOP_TIMEOUT_MILLIS);
    var proxy = trustedProxy;
    var cors = corsPolicy;
    var websocketRoutes = websocketRouter;
    var http = configureHttp();
    connector = createConnector(Objects.requireNonNull(server), http);
    tlsConfiguration = null;
    connector.setHost(options.host());
    connector.setPort(options.port());
    connector.setIdleTimeout(options.idleTimeoutMillis());
    server.addConnector(connector);
    var graceful = new GracefulHandler();
    server.setHandler(graceful);
    var compressionHandler = compression ? new CompressionHandler() : null;
    var applicationHandler =
        new ApplicationHandler(dispatch, proxy, cors, websocketRoutes, compressionHandler);
    var installedSessionHandler =
        installHandlers(
            Objects.requireNonNull(server),
            graceful,
            applicationHandler,
            websocketRoutes,
            compressionHandler);
    configureNativeServer(
        Objects.requireNonNull(server),
        graceful,
        applicationHandler,
        installedSessionHandler,
        Objects.requireNonNull(connector));
    server.start();
  }

  /**
   * Applies native HTTP parsing configuration before the listener is constructed.
   *
   * @return configured Jetty HTTP settings
   * @throws IllegalStateException if a configuration callback closes Shoostr
   */
  private HttpConfiguration configureHttp() {
    var http = new HttpConfiguration();
    http.setSendServerVersion(false);
    var tls = tlsConfiguration;
    if (tls != null) {
      http.addCustomizer(new SecureRequestCustomizer());
    }

    for (var configuration : List.copyOf(httpConfigurations)) {
      configuration.accept(http);
      if (closed) {
        throw new IllegalStateException("Shoostr was closed during HTTP configuration");
      }
    }

    httpConfigurations.clear();
    return http;
  }

  /**
   * Creates the default cleartext or TLS connector with the selected HTTP versions.
   *
   * @param server unstarted transport server
   * @param http configured HTTP settings
   * @return unbound default connector
   * @throws IllegalStateException if TLS configuration closes Shoostr
   */
  private ServerConnector createConnector(Server server, HttpConfiguration http) {
    var tls = tlsConfiguration;
    var http11 = new HttpConnectionFactory(http);
    if (tls == null) {
      return http2
          ? new ServerConnector(server, http11, new HTTP2CServerConnectionFactory(http))
          : new ServerConnector(server, http11);
    }

    var context = new SslContextFactory.Server();
    tls.accept(context);
    if (closed) {
      throw new IllegalStateException("Shoostr was closed during TLS configuration");
    }

    if (http2) {
      var h2 = new HTTP2ServerConnectionFactory(http);
      var alpn = new ALPNServerConnectionFactory();
      alpn.setDefaultProtocol(http11.getProtocol());
      return new ServerConnector(
          server, new SslConnectionFactory(context, alpn.getProtocol()), alpn, h2, http11);
    }

    return new ServerConnector(
        server, new SslConnectionFactory(context, http11.getProtocol()), http11);
  }

  /**
   * Installs session, upgrade and compression handlers around the request dispatcher.
   *
   * @param server unstarted transport server
   * @param graceful root graceful-shutdown handler
   * @param applicationHandler request dispatcher
   * @param websocketRoutes compiled WebSocket routes, or null
   * @param compressionHandler native compression wrapper, or null
   * @return installed session handler, or null
   */
  private @Nullable SessionHandler installHandlers(
      Server server,
      GracefulHandler graceful,
      org.eclipse.jetty.server.Handler applicationHandler,
      @Nullable RadixRoutes websocketRoutes,
      @Nullable CompressionHandler compressionHandler) {
    SessionHandler installedSessionHandler = null;
    ContextHandler context = null;
    if (sessionConfiguration != null || websocketRoutes != null) {
      context = new ContextHandler("/");
    }

    if (sessionConfiguration != null) {
      var sessionHandler = new SessionHandler();
      sessionHandler.setMaxInactiveInterval(1800);
      sessionHandler.setHttpOnly(true);
      sessionHandler.setSameSite(HttpCookie.SameSite.LAX);
      sessionHandler.setSecureRequestOnly(true);
      sessionHandler.setUsingUriParameters(false);
      sessionHandler.setHandler(applicationHandler);
      Objects.requireNonNull(context).setHandler(sessionHandler);
      installedSessionHandler = sessionHandler;
    } else if (context != null) {
      context.setHandler(applicationHandler);
    }

    graceful.setHandler(Objects.requireNonNullElse(context, applicationHandler));

    if (websocketRoutes != null) {
      ServerWebSocketContainer.ensure(server, Objects.requireNonNull(context))
          .setMaxOutgoingFrames(DEFAULT_WEBSOCKET_MAX_OUTGOING_FRAMES);
    }

    if (compressionHandler != null) {
      compressionHandler.setHandler(graceful.getHandler());
      compressionHandler.putConfiguration(
          "/*", CompressionConfig.builder().defaults().decompressExcludePath("/*").build());
      graceful.setHandler(compressionHandler);
    }

    return installedSessionHandler;
  }

  /**
   * Runs native server and session customization, then verifies Shoostr still owns the listener.
   *
   * @param server unstarted transport server
   * @param graceful root graceful-shutdown handler
   * @param applicationHandler request dispatcher
   * @param installedSessionHandler installed session handler, or null
   * @param connector default listener owned by Shoostr
   * @throws IllegalStateException if native configuration changes owned wiring or closes Shoostr
   */
  private void configureNativeServer(
      Server server,
      GracefulHandler graceful,
      org.eclipse.jetty.server.Handler applicationHandler,
      @Nullable SessionHandler installedSessionHandler,
      ServerConnector connector) {
    var installedHandler = graceful.getHandler();
    for (var configuration : List.copyOf(serverConfigurations)) {
      configuration.accept(server);
      if (closed) {
        throw new IllegalStateException("Shoostr was closed during Jetty configuration");
      }
    }

    serverConfigurations.clear();
    if (installedSessionHandler != null) {
      Objects.requireNonNull(sessionConfiguration).accept(installedSessionHandler);
      if (closed) {
        throw new IllegalStateException("Shoostr was closed during session configuration");
      }

      sessionConfiguration = null;
    }

    if (!server.isStopped()
        || server.getStopAtShutdown()
        || server.getDescendant(GracefulHandler.class) != graceful
        || graceful.getHandler() != installedHandler
        || (installedSessionHandler != null
            && (server.getDescendant(SessionHandler.class) != installedSessionHandler
                || installedSessionHandler.getHandler() != applicationHandler))
        || !List.of(server.getConnectors()).contains(connector)) {
      throw new IllegalStateException(
          "Jetty configuration must preserve Shoostr lifecycle, handler and default connector");
    }
  }

  /**
   * Hands a matched upgrade to Jetty while keeping factory rejection inside its creator callback.
   * Jetty owns the response callback whenever this method returns true.
   *
   * @param container context's WebSocket container
   * @param endpoint matched WebSocket route
   * @param request live framework request for handshake decisions
   * @param response framework response for mapped handshake errors
   * @param errors registered application exception handlers
   * @param observation optional terminal request observation
   * @param rawRequest Jetty request
   * @param rawResponse Jetty response
   * @param callback HTTP response callback
   * @return whether Jetty handled the exchange
   */
  @SuppressWarnings(
      "java:S107") // Keep the hot handshake path free of a per-request argument holder.
  private static boolean upgradeWebSocket(
      ServerWebSocketContainer container,
      RadixRoutes.Endpoint endpoint,
      Request request,
      Response response,
      Map<Class<? extends Exception>, ExceptionHandler<Exception>> errors,
      @Nullable Completion observation,
      org.eclipse.jetty.server.Request rawRequest,
      org.eclipse.jetty.server.Response rawResponse,
      Callback callback) {
    var factory = Objects.requireNonNull(endpoint.websocketFactory());
    return container.upgrade(
        (upgradeRequest, upgradeResponse, upgradeCallback) -> {
          try {
            request.webSocketProtocols(upgradeRequest.getSubProtocols());
            return Objects.requireNonNull(factory.apply(request, upgradeResponse));
          } catch (RuntimeException failure) {
            if (observation != null) {
              observation.recordApplicationFailure(failure);
            }

            try {
              renderFailure(errors, failure, request, response, observation);
            } catch (Throwable writeFailure) {
              if (observation != null && writeFailure != failure) {
                failure.addSuppressed(writeFailure);
              }

              response.fail();
              upgradeCallback.failed(
                  new org.eclipse.jetty.server.Request.Handler.AbortException(writeFailure));
            }

            return null;
          }
        },
        rawRequest,
        rawResponse,
        callback);
  }

  /**
   * Applies the same application-wide exception policy to ordinary HTTP handlers and failed
   * WebSocket listener factories, preserving session cookies through response reset.
   *
   * @param errors registered exception handlers
   * @param failure initiating application failure
   * @param request live request for custom handlers
   * @param response uncommitted response to complete
   * @param observation optional terminal observation for mapper failures
   * @throws Throwable if error response submission fails after commitment
   */
  @SuppressWarnings("java:S112") // Transport aborts preserve the original failure, including Error.
  private static void renderFailure(
      Map<Class<? extends Exception>, ExceptionHandler<Exception>> errors,
      Throwable failure,
      Request request,
      Response response,
      @Nullable Completion observation)
      throws Throwable {
    var status =
        failure instanceof HttpException httpFailure
            ? httpFailure.statusCode()
            : HttpStatusCodes.INTERNAL_SERVER_ERROR;
    var errorHandler = exceptionHandler(errors, failure);
    if (failure instanceof AuthenticationRequiredException authentication) {
      response.requiredChallenge(authentication.challenge());
    }

    if (response.encodingRejected()) {
      response.emptyEncodingError();
      return;
    }

    if (errorHandler == null) {
      response.error(status);
      return;
    }

    response.reset(status);
    renderCustomFailure(errorHandler, failure, request, response, observation);
  }

  /**
   * Runs an application error handler with the framework's safe fallback on handler failure.
   *
   * @param errorHandler selected application handler
   * @param failure initiating application failure
   * @param request live request
   * @param response uncommitted response
   * @param observation optional terminal observation
   * @throws Throwable if error response submission fails after commitment
   */
  @SuppressWarnings("java:S112") // A committed response propagates its original transport failure.
  private static void renderCustomFailure(
      ExceptionHandler<Exception> errorHandler,
      Throwable failure,
      Request request,
      Response response,
      @Nullable Completion observation)
      throws Throwable {
    try {
      errorHandler.handle((Exception) failure, request, response);
      response.complete();
    } catch (Exception handlerFailure) {
      var originalHandlerFailure = flushFailure(handlerFailure);
      response.disableFlushHooksAfterBeforeFailure();
      if (response.committed()) {
        throw originalHandlerFailure;
      }

      if (observation != null && originalHandlerFailure != failure) {
        failure.addSuppressed(originalHandlerFailure);
      }

      if (response.encodingRejected()) {
        response.emptyEncodingError();
      } else {
        response.error(HttpStatusCodes.INTERNAL_SERVER_ERROR);
      }
    }
  }

  /**
   * Resolves gzip and identity availability using explicit quality values before Jetty's wildcard
   * negotiation. A response with another explicitly chosen content coding remains
   * application-owned.
   *
   * @param response framework response to configure
   * @param request incoming transport request
   */
  private static void configureEncoding(
      Response response, org.eclipse.jetty.server.Request request) {
    var values = request.getHeaders().getValuesList(HttpHeader.ACCEPT_ENCODING);
    if (values.isEmpty()) {
      return;
    }

    var parser = new QuotedQualityCSV();
    for (var value : values) {
      parser.addValue(value);
    }

    boolean gzipSpecified = false;
    boolean gzipAccepted = false;
    boolean identitySpecified = false;
    boolean identityAccepted = false;
    boolean wildcardSpecified = false;
    boolean wildcardAccepted = false;
    for (var value : parser.getQualityValues()) {
      if ("gzip".equalsIgnoreCase(value.getValue())) {
        gzipSpecified = true;
        gzipAccepted |= value.isAcceptable();
      } else if ("identity".equalsIgnoreCase(value.getValue())) {
        identitySpecified = true;
        identityAccepted |= value.isAcceptable();
      } else if ("*".equals(value.getValue())) {
        wildcardSpecified = true;
        wildcardAccepted |= value.isAcceptable();
      }
    }

    response.gzipExcludedByAccept(gzipSpecified && !gzipAccepted && wildcardAccepted);
    response.identityUnacceptable(
        !(identitySpecified ? identityAccepted : !wildcardSpecified || wildcardAccepted));
    response.encodingUnacceptable(
        !(gzipSpecified ? gzipAccepted : wildcardAccepted)
            && !(identitySpecified ? identityAccepted : !wildcardSpecified || wildcardAccepted));
  }

  /**
   * Finds the closest registered class without unwrapping causes or depending on map order.
   *
   * @param errors immutable application-wide callbacks
   * @param failure directly thrown failure
   * @return closest matching callback, or null for built-in handling
   */
  private static @Nullable ExceptionHandler<Exception> exceptionHandler(
      Map<Class<? extends Exception>, ExceptionHandler<Exception>> errors, Throwable failure) {
    for (Class<?> type = failure.getClass(); type != null; type = type.getSuperclass()) {
      var handler = errors.get(type);
      if (handler != null) {
        return handler;
      }
    }

    return null;
  }

  /**
   * Renders a router-generated status through its optional application-wide customization.
   *
   * @param handlers immutable status callbacks
   * @param status generated status code
   * @param fallback built-in response text
   * @param request live request
   * @param response live uncommitted response
   * @throws Exception if the configured callback fails
   */
  private static void generated(
      Map<Integer, Handler> handlers,
      int status,
      String fallback,
      Request request,
      Response response)
      throws Exception {
    var handler = handlers.get(status);
    if (handler == null) {
      response.status(status).text(fallback);
      return;
    }

    response.status(status);
    handler.handle(request, response);
  }

  /**
   * Runs one ordered flush stage and converts checked callback failures into request failures.
   *
   * @param handlers immutable callbacks for one boundary
   * @param request live request
   * @param response live response
   * @throws FlushFailure if a callback throws a checked exception
   */
  private static void flush(List<Handler> handlers, Request request, Response response) {
    try {
      for (var handler : handlers) {
        handler.handle(request, response);
      }
    } catch (RuntimeException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new FlushFailure(failure);
    }
  }

  /**
   * Recovers the checked callback exception used for existing global error-handler selection.
   *
   * @param failure callback or framework failure
   * @return original checked callback failure when wrapped for a Runnable boundary
   */
  private static Throwable flushFailure(Throwable failure) {
    return failure instanceof FlushFailure wrapped
        ? Objects.requireNonNull(wrapped.getCause())
        : failure;
  }

  /** Carries a checked lifecycle callback failure through a Runnable flush boundary. */
  private static final class FlushFailure extends RuntimeException {
    /**
     * Creates a wrapper for one checked callback failure.
     *
     * @param cause original callback failure
     */
    private FlushFailure(Exception cause) {
      super(cause);
    }
  }

  /**
   * Registers one live lifecycle callback before the application begins serving requests.
   *
   * @param handlers ordered callback collection
   * @param handler callback to add
   * @param name stage description used in validation messages
   * @return this application
   * @throws IllegalStateException if startup has begun or the app is closed
   * @throws NullPointerException if handler is null
   */
  private Shoostr register(List<Handler> handlers, Handler handler, String name) {
    if (started || closed) {
      throw new IllegalStateException(name + " must be registered before startup");
    }

    handlers.add(Objects.requireNonNull(handler));
    return this;
  }

  /** Runs idempotent cleanup from the JVM hook and logs failures that cannot reach a caller. */
  private void shutdown() {
    try {
      close();
    } catch (IOException failure) {
      System.getLogger(Shoostr.class.getName())
          .log(System.Logger.Level.ERROR, "Shoostr shutdown failed", failure);
    }
  }

  /**
   * Releases the runtime's reference to this app after explicit cleanup. During JVM shutdown the
   * runtime rejects hook removal; the already-running hook will observe the closed app instead.
   */
  private void removeShutdownHook() {
    if (shutdownHook == null) {
      return;
    }

    try {
      Runtime.getRuntime().removeShutdownHook(shutdownHook);
    } catch (IllegalStateException _) {
      // Hooks cannot be removed once JVM shutdown has begun.
    } finally {
      shutdownHook = null;
    }
  }

  /** Request dispatcher with one immutable startup snapshot and no registration locks. */
  private final class ApplicationHandler extends org.eclipse.jetty.server.Handler.Abstract {
    private final RadixRoutes compiledRoutes;
    private final Map<Class<? extends Exception>, ExceptionHandler<Exception>> errors;
    private final Map<Integer, Handler> statuses;
    private final List<Handler> gates;
    private final List<Handler> headerHooks;
    private final List<Handler> matchedHooks;
    private final List<Handler> postRouteHooks;
    private final List<Handler> preFlushHooks;
    private final List<Handler> postFlushHooks;
    private final List<Consumer<RequestOutcome>> observers;
    private final List<Function<Request, RequestObservation>> instrumentation;
    private final @Nullable TrustedProxy proxy;
    private final @Nullable CorsPolicy cors;
    private final @Nullable RadixRoutes websocketRoutes;
    private final @Nullable CompressionHandler compressionHandler;

    /**
     * Retains the immutable registration snapshot and startup policies for all requests.
     *
     * @param dispatch immutable application registrations
     * @param proxy trusted proxy policy, or null
     * @param cors cross-origin policy, or null
     * @param websocketRoutes compiled WebSocket routes, or null
     * @param compressionHandler native compression wrapper, or null
     */
    private ApplicationHandler(
        DispatchConfiguration dispatch,
        @Nullable TrustedProxy proxy,
        @Nullable CorsPolicy cors,
        @Nullable RadixRoutes websocketRoutes,
        @Nullable CompressionHandler compressionHandler) {
      compiledRoutes = dispatch.router();
      errors = dispatch.errors();
      statuses = dispatch.statuses();
      gates = dispatch.gates();
      headerHooks = dispatch.headerHooks();
      matchedHooks = dispatch.matchedHooks();
      postRouteHooks = dispatch.postRouteHooks();
      preFlushHooks = dispatch.beforeFlushHooks();
      postFlushHooks = dispatch.afterFlushHooks();
      observers = dispatch.observers();
      instrumentation = dispatch.instrumentation();
      this.proxy = proxy;
      this.cors = cors;
      this.websocketRoutes = websocketRoutes;
      this.compressionHandler = compressionHandler;
    }

    /**
     * Dispatches one request and completes it exactly once through the transport callback.
     * Uncommitted failures become safe error responses; committed or fatal failures abort.
     *
     * @param rawRequest transport-owned input
     * @param rawResponse transport-owned output
     * @param callback completion notification, also used by asynchronous finite writes
     * @return true because this handler owns every request, including 404 and 405 responses
     */
    @Override
    @SuppressWarnings({
      "java:S1181",
      "java:S3516"
    }) // Jetty owns every exchange and must finalize every throwable.
    public boolean handle(
        org.eclipse.jetty.server.Request rawRequest,
        org.eclipse.jetty.server.Response rawResponse,
        Callback callback) {
      var observation =
          observers.isEmpty() && instrumentation.isEmpty()
              ? null
              : new Completion(rawRequest.getMethod(), observers);
      if (observation != null) {
        org.eclipse.jetty.server.Request.addCompletionListener(
            rawRequest,
            failure ->
                observation.complete(
                    rawResponse.isCommitted() ? rawResponse.getStatus() : 0, failure));
      }

      var responseCallback =
          observation == null
              ? callback
              : Callback.from(callback, observation::recordTransportFailure);
      var response =
          new Response(
              rawResponse,
              options,
              responseCallback,
              HttpMethods.HEAD.value().equals(rawRequest.getMethod()),
              rawRequest);
      if (compressionHandler != null) {
        response.compression(compressionHandler);
        configureEncoding(response, rawRequest);
      }

      var request =
          new Request(
              rawRequest,
              response,
              options.maxRequestBytes(),
              options.maxParameters(),
              options.multipart());
      response.flushHooks(
          () -> flush(preFlushHooks, request, response),
          () -> flush(postFlushHooks, request, response));
      Throwable terminalFailure = null;
      Throwable applicationFailure = null;

      try {
        if (processRequest(
            rawRequest, rawResponse, responseCallback, request, response, observation)) {
          return true;
        }
      } catch (Throwable failure) {
        applicationFailure = flushFailure(failure);
        terminalFailure = recoverFailure(applicationFailure, request, response, observation);
      } finally {
        finishRequest(
            request, response, observation, callback, applicationFailure, terminalFailure);
      }

      return true;
    }

    /**
     * Attempts a safe error response or returns the cause that must abort committed output.
     *
     * @param failure unwrapped application or transport failure
     * @param request live request
     * @param response live response
     * @param observation optional terminal observation
     * @return terminal abort cause, or null when error rendering completed
     */
    private @Nullable Throwable recoverFailure(
        Throwable failure, Request request, Response response, @Nullable Completion observation) {
      response.disableFlushHooksAfterBeforeFailure();
      if (failure instanceof Error || response.committed()) {
        response.fail();
        return failure;
      }

      try {
        renderFailure(errors, failure, request, response, observation);
        return null;
      } catch (Throwable writeFailure) {
        if (observation != null && writeFailure != failure) {
          failure.addSuppressed(writeFailure);
        }

        response.fail();
        return writeFailure;
      }
    }

    /**
     * Releases request-scoped resources and reports final application and transport outcomes.
     *
     * @param request live request
     * @param response live response
     * @param observation optional terminal observation
     * @param callback original Jetty callback
     * @param applicationFailure initiating application failure, or null
     * @param terminalFailure transport abort cause, or null
     */
    private void finishRequest(
        Request request,
        Response response,
        @Nullable Completion observation,
        Callback callback,
        @Nullable Throwable applicationFailure,
        @Nullable Throwable terminalFailure) {
      var postFlushFailure = response.afterFlushFailure();
      if (postFlushFailure != null) {
        var originalPostFlushFailure = flushFailure(postFlushFailure);
        if (applicationFailure == null) {
          applicationFailure = originalPostFlushFailure;
        } else if (applicationFailure != originalPostFlushFailure) {
          applicationFailure.addSuppressed(originalPostFlushFailure);
        }
      }

      var routePattern = observation == null ? null : request.routePattern();
      if (observation != null) {
        observation.closeScopes();
      }

      request.finish();

      try {
        if (terminalFailure != null) {
          callback.failed(
              new org.eclipse.jetty.server.Request.Handler.AbortException(terminalFailure));
        }
      } finally {
        if (observation != null) {
          observation.finish(routePattern, applicationFailure);
        }
      }
    }

    /**
     * Applies admission, routing and the selected endpoint before response completion.
     *
     * @param rawRequest transport-owned input
     * @param rawResponse transport-owned output
     * @param responseCallback terminal response callback
     * @param request live framework request
     * @param response live framework response
     * @param observation optional request observation
     * @return whether preflight or WebSocket upgrade already completed the exchange
     * @throws Exception if application callbacks or output fail
     */
    @SuppressWarnings("java:S112") // Handler callbacks may throw their own checked failures.
    private boolean processRequest(
        org.eclipse.jetty.server.Request rawRequest,
        org.eclipse.jetty.server.Response rawResponse,
        Callback responseCallback,
        Request request,
        Response response,
        @Nullable Completion observation)
        throws Exception {
      if (prepareRequest(request, response, observation)) {
        response.preflight();
        response.complete();
        return true;
      }

      var endpoint = matchEndpoint(rawRequest, request);
      if (endpoint == null) {
        handleUnmatched(request, response);
      } else if (handleMatched(
          endpoint, rawRequest, rawResponse, responseCallback, request, response, observation)) {
        return true;
      }

      response.complete();
      return false;
    }

    /**
     * Applies observation, proxy and CORS admission before header hooks.
     *
     * @param request live request
     * @param response live response
     * @param observation optional terminal observation
     * @return whether an admitted preflight should finish without route handling
     * @throws Exception if a header hook fails
     */
    @SuppressWarnings("java:S112") // Header hooks retain their checked exception contracts.
    private boolean prepareRequest(
        Request request, Response response, @Nullable Completion observation) throws Exception {
      if (observation != null) {
        observation.begin(request, instrumentation);
      }

      if (proxy != null) {
        proxy.apply(request);
      }

      boolean preflight = false;
      HttpException corsFailure = null;
      if (cors != null) {
        try {
          preflight = cors.prepare(request, response);
        } catch (HttpException failure) {
          corsFailure = failure;
        }
      }

      response.gating(preflight || corsFailure != null);

      try {
        for (var hook : headerHooks) {
          hook.handle(request, response);
        }
      } finally {
        response.gating(false);
      }

      if (corsFailure != null) {
        throw corsFailure;
      }

      return preflight;
    }

    /**
     * Resolves upgrades, ordinary routes and static resources in precedence order.
     *
     * @param rawRequest transport request with upgrade headers
     * @param request parsed framework request
     * @return matched endpoint, or null
     */
    private RadixRoutes.@Nullable Endpoint matchEndpoint(
        org.eclipse.jetty.server.Request rawRequest, Request request) {
      var method = HttpMethods.httpMethod(request.method()).orElse(null);
      var endpoint =
          websocketRoutes != null
                  && rawRequest.getHeaders().contains(HttpHeader.UPGRADE, "websocket")
              ? websocketRoutes.match(request.path(), method)
              : null;
      if (endpoint == null) {
        endpoint = compiledRoutes.match(request.path(), method);
      }

      return endpoint == null ? compiledRoutes.staticEndpoint(request.path(), method) : endpoint;
    }

    /**
     * Produces the configured not-found or method-not-allowed response.
     *
     * @param request unmatched request
     * @param response live response
     * @throws Exception if a generated-status handler fails
     */
    @SuppressWarnings("java:S112") // Status handlers may throw their own checked failures.
    private void handleUnmatched(Request request, Response response) throws Exception {
      var methods = compiledRoutes.allowedMethods(request.path());
      if (methods.isEmpty()) {
        generated(statuses, HttpStatusCodes.NOT_FOUND.value(), "Not found", request, response);
        return;
      }

      response.requiredAllow(String.join(", ", methods.stream().map(HttpMethods::value).toList()));
      generated(
          statuses,
          HttpStatusCodes.METHOD_NOT_ALLOWED.value(),
          "Method not allowed",
          request,
          response);
    }

    /**
     * Runs route hooks and the endpoint, then performs a requested WebSocket upgrade.
     *
     * @param endpoint selected endpoint
     * @param rawRequest transport request
     * @param rawResponse transport response
     * @param responseCallback terminal response callback
     * @param request live framework request
     * @param response live framework response
     * @param observation optional terminal observation
     * @return whether Jetty accepted the WebSocket upgrade
     * @throws ContentTooLargeException if the declared request body exceeds the configured limit
     * @throws Exception if a hook, handler or generated-status handler fails
     */
    @SuppressWarnings("java:S112") // Route callbacks may throw their own checked failures.
    private boolean handleMatched(
        RadixRoutes.Endpoint endpoint,
        org.eclipse.jetty.server.Request rawRequest,
        org.eclipse.jetty.server.Response rawResponse,
        Callback responseCallback,
        Request request,
        Response response,
        @Nullable Completion observation)
        throws Exception {
      request.route(endpoint);
      for (var hook : matchedHooks) {
        hook.handle(request, response);
      }

      if (rawRequest.getLength() > request.maxBodyBytes()) {
        throw new ContentTooLargeException();
      }

      runGates(request, response);
      endpoint.handler().handle(request, response);
      for (var hook : postRouteHooks) {
        hook.handle(request, response);
      }

      if (endpoint.websocketFactory() == null) {
        return false;
      }

      var container = Objects.requireNonNull(ServerWebSocketContainer.get(rawRequest.getContext()));
      if (upgradeWebSocket(
          container,
          endpoint,
          request,
          response,
          errors,
          observation,
          rawRequest,
          rawResponse,
          responseCallback)) {
        return true;
      }

      response.header(HttpHeader.UPGRADE.asString(), "websocket");
      response.header(
          HttpHeader.SEC_WEBSOCKET_VERSION.asString(), WebSocketConstants.SPEC_VERSION_STRING);
      generated(
          statuses,
          HttpStatusCodes.UPGRADE_REQUIRED.value(),
          "Upgrade required",
          request,
          response);
      return false;
    }

    /**
     * Runs application admission gates while response output is disabled.
     *
     * @param request matched request
     * @param response live response
     * @throws Exception if an admission gate fails
     */
    @SuppressWarnings("java:S112") // Admission gates may throw checked application failures.
    private void runGates(Request request, Response response) throws Exception {
      if (gates.isEmpty()) {
        return;
      }

      response.gating(true);

      try {
        for (var gate : gates) {
          gate.handle(request, response);
        }
      } finally {
        response.gating(false);
      }
    }
  }

  /** Immutable registration snapshot published before the listener accepts requests. */
  private record DispatchConfiguration(
      RadixRoutes router,
      Map<Class<? extends Exception>, ExceptionHandler<Exception>> errors,
      Map<Integer, Handler> statuses,
      List<Handler> gates,
      List<Handler> headerHooks,
      List<Handler> matchedHooks,
      List<Handler> postRouteHooks,
      List<Handler> beforeFlushHooks,
      List<Handler> afterFlushHooks,
      List<Consumer<RequestOutcome>> observers,
      List<Function<Request, RequestObservation>> instrumentation) {
    /**
     * Copies registrations so clearing the mutable builders cannot affect live requests.
     *
     * @param router compiled route lookup
     * @param errors application exception handlers
     * @param statuses generated-status handlers
     * @param gates route admission gates
     * @param headerHooks request-header callbacks
     * @param matchedHooks route-match callbacks
     * @param postRouteHooks callbacks after a successful route
     * @param beforeFlushHooks callbacks before response submission
     * @param afterFlushHooks callbacks after response submission
     * @param observers terminal completion observers
     * @param instrumentation per-request observation factories
     */
    private DispatchConfiguration {
      Objects.requireNonNull(router);
      errors = Map.copyOf(errors);
      statuses = Map.copyOf(statuses);
      gates = List.copyOf(gates);
      headerHooks = List.copyOf(headerHooks);
      matchedHooks = List.copyOf(matchedHooks);
      postRouteHooks = List.copyOf(postRouteHooks);
      beforeFlushHooks = List.copyOf(beforeFlushHooks);
      afterFlushHooks = List.copyOf(afterFlushHooks);
      observers = List.copyOf(observers);
      instrumentation = List.copyOf(instrumentation);
    }
  }

  /**
   * Coordinates application finalization with transport termination without retaining live input.
   */
  private static final class Completion {
    private final String method;
    private final long started;
    private final List<Consumer<RequestOutcome>> observers;
    private final List<RequestObservation> scopes;
    private @Nullable String routePattern;
    private int status;
    private @Nullable Throwable transportFailure;
    private @Nullable Throwable applicationFailure;
    private boolean applicationFinished;
    private boolean transportFinished;
    private boolean notified;

    /**
     * Starts timing only when completion observers are configured.
     *
     * @param method wire method
     * @param observers immutable registration snapshot
     */
    private Completion(String method, List<Consumer<RequestOutcome>> observers) {
      this.method = method;
      this.observers = observers;
      scopes = new ArrayList<>();
      started = System.nanoTime();
    }

    /**
     * Creates scopes before any application work; a faulty observer cannot reject traffic.
     *
     * @param request invocation input, not retained
     * @param factories instrumentation snapshot
     */
    private void begin(Request request, List<Function<Request, RequestObservation>> factories) {
      for (var factory : factories) {
        try {
          scopes.add(Objects.requireNonNull(factory.apply(request)));
        } catch (RuntimeException failure) {
          System.getLogger(Shoostr.class.getName())
              .log(System.Logger.Level.ERROR, "Request instrumentation failed", failure);
        }
      }
    }

    /** Closes context scopes on the invocation thread in reverse nesting order. */
    private void closeScopes() {
      for (int index = scopes.size() - 1; index >= 0; index--) {
        try {
          scopes.get(index).close();
        } catch (RuntimeException failure) {
          System.getLogger(Shoostr.class.getName())
              .log(
                  System.Logger.Level.ERROR,
                  "Request instrumentation scope cleanup failed",
                  failure);
        }
      }
    }

    /**
     * Records application cleanup before allowing a terminal notification.
     *
     * @param routePattern selected named template, or null for unmatched requests
     * @param failure initiating processing failure, or null
     */
    private void finish(@Nullable String routePattern, @Nullable Throwable failure) {
      RequestOutcome outcome;
      synchronized (this) {
        this.routePattern = routePattern;
        if (applicationFailure == null) {
          applicationFailure = failure;
        } else if (failure != null && failure != applicationFailure) {
          applicationFailure.addSuppressed(failure);
        }

        applicationFinished = true;
        outcome = outcome();
      }
      notifyObservers(outcome);
    }

    /**
     * Records a listener-factory failure handled inside Jetty's synchronous creator callback.
     *
     * @param failure initiating factory exception
     */
    private void recordApplicationFailure(Throwable failure) {
      synchronized (this) {
        applicationFailure = failure;
      }
    }

    /**
     * Captures transport values while the server's response remains valid.
     *
     * @param status committed final status, or zero
     * @param failure terminal transport outcome, including application-triggered aborts
     */
    private void complete(int status, @Nullable Throwable failure) {
      RequestOutcome outcome;
      synchronized (this) {
        this.status = status;
        if (failure != null || transportFailure == null) {
          transportFailure = failure;
        }

        transportFinished = true;
        outcome = outcome();
      }
      notifyObservers(outcome);
    }

    /**
     * Retains an asynchronous response failure until the transport reports terminal completion.
     *
     * @param failure response callback failure
     */
    private void recordTransportFailure(Throwable failure) {
      synchronized (this) {
        if (transportFailure == null) {
          transportFailure = failure;
        }
      }
    }

    /**
     * Freezes one summary after both parties finish; caller holds this coordinator's monitor.
     *
     * @return summary to publish, or null while pending or already notified
     */
    private @Nullable RequestOutcome outcome() {
      if (!applicationFinished || !transportFinished || notified) {
        return null;
      }

      notified = true;
      return new RequestOutcome(
          method,
          routePattern,
          status,
          System.nanoTime() - started,
          applicationFailure,
          transportFailure);
    }

    /**
     * Dispatches outside the coordinator's monitor in registration order.
     *
     * @param outcome terminal snapshot, or null when no notification is due
     */
    private void notifyObservers(@Nullable RequestOutcome outcome) {
      if (outcome == null) {
        return;
      }

      for (var scope : scopes) {
        try {
          scope.complete(outcome);
        } catch (RuntimeException failure) {
          System.getLogger(Shoostr.class.getName())
              .log(System.Logger.Level.ERROR, "Request instrumentation completion failed", failure);
        }
      }

      for (var observer : observers) {
        try {
          observer.accept(outcome);
        } catch (RuntimeException failure) {
          System.getLogger(Shoostr.class.getName())
              .log(System.Logger.Level.ERROR, "Request completion observer failed", failure);
        }
      }
    }
  }
}
