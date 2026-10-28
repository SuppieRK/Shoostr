package io.github.suppierk.shoostr.example.authentication;

import io.github.suppierk.shoostr.AuthenticationExtension;
import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.Response;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.exceptions.AuthenticationRequiredException;
import io.github.suppierk.shoostr.http.exceptions.ForbiddenException;

/** Route-scoped identity and authorization using fixed tokens for local demonstrations only. */
public final class RouteAuthenticationExample {
  private static final String CHALLENGE = "Bearer realm=\"account\"";

  /** Prevents utility-class construction. */
  private RouteAuthenticationExample() {}

  /**
   * Creates an application with separately selected identity requirements.
   *
   * @return unstarted, caller-owned application
   */
  public static Shoostr application() {
    var authentication =
        new AuthenticationExtension() {
          /** {@inheritDoc} */
          @Override
          public void handle(Request request, Response response) {
            request.principal(null);
            var values = request.headers("Authorization");
            if (values.size() != 1) {
              throw new AuthenticationRequiredException(CHALLENGE);
            }

            var fields = values.getFirst().split(" +", 2);
            if (fields.length != 2 || !"Bearer".equalsIgnoreCase(fields[0])) {
              throw new AuthenticationRequiredException(CHALLENGE);
            }

            // Fixed tokens are local demonstration credentials, not a production credential store.
            var name =
                switch (fields[1]) {
                  case "reader-demo-token" -> "reader";
                  case "admin-demo-token" -> "admin";
                  default -> throw new AuthenticationRequiredException(CHALLENGE);
                };
            request.principal(() -> name);
          }
        };

    return new Shoostr()
        // Installation leaves /public open; the /private group explicitly selects authentication.
        .authentication(authentication)
        .routes(
            routes ->
                routes
                    .get("/public", (ignored, response) -> response.text("public"))
                    .path(
                        "/private",
                        group ->
                            group
                                .get(
                                    "/me",
                                    (request, response) ->
                                        response.text(request.principal().orElseThrow().getName()))
                                .get(
                                    "/admin",
                                    (request, response) -> {
                                      // Authentication establishes identity; this handler adds an
                                      // authorization rule.
                                      if (!"admin"
                                          .equals(request.principal().orElseThrow().getName())) {
                                        throw new ForbiddenException();
                                      }

                                      response.text("admin operation allowed");
                                    }),
                        extensions -> extensions.with(authentication)));
  }
}
