package io.github.suppierk.shoostr.example.mcp.oauth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.text.ParseException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.net.ssl.SSLContext;
import org.pac4j.core.context.CallContext;
import org.pac4j.core.credentials.Credentials;
import org.pac4j.core.credentials.TokenCredentials;
import org.pac4j.core.credentials.authenticator.Authenticator;
import org.pac4j.core.profile.CommonProfile;

/** Example-owned verification for one explicitly trusted Keycloak issuer and MCP resource. */
final class KeycloakAuthenticator implements Authenticator, Closeable {
  private final ConfigurableJWTProcessor<SecurityContext> processor;
  private final Closeable keySource;

  /**
   * Configures signature, token type and claim validation before requests are admitted. Keys are
   * fetched from the configured issuer, never an unverified token URL. The caller owns this
   * authenticator's lifetime and must close it after requests finish.
   *
   * @param issuer exact HTTPS Keycloak realm issuer, without a trailing slash
   * @param resource exact HTTPS resource audience
   * @param tls explicit trust for the issuer's TLS certificate
   * @throws IOException if the issuer cannot be represented as a URL
   * @throws IllegalArgumentException if a URL is not an absolute HTTPS URL without credentials,
   *     query or fragment, or the issuer ends with a slash
   * @throws NullPointerException if an argument is absent
   * @throws IllegalStateException if the native key source cannot be closed
   */
  KeycloakAuthenticator(URI issuer, URI resource, SSLContext tls) throws IOException {
    validateUrl(issuer);
    validateUrl(resource);
    Objects.requireNonNull(tls);
    if (issuer.getPath().endsWith("/")) {
      throw new IllegalArgumentException("The Keycloak issuer must not end with a slash");
    }

    // Nimbus retrieves verification keys from this trusted issuer using the supplied JDK TLS trust.
    var retrieval = new DefaultResourceRetriever(2000, 2000, 131072, true, tls.getSocketFactory());
    var keys =
        JWKSourceBuilder.create(
                URI.create(issuer + "/protocol/openid-connect/certs").toURL(), retrieval)
            .refreshAheadCache(false)
            .build();
    if (!(keys instanceof Closeable closeable)) {
      throw new IllegalStateException("The native Keycloak key source must be closeable");
    }

    keySource = closeable;
    // Nimbus checks the signature, token type and claims; pac4j only supplies extracted
    // credentials.
    processor = new DefaultJWTProcessor<>();
    processor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(new JOSEObjectType("at+jwt")));
    processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
    var claims =
        new DefaultJWTClaimsVerifier<>(
            resource.toString(),
            new JWTClaimsSet.Builder().issuer(issuer.toString()).claim("typ", "Bearer").build(),
            Set.of("exp", "iat", "sub", "aud", "azp", "scope", "jti"));
    claims.setMaxClockSkew(30);
    processor.setJWTClaimsSetVerifier(claims);
  }

  /**
   * Authenticates signed access credentials without exposing their raw token in the profile.
   *
   * @param context native request context
   * @param supplied extracted credentials
   * @return verified credentials, or empty for malformed or invalid access tokens
   * @throws IllegalStateException if key retrieval or cryptographic processing fails internally
   */
  @Override
  public Optional<Credentials> validate(CallContext context, Credentials supplied) {
    if (!(supplied instanceof TokenCredentials credentials)
        || credentials.getToken() == null
        || credentials.getToken().isBlank()) {
      return Optional.empty();
    }

    try {
      var token = SignedJWT.parse(credentials.getToken());
      var claims = processor.process(token, null);
      var payload = token.getPayload().toJSONObject();
      if (payload == null || !(payload.get("sub") instanceof String subject)) {
        return Optional.empty();
      }

      var scope = claims.getStringClaim("scope");
      var client = claims.getStringClaim("azp");
      var tokenId = claims.getStringClaim("jti");
      if (claims.getExpirationTime() == null
          || claims.getIssueTime() == null
          || subject.isBlank()
          || scope == null
          || scope.isBlank()
          || client == null
          || client.isBlank()
          || tokenId == null
          || tokenId.isBlank()) {
        return Optional.empty();
      }

      validateScopes(scope);
      // pac4j's profile carries only verified identity/grants to Shoostr's authentication adapter.
      var profile = new CommonProfile();
      profile.setId(subject);
      profile.addAttribute("iss", claims.getIssuer());
      profile.addAttribute("azp", client);
      profile.addAttribute("scope", scope);
      profile.addAttribute("expiresAt", claims.getExpirationTime().toInstant());
      credentials.setUserProfile(profile);
      return Optional.of(credentials);
    } catch (ParseException | BadJOSEException _) {
      return Optional.empty();
    } catch (JOSEException failure) {
      throw new IllegalStateException("Keycloak access-token verification is unavailable", failure);
    }
  }

  /**
   * Releases the native key source after the application's requests have finished.
   *
   * @throws IOException if native cleanup fails
   */
  @Override
  public void close() throws IOException {
    keySource.close();
  }

  /**
   * Validates OAuth scope tokens before retaining the provider's immutable scope string.
   *
   * @param scope space-delimited scope tokens
   * @throws ParseException if a token is empty or contains a character forbidden by RFC 6749
   */
  private static void validateScopes(String scope) throws ParseException {
    for (var token : scope.split(" ", -1)) {
      if (token.isEmpty()
          || token
              .chars()
              .anyMatch(value -> value < 0x21 || value > 0x7e || value == '"' || value == '\\')) {
        throw new ParseException("Invalid OAuth scope token", 0);
      }
    }
  }

  /**
   * Validates trusted configuration before constructing any resources.
   *
   * @param value configured issuer or resource URL
   * @throws NullPointerException if the URL is absent
   * @throws IllegalArgumentException if the URL is not an absolute HTTPS URL without credentials,
   *     query or fragment
   */
  private static void validateUrl(URI value) {
    Objects.requireNonNull(value);
    if (!"https".equals(value.getScheme())
        || value.getHost() == null
        || value.getRawUserInfo() != null
        || value.getRawQuery() != null
        || value.getRawFragment() != null) {
      throw new IllegalArgumentException("Expected an absolute HTTPS URL without credentials");
    }
  }
}
