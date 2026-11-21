package io.github.suppierk.shoostr.mcp;

import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Mutable session limits; MCP captures a copy before constructing its native server. Allocations
 * include initialization and cleanup. The idle timeout measures the quiescent interval after
 * accepted activity or completion of native work.
 */
public final class McpSessionLimits {
  private static final int DEFAULT_MAX_SESSIONS = 1024;
  private static final int DEFAULT_MAX_SESSIONS_PER_OWNER = 32;
  private static final long DEFAULT_IDLE_TIMEOUT_MINUTES = 30;

  private int maxSessions = DEFAULT_MAX_SESSIONS;
  private int maxSessionsPerOwner = DEFAULT_MAX_SESSIONS_PER_OWNER;
  private Duration idleTimeout = Duration.ofMinutes(DEFAULT_IDLE_TIMEOUT_MINUTES);

  /** Creates the existing capacity and idle-time defaults. */
  public McpSessionLimits() {}

  /**
   * Creates validated session limits.
   *
   * @param maxSessions maximum simultaneous allocations
   * @param maxSessionsPerOwner maximum allocations for one verified owner
   * @param idleTimeout positive quiescent interval
   */
  public McpSessionLimits(int maxSessions, int maxSessionsPerOwner, Duration idleTimeout) {
    setMaxSessions(maxSessions);
    setMaxSessionsPerOwner(maxSessionsPerOwner);
    setIdleTimeout(Objects.requireNonNull(idleTimeout));
  }

  /**
   * Captures limits before native server construction.
   *
   * @param source mutable caller configuration
   */
  McpSessionLimits(McpSessionLimits source) {
    this(source.maxSessions, source.maxSessionsPerOwner, source.idleTimeout);
  }

  /**
   * Returns a new configuration with 1024 sessions, 32 per owner and a 30-minute timeout.
   *
   * @return a new configuration with 1024 sessions, 32 per owner and a 30-minute timeout
   */
  public static McpSessionLimits defaults() {
    return new McpSessionLimits();
  }

  /**
   * Returns maximum simultaneous allocations.
   *
   * @return maximum simultaneous allocations
   */
  public int maxSessions() {
    return maxSessions;
  }

  /**
   * Returns maximum simultaneous allocations.
   *
   * @return maximum simultaneous allocations
   */
  public Integer getMaxSessions() {
    return maxSessions;
  }

  /**
   * Sets the total capacity.
   *
   * @param value positive capacity, or null to restore 1024
   * @throws IllegalArgumentException if the supplied value is not positive
   */
  public void setMaxSessions(@Nullable Integer value) {
    int resolved = value == null ? DEFAULT_MAX_SESSIONS : value;
    if (resolved <= 0) {
      throw new IllegalArgumentException("MCP session capacity must be positive");
    }

    maxSessions = resolved;
  }

  /**
   * Returns maximum allocations for one verified owner.
   *
   * @return maximum allocations for one verified owner
   */
  public int maxSessionsPerOwner() {
    return maxSessionsPerOwner;
  }

  /**
   * Returns maximum allocations for one verified owner.
   *
   * @return maximum allocations for one verified owner
   */
  public Integer getMaxSessionsPerOwner() {
    return maxSessionsPerOwner;
  }

  /**
   * Sets the per-owner capacity.
   *
   * @param value positive capacity, or null to restore 32
   * @throws IllegalArgumentException if the supplied value is not positive
   */
  public void setMaxSessionsPerOwner(@Nullable Integer value) {
    int resolved = value == null ? DEFAULT_MAX_SESSIONS_PER_OWNER : value;
    if (resolved <= 0) {
      throw new IllegalArgumentException("MCP owner capacity must be positive");
    }

    maxSessionsPerOwner = resolved;
  }

  /**
   * Returns positive quiescent interval.
   *
   * @return positive quiescent interval
   */
  public Duration idleTimeout() {
    return idleTimeout;
  }

  /**
   * Returns positive quiescent interval.
   *
   * @return positive quiescent interval
   */
  public Duration getIdleTimeout() {
    return idleTimeout;
  }

  /**
   * Sets the idle timeout.
   *
   * @param value positive timeout, or null to restore 30 minutes
   * @throws IllegalArgumentException if the supplied value is not positive
   */
  public void setIdleTimeout(@Nullable Duration value) {
    var resolved = value == null ? Duration.ofMinutes(DEFAULT_IDLE_TIMEOUT_MINUTES) : value;
    if (!resolved.isPositive()) {
      throw new IllegalArgumentException("MCP idle timeout must be positive");
    }

    idleTimeout = resolved;
  }

  /**
   * Compares current limits by value.
   *
   * @param other candidate configuration
   * @return whether all three settings are equal
   */
  @Override
  public boolean equals(@Nullable Object other) {
    return this == other
        || (other instanceof McpSessionLimits that
            && maxSessions == that.maxSessions
            && maxSessionsPerOwner == that.maxSessionsPerOwner
            && idleTimeout.equals(that.idleTimeout));
  }

  /**
   * Hashes the current mutable settings.
   *
   * @return hash of the three limits
   */
  @Override
  public int hashCode() {
    return Objects.hash(maxSessions, maxSessionsPerOwner, idleTimeout);
  }

  /**
   * Describes the current settings.
   *
   * @return capacity and timeout values
   */
  @Override
  public String toString() {
    return "McpSessionLimits[maxSessions="
        + maxSessions
        + ", maxSessionsPerOwner="
        + maxSessionsPerOwner
        + ", idleTimeout="
        + idleTimeout
        + "]";
  }
}
