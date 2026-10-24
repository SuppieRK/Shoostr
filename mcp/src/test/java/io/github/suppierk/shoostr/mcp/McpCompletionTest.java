package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class McpCompletionTest {
  @ParameterizedTest
  @ValueSource(strings = {"prompt", "resource"})
  @Timeout(20)
  void completesConfiguredArgumentsThroughTheNativeClient(String kind) throws Exception {
    McpSchema.CompleteReference reference =
        "prompt".equals(kind)
            ? new McpSchema.PromptReference("language-prompt")
            : new McpSchema.ResourceReference("languages://{language}");
    var received = new CompletableFuture<McpSchema.CompleteRequest>();
    var mcp =
        Mcp.sync(
            new McpHttpSecurity(
                Set.of(),
                ignored -> new McpIdentity("public", McpTransportContext.EMPTY, null),
                (ignored2, ignored3) -> {}),
            transport ->
                McpServer.sync(transport)
                    .capabilities(
                        McpSchema.ServerCapabilities.builder()
                            .prompts(false)
                            .resources(false, false)
                            .completions()
                            .build())
                    .prompts(
                        new McpServerFeatures.SyncPromptSpecification(
                            McpSchema.Prompt.builder("language-prompt")
                                .arguments(
                                    List.of(
                                        McpSchema.PromptArgument.builder("language")
                                            .description("Programming language")
                                            .required(true)
                                            .build()))
                                .build(),
                            (ignored4, ignored5) ->
                                McpSchema.GetPromptResult.builder(List.of()).build()))
                    .resourceTemplates(
                        new McpServerFeatures.SyncResourceTemplateSpecification(
                            McpSchema.ResourceTemplate.builder(
                                    "languages://{language}", "languages")
                                .build(),
                            (ignored6, ignored7) ->
                                McpSchema.ReadResourceResult.builder(List.of()).build()))
                    .completions(
                        new McpServerFeatures.SyncCompletionSpecification(
                            reference,
                            (ignored8, request) -> {
                              received.complete(request);
                              return new McpSchema.CompleteResult(
                                  new McpSchema.CompleteResult.CompleteCompletion(
                                      List.of("java", "javascript"), 2, false));
                            }))
                    .build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app);
        var client =
            McpClient.sync(
                    HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                        .endpoint("/mcp")
                        .build())
                .requestTimeout(Duration.ofSeconds(5))
                .initializationTimeout(Duration.ofSeconds(5))
                .build()) {
      client.initialize();
      var result =
          client.completeCompletion(
              McpSchema.CompleteRequest.builder(
                      reference, new McpSchema.CompleteRequest.CompleteArgument("language", "ja"))
                  .build());

      assertEquals(List.of("java", "javascript"), result.completion().values());
      assertEquals(2, result.completion().total());
      assertEquals(false, result.completion().hasMore());
      var request = received.get(5, TimeUnit.SECONDS);
      assertEquals("language", request.argument().name());
      assertEquals("ja", request.argument().value());
      assertTrue(client.closeGracefully());
    }
  }
}
