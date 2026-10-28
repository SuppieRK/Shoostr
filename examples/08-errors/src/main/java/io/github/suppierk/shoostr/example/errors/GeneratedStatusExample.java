package io.github.suppierk.shoostr.example.errors;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.HttpStatusCodes;
import io.github.suppierk.shoostr.http.exceptions.NotFoundException;

/** Customizes generated status responses separately from thrown exceptions. */
public final class GeneratedStatusExample {
  /** Prevents construction of this example factory. */
  private GeneratedStatusExample() {}

  /**
   * Creates an unstarted application.
   *
   * @return application owned by its caller
   */
  public static Shoostr application() {
    return new Shoostr()
        // Generated status rendering is separate from handler output and thrown exceptions.
        .status(
            HttpStatusCodes.NOT_FOUND.value(),
            (ignored, response) -> response.text("Route not found"))
        .status(
            HttpStatusCodes.METHOD_NOT_ALLOWED.value(),
            (ignored2, response) -> response.text("Method unavailable"))
        .routes(
            routes ->
                routes
                    .get("/read-only", (ignored3, response) -> response.text("ready"))
                    // This explicit response is not replaced by the generated 404 renderer.
                    .get(
                        "/manual",
                        (ignored4, response) ->
                            response
                                .status(HttpStatusCodes.NOT_FOUND.value())
                                .text("Chosen by the handler"))
                    // A thrown HTTP exception follows exception rendering, not generated-status
                    // hooks.
                    .get(
                        "/thrown",
                        (ignored5, ignored6) -> {
                          throw new NotFoundException("Private lookup diagnostics");
                        })
                    // The group's renderer customizes the concealed 404 from its unavailable route.
                    .path(
                        "/reports",
                        reports ->
                            reports.when(
                                () -> false,
                                unavailable ->
                                    unavailable.get(
                                        "/daily",
                                        (ignored7, response) -> response.text("Report ready"))),
                        configuration ->
                            configuration
                                .lifecycle()
                                .status(
                                    HttpStatusCodes.NOT_FOUND.value(),
                                    (ignored8, response) -> response.text("Report unavailable"))));
  }
}
