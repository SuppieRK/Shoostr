package io.github.suppierk.shoostr.example.responses;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.HttpStatusCodes;

/** Redirects to application-selected destinations with explicit method semantics. */
public final class RedirectExample {
  /** Prevents construction of this example factory. */
  private RedirectExample() {}

  /**
   * Creates an unstarted application with temporary navigation and POST redirects.
   *
   * @return application owned by its caller
   */
  public static Shoostr application() {
    return new Shoostr()
        .routes(
            routes -> {
              routes
                  .get("/old", (ignored, response) -> response.redirect("/current"))
                  .get("/current", (ignored2, response) -> response.text("current"))
                  // 303 makes the client fetch the receipt with GET after a successful POST.
                  .post(
                      "/submit",
                      (ignored3, response) ->
                          response.redirect("/receipt", HttpStatusCodes.SEE_OTHER))
                  .get(
                      "/receipt",
                      (request, response) -> response.text(request.method() + ":receipt"))
                  // 307 preserves the original method and body when forwarding the POST.
                  .post(
                      "/forward",
                      (ignored4, response) ->
                          response.redirect("/receiver", HttpStatusCodes.TEMPORARY_REDIRECT))
                  .post(
                      "/receiver",
                      (request, response) ->
                          response.text(request.method() + ":" + request.bodyText()));
            });
  }
}
