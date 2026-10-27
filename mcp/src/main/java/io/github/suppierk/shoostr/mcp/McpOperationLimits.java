package io.github.suppierk.shoostr.mcp;

import java.time.Duration;
import java.util.Objects;

/**
 * Bounds operations within one MCP capability and its verified owners and sessions. Ordinary
 * policies independently bound admitted operations and managed callables using the same capacities;
 * these are separate ceilings, not a combined memory budget. Control policies reserve capacity for
 * replies and cancellation. A started managed callable retains execution capacity until actual
 * exit, including after cancellation or timeout. Progress never renews the deadline.
 *
 * @param maxOperations maximum simultaneous operations in this capability
 * @param maxOperationsPerOwner maximum operations sharing one verified owner
 * @param maxOperationsPerSession maximum operations in one session
 * @param timeout absolute operation lifetime from admission, including scheduling delay
 */
public record McpOperationLimits(
    int maxOperations, int maxOperationsPerOwner, int maxOperationsPerSession, Duration timeout) {
  /**
   * Validates finite positive capacities and duration before native factory invocation.
   *
   * @throws NullPointerException if the timeout is absent
   * @throws IllegalArgumentException if a capacity or timeout is not positive
   */
  public McpOperationLimits {
    Objects.requireNonNull(timeout);
    if (maxOperations <= 0
        || maxOperationsPerOwner <= 0
        || maxOperationsPerSession <= 0
        || !timeout.isPositive()) {
      throw new IllegalArgumentException("MCP operation capacities and timeout must be positive");
    }
  }

  /**
   * Returns tunable starting limits, rather than a measured deployment capacity.
   *
   * @return 256 operations, 64 per owner, 16 per session and a five-minute absolute deadline
   */
  public static McpOperationLimits defaults() {
    return new McpOperationLimits(256, 64, 16, Duration.ofMinutes(5));
  }
}
