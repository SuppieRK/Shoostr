package io.github.suppierk.shoostr.testing;

import io.github.suppierk.shoostr.Shoostr;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.BindException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.channels.ServerSocketChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.jspecify.annotations.Nullable;

/** A real HTTP and WebSocket fixture owning the supplied application's lifetime on loopback. */
public final class TestServer implements Closeable {
  private static final Map<Shoostr, ReentrantLock> STARTUP_LOCKS = new WeakHashMap<>();
  private static final ReentrantLock STARTUP_LOCKS_GUARD = new ReentrantLock();

  private final Shoostr app;
  private final URI baseUri;
  private final HttpClient client;
  private final @Nullable ServerSocketChannel reservation;
  private final Set<TestWebSocket> webSockets;
  private final ReentrantLock closeLock;
  private volatile boolean closed;

  /**
   * Keeps the started application for the test scope.
   *
   * @param app started application owned by this fixture
   * @param baseUri actual default listener origin
   * @param client HTTP client owned by this fixture
   * @param reservation pre-start socket binding, if a fixed port or range was requested
   */
  private TestServer(
      Shoostr app, URI baseUri, HttpClient client, @Nullable ServerSocketChannel reservation) {
    this.app = app;
    this.baseUri = baseUri;
    this.client = client;
    this.reservation = reservation;
    this.webSockets = ConcurrentHashMap.newKeySet();
    this.closeLock = new ReentrantLock();
    this.closed = false;
  }

  /**
   * Starts an existing unstarted application on automatic loopback ports. Configure routes,
   * extensions and dependencies before calling this method. The same application is closed when
   * this fixture closes or startup fails. Rejected running/closed apps are not closed by this
   * method. All listeners must be unbound Jetty ServerConnectors; native TLS/HTTP2 settings are
   * preserved. Application lifecycle must not be independently controlled while the fixture owns
   * it.
   *
   * @param app existing application whose lifetime transfers to the fixture
   * @return started fixture
   * @throws Exception if configuration or startup fails
   * @throws NullPointerException if app is null
   */
  public static TestServer start(Shoostr app) throws Exception {
    return start(app, ignored -> {});
  }

  /**
   * Starts the supplied app with optional customization of its owned JDK client. Defaults are
   * applied first: isolated cookies, redirects off, no proxy, normal protocol/TLS negotiation and a
   * three-second connect timeout. A supplied executor remains caller-owned. Rejected apps do not
   * invoke the callback; failure after ownership transfers closes acquired resources.
   *
   * @param app existing unstarted application
   * @param configureClient callback configuring only the fixture-owned client
   * @return started fixture
   * @throws Exception if client configuration or application startup fails
   * @throws NullPointerException if an argument is null
   */
  public static TestServer start(Shoostr app, Consumer<HttpClient.Builder> configureClient)
      throws Exception {
    return startFixture(app, 0, 0, configureClient);
  }

  /**
   * Starts a single-listener app on an exact loopback port, without automatic fallback.
   *
   * @param app existing unstarted application owned by the fixture
   * @param port exact port between 1 and 65535
   * @return started fixture
   * @throws Exception if the listener cannot bind or startup fails
   * @throws IllegalArgumentException if the port is invalid or multiple listeners are configured
   * @throws NullPointerException if app is null
   */
  public static TestServer startOnPort(Shoostr app, int port) throws Exception {
    return startOnPort(app, port, ignoredClientBuilder -> {});
  }

  /**
   * Starts a single-listener app on an exact loopback port with an owned-client builder callback.
   * Client defaults and ownership are the same as {@link #start(Shoostr, Consumer)}.
   *
   * @param app existing unstarted application owned by the fixture
   * @param port exact port between 1 and 65535
   * @param configureClient callback applied after client defaults
   * @return started fixture
   * @throws Exception if client configuration, binding or startup fails
   * @throws IllegalArgumentException if the port is invalid or multiple listeners are configured
   * @throws NullPointerException if an argument is null
   */
  public static TestServer startOnPort(
      Shoostr app, int port, Consumer<HttpClient.Builder> configureClient) throws Exception {
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("Fixed test port must be between 1 and 65535");
    }

    return startFixture(app, port, port, configureClient);
  }

  /**
   * Starts a single-listener app on the first bindable loopback port in an inclusive range.
   * Candidates are tried in ascending order; exhaustion fails without out-of-range fallback.
   *
   * @param app existing unstarted application owned by the fixture
   * @param minimum inclusive minimum port between 1 and 65535
   * @param maximum inclusive maximum port between minimum and 65535
   * @return started fixture reporting the actual selected port
   * @throws Exception if no candidate binds or startup fails
   * @throws IllegalArgumentException if bounds are invalid or multiple listeners are configured
   * @throws NullPointerException if app is null
   */
  public static TestServer startOnPortRange(Shoostr app, int minimum, int maximum)
      throws Exception {
    return startOnPortRange(app, minimum, maximum, ignoredClientBuilder -> {});
  }

  /**
   * Starts a single-listener app within an inclusive range with an owned-client builder callback.
   * Client defaults and ownership are the same as {@link #start(Shoostr, Consumer)}. A successful
   * binding remains reserved until shutdown; bind failures are retained as exception causes.
   *
   * @param app existing unstarted application owned by the fixture
   * @param minimum inclusive minimum port between 1 and 65535
   * @param maximum inclusive maximum port between minimum and 65535
   * @param configureClient callback applied after client defaults
   * @return started fixture reporting the actual selected port
   * @throws Exception if client configuration, binding or startup fails
   * @throws IllegalArgumentException if bounds are invalid or multiple listeners are configured
   * @throws NullPointerException if an argument is null
   */
  public static TestServer startOnPortRange(
      Shoostr app, int minimum, int maximum, Consumer<HttpClient.Builder> configureClient)
      throws Exception {
    if (minimum < 1 || minimum > maximum || maximum > 65535) {
      throw new IllegalArgumentException(
          "Test port range must satisfy 1 <= minimum <= maximum <= 65535");
    }

    return startFixture(app, minimum, maximum, configureClient);
  }

  /**
   * Applies test transport settings through native configuration and owns startup cleanup.
   *
   * @param app existing unstarted application
   * @param minimum automatic port zero or a validated positive minimum
   * @param maximum automatic port zero or a validated positive maximum
   * @param configureClient owned-client configuration
   * @return started fixture
   * @throws Exception if configuration or startup fails
   */
  @SuppressWarnings("java:S1181") // Cleanup must also run after Error.
  private static TestServer startFixture(
      Shoostr app, int minimum, int maximum, Consumer<HttpClient.Builder> configureClient)
      throws Exception {
    Objects.requireNonNull(app);
    Objects.requireNonNull(configureClient);
    final var connectors = new ArrayList<ServerConnector>();
    final var reservation = new AtomicReference<@Nullable ServerSocketChannel>();
    final var bindingBridge = new AtomicReference<@Nullable UncheckedIOException>();
    final var lock = startupLock(app);
    lock.lock();

    try {
      // Registration rejects ineligible apps before this fixture acquires ownership.
      app.modifyServer(server ->
          configureListeners(server, minimum, maximum, connectors, reservation, bindingBridge));

      HttpClient client = null;

      try {
        client = createClient(configureClient);

        app.start();
        final var boundPort = app.port();
        final var secure = usesSecureListener(connectors, boundPort);

        return new TestServer(
            app,
            URI.create((secure ? "https" : "http") + "://127.0.0.1:" + boundPort + "/"),
            client,
            reservation.get());
      } catch (Exception | Error failure) {
        try {
          closeResources(app, client, reservation.get());
        } catch (Exception | Error cleanup) {
          failure.addSuppressed(cleanup);
        }

        final var bridge = bindingBridge.get();
        if (bridge != null && bridge.equals(failure)) {
          final var original = Objects.requireNonNull(bridge.getCause());
          for (final var cleanup : failure.getSuppressed()) {
            original.addSuppressed(cleanup);
          }

          throw original;
        }

        throw failure;
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Finds the startup lock shared by attempts for the same application. The weak keys let the
   * registry release applications after their owners do.
   *
   * @param app application supplied to the fixture
   * @return lock for this application's startup attempts
   */
  private static ReentrantLock startupLock(Shoostr app) {
    STARTUP_LOCKS_GUARD.lock();

    try {
      return STARTUP_LOCKS.computeIfAbsent(app, ignoredApp -> new ReentrantLock());
    } finally {
      STARTUP_LOCKS_GUARD.unlock();
    }
  }

  /**
   * Validates every listener before changing addresses, then reserves a requested named port.
   *
   * @param server application server during native configuration
   * @param minimum automatic zero or validated range minimum
   * @param maximum automatic zero or validated range maximum
   * @param connectors listener identities retained for default-origin discovery
   * @param reservation channel retained for startup-failure cleanup
   * @param bindingBridge exact wrapper retained to restore checked binding failures
   * @throws IllegalArgumentException if the app has unsupported or already bound listeners
   */
  private static void configureListeners(
      Server server,
      int minimum,
      int maximum,
      List<ServerConnector> connectors,
      AtomicReference<@Nullable ServerSocketChannel> reservation,
      AtomicReference<@Nullable UncheckedIOException> bindingBridge) {
    if (minimum > 0 && server.getConnectors().length != 1) {
      throw new IllegalArgumentException("Fixed/range test ports require exactly one listener");
    }

    for (final var connector : server.getConnectors()) {
      if (!(connector instanceof ServerConnector listener) || listener.getLocalPort() > 0) {
        throw new IllegalArgumentException("TestServer requires unbound ServerConnector listeners");
      }

      connectors.add(listener);
    }

    for (final var listener : connectors) {
      listener.setHost("127.0.0.1");
      listener.setPort(minimum);
      listener.setInheritChannel(false);
    }

    if (minimum > 0) {
      reservePort(connectors.getFirst(), minimum, maximum, reservation, bindingBridge);
    }
  }

  /**
   * Retains Jetty's actual binding and bridges checked bind errors through its consumer callback.
   *
   * @param listener validated single listener
   * @param minimum inclusive first candidate
   * @param maximum inclusive last candidate
   * @param reservation channel retained even when later startup fails
   * @param bindingBridge exact wrapper identity retained for exception restoration
   */
  private static void reservePort(
      ServerConnector listener,
      int minimum,
      int maximum,
      AtomicReference<@Nullable ServerSocketChannel> reservation,
      AtomicReference<@Nullable UncheckedIOException> bindingBridge) {
    try {
      bind(listener, minimum, maximum);
    } catch (IOException failure) {
      final var bridge = new UncheckedIOException("Could not bind requested test ports", failure);
      bindingBridge.set(bridge);
      throw bridge;
    } finally {
      if (listener.getTransport() instanceof ServerSocketChannel channel) {
        reservation.set(channel);
      }
    }
  }

  /**
   * Retains the first successful actual bind without probing and releasing a candidate socket.
   * Retries only I/O failures caused by a bind rejection; unrelated I/O failures abort selection.
   *
   * @param listener unbound single listener
   * @param minimum inclusive validated minimum
   * @param maximum inclusive validated maximum
   * @throws IOException if no candidate binds or another I/O failure occurs
   */
  private static void bind(ServerConnector listener, int minimum, int maximum) throws IOException {
    IOException firstFailure = null;

    for (var port = minimum; port <= maximum; port++) {
      listener.setPort(port);

      try {
        listener.open();
        return;
      } catch (IOException failure) {
        if (!isBindFailure(failure)) {
          throw failure;
        }

        if (firstFailure == null) {
          firstFailure = failure;
        }
      }
    }

    throw Objects.requireNonNull(firstFailure);
  }

  /**
   * Recognizes a bind rejection even when Jetty wraps it in another I/O exception.
   *
   * @param failure binding failure
   * @return whether a cause is a bind exception
   */
  private static boolean isBindFailure(IOException failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof BindException) {
        return true;
      }
    }

    return false;
  }

  /**
   * Determines the scheme from the connector that owns the bound port.
   *
   * @param connectors configured listener identities
   * @param boundPort port selected during startup
   * @return whether the bound connector supplies TLS
   */
  private static boolean usesSecureListener(List<ServerConnector> connectors, int boundPort) {
    return connectors.stream()
        .anyMatch(listener ->
            listener.getLocalPort() == boundPort
                && listener.getConnectionFactories().stream()
                    .anyMatch(SslConnectionFactory.class::isInstance));
  }

  /**
   * Creates the owned client inside the application's startup rollback scope.
   *
   * @param configureClient caller customization applied after fixture defaults
   * @return the client immediately retained by the fixture's cleanup owner
   */
  private static HttpClient createClient(Consumer<HttpClient.Builder> configureClient) {
    final var builder = clientProperties();
    configureClient.accept(builder);

    return builder.build();
  }

  /**
   * Configures the isolated client defaults before caller customization.
   *
   * @return builder carrying the fixture transport and cookie policy
   */
  private static HttpClient.Builder clientProperties() {
    return HttpClient.newBuilder()
        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER))
        .followRedirects(HttpClient.Redirect.NEVER)
        .proxy(HttpClient.Builder.NO_PROXY)
        .connectTimeout(Duration.ofSeconds(3));
  }

  /**
   * Cancels active exchanges before bounded shutdown so an unread body cannot stall cleanup.
   *
   * @param client owned JDK client
   * @throws IOException if bounded termination fails or the calling thread is interrupted
   */
  private static void closeClient(HttpClient client) throws IOException {
    client.shutdownNow();

    try {
      if (!client.awaitTermination(Duration.ofSeconds(3))) {
        throw new IOException("HTTP test client did not terminate within three seconds");
      }

      client.close();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while closing HTTP test client", interrupted);
    }
  }

  /**
   * Sends one configured request and consumes its response as exact bytes, including error bodies.
   *
   * @param configure synchronous request configuration callback
   * @return ordinary JDK response with a byte-array body
   * @throws IOException if transport fails
   * @throws InterruptedException if the calling thread is interrupted
   * @throws NullPointerException if configure is null
   * @throws IllegalStateException if the fixture is closed or no path is configured
   */
  public HttpResponse<byte[]> send(Consumer<TestRequest> configure)
      throws IOException, InterruptedException {
    return send(configure, HttpResponse.BodyHandlers.ofByteArray());
  }

  /**
   * Sends one configured request using an explicit JDK body handler. The caller must consume or
   * close returned streams and cancel subscriptions; fixture shutdown cancels outstanding work.
   *
   * @param configure synchronous request configuration callback
   * @param bodyHandler explicit response consumption strategy
   * @param <T> response body type
   * @return ordinary JDK response
   * @throws IOException if transport fails
   * @throws InterruptedException if the calling thread is interrupted
   * @throws NullPointerException if an argument is null
   * @throws IllegalStateException if the fixture is closed or no path is configured
   */
  public <T> HttpResponse<T> send(
      Consumer<TestRequest> configure, HttpResponse.BodyHandler<T> bodyHandler)
      throws IOException, InterruptedException {
    Objects.requireNonNull(configure);
    Objects.requireNonNull(bodyHandler);
    final var request = new TestRequest(baseUri());
    HttpRequest outgoing;

    try {
      configure.accept(request);
      outgoing = request.build();
    } finally {
      request.finish();
    }

    return client.send(outgoing, bodyHandler);
  }

  /**
   * Opens a WebSocket using this fixture's client and a three-second handshake timeout.
   *
   * @param path relative route path, optionally including a raw query
   * @return connected test socket, also owned by this fixture
   * @throws IOException if the handshake or transport fails
   * @throws InterruptedException if the caller is interrupted while opening the socket
   */
  public TestWebSocket openWebSocket(String path) throws IOException, InterruptedException {
    return openWebSocket(path, ignored -> {});
  }

  /**
   * Opens a WebSocket through the fixture's configured client. The callback can set handshake
   * headers, subprotocols and the handshake timeout. Close the returned socket to release it early.
   * A rejected handshake preserves the JDK exception and its HTTP response.
   *
   * @param path relative route path, optionally including a raw query
   * @param configure synchronous WebSocket builder configuration
   * @return connected test socket, also owned by this fixture
   * @throws IOException if the handshake or transport fails
   * @throws InterruptedException if the caller is interrupted while opening the socket
   * @throws IllegalStateException if the fixture is closed
   */
  public TestWebSocket openWebSocket(String path, Consumer<WebSocket.Builder> configure)
      throws IOException, InterruptedException {
    Objects.requireNonNull(configure);
    final var target = TestRequest.resolvePath(baseUri(), path);
    final var scheme = "https".equals(target.getScheme()) ? "wss" : "ws";
    // Replacing only the scheme keeps the encoded path and query in the resolved URI.
    final var uri = URI.create(scheme + target.toString().substring(target.getScheme().length()));
    final var builder = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3));
    configure.accept(builder);
    final var socket = new TestWebSocket(webSockets::remove);

    try {
      socket.awaitOpen(startWebSocket(socket, builder, uri));
      return socket;
    } catch (IOException | InterruptedException | RuntimeException failure) {
      try (socket) {
        throw failure;
      }
    }
  }

  /**
   * Fills named path segments and appends query parameters before configuring the handshake. Both
   * callbacks run synchronously before the fixture starts the upgrade.
   *
   * @param template relative path template with an optional raw query
   * @param configurePath path parameter and query configuration
   * @param configureHandshake JDK WebSocket builder configuration
   * @return connected test socket, also owned by this fixture
   * @throws IOException if the handshake or transport fails
   * @throws InterruptedException if the caller is interrupted while opening the socket
   * @throws IllegalArgumentException if the template or a parameter is invalid
   * @throws IllegalStateException if the fixture is closed or a placeholder lacks a value
   * @throws NullPointerException if an argument is null
   */
  public TestWebSocket openWebSocket(
      String template,
      Consumer<TestRequest.PathBuilder> configurePath,
      Consumer<WebSocket.Builder> configureHandshake)
      throws IOException, InterruptedException {
    Objects.requireNonNull(configureHandshake);
    baseUri();

    return openWebSocket(
        TestRequest.buildPathFromTemplate(template, configurePath), configureHandshake);
  }

  /**
   * Registers the pending connection before releasing the fixture lock to wait for the upgrade.
   *
   * @param socket fixture-owned test socket
   * @param builder configured JDK handshake builder
   * @param uri absolute WebSocket target on the fixture origin
   * @return pending handshake
   */
  private CompletableFuture<WebSocket> startWebSocket(
      TestWebSocket socket, WebSocket.Builder builder, URI uri) {
    closeLock.lock();

    try {
      baseUri();
      webSockets.add(socket);
      return socket.start(builder, uri);
    } finally {
      closeLock.unlock();
    }
  }

  /**
   * Returns the actual loopback origin for browser debugging or independently owned native clients.
   * Ordinary requests use {@link #send(Consumer)} instead of assembling URLs.
   *
   * @return base HTTP or HTTPS URI with a trailing slash
   * @throws IllegalStateException if the server is closed
   */
  public URI baseUri() {
    if (closed) {
      throw new IllegalStateException("TestServer is closed");
    }

    app.port();

    return baseUri;
  }

  /**
   * Makes owned-client termination independently observable by the harness's lifetime tests.
   *
   * @return fixture-owned client while running
   * @throws IllegalStateException if the fixture is closed
   */
  HttpClient httpClient() {
    baseUri();

    return client;
  }

  /**
   * Closes test sockets, cancels owned client work, then stops the application and releases its
   * listener. Safe to call repeatedly. Waits at most three seconds for client termination.
   * Application shutdown retains its native drain timeout and reports stop failures; it does not
   * promise to finish caller-owned work or an uncooperative handler.
   *
   * @throws IOException if the application cannot stop cleanly
   */
  @Override
  public void close() throws IOException {
    closeLock.lock();

    try {
      if (closed) {
        return;
      }

      closed = true;

      try (Closeable resources = () -> closeResources(app, client, reservation)) {
        closeWebSockets();
      }
    } finally {
      closeLock.unlock();
    }
  }

  /** Releases every socket even when one fails during local cleanup. */
  private void closeWebSockets() {
    Throwable failure = null;
    for (final var socket : List.copyOf(webSockets)) {
      try {
        socket.close();
      } catch (RuntimeException cleanup) {
        if (failure == null) {
          failure = cleanup;
        } else if (!failure.equals(cleanup)) {
          failure.addSuppressed(cleanup);
        }
      }
    }

    if (failure instanceof RuntimeException ordinary) {
      throw ordinary;
    }
  }

  /**
   * Closes resources in ownership order after failed startup or normal fixture use.
   *
   * @param app owned application
   * @param client owned client, or null if creation failed
   * @param reservation listener reservation, or null if none was acquired
   * @throws IOException if an owned resource cannot be closed cleanly
   */
  private static void closeResources(
      Shoostr app, @Nullable HttpClient client, @Nullable ServerSocketChannel reservation)
      throws IOException {
    try (reservation;
        Closeable application = app::close) {
      if (client != null) {
        closeClient(client);
      }
    }
  }
}
