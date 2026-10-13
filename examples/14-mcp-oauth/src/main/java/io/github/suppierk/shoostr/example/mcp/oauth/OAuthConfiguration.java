package io.github.suppierk.shoostr.example.mcp.oauth;

import com.nimbusds.jose.util.JSONObjectUtils;
import io.github.suppierk.shoostr.Extension;
import io.github.suppierk.shoostr.http.exceptions.AuthenticationRequiredException;
import io.github.suppierk.shoostr.http.exceptions.AuthorizationDeniedException;
import io.github.suppierk.shoostr.mcp.McpHttpSecurity;
import io.github.suppierk.shoostr.mcp.McpIdentity;
import io.github.suppierk.shoostr.pac4j.Pac4j;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.net.ssl.SSLContext;
import org.pac4j.core.credentials.UsernamePasswordCredentials;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.core.profile.UserProfile;
import org.pac4j.http.client.direct.DirectBasicAuthClient;
import org.pac4j.http.client.direct.DirectBearerAuthClient;

/** Owns token verification, detached identity, scope policy and public discovery metadata. */
final class OAuthConfiguration implements Extension<Void> {
  private static final String IDENTITY_ATTRIBUTE = McpOAuthExample.class.getName() + ".identity";
  private final KeycloakAuthenticator verifier;
  private final URI metadata;
  private final byte[] document;
  private final Pac4j authentication;
  private final Pac4j restAuthentication;
  private final McpHttpSecurity security;

  /**
   * Configures the two independent authentication schemes and MCP permission checks.
   *
   * @param issuer trusted HTTPS authorization server
   * @param resource canonical HTTPS resource audience
   * @param issuerTrust explicit issuer TLS trust
   * @throws IOException if key-source setup or failure cleanup fails
   */
  OAuthConfiguration(URI issuer, URI resource, SSLContext issuerTrust) throws IOException {
    // KeycloakAuthenticator is example-local; Nimbus verifies the issuer's signed access tokens.
    verifier = new KeycloakAuthenticator(issuer, resource, issuerTrust);

    try {
      metadata = resource.resolve("/.well-known/oauth-protected-resource" + resource.getRawPath());
      // OAuth resource metadata tells clients where to obtain tokens; it is not an OpenAPI
      // document.
      document =
          JSONObjectUtils.toJSONString(
                  Map.of(
                      "resource", resource.toASCIIString(),
                      "authorization_servers", List.of(issuer.toASCIIString()),
                      "scopes_supported", List.of("mcp:read"),
                      "bearer_methods_supported", List.of("header")))
              .getBytes(StandardCharsets.UTF_8);
      // Shoostr's Pac4j extension wraps pac4j's Bearer client and checks this resource's grants.
      authentication =
          new Pac4j(
              new DirectBearerAuthClient(verifier),
              "Bearer resource_metadata=\"" + metadata.toASCIIString() + "\"",
              (context, _, profiles) -> {
                var identity = identity(profiles.getFirst());
                if (!Objects.requireNonNull(identity.expiresAt()).isAfter(Instant.now())) {
                  throw new AuthenticationRequiredException(
                      "Bearer error=\"invalid_token\", resource_metadata=\""
                          + metadata.toASCIIString()
                          + "\"");
                }

                requireScopes(identity, Set.of("mcp:read"), metadata);
                context.setRequestAttribute(IDENTITY_ATTRIBUTE, identity);
                return true;
              });
      // A separate Basic configuration serves REST; groups choose it instead of the Bearer
      // instance.
      restAuthentication = createRestAuthentication();
      // Shoostr's MCP security config checks Origin, extracts identity and authorizes before
      // dispatch.
      security =
          new McpHttpSecurity(
              Set.of(resource.getScheme() + "://" + resource.getRawAuthority()),
              request ->
                  request
                      .attribute(IDENTITY_ATTRIBUTE)
                      .filter(McpIdentity.class::isInstance)
                      .map(McpIdentity.class::cast)
                      .orElseThrow(
                          () -> new IllegalStateException("Verified MCP identity is absent")),
              (identity, message) -> {
                // This policy checks OAuth scopes; they are token grants, not route groups.
                if (message instanceof McpSchema.JSONRPCRequest request
                    && McpSchema.METHOD_TOOLS_CALL.equals(request.method())) {
                  requireScopes(identity, Set.of("mcp:read", "mcp:tools"), metadata);
                }
              },
              metadata);
    } catch (RuntimeException | Error failure) {
      try {
        verifier.close();
      } catch (IOException cleanup) {
        failure.addSuppressed(cleanup);
      }

      throw failure;
    }
  }

  /**
   * Supplies the protected-resource metadata location.
   *
   * @return public discovery URI
   */
  URI metadata() {
    return metadata;
  }

  /**
   * Supplies the eagerly generated protected-resource metadata.
   *
   * @return immutable-by-convention JSON bytes used only by its handler
   */
  byte[] document() {
    return document;
  }

  /**
   * Supplies the MCP Bearer identity source.
   *
   * @return installed managed authentication
   */
  Pac4j authentication() {
    return authentication;
  }

  /**
   * Supplies the separate REST Basic identity source.
   *
   * @return installed REST authentication
   */
  Pac4j restAuthentication() {
    return restAuthentication;
  }

  /**
   * Supplies the protocol security policy and immutable identity extractor.
   *
   * @return native MCP HTTP security configuration
   */
  McpHttpSecurity security() {
    return security;
  }

  /**
   * Releases issuer keys after all borrowing adapters have closed.
   *
   * @throws IOException if key-source cleanup fails
   */
  @Override
  public void close() throws IOException {
    verifier.close();
  }

  /**
   * Rejects an operation before dispatch with the complete required scope set.
   *
   * @param identity detached verified identity
   * @param required complete operation grant requirement
   * @param metadata public resource-metadata location for scope escalation
   * @throws AuthorizationDeniedException if any required grant is absent
   */
  private static void requireScopes(McpIdentity identity, Set<String> required, URI metadata) {
    if (!(identity.context().get("scope") instanceof String granted)
        || !List.of(granted.split(" ")).containsAll(required)) {
      throw McpHttpSecurity.scopeDenied(required, metadata);
    }
  }

  /**
   * Configures the separate fixed-credential REST policy for the local TLS fixture only.
   *
   * @return managed Basic identity source, never selected on MCP routes
   */
  private static Pac4j createRestAuthentication() {
    return new Pac4j(
        new DirectBasicAuthClient(
            (_, supplied) -> {
              if (!(supplied instanceof UsernamePasswordCredentials credentials)
                  || !"rest-demo".equals(credentials.getUsername())
                  || !"rest-demo-local-only".equals(credentials.getPassword())) {
                return Optional.empty();
              }

              var profile = new CommonProfile();
              profile.setId("rest-demo");
              credentials.setUserProfile(profile);
              return Optional.of(credentials);
            }),
        "Basic realm=\"shoostr-rest-example\", charset=\"UTF-8\"");
  }

  /**
   * Detaches only the verified identity, grant string and expiry from the native profile.
   *
   * @param profile verified profile produced by the example-owned authenticator
   * @return immutable identity, with a collision-free issuer/subject/client owner key
   */
  private static McpIdentity identity(UserProfile profile) {
    // Copy verified claims into Shoostr's immutable MCP identity and the SDK's transport context.
    var issuer = Objects.requireNonNull((String) profile.getAttribute("iss"));
    var subject = Objects.requireNonNull(profile.getId());
    var client = Objects.requireNonNull((String) profile.getAttribute("azp"));
    var scope = Objects.requireNonNull((String) profile.getAttribute("scope"));
    var expiry = Objects.requireNonNull((Instant) profile.getAttribute("expiresAt"));
    return new McpIdentity(
        part(issuer) + part(subject) + part(client),
        McpTransportContext.create(Map.of("subject", subject, "scope", scope)),
        expiry);
  }

  /**
   * Length-prefixes a verified string so adjacent owner-key fields cannot collide.
   *
   * @param value one verified identity field
   * @return unambiguous owner-key component
   */
  private static String part(String value) {
    return value.length() + ":" + value;
  }
}
