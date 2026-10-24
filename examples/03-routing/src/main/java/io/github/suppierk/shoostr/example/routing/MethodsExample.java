package io.github.suppierk.shoostr.example.routing;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.HttpMethods;

/** Registers explicit handlers for individual HTTP methods. */
public final class MethodsExample {
  private static final String NOTES_PATH = "/notes";

  private static final String ALLOWED = "GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS";

  /** Prevents construction of this example factory. */
  private MethodsExample() {}

  /**
   * Creates an unstarted application with method-specific routes.
   *
   * @return application owned by its caller
   */
  public static Shoostr application() {
    return new Shoostr()
        .routes(
            routes -> {
              routes
                  .get(NOTES_PATH, (ignored, response) -> response.text("notes"))
                  .post(NOTES_PATH, (ignored2, response) -> response.status(201).text("created"))
                  .put(NOTES_PATH, (ignored3, response) -> response.text("replaced"))
                  .patch(NOTES_PATH, (ignored4, response) -> response.text("updated"))
                  .delete(NOTES_PATH, (ignored5, response) -> response.status(204))
                  // GET does not register HEAD or OPTIONS; select each method explicitly.
                  .head(
                      NOTES_PATH,
                      (ignored6, response) -> response.setHeader("X-Notes-Mode", "metadata"))
                  .options(
                      NOTES_PATH,
                      (ignored7, response) -> response.status(204).setHeader("Allow", ALLOWED))
                  // Use the generic form for a method without a convenience helper.
                  .route(
                      HttpMethods.PROPFIND,
                      "/properties",
                      (request, response) -> response.text(request.method()))
                  .get("/read-only", (ignored8, response) -> response.text("read-only"));
            });
  }
}
