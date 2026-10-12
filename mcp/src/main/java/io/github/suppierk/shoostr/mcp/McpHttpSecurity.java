package io.github.suppierk.shoostr.mcp;

import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.http.exceptions.AuthorizationDeniedException;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Explicit HTTP security inputs. Authentication stays in the application's managed authentication
 * phase. The extractor must return only verified, detached identity; the authorizer throws to
 * reject a native incoming message before SDK dispatch. Neither callback may retain the live
 * request. Throw the exception returned by {@link #scopeDenied(Set, URI)} for HTTP403 with an OAuth
 * scope challenge; ordinary native callback failures remain MCP protocol errors.
 *
 * @param allowedOrigins exact browser origins; absent Origin is allowed for non-browser clients
 * @param identity current authenticated-request identity extractor
 * @param authorize pre-dispatch permission callback for requests, notifications and responses
 */
public record McpHttpSecurity(
    Set<String> allowedOrigins,
    Function<Request, McpIdentity> identity,
    BiConsumer<McpIdentity, McpSchema.JSONRPCMessage> authorize) {
  /**
   * Copies and validates all configuration before any request can run.
   *
   * @throws NullPointerException if an input or origin is absent
   * @throws IllegalArgumentException if an origin is not an HTTP(S) origin
   */
  public McpHttpSecurity {
    allowedOrigins =
        Set.copyOf(Objects.requireNonNull(allowedOrigins)).stream()
            .map(McpHttpSecurity::origin)
            .collect(Collectors.toUnmodifiableSet());
    Objects.requireNonNull(identity);
    Objects.requireNonNull(authorize);
  }

  /**
   * Creates a shared HTTP authorization failure with a complete OAuth scope challenge. MCP owns
   * scope and discovery validation; core renders HTTP403 and preserves the challenge without a
   * caller-provided exception handler. Native tool/resource failures remain protocol errors.
   *
   * @param requiredScopes nonempty complete set of OAuth scope tokens required by the operation
   * @param resourceMetadata absolute HTTPS metadata URL without credentials or fragment
   * @return detached shared HTTP failure; input collections are not retained
   * @throws NullPointerException if an argument or scope is null
   * @throws IllegalArgumentException if the scopes or metadata URL are invalid
   */
  public static AuthorizationDeniedException scopeDenied(
      Set<String> requiredScopes, URI resourceMetadata) {
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

    return new AuthorizationDeniedException(
        "Bearer error=\"insufficient_scope\", scope=\""
            + String.join(" ", scopes.stream().sorted().toList())
            + "\", resource_metadata=\""
            + resourceMetadata.toASCIIString()
            + "\"");
  }

  /**
   * Validates and canonicalizes one serialized HTTP(S) origin without wildcard matching.
   *
   * @param value serialized origin
   * @return canonical scheme, host and nondefault port
   * @throws IllegalArgumentException if the value is not a serialized HTTP(S) origin
   */
  static String origin(String value) {
    var uri = URI.create(Objects.requireNonNull(value));
    var scheme = uri.getScheme();
    if (scheme == null
        || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
        || uri.getHost() == null
        || uri.getRawAuthority().endsWith(":")
        || uri.getRawUserInfo() != null
        || !uri.getRawPath().isEmpty()
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || uri.getPort() > 65535) {
      throw new IllegalArgumentException("Expected a serialized HTTP(S) Origin");
    }

    scheme = scheme.toLowerCase(Locale.ROOT);
    var port = uri.getPort();
    var defaultPort = "https".equals(scheme) ? 443 : 80;
    return scheme
        + "://"
        + uri.getHost().toLowerCase(Locale.ROOT)
        + (port == -1 || port == defaultPort ? "" : ":" + port);
  }
}
