package io.github.suppierk.shoostr.example.mcp;

import io.github.suppierk.shoostr.Routes;
import io.github.suppierk.shoostr.mcp.Mcp;

/** Keeps HTTP registration separate from startup and native SDK configuration. */
final class McpRoutes {
  /** Prevents construction of the registration function. */
  private McpRoutes() {}

  /**
   * Registers the readiness endpoint and explicitly mounts the installed MCP extension.
   *
   * @param routes application registration scope
   * @param mcp installed native SDK adapter
   */
  static void register(Routes routes, Mcp<?> mcp) {
    routes.get("/health", (_, response) -> response.text("healthy"));
    routes.path("/mcp", mcp::routes);
  }
}
