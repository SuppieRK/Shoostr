package io.github.suppierk.shoostr.example.mcp;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Public MCP example using the SDK's own configuration and feature types. */
public final class McpExample {
  private static final Logger LOGGER = LoggerFactory.getLogger(McpExample.class);

  /** Prevents instantiation of the example factory. */
  private McpExample() {}

  /**
   * Starts the fixture; Shoostr owns JVM shutdown cleanup.
   *
   * @throws Exception if startup fails
   */
  static void main() throws Exception {
    var app = application();

    try {
      app.start();
    } catch (Exception | Error failure) {
      try {
        app.close();
      } catch (IOException cleanup) {
        failure.addSuppressed(cleanup);
      }

      throw failure;
    }

    LOGGER.info("MCP endpoint: http://127.0.0.1:{}/mcp", app.port());
  }

  /**
   * Creates an unstarted MCP application with default listener settings.
   *
   * @return application owned by its eventual caller
   */
  public static Shoostr application() {
    return application(Options.defaults());
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
      return app.extensions(mcp)
          .routes(
              routes -> {
                routes.get("/health", (_, response) -> response.text("healthy"));
                routes.path("/mcp", mcp::routes);
              });
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
