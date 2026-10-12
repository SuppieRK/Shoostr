package io.github.suppierk.shoostr.mcp;

import io.github.suppierk.shoostr.http.exceptions.ForbiddenException;
import java.io.Serial;
import java.net.URI;
import java.util.Objects;
import java.util.Set;

/**
 * Rejects an incoming MCP operation before native dispatch with an OAuth insufficient-scope
 * challenge. Throw from {@link McpHttpSecurity#authorize()}; native tool/resource failures remain
 * protocol errors instead. The application explicitly serves the referenced metadata document.
 */
@SuppressWarnings("java:S110") // Preserve the established typed HTTP403 exception hierarchy.
public final class McpScopeDeniedException extends ForbiddenException {
  @Serial private static final long serialVersionUID = 1L;

  /** Validated immutable HTTP challenge; no credentials or mutable scope inputs are retained. */
  private final String challenge;

  /**
   * Constructs an immutable challenge from the complete scope set required for this operation.
   * Scope order is deterministic and input collections are not retained.
   *
   * @param requiredScopes nonempty set of OAuth scope tokens
   * @param resourceMetadata absolute HTTPS metadata URL without credentials or fragment
   * @throws NullPointerException if an argument or scope is absent
   * @throws IllegalArgumentException if a scope set is empty, a token contains a character
   *     forbidden by OAuth, or the metadata URL is invalid
   */
  public McpScopeDeniedException(Set<String> requiredScopes, URI resourceMetadata) {
    var scopes = Set.copyOf(Objects.requireNonNull(requiredScopes));
    if (scopes.isEmpty()) {
      throw new IllegalArgumentException("At least one required OAuth scope must be supplied");
    }

    for (var scope : scopes) {
      if (scope.isEmpty()
          || scope
              .chars()
              .anyMatch(value -> value < 0x21 || value > 0x7e || value == '"' || value == '\\')) {
        throw new IllegalArgumentException("Invalid OAuth scope token");
      }
    }

    Objects.requireNonNull(resourceMetadata);
    if (!"https".equalsIgnoreCase(resourceMetadata.getScheme())
        || resourceMetadata.getHost() == null
        || resourceMetadata.getRawUserInfo() != null
        || resourceMetadata.getRawFragment() != null
        || resourceMetadata.getRawAuthority().endsWith(":")
        || resourceMetadata.getPort() > 65535) {
      throw new IllegalArgumentException("Expected an absolute HTTPS metadata URL");
    }

    challenge =
        "Bearer error=\"insufficient_scope\", scope=\""
            + String.join(" ", scopes.stream().sorted().toList())
            + "\", resource_metadata=\""
            + resourceMetadata.toASCIIString()
            + "\"";
  }

  /**
   * Returns the detached HTTP challenge, containing no access credentials.
   *
   * @return complete WWW-Authenticate field value
   */
  public String challenge() {
    return challenge;
  }
}
