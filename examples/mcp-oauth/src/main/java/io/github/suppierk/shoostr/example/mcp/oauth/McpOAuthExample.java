package io.github.suppierk.shoostr.example.mcp.oauth;

import com.nimbusds.jose.util.JSONObjectUtils;
import io.github.suppierk.shoostr.Extension;
import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.exceptions.AuthenticationRequiredException;
import io.github.suppierk.shoostr.http.exceptions.AuthorizationDeniedException;
import io.github.suppierk.shoostr.mcp.Mcp;
import io.github.suppierk.shoostr.mcp.McpHttpSecurity;
import io.github.suppierk.shoostr.mcp.McpIdentity;
import io.github.suppierk.shoostr.pac4j.Pac4j;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.pac4j.core.credentials.UsernamePasswordCredentials;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.core.profile.UserProfile;
import org.pac4j.http.client.direct.DirectBasicAuthClient;
import org.pac4j.http.client.direct.DirectBearerAuthClient;

/** Explicit MCP resource server using an external Keycloak authorization server. */
public final class McpOAuthExample {
  private static final String IDENTITY_ATTRIBUTE = McpOAuthExample.class.getName() + ".identity";

  /** Prevents instantiation of the example factory. */
  private McpOAuthExample() {}

  /**
   * Starts the local HTTPS example; core owns the installed resources and JVM shutdown.
   *
   * @throws Exception if fixture TLS material cannot be loaded or startup fails
   */
  static void main() throws Exception {
    var certificate =
        KeyStore.getInstance(
            Path.of(System.getProperty("shoostr.oauth.keyStore", ".local/fixture.p12")).toFile(),
            "changeit".toCharArray());
    var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    managers.init(certificate);
    var trust = SSLContext.getInstance("TLS");
    trust.init(null, managers.getTrustManagers(), null);
    var app =
        application(
                Options.defaults().withPort(9443),
                URI.create("https://localhost:8443/realms/shoostr-mcp"),
                URI.create("https://localhost:9443/mcp"),
                trust)
            .tls(
                tls -> {
                  tls.setKeyStore(certificate);
                  tls.setKeyStorePassword("changeit");
                })
            .start();
    System.out.println("MCP endpoint: https://localhost:" + app.port() + "/mcp");
    System.out.println("Keycloak demo login: mcp-demo / mcp-demo-local-only");
    System.out.println("REST Basic demo login: rest-demo / rest-demo-local-only");
  }

  /**
   * Creates an unstarted application with explicit Bearer authentication and public discovery.
   * Listener TLS is configured separately before startup. The returned app owns its native MCP
   * server and token-verifier key source; callers close only the app.
   *
   * @param options listener and resource settings
   * @param issuer exact trusted Keycloak HTTPS issuer
   * @param resource canonical HTTPS MCP resource audience and endpoint path
   * @param issuerTrust explicit TLS trust for the configured issuer
   * @return app owned by its caller
   * @throws IOException if verifier configuration or failure cleanup fails
   */
  public static Shoostr application(
      Options options, URI issuer, URI resource, SSLContext issuerTrust) throws IOException {
    var app = new Shoostr(options);

    try {
      var verifier = new KeycloakAuthenticator(issuer, resource, issuerTrust);
      app.extensions(
          new Extension<Void>() {
            /**
             * Releases the verifier after the app has drained its requests.
             *
             * @throws IOException if the verifier cannot be closed
             */
            @Override
            public void close() throws IOException {
              verifier.close();
            }
          });
      var metadata =
          resource.resolve("/.well-known/oauth-protected-resource" + resource.getRawPath());
      var document =
          JSONObjectUtils.toJSONString(
                  Map.of(
                      "resource", resource.toASCIIString(),
                      "authorization_servers", List.of(issuer.toASCIIString()),
                      "scopes_supported", List.of("mcp:read"),
                      "bearer_methods_supported", List.of("header")))
              .getBytes(StandardCharsets.UTF_8);
      var authentication =
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
      var restAuthentication = restAuthentication();
      var security =
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
                if (message instanceof McpSchema.JSONRPCRequest request
                    && McpSchema.METHOD_TOOLS_CALL.equals(request.method())) {
                  requireScopes(identity, Set.of("mcp:read", "mcp:tools"), metadata);
                }
              });
      var calls = new AtomicInteger();
      var mcp =
          Mcp.sync(
              security,
              transport ->
                  McpServer.sync(transport)
                      .serverInfo("shoostr-mcp-oauth-example", "1.0")
                      .requestTimeout(Duration.ofSeconds(30))
                      .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                      .tools(
                          McpServerFeatures.SyncToolSpecification.builder()
                              .tool(
                                  McpSchema.Tool.builder("increment", Map.of("type", "object"))
                                      .description("Records a tool invocation; requires mcp:tools")
                                      .build())
                              .callHandler(
                                  (exchange, _) ->
                                      McpSchema.CallToolResult.builder()
                                          .addTextContent(
                                              "Tool invocation "
                                                  + calls.incrementAndGet()
                                                  + " by "
                                                  + exchange.transportContext().get("subject"))
                                          .build())
                              .build())
                      .build());
      return app.authentication(authentication, restAuthentication)
          .extensions(mcp)
          .routes(
              routes -> {
                routes.get(
                    metadata.getPath(),
                    (_, response) -> response.body("application/json", document));
                routes.path(
                    "/api",
                    api ->
                        api.get(
                            "/profile",
                            (request, response) ->
                                response.text(request.principal().orElseThrow().getName())),
                    extensions -> extensions.get(restAuthentication).required());
                routes.path(
                    resource.getPath(),
                    mcp::routes,
                    extensions -> extensions.get(authentication).required());
              });
    } catch (IOException | RuntimeException | Error failure) {
      try {
        app.close();
      } catch (IOException | RuntimeException cleanup) {
        failure.addSuppressed(cleanup);
      }

      throw failure;
    }
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
  private static Pac4j restAuthentication() {
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
