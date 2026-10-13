package io.github.suppierk.shoostr.example.openapi;

import io.github.suppierk.shoostr.pac4j.Pac4j;
import java.util.Optional;
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
    return new Pac4j(
        new DirectBasicAuthClient(
            (_, supplied) -> {
              if (!(supplied instanceof UsernamePasswordCredentials credentials)
                  || !"demo".equals(credentials.getUsername())
                  || !"local-only".equals(credentials.getPassword())) {
                return Optional.empty();
              }

              var profile = new CommonProfile();
              profile.setId("demo");
              credentials.setUserProfile(profile);
              return Optional.of(credentials);
            }),
        "Basic realm=\"shoostr-example\", charset=\"UTF-8\"");
  }
}
