package io.github.suppierk.shoostr.example.mcp;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import java.io.IOException;
import org.eclipse.jetty.server.ServerConnector;

/** Public loopback fixture using the SDK's own configuration and feature types. */
public final class McpExample {
  /** Prevents instantiation of the example factory. */
  private McpExample() {}

  /**
   * Starts the fixture; Shoostr owns JVM shutdown cleanup.
   *
   * @throws Exception if startup fails
   */
  static void main() throws Exception {
    var app = application(Options.defaults());

    try {
      app.modifyServer(
          server ->
              ((ServerConnector) server.getConnectors()[0])
                  .setHost(System.getProperty("shoostr.host", "127.0.0.1")));
      app.start();
    } catch (Exception | Error failure) {
      try {
        app.close();
      } catch (IOException cleanup) {
        failure.addSuppressed(cleanup);
      }

      throw failure;
    }

    System.out.println("MCP endpoint: http://127.0.0.1:" + app.port() + "/mcp");
  }

  /**
   * Creates an unstarted application with explicit native SDK registrations.
   *
   * @param options listener and resource settings
   * @return app owned by its eventual caller or test fixture
   */
  public static Shoostr application(Options options) {
    var app = new Shoostr(options);

    try {
      var mcp = McpConfiguration.create(options);
      return app.extensions(mcp).routes(routes -> McpRoutes.register(routes, mcp));
    } catch (RuntimeException | Error failure) {
      try {
        app.close();
      } catch (IOException cleanup) {
        failure.addSuppressed(cleanup);
      }

      throw failure;
    }
  }
}
