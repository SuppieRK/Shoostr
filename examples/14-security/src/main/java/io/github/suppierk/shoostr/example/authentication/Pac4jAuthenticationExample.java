package io.github.suppierk.shoostr.example.authentication;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.pac4j.Pac4j;
import java.util.Optional;
import org.pac4j.core.context.CallContext;
import org.pac4j.core.credentials.Credentials;
import org.pac4j.core.credentials.UsernamePasswordCredentials;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.http.client.direct.DirectBasicAuthClient;

/** HTTP Basic direct authentication using development-only accounts and an explicit role policy. */
public final class Pac4jAuthenticationExample {
  /** Prevents utility-class construction. */
  private Pac4jAuthenticationExample() {}

  /**
   * Creates an application with a stateless direct client selected on account routes.
   *
   * @return unstarted, caller-owned application
   */
  public static Shoostr application() {
    // Replace this development-only verifier with the application's real credential verification.
    var client = authenticationClient();
    // The direct client verifies identity; the authorizer separately requires the admin role.
    var authentication = authentication(client);

    return new Shoostr()
        .routes(
            routes ->
                routes.path(
                    "/accounts",
                    group ->
                        group
                            .get(
                                "/me",
                                (request, response) ->
                                    response.text(request.principal().orElseThrow().getName()))
                            .get(
                                "/admin", (ignoredRequest, response) -> response.text("permitted")),
                    // Select authentication for both account endpoints, not for every route.
                    lifecycle -> lifecycle.withExtension(authentication)));
  }

  /**
   * Creates the example's HTTP authentication client with its credential verifier.
   *
   * @return configured authentication client
   */
  private static DirectBasicAuthClient authenticationClient() {
    return new DirectBasicAuthClient(Pac4jAuthenticationExample::authenticate);
  }

  /**
   * Configures the identity checks used by the protected example endpoints.
   *
   * @param client configured pac4j authentication client
   * @return authentication selected by the application
   */
  private static Pac4j authentication(DirectBasicAuthClient client) {
    return new Pac4j(
        client,
        "Basic realm=\"accounts\", charset=\"UTF-8\"",
        (context, ignoredSessionStore, profiles) ->
            !"/accounts/admin".equals(context.getPath())
                || profiles.getFirst().getRoles().contains("administrator"));
  }

  /**
   * Verifies the fixed demonstration credentials and attaches the accepted profile.
   *
   * @param context current pac4j exchange
   * @param supplied extracted credentials
   * @return accepted credentials, or empty when verification fails
   */
  private static Optional<Credentials> authenticate(CallContext context, Credentials supplied) {
    if (!(supplied instanceof UsernamePasswordCredentials credentials)
        || !("reader".equals(credentials.getUsername())
            || "admin".equals(credentials.getUsername()))
        || !"local-only".equals(credentials.getPassword())) {
      return Optional.empty();
    }

    var profile = new CommonProfile();
    profile.setId(credentials.getUsername());
    if ("admin".equals(credentials.getUsername())) {
      profile.addRole("administrator");
    }

    credentials.setUserProfile(profile);

    return Optional.of(credentials);
  }
}
