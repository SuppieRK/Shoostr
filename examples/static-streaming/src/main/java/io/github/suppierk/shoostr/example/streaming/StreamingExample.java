package io.github.suppierk.shoostr.example.streaming;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import org.eclipse.jetty.server.ServerConnector;

/** Composes one file-and-stream example without managing a second server. */
public final class StreamingExample {
  /** Prevents construction of the launcher. */
  private StreamingExample() {}

  /**
   * Starts the standalone example using the framework's existing shutdown hook.
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
  }

  /**
   * Returns an unstarted app whose lifecycle belongs to its caller.
   *
   * @param options listener settings
   * @return app with file and stream handlers
   */
  public static Shoostr application(Options options) {
    return new Shoostr(options).routes(StreamingRoutes::register);
  }
}
