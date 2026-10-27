package io.github.suppierk.shoostr.mcp;

import java.util.concurrent.Callable;
import reactor.core.publisher.Mono;

/**
 * Borrowed managed execution owned by one MCP capability. Return or compose the publisher from a
 * native async handler; a separate subscription in a synchronous handler does not transfer
 * operation ownership. Independently launched tasks and arbitrary native publishers remain
 * application-owned. Capacity is reserved before scheduling; a started callable retains its charge
 * until actual exit. Timeout or cancellation requests interruption without promising forced
 * termination. The owning capability closes execution; applications must not attempt to close it
 * independently.
 */
public interface McpWork {
  /**
   * Lazily runs a callable within its originating admitted operation on owned execution.
   *
   * @param <T> result type
   * @param action blocking work; must cooperate with interruption to stop promptly
   * @return result publisher; subscription without live ownership or available capacity fails
   *     before execution
   * @throws NullPointerException if the callable is absent
   */
  <T> Mono<T> call(Callable<? extends T> action);
}
