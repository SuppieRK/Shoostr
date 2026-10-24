package io.github.suppierk.shoostr.example.mcp;

import io.github.suppierk.shoostr.Shoostr;
import org.eclipse.jetty.server.ServerConnector;
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
   * @param args unused command-line arguments
   * @throws Exception if startup fails
   */
  public static void main(String[] args) throws Exception {
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
    return application(8080);
  }

  /**
   * Creates an unstarted application with explicit native SDK registrations.
   *
   * <p>The app owns the installed MCP extension and its resources.
   *
   * @param port listening port and the local public URL's port
   * @return app owned by its eventual caller or test fixture
   */
  public static Shoostr application(int port) {
    // Configure Jetty's listener separately from the SDK's MCP features.
    var app =
        new Shoostr()
            .modifyServer(server -> ((ServerConnector) server.getConnectors()[0]).setPort(port));

    // This example-local factory builds Shoostr's mcp extension using the official MCP Java SDK.
    var mcp = McpConfiguration.create(port);
    // Install it for lifecycle management; installation alone does not register HTTP endpoints.
    return app.extensions(mcp)
        .routes(
            routes -> {
              routes.get("/health", (ignored, response) -> response.text("healthy"));
              // The extension contributes its protocol handlers beneath Shoostr's /mcp route group.
              routes.path("/mcp", mcp::routes);
            });
  }
}
