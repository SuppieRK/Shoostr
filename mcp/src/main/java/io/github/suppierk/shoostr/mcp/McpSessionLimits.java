package io.github.suppierk.shoostr.mcp;

import java.time.Duration;
import java.util.Objects;

/**
 * Bounds session ownership within one MCP capability, including initialization and cleanup.
 *
 * @param maxSessions maximum simultaneous session allocations in this capability
 * @param maxSessionsPerOwner maximum allocations sharing one verified session-owner key
 * @param idleTimeout quiescent interval after accepted activity or completion of native work
 */
public record McpSessionLimits(int maxSessions, int maxSessionsPerOwner, Duration idleTimeout) {
  /**
   * Validates finite capacity and a positive idle interval before native server construction.
   *
   * @throws NullPointerException if the timeout is absent
   * @throws IllegalArgumentException if a capacity or the idle timeout is not positive
   */
  public McpSessionLimits {
    Objects.requireNonNull(idleTimeout);
    if (maxSessions <= 0 || maxSessionsPerOwner <= 0 || !idleTimeout.isPositive()) {
      throw new IllegalArgumentException(
          "MCP session capacities and idle timeout must be positive");
    }
  }

  /**
   * Returns operational defaults, which applications can tune for their verified owner model.
   *
   * @return 1024 total sessions, 32 per owner and a 30-minute quiescent timeout
   */
  public static McpSessionLimits defaults() {
    return new McpSessionLimits(1024, 32, Duration.ofMinutes(30));
  }
}
