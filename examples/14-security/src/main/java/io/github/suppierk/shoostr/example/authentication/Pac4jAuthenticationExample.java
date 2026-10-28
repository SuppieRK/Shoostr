package io.github.suppierk.shoostr.example.authentication;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.pac4j.Pac4j;
import java.util.Optional;
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
    var client =
        new DirectBasicAuthClient(
            (ignored, supplied) -> {
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
            });
    // The direct client verifies identity; the authorizer separately requires the admin role.
    var authentication =
        new Pac4j(
            client,
            "Basic realm=\"accounts\", charset=\"UTF-8\"",
            (context, ignored2, profiles) ->
                !"/accounts/admin".equals(context.getPath())
                    || profiles.getFirst().getRoles().contains("administrator"));
    return new Shoostr()
        .authentication(authentication)
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
                            .get("/admin", (ignored3, response) -> response.text("permitted")),
                    // Select authentication for both account endpoints, not for every route.
                    extensions -> extensions.with(authentication)));
  }
}
