package io.github.suppierk.shoostr.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Detached identity from the current authenticated HTTP request. Context values must be immutable
 * application data, never live request/response objects or mutable provider profiles. The owner key
 * must distinguish identities and clients sharing this resource, not just a display name.
 *
 * @param sessionOwner stable, nonblank session-owner key
 * @param context native context forwarded to SDK exchanges
 * @param expiresAt verified token expiry, or null for an explicitly public/non-token policy
 */
public record McpIdentity(
    String sessionOwner, McpTransportContext context, @Nullable Instant expiresAt) {
  /**
   * Establishes detached-input invariants. Token freshness is checked per request, not here.
   *
   * @throws NullPointerException if owner or context is absent
   * @throws IllegalArgumentException if the owner is blank
   */
  public McpIdentity {
    Objects.requireNonNull(sessionOwner);
    Objects.requireNonNull(context);
    if (sessionOwner.isBlank()) {
      throw new IllegalArgumentException("A stable MCP session owner is required");
    }
  }
}
