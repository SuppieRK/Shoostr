package io.github.suppierk.shoostr.example.mcp.oauth;

import io.github.suppierk.shoostr.Routes;
import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.mcp.Mcp;
import java.net.URI;

/** Mounts public discovery, Basic REST and Bearer MCP without changing protocol machinery. */
final class OAuthRoutes {
  /** Prevents construction of the registration function. */
  private OAuthRoutes() {}

  /**
   * Selects exactly one managed identity source per protected route group.
   *
   * @param routes root registration scope
   * @param resource canonical MCP endpoint location
   * @param configuration installed security and metadata configuration
   * @param mcp installed MCP extension
   */
  static void register(Routes routes, URI resource, OAuthConfiguration configuration, Mcp<?> mcp) {
    var metadata = configuration.metadata();
    var document = configuration.document();
    var authentication = configuration.authentication();
    var restAuthentication = configuration.restAuthentication();
    routes.get(
        metadata.getPath(), (_, response) -> response.body(MediaType.APPLICATION_JSON, document));
    routes.path(
        "/api",
        api ->
            api.get(
                "/profile",
                (request, response) -> response.text(request.principal().orElseThrow().getName())),
        extensions -> extensions.get(restAuthentication).required());
    routes.path(
        resource.getPath(), mcp::routes, extensions -> extensions.get(authentication).required());
  }
}
