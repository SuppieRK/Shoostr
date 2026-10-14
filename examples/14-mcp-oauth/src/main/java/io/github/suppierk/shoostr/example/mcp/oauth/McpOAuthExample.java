package io.github.suppierk.shoostr.example.mcp.oauth;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.MediaType;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.security.KeyStore;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Composes one HTTPS MCP resource server with separate security and SDK configuration. */
public final class McpOAuthExample {
  private static final Logger LOGGER = LoggerFactory.getLogger(McpOAuthExample.class);

  /** Prevents instantiation of the example factory. */
  private McpOAuthExample() {}

  /**
   * Starts the local HTTPS example; core owns the installed resources and JVM shutdown.
   *
   * <p>S1181 is suppressed because failed startup must release the owned verifier and MCP server
   * even when an Error escapes; cleanup runs before the original failure is rethrown.
   *
   * @throws IllegalStateException if SHOOSTR_OAUTH_KEYSTORE_PASSWORD is absent or blank
   * @throws Exception if fixture TLS material cannot be loaded or startup fails
   */
  @SuppressWarnings("java:S1181")
  static void main() throws Exception {
    var password = System.getenv("SHOOSTR_OAUTH_KEYSTORE_PASSWORD");
    if (password == null || password.isBlank()) {
      throw new IllegalStateException("SHOOSTR_OAUTH_KEYSTORE_PASSWORD must be set and nonblank");
    }

    // JDK TLS types load the local certificate; this demo trusts its own development issuer.
    var certificate =
        KeyStore.getInstance(
            Path.of(System.getProperty("shoostr.oauth.keyStore", ".local/fixture.p12")).toFile(),
            password.toCharArray());
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
            // Shoostr's TLS configurator uses the certificate to serve HTTPS on the app listener.
            .tls(
                tls -> {
                  tls.setKeyStore(certificate);
                  tls.setKeyStorePassword(password);
                });

    try {
      app.start();
    } catch (Exception | Error failure) {
      try {
        app.close();
      } catch (IOException cleanup) {
        failure.addSuppressed(cleanup);
      }

      throw failure;
    }

    LOGGER.info("MCP endpoint: https://localhost:{}/mcp", app.port());
    LOGGER.info("Keycloak demo login: mcp-demo / mcp-demo-local-only");
    LOGGER.info("REST Basic demo login: rest-demo / rest-demo-local-only");
  }

  /**
   * Creates an unstarted OAuth application with default listener settings.
   *
   * @param issuer exact trusted HTTPS authorization server
   * @param resource canonical HTTPS MCP resource audience
   * @param issuerTrust explicit issuer TLS trust
   * @return application owned by its caller
   * @throws IOException if verifier configuration or failure cleanup fails
   */
  public static Shoostr application(URI issuer, URI resource, SSLContext issuerTrust)
      throws IOException {
    return application(Options.defaults(), issuer, resource, issuerTrust);
  }

  /**
   * Creates an unstarted app that owns its security configuration and native MCP server.
   *
   * <p>S2095 is suppressed because the caller owns the returned app, which owns the installed
   * verifier and MCP extension. S1181 is suppressed because failed assembly must also clean up
   * after an Error before rethrowing the original failure.
   *
   * @param options listener settings
   * @param issuer exact trusted HTTPS authorization server
   * @param resource canonical HTTPS MCP resource audience
   * @param issuerTrust explicit issuer TLS trust
   * @return app owned by its caller
   * @throws IOException if verifier configuration or failure cleanup fails
   */
  @SuppressWarnings({"java:S2095", "java:S1181"})
  public static Shoostr application(
      Options options, URI issuer, URI resource, SSLContext issuerTrust) throws IOException {
    var app = new Shoostr(options);

    try {
      // Example-local configuration combines Nimbus token verification and Shoostr's Pac4j adapter.
      var configuration = new OAuthConfiguration(issuer, resource, issuerTrust);
      // This local class implements core's Extension so app.close() releases its issuer-key source.
      app.extensions(configuration);
      // The local factory creates Shoostr's mcp extension with the official SDK's tool definitions.
      var mcp = OAuthMcpConfiguration.create(configuration.security());
      // Install both authenticators; each protected group selects one, not a chained requirement.
      return app.authentication(configuration.authentication(), configuration.restAuthentication())
          .extensions(mcp)
          .routes(
              routes -> {
                var metadata = configuration.metadata();
                var document = configuration.document();
                var authentication = configuration.authentication();
                var restAuthentication = configuration.restAuthentication();
                // Publish OAuth discovery metadata explicitly; it must be accessible without a
                // token.
                routes.get(
                    metadata.getPath(),
                    (_, response) -> response.body(MediaType.APPLICATION_JSON, document));
                // REST selects the installed Basic authenticator for this group only.
                routes.path(
                    "/api",
                    api ->
                        api.get(
                            "/profile",
                            (request, response) ->
                                response.text(request.principal().orElseThrow().getName())),
                    extensions -> extensions.get(restAuthentication).required());
                // MCP selects the Bearer authenticator; mcp::routes adds the protocol's HTTP
                // handlers.
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
}
