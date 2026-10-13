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
import org.eclipse.jetty.server.ServerConnector;

/** Composes one HTTPS MCP resource server with separate security and SDK configuration. */
public final class McpOAuthExample {
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
                });

    try {
      app.modifyServer(
          server ->
              ((ServerConnector) server.getConnectors()[0])
                  .setHost(System.getProperty("shoostr.host", "127.0.0.1")));
      app.start();
    } catch (Exception | Error failure) {
      try {
        app.close();
      } catch (IOException cleanup) {
        failure.addSuppressed(cleanup);
      }

      throw failure;
    }

    System.out.println("MCP endpoint: https://localhost:" + app.port() + "/mcp");
    System.out.println("Keycloak demo login: mcp-demo / mcp-demo-local-only");
    System.out.println("REST Basic demo login: rest-demo / rest-demo-local-only");
  }

  /**
   * Creates an unstarted app that owns its security configuration and native MCP server.
   *
   * @param options listener settings
   * @param issuer exact trusted HTTPS authorization server
   * @param resource canonical HTTPS MCP resource audience
   * @param issuerTrust explicit issuer TLS trust
   * @return app owned by its caller
   * @throws IOException if verifier configuration or failure cleanup fails
   */
  public static Shoostr application(
      Options options, URI issuer, URI resource, SSLContext issuerTrust) throws IOException {
    var app = new Shoostr(options);

    try {
      var configuration = new OAuthConfiguration(issuer, resource, issuerTrust);
      app.extensions(configuration);
      var mcp = OAuthMcpConfiguration.create(configuration.security());
      return app.authentication(configuration.authentication(), configuration.restAuthentication())
          .extensions(mcp)
          .routes(
              routes -> {
                var metadata = configuration.metadata();
                var document = configuration.document();
                var authentication = configuration.authentication();
                var restAuthentication = configuration.restAuthentication();
                routes.get(
                    metadata.getPath(),
                    (_, response) -> response.body(MediaType.APPLICATION_JSON, document));
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
}
