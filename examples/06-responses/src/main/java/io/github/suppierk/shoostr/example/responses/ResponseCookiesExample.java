package io.github.suppierk.shoostr.example.responses;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.Cookie;
import io.github.suppierk.shoostr.http.HttpStatusCodes;

/** Sets and deletes explicitly scoped preference cookies. */
public final class ResponseCookiesExample {
  private static final String THEME_COOKIE = "theme";
  private static final String THEME_PATH = "/preferences/theme";

  /** Prevents construction of this example factory. */
  private ResponseCookiesExample() {}

  /**
   * Creates an unstarted application with a path-scoped theme preference.
   *
   * @return application owned by its caller
   */
  public static Shoostr application() {
    // Restrict the preference to /preferences; deletion must use this same cookie scope.
    var theme =
        new Cookie(THEME_COOKIE, "dark")
            .withPath("/preferences")
            .withMaxAge(3600)
            .withSameSite(Cookie.SameSite.LAX);
    // Serve this Secure __Host- cookie over HTTPS, with Path=/ and no Domain.
    var secureTheme =
        Cookie.secure("__Host-theme", "dark").withHttpOnly(true).withSameSite(Cookie.SameSite.NONE);
    return new Shoostr()
        .routes(
            routes ->
                routes
                    .post(
                        THEME_PATH,
                        (ignored, response) ->
                            response.cookie(theme).status(HttpStatusCodes.NO_CONTENT.value()))
                    .get(
                        THEME_PATH,
                        (request, response) ->
                            response.text(request.cookie(THEME_COOKIE).orElse("light")))
                    .delete(
                        THEME_PATH,
                        (ignored2, response) ->
                            response.removeCookie(theme).status(HttpStatusCodes.NO_CONTENT.value()))
                    // A browser will not attach the path-scoped preference to this endpoint.
                    .get(
                        "/outside",
                        (request, response) ->
                            response.text(request.cookie(THEME_COOKIE).orElse("light")))
                    .post(
                        "/secure-preference",
                        (ignored3, response) ->
                            response.cookie(secureTheme).status(HttpStatusCodes.NO_CONTENT.value()))
                    .delete(
                        "/secure-preference",
                        (ignored4, response) ->
                            response
                                .removeCookie(secureTheme)
                                .status(HttpStatusCodes.NO_CONTENT.value())));
  }
}
