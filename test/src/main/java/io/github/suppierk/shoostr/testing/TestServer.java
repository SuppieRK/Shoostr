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
import java.net.http.HttpResponse;
import java.nio.channels.ServerSocketChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.jspecify.annotations.Nullable;

/** A real HTTP fixture owning the supplied application's lifetime on loopback. */
public final class TestServer implements Closeable {
  private final Shoostr app;
  private final URI baseUri;
  private final HttpClient client;
  private final @Nullable ServerSocketChannel reservation;
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
    closed = false;
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
    return start(app, _ -> {});
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
    return start(app, 0, 0, configureClient);
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
    return startOnPort(app, port, _ -> {});
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

    return start(app, port, port, configureClient);
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
    return startOnPortRange(app, minimum, maximum, _ -> {});
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

    return start(app, minimum, maximum, configureClient);
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
  @SuppressWarnings("java:S1181") // Cleanup must run even when startup or cleanup throws Error.
  private static TestServer start(
      Shoostr app, int minimum, int maximum, Consumer<HttpClient.Builder> configureClient)
      throws Exception {
    Objects.requireNonNull(app);
    Objects.requireNonNull(configureClient);
    var connectors = new ArrayList<ServerConnector>();
    var reservation = new AtomicReference<@Nullable ServerSocketChannel>();
    var bindingBridge = new AtomicReference<@Nullable UncheckedIOException>();
    synchronized (app) {
      // Registration rejects ineligible apps before this fixture acquires ownership.
      app.modifyServer(
          server -> {
            if (minimum > 0 && server.getConnectors().length != 1) {
              throw new IllegalArgumentException(
                  "Fixed/range test ports require exactly one listener");
            }

            for (var connector : server.getConnectors()) {
              if (!(connector instanceof ServerConnector listener) || listener.getLocalPort() > 0) {
                throw new IllegalArgumentException(
                    "TestServer requires unbound ServerConnector listeners");
              }

              connectors.add(listener);
            }

            for (var listener : connectors) {
              listener.setHost("127.0.0.1");
              listener.setPort(minimum);
              listener.setInheritChannel(false);
            }

            if (minimum > 0) {
              var listener = connectors.getFirst();

              try {
                bind(listener, minimum, maximum);
              } catch (IOException failure) {
                var bridge =
                    new UncheckedIOException("Could not bind requested test ports", failure);
                bindingBridge.set(bridge);
                throw bridge;
              } finally {
                if (listener.getTransport() instanceof ServerSocketChannel channel) {
                  reservation.set(channel);
                }
              }
            }
          });

      HttpClient client = null;

      try {
        var builder =
            HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(HttpClient.Builder.NO_PROXY)
                .connectTimeout(Duration.ofSeconds(3));
        configureClient.accept(builder);
        client = builder.build();
        app.start();
        var boundPort = app.port();
        var secure =
            connectors.stream()
                .anyMatch(
                    listener ->
                        listener.getLocalPort() == boundPort
                            && listener.getConnectionFactories().stream()
                                .anyMatch(SslConnectionFactory.class::isInstance));
        return new TestServer(
            app,
            URI.create((secure ? "https" : "http") + "://127.0.0.1:" + boundPort + "/"),
            client,
            reservation.get());
      } catch (Exception | Error failure) {
        try (var _ = reservation.get();
            app) {
          if (client != null) {
            closeClient(client);
          }
        } catch (Exception | Error cleanup) {
          failure.addSuppressed(cleanup);
        }

        if (failure == bindingBridge.get()) {
          var original = Objects.requireNonNull(((UncheckedIOException) failure).getCause());
          for (var cleanup : failure.getSuppressed()) {
            original.addSuppressed(cleanup);
          }

          throw original;
        }

        throw failure;
      }
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
    var request = new TestRequest(baseUri());

    try {
      configure.accept(request);
      return client.send(request.build(), bodyHandler);
    } finally {
      request.finish();
    }
  }

  /**
   * Returns the loopback base URI while the fixture is running. Resolve a relative route path
   * against this URI to issue a test request.
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
   * Borrows the owned JDK client for async requests, WebSockets or advanced body publishers. Do not
   * close it separately. Requests made directly through it need their own URI and timeout; callers
   * must consume/cancel their streams, subscriptions and WebSockets.
   *
   * @return fixture-owned client while running
   * @throws IllegalStateException if the fixture is closed
   */
  public HttpClient httpClient() {
    baseUri();
    return client;
  }

  /**
   * Cancels owned client work, then stops the application and releases its listener. Safe to call
   * repeatedly. Waits at most three seconds for client termination, not indefinitely for a body.
   *
   * @throws IOException if the application cannot stop cleanly
   */
  @Override
  public synchronized void close() throws IOException {
    if (closed) {
      return;
    }

    closed = true;

    try (var _ = reservation;
        app) {
      closeClient(client);
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

    for (int port = minimum; port <= maximum; port++) {
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
}
