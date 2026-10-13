package io.github.suppierk.shoostr.example.hello;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import org.eclipse.jetty.server.ServerConnector;

/** Composes the hello application; Shoostr owns the listener and shutdown hook. */
public final class HelloExample {
  /** Prevents construction of the launcher. */
  private HelloExample() {}

  /**
   * Starts a loopback server, or the explicitly configured container bind address.
   *
   * @throws Exception if the listener cannot start
   */
  static void main() throws Exception {
    var app = application(Options.defaults());
    app.modifyServer(
        server ->
            ((ServerConnector) server.getConnectors()[0])
                .setHost(System.getProperty("shoostr.host", "127.0.0.1")));
    app.start();
    System.out.println("Hello: http://127.0.0.1:" + app.port() + "/hello");
  }

  /**
   * Returns an unstarted app; callers or TestServer own its eventual lifetime.
   *
   * @param options listener settings
   * @return configured application
   */
  public static Shoostr application(Options options) {
    return new Shoostr(options).routes(HelloRoutes::register);
  }
}
