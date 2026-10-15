package io.github.suppierk.shoostr.example.mcp;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
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
    app.start();
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
   * <p>S2095 is suppressed because the caller owns the returned app, which owns the immediately
   * installed MCP extension and its resources.
   *
   * @param options listener and resource settings
   * @return app owned by its eventual caller or test fixture
   */
  @SuppressWarnings("java:S2095")
  public static Shoostr application(Options options) {
    // Options comes from Shoostr core and configures the listener, not the SDK's MCP features.
    var app = new Shoostr(options);

    // This example-local factory builds Shoostr's mcp extension using the official MCP Java SDK.
    var mcp = McpConfiguration.create(options);
    // Install it for lifecycle management; installation alone does not register HTTP endpoints.
    return app.extensions(mcp)
        .routes(
            routes -> {
              routes.get("/health", (_, response) -> response.text("healthy"));
              // The extension contributes its protocol handlers beneath Shoostr's /mcp route group.
              routes.path("/mcp", mcp::routes);
            });
  }
}
