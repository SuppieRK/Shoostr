package io.github.suppierk.shoostr.testing;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import java.util.function.Consumer;

/** A scoped HTTP test server bound to an operating-system-selected loopback port. */
public final class TestServer implements Closeable {
  private final Shoostr app;

  /**
   * Keeps the started application for the test scope.
   *
   * @param app started application owned by this fixture
   */
  private TestServer(Shoostr app) {
    this.app = app;
  }

  /**
   * Configures and starts an isolated application. Closing the returned fixture stops the server.
   * If configuration or startup fails, acquired resources are closed before the failure escapes.
   *
   * @param configure route and server configuration callback run before startup
   * @return started fixture
   * @throws Exception if configuration or startup fails
   * @throws NullPointerException if configure is null
   */
  @SuppressWarnings("java:S1181") // Startup failure must close the acquired server even for Error.
  public static TestServer start(Consumer<Shoostr> configure) throws Exception {
    Objects.requireNonNull(configure);
    var app = new Shoostr(Options.defaults().withPort(0));

    try {
      configure.accept(app);
      app.start();
      return new TestServer(app);
    } catch (Exception | Error failure) {
      try {
        app.close();
      } catch (IOException cleanup) {
        failure.addSuppressed(cleanup);
      }

      throw failure;
    }
  }

  /**
   * Returns the loopback base URI while the fixture is running. Resolve a relative route path
   * against this URI to issue a test request.
   *
   * @return base HTTP URI with a trailing slash
   * @throws IllegalStateException if the server is closed
   */
  public URI baseUri() {
    return URI.create("http://127.0.0.1:" + app.port() + "/");
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
