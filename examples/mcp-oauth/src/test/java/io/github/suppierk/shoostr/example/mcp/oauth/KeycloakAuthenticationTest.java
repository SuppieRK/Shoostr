package io.github.suppierk.shoostr.example.mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.pac4j.Pac4j;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.core.profile.UserProfile;
import org.pac4j.http.client.direct.DirectBearerAuthClient;

class KeycloakAuthenticationTest {
  @TempDir static Path directory;

  private static KeyStore certificate;
  private static SSLContext trust;

  @BeforeAll
  static void createTrustedTlsFixture() throws Exception {
    var file = directory.resolve("localhost.p12");
    var generator =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair",
                "-alias",
                "localhost",
                "-keyalg",
                "RSA",
                "-keystore",
                file.toString(),
                "-storetype",
                "PKCS12",
                "-storepass",
                "changeit",
                "-keypass",
                "changeit",
                "-dname",
                "CN=localhost",
                "-ext",
                "SAN=ip:127.0.0.1,dns:localhost",
                "-validity",
                "1",
                "-noprompt")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();

    try {
      assertTrue(generator.waitFor(15, TimeUnit.SECONDS));
      assertEquals(0, generator.exitValue());
    } finally {
      generator.destroyForcibly();
    }

    certificate = KeyStore.getInstance("PKCS12");

    try (var input = Files.newInputStream(file)) {
      certificate.load(input, "changeit".toCharArray());
    }

    var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    managers.init(certificate);
    trust = SSLContext.getInstance("TLS");
    trust.init(null, managers.getTrustManagers(), null);
  }

  @Test
  void invokesTheExampleToolWithTheCurrentVerifiedIdentityThroughTheOfficialClient()
      throws Exception {
    var key = new RSAKeyGenerator(2048).keyID("trusted-key").generate();

    try (var issuer = issuerFixture(key)) {
      var issuerUri = issuer.baseUri().resolve("/realms/fixture");
      var token = sign(key, accessClaims(issuerUri).claim("scope", "mcp:read mcp:tools").build());
      var app =
          McpOAuthExample.application(
              Options.defaults(), issuerUri, URI.create("https://localhost:9443/mcp"), trust);

      try (var test = TestServer.start(app);
          var client =
              McpClient.sync(
                      HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                          .endpoint("/mcp")
                          .httpRequestCustomizer(
                              (request, _, _, _, _) ->
                                  request.setHeader("Authorization", "Bearer " + token))
                          .build())
                  .requestTimeout(Duration.ofSeconds(5))
                  .initializationTimeout(Duration.ofSeconds(5))
                  .build()) {
        client.initialize();
        var result =
            client.callTool(
                McpSchema.CallToolRequest.builder("increment").arguments(Map.of()).build());
        assertEquals(
            "Tool invocation 1 by alice",
            assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst()).text());
      }
    }
  }

  @Test
  void deniesInsufficientToolScopeBeforeRecordingAnInvocation() throws Exception {
    var key = new RSAKeyGenerator(2048).keyID("trusted-key").generate();

    try (var issuer = issuerFixture(key)) {
      var issuerUri = issuer.baseUri().resolve("/realms/fixture");
      var readToken = sign(key, accessClaims(issuerUri).build());
      var toolToken =
          sign(key, accessClaims(issuerUri).claim("scope", "mcp:read mcp:tools").build());
      var app =
          McpOAuthExample.application(
              Options.defaults(), issuerUri, URI.create("https://localhost:9443/mcp"), trust);

      try (var test = TestServer.start(app)) {
        var initialized =
            test.send(
                request ->
                    request
                        .path("/mcp")
                        .method("POST")
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .header("Authorization", "Bearer " + readToken)
                        .body(
                            """
                            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                              "protocolVersion":"2025-11-25","capabilities":{},
                              "clientInfo":{"name":"oauth-fixture","version":"1.0"}}}
                            """
                                .getBytes(StandardCharsets.UTF_8)));
        assertEquals(200, initialized.statusCode());
        var session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
        var call =
            """
            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"increment","arguments":{}}}
            """
                .getBytes(StandardCharsets.UTF_8);
        var denied =
            test.send(
                request ->
                    request
                        .path("/mcp")
                        .method("POST")
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .header("Mcp-Session-Id", session)
                        .header("MCP-Protocol-Version", "2025-11-25")
                        .header("Authorization", "Bearer " + readToken)
                        .body(call));
        assertEquals(403, denied.statusCode());
        assertEquals(
            "Bearer error=\"insufficient_scope\", scope=\"mcp:read mcp:tools\", "
                + "resource_metadata=\"https://localhost:9443/.well-known/oauth-protected-resource/mcp\"",
            denied.headers().firstValue("WWW-Authenticate").orElseThrow());
        assertFalse(
            denied.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"));

        var granted =
            test.send(
                request ->
                    request
                        .path("/mcp")
                        .method("POST")
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .header("Mcp-Session-Id", session)
                        .header("MCP-Protocol-Version", "2025-11-25")
                        .header("Authorization", "Bearer " + toolToken)
                        .body(call),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, granted.statusCode());
        assertTrue(granted.body().contains("Tool invocation 1 by alice"));
      }
    }
  }

  @Test
  void rejectsARecentlyExpiredTokenBeforeCheckingItsMissingReadScope() throws Exception {
    var key = new RSAKeyGenerator(2048).keyID("trusted-key").generate();

    try (var issuer = issuerFixture(key)) {
      var issuerUri = issuer.baseUri().resolve("/realms/fixture");
      var token =
          sign(
              key,
              accessClaims(issuerUri)
                  .expirationTime(Date.from(Instant.now().minusSeconds(5)))
                  .claim("scope", "mcp:tools")
                  .build());
      var app =
          McpOAuthExample.application(
              Options.defaults(), issuerUri, URI.create("https://localhost:9443/mcp"), trust);

      try (var test = TestServer.start(app)) {
        var response =
            test.send(
                request ->
                    request
                        .path("/mcp")
                        .method("POST")
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .header("Authorization", "Bearer " + token)
                        .body(
                            """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-11-25","capabilities":{},
                  "clientInfo":{"name":"expired-fixture","version":"1.0"}}}
                """
                                .getBytes(StandardCharsets.UTF_8)));
        assertEquals(401, response.statusCode());
        assertEquals(
            "Bearer error=\"invalid_token\", resource_metadata=\"https://localhost:9443/.well-known/oauth-protected-resource/mcp\"",
            response.headers().firstValue("WWW-Authenticate").orElseThrow());
        assertTrue(response.headers().firstValue("Mcp-Session-Id").isEmpty());
      }
    }
  }

  @Test
  @Timeout(20)
  void preservesDiscoveryMetadataWhenATokenExpiresAfterAuthenticationBeforeMcpDispatch()
      throws Exception {
    var key = new RSAKeyGenerator(2048).keyID("trusted-key").generate();
    var expiresAt = new AtomicReference<Instant>();
    var authenticated = new AtomicBoolean();

    try (var issuer = issuerFixture(key)) {
      var issuerUri = issuer.baseUri().resolve("/realms/fixture");
      var app =
          McpOAuthExample.application(
              Options.defaults(), issuerUri, URI.create("https://localhost:9443/mcp"), trust);
      app.beforeRouteHandler(
          (request, _) -> {
            assertEquals("alice", request.principal().orElseThrow().getName());
            var deadline = Objects.requireNonNull(expiresAt.get());
            assertTrue(Instant.now().isBefore(deadline), "Authentication must precede expiry");
            authenticated.set(true);
            var wait = new CountDownLatch(1);
            while (Instant.now().isBefore(deadline.plusMillis(100))) {
              assertFalse(wait.await(100, TimeUnit.MILLISECONDS));
            }
          });

      try (var test = TestServer.start(app)) {
        expiresAt.set(Instant.now().plusSeconds(5).truncatedTo(ChronoUnit.SECONDS));
        var token =
            sign(
                key,
                accessClaims(issuerUri)
                    .expirationTime(Date.from(expiresAt.get()))
                    .claim("scope", "mcp:read")
                    .build());
        var response =
            test.send(
                request ->
                    request
                        .path("/mcp")
                        .method("POST")
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .header("Authorization", "Bearer " + token)
                        .body(
                            """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-11-25","capabilities":{},
                  "clientInfo":{"name":"post-auth-expiry","version":"1.0"}}}
                """
                                .getBytes(StandardCharsets.UTF_8)));
        assertTrue(authenticated.get(), "Exercise expiry after successful managed authentication");
        assertEquals(401, response.statusCode());
        assertEquals(
            "Bearer error=\"invalid_token\", resource_metadata=\"https://localhost:9443/.well-known/oauth-protected-resource/mcp\"",
            response.headers().firstValue("WWW-Authenticate").orElseThrow());
        assertTrue(response.headers().firstValue("Mcp-Session-Id").isEmpty());
      }
    }
  }

  @Test
  void establishesIdentityOnlyAfterVerifyingAnAudienceBoundSignedAccessToken() throws Exception {
    var key =
        new RSAKeyGenerator(2048)
            .keyID("trusted-key")
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(JWSAlgorithm.RS256)
            .generate();
    var keys = new Shoostr();
    keys.tls(
        tls -> {
          tls.setKeyStore(certificate);
          tls.setKeyStorePassword("changeit");
        });
    keys.routes()
        .get(
            "/realms/fixture/protocol/openid-connect/certs",
            (_, response) ->
                response.body(
                    "application/json",
                    new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8)));

    try (var issuer = TestServer.start(keys)) {
      var issuerUri = issuer.baseUri().resolve("/realms/fixture");
      var resource = URI.create("https://localhost:9443/mcp");
      var expiresAt = Instant.now().plusSeconds(300).truncatedTo(ChronoUnit.SECONDS);
      var claims =
          new JWTClaimsSet.Builder()
              .issuer(issuerUri.toString())
              .audience(resource.toString())
              .subject("alice")
              .issueTime(Date.from(Instant.now()))
              .expirationTime(Date.from(expiresAt))
              .jwtID("access-token-1")
              .claim("azp", "example-client")
              .claim("scope", "mcp:read")
              .claim("typ", "Bearer")
              .claim("email", "alice@example.test")
              .build();
      var token =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256)
                  .type(new JOSEObjectType("at+jwt"))
                  .keyID("trusted-key")
                  .build(),
              claims);
      token.sign(new RSASSASigner(key));

      try (var authenticator = new KeycloakAuthenticator(issuerUri, resource, trust)) {
        var verified = new CompletableFuture<UserProfile>();
        var authentication =
            new Pac4j(
                new DirectBearerAuthClient(authenticator),
                "Bearer realm=\"test\"",
                (_, _, profiles) -> {
                  verified.complete(profiles.getFirst());
                  return true;
                });
        var app = new Shoostr();
        app.authentication(authentication)
            .routes(
                routes ->
                    routes.get(
                        "/identity",
                        (request, response) ->
                            response.text(request.principal().orElseThrow().getName()),
                        extensions -> extensions.get(authentication).required()));

        try (var test = TestServer.start(app)) {
          var result =
              test.send(
                  request ->
                      request
                          .path("/identity")
                          .header("Authorization", "Bearer " + token.serialize()),
                  HttpResponse.BodyHandlers.ofString());

          assertEquals(200, result.statusCode());
          assertEquals("alice", result.body());
          var profile = verified.get(5, TimeUnit.SECONDS);
          assertEquals("alice", profile.getId());
          assertEquals(
              Map.of(
                  "iss",
                  issuerUri.toString(),
                  "azp",
                  "example-client",
                  "scope",
                  "mcp:read",
                  "expiresAt",
                  expiresAt),
              profile.getAttributes());
          assertTrue(
              assertInstanceOf(CommonProfile.class, profile)
                  .getAuthenticationAttributes()
                  .isEmpty());
        }
      }
    }
  }

  @Test
  void rejectsAnAccessTokenWithAnEmptySubject() throws Exception {
    var key = new RSAKeyGenerator(2048).keyID("trusted-key").generate();

    try (var issuer = issuerFixture(key)) {
      var issuerUri = issuer.baseUri().resolve("/realms/fixture");
      var token = sign(key, accessClaims(issuerUri).subject("").build());

      try (var authenticator =
              new KeycloakAuthenticator(
                  issuerUri, URI.create("https://localhost:9443/mcp"), trust);
          var test = TestServer.start(authenticatedApp(authenticator))) {
        var result =
            test.send(
                request -> request.path("/identity").header("Authorization", "Bearer " + token));

        assertEquals(401, result.statusCode());
      }
    }
  }

  @Test
  void rejectsAnAccessTokenWhoseOriginalSubjectIsNumeric() throws Exception {
    var key = new RSAKeyGenerator(2048).keyID("trusted-key").generate();

    try (var issuer = issuerFixture(key)) {
      var issuerUri = issuer.baseUri().resolve("/realms/fixture");
      var claims = accessClaims(issuerUri).build().toJSONObject();
      claims.put("sub", 123);
      var token = signRawClaims(key, claims);

      try (var authenticator =
              new KeycloakAuthenticator(
                  issuerUri, URI.create("https://localhost:9443/mcp"), trust);
          var test = TestServer.start(authenticatedApp(authenticator))) {
        var result =
            test.send(
                request -> request.path("/identity").header("Authorization", "Bearer " + token));

        assertEquals(401, result.statusCode());
      }
    }
  }

  @Test
  void rejectsAnAccessTokenWithNonStringScopes() throws Exception {
    var key = new RSAKeyGenerator(2048).keyID("trusted-key").generate();

    try (var issuer = issuerFixture(key)) {
      var issuerUri = issuer.baseUri().resolve("/realms/fixture");
      var token = sign(key, accessClaims(issuerUri).claim("scope", List.of("mcp:read")).build());

      try (var authenticator =
              new KeycloakAuthenticator(
                  issuerUri, URI.create("https://localhost:9443/mcp"), trust);
          var test = TestServer.start(authenticatedApp(authenticator))) {
        var result =
            test.send(
                request -> request.path("/identity").header("Authorization", "Bearer " + token));

        assertEquals(401, result.statusCode());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "mcp:read\tmcp:tools",
        "mcp:read  mcp:tools",
        " mcp:read",
        "mcp:read ",
        "mcp:\"read",
        "mcp:\\read",
        "mcp:réad"
      })
  void rejectsAnAccessTokenWithMalformedScopeTokens(String scope) throws Exception {
    var key = new RSAKeyGenerator(2048).keyID("trusted-key").generate();

    try (var issuer = issuerFixture(key)) {
      var issuerUri = issuer.baseUri().resolve("/realms/fixture");
      var token = sign(key, accessClaims(issuerUri).claim("scope", scope).build());

      try (var authenticator =
              new KeycloakAuthenticator(
                  issuerUri, URI.create("https://localhost:9443/mcp"), trust);
          var test = TestServer.start(authenticatedApp(authenticator))) {
        var result =
            test.send(
                request -> request.path("/identity").header("Authorization", "Bearer " + token));

        assertEquals(401, result.statusCode());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"azp", "jti"})
  void rejectsAnAccessTokenWithAnEmptyRequiredIdentityClaim(String name) throws Exception {
    var key = new RSAKeyGenerator(2048).keyID("trusted-key").generate();

    try (var issuer = issuerFixture(key)) {
      var issuerUri = issuer.baseUri().resolve("/realms/fixture");
      var token = sign(key, accessClaims(issuerUri).claim(name, "").build());

      try (var authenticator =
              new KeycloakAuthenticator(
                  issuerUri, URI.create("https://localhost:9443/mcp"), trust);
          var test = TestServer.start(authenticatedApp(authenticator))) {
        var result =
            test.send(
                request -> request.path("/identity").header("Authorization", "Bearer " + token));

        assertEquals(401, result.statusCode());
      }
    }
  }

  @Test
  void reportsKeyServiceFailureAsAServerErrorRatherThanInvalidCredentials() throws Exception {
    var key = new RSAKeyGenerator(2048).keyID("trusted-key").generate();
    var keys = new Shoostr();
    keys.tls(
        tls -> {
          tls.setKeyStore(certificate);
          tls.setKeyStorePassword("changeit");
        });
    keys.routes()
        .get(
            "/realms/fixture/protocol/openid-connect/certs",
            (_, response) -> response.status(503).text("Key service unavailable"));

    try (var issuer = TestServer.start(keys)) {
      var issuerUri = issuer.baseUri().resolve("/realms/fixture");
      var token = sign(key, accessClaims(issuerUri).build());

      try (var authenticator =
              new KeycloakAuthenticator(
                  issuerUri, URI.create("https://localhost:9443/mcp"), trust);
          var test = TestServer.start(authenticatedApp(authenticator))) {
        var result =
            test.send(
                request -> request.path("/identity").header("Authorization", "Bearer " + token));

        assertEquals(500, result.statusCode());
        assertTrue(result.headers().firstValue("WWW-Authenticate").isEmpty());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"exp", "iat"})
  void rejectsAnAccessTokenWithALiteralNullRequiredTimestamp(String name) throws Exception {
    var key = new RSAKeyGenerator(2048).keyID("trusted-key").generate();

    try (var issuer = issuerFixture(key)) {
      var issuerUri = issuer.baseUri().resolve("/realms/fixture");
      var claims = accessClaims(issuerUri).build().toJSONObject();
      claims.put(name, null);
      var token = signRawClaims(key, claims);

      try (var authenticator =
              new KeycloakAuthenticator(
                  issuerUri, URI.create("https://localhost:9443/mcp"), trust);
          var test = TestServer.start(authenticatedApp(authenticator))) {
        var result =
            test.send(
                request -> request.path("/identity").header("Authorization", "Bearer " + token));

        assertEquals(401, result.statusCode());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"exp", "iat", "sub", "aud", "azp", "scope", "jti"})
  void rejectsAnAccessTokenMissingARequiredClaim(String name) throws Exception {
    var key = new RSAKeyGenerator(2048).keyID("trusted-key").generate();

    try (var issuer = issuerFixture(key)) {
      var issuerUri = issuer.baseUri().resolve("/realms/fixture");
      var token = sign(key, accessClaims(issuerUri).claim(name, null).build());

      try (var authenticator =
              new KeycloakAuthenticator(
                  issuerUri, URI.create("https://localhost:9443/mcp"), trust);
          var test = TestServer.start(authenticatedApp(authenticator))) {
        var result =
            test.send(
                request -> request.path("/identity").header("Authorization", "Bearer " + token));

        assertEquals(401, result.statusCode());
        assertEquals(
            "Bearer realm=\"test\"", result.headers().firstValue("WWW-Authenticate").orElseThrow());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"issuer", "audience", "expiration", "notBefore", "payloadType"})
  void rejectsAnAccessTokenWhoseClaimsViolateTheConfiguredTrust(String invalid) throws Exception {
    var key = new RSAKeyGenerator(2048).keyID("trusted-key").generate();

    try (var issuer = issuerFixture(key)) {
      var issuerUri = issuer.baseUri().resolve("/realms/fixture");
      var claims = accessClaims(issuerUri);
      switch (invalid) {
        case "issuer" -> claims.issuer("https://untrusted.example/realms/fixture");
        case "audience" -> claims.audience("https://another-resource.example/mcp");
        case "expiration" -> claims.expirationTime(Date.from(Instant.now().minusSeconds(3600)));
        case "notBefore" -> claims.notBeforeTime(Date.from(Instant.now().plusSeconds(3600)));
        case "payloadType" -> claims.claim("typ", "ID");
        default -> throw new IllegalArgumentException(invalid);
      }

      var token = sign(key, claims.build());

      try (var authenticator =
              new KeycloakAuthenticator(
                  issuerUri, URI.create("https://localhost:9443/mcp"), trust);
          var test = TestServer.start(authenticatedApp(authenticator))) {
        var result =
            test.send(
                request -> request.path("/identity").header("Authorization", "Bearer " + token));

        assertEquals(401, result.statusCode());
      }
    }
  }

  @Test
  void rejectsAnAccessTokenSignedByAnUntrustedKeyWithTheSameKeyId() throws Exception {
    var trusted = new RSAKeyGenerator(2048).keyID("trusted-key").generate();
    var untrusted = new RSAKeyGenerator(2048).keyID("trusted-key").generate();

    try (var issuer = issuerFixture(trusted)) {
      var issuerUri = issuer.baseUri().resolve("/realms/fixture");
      var token = sign(untrusted, accessClaims(issuerUri).build());

      try (var authenticator =
              new KeycloakAuthenticator(
                  issuerUri, URI.create("https://localhost:9443/mcp"), trust);
          var test = TestServer.start(authenticatedApp(authenticator))) {
        var result =
            test.send(
                request -> request.path("/identity").header("Authorization", "Bearer " + token));

        assertEquals(401, result.statusCode());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"JWT", "missing"})
  void rejectsASignedTokenWithoutTheAccessTokenJoseType(String type) throws Exception {
    var key = new RSAKeyGenerator(2048).keyID("trusted-key").generate();

    try (var issuer = issuerFixture(key)) {
      var issuerUri = issuer.baseUri().resolve("/realms/fixture");
      var header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID());
      if (!"missing".equals(type)) {
        header.type(new JOSEObjectType(type));
      }

      var token = new SignedJWT(header.build(), accessClaims(issuerUri).build());
      token.sign(new RSASSASigner(key));

      try (var authenticator =
              new KeycloakAuthenticator(
                  issuerUri, URI.create("https://localhost:9443/mcp"), trust);
          var test = TestServer.start(authenticatedApp(authenticator))) {
        var result =
            test.send(
                request ->
                    request
                        .path("/identity")
                        .header("Authorization", "Bearer " + token.serialize()));

        assertEquals(401, result.statusCode());
      }
    }
  }

  @Test
  void rejectsAnUnsignedAccessToken() throws Exception {
    var key = new RSAKeyGenerator(2048).keyID("trusted-key").generate();

    try (var issuer = issuerFixture(key)) {
      var issuerUri = issuer.baseUri().resolve("/realms/fixture");
      var token = new PlainJWT(accessClaims(issuerUri).build()).serialize();

      try (var authenticator =
              new KeycloakAuthenticator(
                  issuerUri, URI.create("https://localhost:9443/mcp"), trust);
          var test = TestServer.start(authenticatedApp(authenticator))) {
        var result =
            test.send(
                request -> request.path("/identity").header("Authorization", "Bearer " + token));

        assertEquals(401, result.statusCode());
      }
    }
  }

  @Test
  void rejectsATrustedSignatureUsingAnUnconfiguredAlgorithm() throws Exception {
    var key = new RSAKeyGenerator(2048).keyID("trusted-key").generate();

    try (var issuer = issuerFixture(key)) {
      var issuerUri = issuer.baseUri().resolve("/realms/fixture");
      var token =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS512)
                  .type(new JOSEObjectType("at+jwt"))
                  .keyID(key.getKeyID())
                  .build(),
              accessClaims(issuerUri).build());
      token.sign(new RSASSASigner(key));

      try (var authenticator =
              new KeycloakAuthenticator(
                  issuerUri, URI.create("https://localhost:9443/mcp"), trust);
          var test = TestServer.start(authenticatedApp(authenticator))) {
        var result =
            test.send(
                request ->
                    request
                        .path("/identity")
                        .header("Authorization", "Bearer " + token.serialize()));

        assertEquals(401, result.statusCode());
      }
    }
  }

  private static TestServer issuerFixture(RSAKey key) throws Exception {
    var app = new Shoostr();
    app.tls(
        tls -> {
          tls.setKeyStore(certificate);
          tls.setKeyStorePassword("changeit");
        });
    app.routes()
        .get(
            "/realms/fixture/protocol/openid-connect/certs",
            (_, response) ->
                response.body(
                    "application/json",
                    new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8)));
    return TestServer.start(app);
  }

  private static Shoostr authenticatedApp(KeycloakAuthenticator authenticator) {
    var authentication =
        new Pac4j(new DirectBearerAuthClient(authenticator), "Bearer realm=\"test\"");
    var app = new Shoostr();
    app.authentication(authentication)
        .routes(
            routes ->
                routes.get(
                    "/identity",
                    (request, response) ->
                        response.text(request.principal().orElseThrow().getName()),
                    extensions -> extensions.get(authentication).required()));
    return app;
  }

  private static JWTClaimsSet.Builder accessClaims(URI issuer) {
    return new JWTClaimsSet.Builder()
        .issuer(issuer.toString())
        .audience("https://localhost:9443/mcp")
        .subject("alice")
        .issueTime(Date.from(Instant.now()))
        .expirationTime(Date.from(Instant.now().plusSeconds(300)))
        .jwtID("access-token-1")
        .claim("azp", "example-client")
        .claim("scope", "mcp:read")
        .claim("typ", "Bearer");
  }

  private static String sign(RSAKey key, JWTClaimsSet claims) throws Exception {
    var token =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(new JOSEObjectType("at+jwt"))
                .keyID(key.getKeyID())
                .build(),
            claims);
    token.sign(new RSASSASigner(key));
    return token.serialize();
  }

  private static String signRawClaims(RSAKey key, Map<String, Object> claims) throws Exception {
    var token =
        new JWSObject(
            new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(new JOSEObjectType("at+jwt"))
                .keyID(key.getKeyID())
                .build(),
            new Payload(claims));
    token.sign(new RSASSASigner(key));
    return token.serialize();
  }
}
