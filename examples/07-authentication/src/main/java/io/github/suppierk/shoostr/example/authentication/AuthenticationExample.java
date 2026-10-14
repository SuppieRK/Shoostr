package io.github.suppierk.shoostr.example.authentication;

import io.github.suppierk.shoostr.Shoostr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Registers routes using a separately configured authentication policy. */
public final class AuthenticationExample {
  private static final Logger LOGGER = LoggerFactory.getLogger(AuthenticationExample.class);

  /** Prevents construction of the launcher. */
  private AuthenticationExample() {}

  /**
   * Starts the local fixture; these demonstration passwords are not production identities.
   *
   * @throws Exception if startup fails
   */
  static void main() throws Exception {
    var app = application();
    app.start();
    LOGGER.info("Auth: http://127.0.0.1:{}/private/me", app.port());
    LOGGER.info("Demo logins: demo / local-only; admin / local-only");
  }

  /**
   * Returns an unstarted app with explicit group protection.
   *
   * <p>S2095 is suppressed because the caller owns the returned app and its installed
   * authenticator; closing it here would return an unusable app.
   *
   * @return app owned by its eventual caller
   */
  @SuppressWarnings("java:S2095")
  public static Shoostr application() {
    // DemoAuthentication is this example's configuration, not a framework class.
    // It creates a Pac4j authentication extension from Shoostr's separate pac4j module.
    var auth = DemoAuthentication.create();
    return new Shoostr()
        // Install the instance; authentication is required only where routes select it below.
        .authentication(auth)
        .routes(
            routes -> {
              routes.get("/public", (_, response) -> response.text("public"));
              // Select that same extension instance for the /private route group only.
              routes.path(
                  "/private",
                  group -> {
                    group.get(
                        "/me",
                        (request, response) ->
                            response.text(request.principal().orElseThrow().getName()));
                    group.get("/admin", (_, response) -> response.text("admin operation allowed"));
                  },
                  extensions -> extensions.get(auth).required());
            });
  }
}
