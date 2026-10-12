package io.github.suppierk.shoostr.example.mcp;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.mcp.Mcp;
import io.github.suppierk.shoostr.mcp.McpHttpSecurity;
import io.github.suppierk.shoostr.mcp.McpIdentity;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Set;

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
    var app = application(Options.defaults()).start();
    System.out.println("MCP endpoint: http://127.0.0.1:" + app.port() + "/mcp");
  }

  /**
   * Creates an unstarted application with explicit native SDK registrations.
   *
   * @param options listener and resource settings
   * @return app owned by its eventual caller or test fixture
   */
  public static Shoostr application(Options options) {
    var security =
        new McpHttpSecurity(
            Set.of("http://" + options.host() + ":" + options.port()),
            _ -> new McpIdentity("public-example", McpTransportContext.EMPTY, null),
            (_, _) -> {});
    var mcp =
        Mcp.sync(
            security,
            transport ->
                McpServer.sync(transport)
                    .serverInfo("shoostr-mcp-example", "1.0")
                    .requestTimeout(Duration.ofSeconds(30))
                    .capabilities(
                        McpSchema.ServerCapabilities.builder()
                            .tools(false)
                            .resources(true, false)
                            .prompts(false)
                            .completions()
                            .build())
                    .tools(CompatibilityFixtures.tools())
                    .resources(CompatibilityFixtures.resources())
                    .resourceTemplates(CompatibilityFixtures.resourceTemplates())
                    .prompts(CompatibilityFixtures.prompts())
                    .completions(
                        new McpServerFeatures.SyncCompletionSpecification(
                            new McpSchema.PromptReference("test_prompt_with_arguments"),
                            (_, _) ->
                                new McpSchema.CompleteResult(
                                    new McpSchema.CompleteResult.CompleteCompletion(
                                        List.of(), 0, false))))
                    .build());
    return new Shoostr(options)
        .extensions(mcp)
        .routes(
            routes -> {
              routes.get("/health", (_, response) -> response.text("healthy"));
              routes.path("/mcp", mcp::routes);
            });
  }
}
