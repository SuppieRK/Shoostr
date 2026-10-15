package io.github.suppierk.shoostr.example.authentication;

import io.github.suppierk.shoostr.pac4j.Pac4j;
import java.util.Optional;
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
        new DirectBasicAuthClient(
            (_, supplied) -> {
              if (!(supplied instanceof UsernamePasswordCredentials credentials)
                  || !("demo".equals(credentials.getUsername())
                      || ADMIN.equals(credentials.getUsername()))
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
            }),
        "Basic realm=\"shoostr-demo\", charset=\"UTF-8\"",
        // This authorization callback requires the admin role only for /private/admin.
        (context, _, profiles) ->
            !"/private/admin".equals(context.getPath())
                || profiles.getFirst().getRoles().contains(ADMIN));
  }
}
