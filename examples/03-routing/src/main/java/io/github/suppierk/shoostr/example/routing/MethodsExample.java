package io.github.suppierk.shoostr.example.routing;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.HttpMethods;

/** Registers explicit handlers for individual HTTP methods. */
public final class MethodsExample {
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
                  .get("/notes", (ignored, response) -> response.text("notes"))
                  .post("/notes", (ignored2, response) -> response.status(201).text("created"))
                  .put("/notes", (ignored3, response) -> response.text("replaced"))
                  .patch("/notes", (ignored4, response) -> response.text("updated"))
                  .delete("/notes", (ignored5, response) -> response.status(204))
                  // GET does not register HEAD or OPTIONS; select each method explicitly.
                  .head(
                      "/notes",
                      (ignored6, response) -> response.setHeader("X-Notes-Mode", "metadata"))
                  .options(
                      "/notes",
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
