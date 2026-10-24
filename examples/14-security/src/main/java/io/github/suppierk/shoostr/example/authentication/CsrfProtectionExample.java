package io.github.suppierk.shoostr.example.authentication;

import io.github.suppierk.shoostr.Csrf;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;

/** Session-bound CSRF tokens for explicitly selected preference writes. */
public final class CsrfProtectionExample {
  /** Prevents utility-class construction. */
  private CsrfProtectionExample() {}

  /**
   * Creates an application that delivers tokens through a non-cacheable response body.
   *
   * @return unstarted, caller-owned application
   */
  public static Shoostr application() {
    var csrf = new Csrf();
    return new Shoostr()
        .sessions(handler -> handler.setSecureCookies(true))
        // Installation is separate from selection on the two protected writes below.
        .extensions(csrf)
        .routes(
            routes -> {
              routes
                  .get(
                      "/csrf-token",
                      (request, response) ->
                          // Send the session-bound token without caching it; clients echo it on
                          // writes.
                          response.setHeader("Cache-Control", "no-store").text(csrf.token(request)))
                  .get(
                      "/preferences",
                      (request, response) ->
                          response.text(
                              request
                                  .getSession()
                                  .map(session -> (String) session.getAttribute("theme"))
                                  .orElse("unset")))
                  .post(
                      "/preferences",
                      (request, response) -> {
                        var theme =
                            request
                                .formParam("theme")
                                .filter(value -> "light".equals(value) || "dark".equals(value))
                                .orElseThrow(BadRequestException::new);
                        request.getSession().orElseThrow().setAttribute("theme", theme);
                        response.text(theme);
                      },
                      extensions -> extensions.with(csrf))
                  .post(
                      "/session/renew",
                      (request, response) -> {
                        request.renewSessionId();
                        // Return the token for the renewed session binding, not the previous ID.
                        response.setHeader("Cache-Control", "no-store").text(csrf.token(request));
                      },
                      extensions -> extensions.with(csrf));
            });
  }
}
