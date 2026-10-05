package io.github.suppierk.shoostr.testing;

import io.github.suppierk.shoostr.Shoostr;
import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Objects;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;

/** A real HTTP fixture owning the supplied application's lifetime on loopback. */
public final class TestServer implements Closeable {
  private final Shoostr app;
  private final URI baseUri;

  /**
   * Keeps the started application for the test scope.
   *
   * @param app started application owned by this fixture
   * @param baseUri actual default listener origin
   */
  private TestServer(Shoostr app, URI baseUri) {
    this.app = app;
    this.baseUri = baseUri;
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
  @SuppressWarnings("java:S1181") // Startup failure must close the acquired server even for Error.
  public static TestServer start(Shoostr app) throws Exception {
    Objects.requireNonNull(app);
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

      try {
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
            app, URI.create((secure ? "https" : "http") + "://127.0.0.1:" + port + "/"));
      } catch (Exception | Error failure) {
        try {
          app.close();
        } catch (IOException cleanup) {
          failure.addSuppressed(cleanup);
        }

        throw failure;
      }
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
    app.port();
    return baseUri;
  }

  /**
   * Stops the application and releases its listener. Safe to call repeatedly.
   *
   * @throws IOException if the application cannot stop cleanly
   */
  @Override
  public void close() throws IOException {
    app.close();
  }
}
