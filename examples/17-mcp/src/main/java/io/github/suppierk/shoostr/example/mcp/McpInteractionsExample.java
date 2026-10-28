package io.github.suppierk.shoostr.example.mcp;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import io.github.suppierk.shoostr.mcp.Mcp;
import io.github.suppierk.shoostr.mcp.McpHttpSecurity;
import io.github.suppierk.shoostr.mcp.McpIdentity;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Requests client interactions and publishes a shared, nonsecret status resource. */
public final class McpInteractionsExample {
  private static final String AUDIENCE_FIELD = "audience";

  /** Prevents construction of the factory. */
  private McpInteractionsExample() {}

  /**
   * Creates an unstarted application with a public native MCP catalog.
   *
   * @return unstarted application
   */
  public static Shoostr application() {
    // This value is shared by all clients; it must not contain per-client secrets.
    var status = new AtomicReference<>("ready");
    var security =
        new McpHttpSecurity(
            Set.of("https://client.example"),
            ignored -> new McpIdentity("public-status", McpTransportContext.EMPTY, null),
            (ignored2, ignored3) -> {});
    var mcp =
        Mcp.sync(
            security,
            transport ->
                McpServer.sync(transport)
                    .serverInfo("shoostr-interactions", "1.0")
                    .requestTimeout(Duration.ofMinutes(2))
                    .capabilities(
                        McpSchema.ServerCapabilities.builder()
                            .tools(false)
                            .logging()
                            // Resource subscriptions are advertised separately from catalog
                            // changes.
                            .resources(true, false)
                            .build())
                    .tools(
                        McpServerFeatures.SyncToolSpecification.builder()
                            .tool(
                                McpSchema.Tool.builder("prepare-summary", Map.of("type", "object"))
                                    .description(
                                        "Ask the client to summarize the current public status")
                                    .build())
                            .callHandler(
                                (exchange, request) -> summarize(exchange, request, status.get()))
                            .build())
                    .resources(
                        new McpServerFeatures.SyncResourceSpecification(
                            McpSchema.Resource.builder("status://current", "Public service status")
                                .mimeType("text/plain")
                                .build(),
                            (ignored4, request) ->
                                McpSchema.ReadResourceResult.builder(
                                        List.of(
                                            McpSchema.TextResourceContents.builder(
                                                    request.uri(), status.get())
                                                .mimeType("text/plain")
                                                .build()))
                                    .build()))
                    .build());
    return new Shoostr()
        .extensions(mcp)
        .routes(
            routes ->
                routes
                    .path("/mcp", mcp::routes)
                    .post(
                        "/status",
                        (request, response) -> {
                          var value = request.bodyText().strip();
                          if (value.isEmpty()
                              || value.length() > 80
                              || value.contains("\r")
                              || value.contains("\n")) {
                            throw new BadRequestException(
                                "Status must be nonblank single-line text with length at most 80");
                          }

                          status.set(value);
                          // Notify subscribers after the change; they re-read the resource for its
                          // content.
                          mcp.server()
                              .notifyResourcesUpdated(
                                  new McpSchema.ResourcesUpdatedNotification("status://current"));
                          response.status(204);
                        }));
  }

  /**
   * Gets a nonsecret audience choice before asking the client to generate text.
   *
   * @param exchange native exchange belonging to the initiating client
   * @param request tool request containing optional progress metadata
   * @param status status snapshot for this operation
   * @return native tool result
   */
  private static McpSchema.CallToolResult summarize(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request, String status) {
    // Server features do not imply that this initiating client supports reverse requests.
    var capabilities = exchange.getClientCapabilities();
    var elicitation = capabilities == null ? null : capabilities.elicitation();
    if (capabilities == null
        || capabilities.sampling() == null
        || elicitation == null
        || elicitation.form() == null) {
      return result("Client must support sampling and form elicitation", true);
    }

    exchange.loggingNotification(
        McpSchema.LoggingMessageNotification.builder(
                McpSchema.LoggingLevel.INFO, "Preparing a status summary")
            .build());
    progress(exchange, request, 0.0);
    // Ask this client for a nonsecret audience choice before requesting text generation.
    var answer =
        exchange.createElicitation(
            McpSchema.ElicitFormRequest.builder(
                    "Choose an audience for the status summary.",
                    Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                            AUDIENCE_FIELD,
                            Map.of("type", "string", "enum", List.of("developers", "operators"))),
                        "required",
                        List.of(AUDIENCE_FIELD)))
                .build());
    // Decline and cancel are normal choices, not permission to continue with generation.
    switch (answer.action()) {
      case DECLINE -> {
        return result("Summary declined", false);
      }
      case CANCEL -> {
        return result("Summary cancelled", false);
      }
      case null -> {
        return result("Client returned no elicitation action", true);
      }
      case ACCEPT -> {
        // Continue below to validate the accepted content before using it.
      }
    }
    // Treat even accepted form content as untrusted input before using the selected audience.
    var content = answer.content();
    if (content == null
        || !(content.get(AUDIENCE_FIELD) instanceof String audience)
        || !Set.of("developers", "operators").contains(audience)) {
      return result("Client returned an invalid audience", true);
    }

    progress(exchange, request, 0.5);
    // Sampling delegates generation to the initiating client, not to a server-selected model.
    var sampled =
        exchange.createMessage(
            McpSchema.CreateMessageRequest.builder(
                    List.of(
                        McpSchema.SamplingMessage.builder(
                                McpSchema.Role.USER,
                                McpSchema.TextContent.builder(
                                        "Summarize status '" + status + "' for " + audience + ".")
                                    .build())
                            .build()),
                    128)
                .build());
    if (!(sampled.content() instanceof McpSchema.TextContent text) || text.text().isBlank()) {
      return result("Client returned no text summary", true);
    }

    progress(exchange, request, 1.0);
    exchange.loggingNotification(
        McpSchema.LoggingMessageNotification.builder(
                McpSchema.LoggingLevel.INFO, "Prepared a status summary")
            .build());
    return result("Client-generated summary: " + text.text(), false);
  }

  /**
   * Sends progress only when the caller supplied a correlation token.
   *
   * @param exchange initiating native exchange
   * @param request current tool request
   * @param completed completed fraction of the operation
   */
  private static void progress(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request, double completed) {
    var metadata = request.meta();
    // Emit correlated progress only when the caller supplied a token for this operation.
    var token = metadata == null ? null : metadata.get("progressToken");
    if (token != null) {
      exchange.progressNotification(
          McpSchema.ProgressNotification.builder(token, completed).total(1.0).build());
    }
  }

  /**
   * Builds a text result with an explicit protocol error flag.
   *
   * @param text response text
   * @param error whether the operation failed
   * @return native tool result
   */
  private static McpSchema.CallToolResult result(String text, boolean error) {
    return McpSchema.CallToolResult.builder().addTextContent(text).isError(error).build();
  }
}
