package io.github.suppierk.shoostr.example.mcp;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.mcp.Mcp;
import io.github.suppierk.shoostr.mcp.McpHttpSecurity;
import io.github.suppierk.shoostr.mcp.McpIdentity;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/** Composes an application-owned blocking tool with existing libraries. */
public final class McpOperationsExample {
  /** Prevents construction of this example factory. */
  private McpOperationsExample() {}

  /**
   * Exposes a public status tool without retaining live HTTP objects in native callbacks.
   *
   * @param readStatus concurrent application operation
   * @param bulkhead application-configured semaphore bulkhead
   * @param callbacks application-owned scheduler, kept available until work has exited
   * @return unstarted application
   */
  public static Shoostr application(
      Callable<String> readStatus, Bulkhead bulkhead, Scheduler callbacks) {
    Objects.requireNonNull(readStatus);
    Objects.requireNonNull(bulkhead);
    Objects.requireNonNull(callbacks);
    var mcp =
        Mcp.async(
            new McpHttpSecurity(
                Set.of(),
                ignored -> new McpIdentity("public", McpTransportContext.EMPTY, null),
                (identity, message) -> {}),
            transport ->
                McpServer.async(transport)
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .tools(
                        McpServerFeatures.AsyncToolSpecification.builder()
                            .tool(
                                McpSchema.Tool.builder("status", Map.of("type", "object")).build())
                            .callHandler(
                                (exchange, request) ->
                                    Mono.fromCallable(
                                            // The library releases its permit after the callable
                                            // actually exits.
                                            Bulkhead.decorateCallable(
                                                bulkhead,
                                                () -> {
                                                  new McpSyncServerExchange(exchange).ping();
                                                  return readStatus.call();
                                                }))
                                        // Only the blocking tool runs here; protocol replies use
                                        // their existing path.
                                        .subscribeOn(callbacks)
                                        .map(
                                            value ->
                                                McpSchema.CallToolResult.builder()
                                                    .addTextContent(value)
                                                    .build())
                                        .onErrorResume(
                                            BulkheadFullException.class,
                                            failure ->
                                                Mono.just(
                                                    McpSchema.CallToolResult.builder()
                                                        .isError(true)
                                                        .addTextContent("Status is busy")
                                                        .build())))
                            .build())
                    .build());
    return new Shoostr()
        // Idle HTTP streams release virtual-thread carriers; callback execution is independent.
        .virtualThreads()
        .extensions(mcp)
        .routes(
            routes ->
                routes
                    .path("/mcp", mcp::routes)
                    .get("/health", (request, response) -> response.text("healthy")));
  }
}
