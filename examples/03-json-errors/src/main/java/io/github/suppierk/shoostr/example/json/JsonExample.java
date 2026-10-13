package io.github.suppierk.shoostr.example.json;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import org.eclipse.jetty.server.ServerConnector;

/** Wires the codec and handlers without adding serialization to the framework core. */
public final class JsonExample {
  /** Prevents construction of the launcher. */
  private JsonExample() {}

  /**
   * Starts the app and leaves shutdown ownership to Shoostr.
   *
   * @throws Exception if startup fails
   */
  static void main() throws Exception {
    var app = application(Options.defaults());
    app.modifyServer(
        server ->
            ((ServerConnector) server.getConnectors()[0])
                .setHost(System.getProperty("shoostr.host", "127.0.0.1")));
    app.start();
    System.out.println("JSON: http://127.0.0.1:" + app.port() + "/users");
  }

  /**
   * Creates the unstarted JSON app; tests use its actual HTTP interface.
   *
   * @param options listener settings
   * @return application owned by the caller
   */
  public static Shoostr application(Options options) {
    var app = new Shoostr(options);
    app.codec(new UserCodec()).routes(UserRoutes::register);
    return app;
  }
}
