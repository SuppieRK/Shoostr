package io.github.suppierk.shoostr.testing;

import io.github.suppierk.shoostr.Shoostr;
import java.io.Closeable;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Consumer;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;

/** A real HTTP fixture owning the supplied application's lifetime on loopback. */
public final class TestServer implements Closeable {
  private final Shoostr app;
  private final URI baseUri;
  private final HttpClient client;
  private volatile boolean closed;

  /**
   * Keeps the started application for the test scope.
   *
   * @param app started application owned by this fixture
   * @param baseUri actual default listener origin
   * @param client HTTP client owned by this fixture
   */
  private TestServer(Shoostr app, URI baseUri, HttpClient client) {
    this.app = app;
    this.baseUri = baseUri;
    this.client = client;
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
  @SuppressWarnings("java:S1181") // Cleanup must run even when startup or cleanup throws Error.
  public static TestServer start(Shoostr app, Consumer<HttpClient.Builder> configureClient)
      throws Exception {
    Objects.requireNonNull(app);
    Objects.requireNonNull(configureClient);
    var connectors = new ArrayList<ServerConnector>();
    synchronized (app) {
      // Registration rejects ineligible apps before this fixture acquires ownership.
      app.modifyServer(
          server -> {
            for (var connector : server.getConnectors()) {
              if (!(connector instanceof ServerConnector listener) || listener.getLocalPort() > 0) {
                throw new IllegalArgumentException(
                    "TestServer requires unbound ServerConnector listeners");
              }

              connectors.add(listener);
            }

            for (var listener : connectors) {
              listener.setHost("127.0.0.1");
              listener.setPort(0);
              listener.setInheritChannel(false);
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
        var port = app.port();
        var secure =
            connectors.stream()
                .anyMatch(
                    listener ->
                        listener.getLocalPort() == port
                            && listener.getConnectionFactories().stream()
                                .anyMatch(SslConnectionFactory.class::isInstance));
        return new TestServer(
            app, URI.create((secure ? "https" : "http") + "://127.0.0.1:" + port + "/"), client);
      } catch (Exception | Error failure) {
        try (app) {
          if (client != null) {
            closeClient(client);
          }
        } catch (Exception | Error cleanup) {
          failure.addSuppressed(cleanup);
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

    try (app) {
      closeClient(client);
    }
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
