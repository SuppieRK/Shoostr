package io.github.suppierk.shoostr.example.authentication;

import io.github.suppierk.shoostr.pac4j.Pac4j;
import java.util.Optional;
import org.pac4j.core.context.CallContext;
import org.pac4j.core.credentials.Credentials;
import org.pac4j.core.credentials.UsernamePasswordCredentials;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.http.client.direct.DirectBasicAuthClient;

/** Fixed demonstration credentials only; replace with a real identity provider for deployment. */
final class DemoAuthentication {
  private static final String ADMIN = "admin";

  /** Prevents construction of the configuration factory. */
  private DemoAuthentication() {}

  /**
   * Configures an explicit managed identity source, not an app-wide authentication chain.
   *
   * @return extension to install and select on the protected route group
   */
  static Pac4j create() {
    // Shoostr's pac4j module adapts the third-party pac4j clients to managed authentication.
    return new Pac4j(
        // pac4j extracts HTTP Basic credentials; this example checks fixed demo accounts.
        authenticationClient(),
        "Basic realm=\"shoostr-demo\", charset=\"UTF-8\"",
        // This authorization callback requires the admin role only for /private/admin.
        (context, ignoredSessionStore, profiles) ->
            !"/private/admin".equals(context.getPath())
                || profiles.getFirst().getRoles().contains(ADMIN));
  }

  /**
   * Creates the HTTP Basic client using this example's fixed account verifier.
   *
   * @return configured authentication client
   */
  private static DirectBasicAuthClient authenticationClient() {
    return new DirectBasicAuthClient(DemoAuthentication::authenticate);
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
        || !("demo".equals(credentials.getUsername()) || ADMIN.equals(credentials.getUsername()))
        || !"local-only".equals(credentials.getPassword())) {
      return Optional.empty();
    }

    var profile = new CommonProfile();
    profile.setId(credentials.getUsername());
    if (ADMIN.equals(credentials.getUsername())) {
      profile.addRole(ADMIN);
    }

    credentials.setUserProfile(profile);
    return Optional.of(credentials);
  }
}
