package io.github.suppierk.shoostr.example.mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("oauth")
class ExternalOAuthTest {
  @Test
  @Timeout(value = 4, unit = TimeUnit.MINUTES)
  void authorizesTheNativeMcpClientThroughExternalPkceAndScopeEscalation() throws Exception {
    var password = System.getenv("SHOOSTR_OAUTH_KEYSTORE_PASSWORD");
    assertNotNull(password, "Set SHOOSTR_OAUTH_KEYSTORE_PASSWORD for the external TLS fixture");
    assertFalse(password.isBlank(), "SHOOSTR_OAUTH_KEYSTORE_PASSWORD must be nonblank");
    var directory = Path.of(System.getProperty("oauth.directory"));
    var certificate = directory.resolve(".local/fixture-ca.pem");
    assertTrue(Files.isRegularFile(certificate), "Create the example TLS fixture first");
    var keys =
        KeyStore.getInstance(
            directory.resolve(".local/fixture.p12").toFile(), password.toCharArray());
    var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    managers.init(keys);
    var trust = SSLContext.getInstance("TLS");
    trust.init(null, managers.getTrustManagers(), null);
    var issuer = URI.create("https://localhost:8443/realms/shoostr-mcp");
    var resource = URI.create("https://localhost:9443/mcp");

    try (var client =
        HttpClient.newBuilder().sslContext(trust).connectTimeout(Duration.ofSeconds(5)).build()) {
      var discovery =
          client.send(
              HttpRequest.newBuilder(URI.create(issuer + "/.well-known/openid-configuration"))
                  .timeout(Duration.ofSeconds(5))
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      assertEquals(200, discovery.statusCode(), "Start the external Keycloak fixture first");
    }

    var output = directory.resolve("build/oauth");
    Files.createDirectories(output);
    var log = output.resolve("runner.log");
    var app =
        McpOAuthExample.application(issuer, resource, trust)
            .tls(
                tls -> {
                  tls.setKeyStore(keys);
                  tls.setKeyStorePassword(password);
                });

    try (var test = TestServer.startOnPort(app, 9443, client -> client.sslContext(trust))) {
      assertEquals(
          200,
          test.send(request -> request.path("/.well-known/oauth-protected-resource/mcp"))
              .statusCode());
      var builder =
          new ProcessBuilder("node", "--test", "oauth-test.mjs")
              .directory(directory.toFile())
              .redirectErrorStream(true)
              .redirectOutput(log.toFile());
      builder.environment().put("NODE_EXTRA_CA_CERTS", certificate.toString());
      var process = builder.start();

      try {
        assertTrue(
            process.waitFor(3, TimeUnit.MINUTES), "OAuth verification timed out; see " + log);
        assertEquals(0, process.exitValue(), () -> "OAuth verification failed; see " + log);
      } finally {
        if (process.isAlive()) {
          process.destroy();
          if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Cannot stop OAuth verification");
          }
        }
      }
    }
  }
}
