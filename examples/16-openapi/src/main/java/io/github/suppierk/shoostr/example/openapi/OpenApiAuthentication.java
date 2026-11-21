package io.github.suppierk.shoostr.example.openapi;

import io.github.suppierk.shoostr.pac4j.Pac4j;
import java.util.Optional;
import org.pac4j.core.context.CallContext;
import org.pac4j.core.credentials.Credentials;
import org.pac4j.core.credentials.UsernamePasswordCredentials;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.http.client.direct.DirectBasicAuthClient;

/** Configures local Basic authentication independently of documentation. */
final class OpenApiAuthentication {
  /** Prevents construction of the configuration factory. */
  private OpenApiAuthentication() {}

  /**
   * Configures real Pac4j HTTP Basic for this local fixture only. Use TLS and a proper identity
   * provider for production; Basic credentials are not encrypted by this example.
   *
   * @return explicit managed authentication extension
   */
  static Pac4j create() {
    // Shoostr's Pac4j extension wraps the third-party pac4j HTTP Basic client.
    // This callback checks fixed local credentials, not accounts managed by OpenAPI.
    return new Pac4j(authenticationClient(), "Basic realm=\"shoostr-example\", charset=\"UTF-8\"");
  }

  /**
   * Creates the HTTP Basic client using this example's fixed account verifier.
   *
   * @return configured authentication client
   */
  private static DirectBasicAuthClient authenticationClient() {
    return new DirectBasicAuthClient(OpenApiAuthentication::authenticate);
  }

  /**
   * Verifies the demonstration account and attaches its profile to accepted credentials.
   *
   * @param context current pac4j exchange
   * @param supplied credentials extracted by the HTTP Basic client
   * @return accepted credentials, or empty when the account or password is invalid
   */
  private static Optional<Credentials> authenticate(CallContext context, Credentials supplied) {
    if (!(supplied instanceof UsernamePasswordCredentials credentials)
        || !"demo".equals(credentials.getUsername())
        || !"local-only".equals(credentials.getPassword())) {
      return Optional.empty();
    }

    var profile = new CommonProfile();
    profile.setId("demo");
    credentials.setUserProfile(profile);
    return Optional.of(credentials);
  }
}
