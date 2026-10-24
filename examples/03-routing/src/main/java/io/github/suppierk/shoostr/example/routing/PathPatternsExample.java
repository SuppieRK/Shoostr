package io.github.suppierk.shoostr.example.routing;

import io.github.suppierk.shoostr.Shoostr;

/** Matches literal, constrained, single-segment, and catch-all paths. */
public final class PathPatternsExample {
  /** Prevents construction of this example factory. */
  private PathPatternsExample() {}

  /**
   * Creates an unstarted application with overlapping route patterns.
   *
   * @return application owned by its caller
   */
  public static Shoostr application() {
    return new Shoostr()
        .routes(
            routes -> {
              // Broad patterns come first to demonstrate specificity rather than insertion order.
              routes
                  .get(
                      "/items/{*tail}",
                      (request, response) ->
                          response.text("tail:" + request.pathParam("tail").orElseThrow()))
                  .get(
                      "/items/{name}",
                      (request, response) ->
                          response.text("name:" + request.pathParam("name").orElseThrow()))
                  .get(
                      "/items/{id:[0-9]+}",
                      (request, response) ->
                          response.text("id:" + request.pathParam("id").orElseThrow()))
                  // A literal wins over matching parameter and catch-all patterns.
                  .get("/items/latest", (ignored, response) -> response.text("latest"))
                  .post(
                      "/items/{name}",
                      (request, response) ->
                          response.text("post:" + request.pathParam("name").orElseThrow()))
                  .get(
                      "/codes/{single:[0-9]}",
                      (request, response) ->
                          response.text("single:" + request.pathParam("single").orElseThrow()))
                  // Both constraints match one digit, so the earlier single-digit route wins there.
                  .get(
                      "/codes/{digits:[0-9]{1,3}}",
                      (request, response) ->
                          response.text("digits:" + request.pathParam("digits").orElseThrow()))
                  // A suffix mismatch can fall back to the parameter branch for /fallback/fixed/y.
                  .get("/fallback/fixed/x", (ignored2, response) -> response.text("literal-suffix"))
                  .get(
                      "/fallback/{name}/y",
                      (request, response) ->
                          response.text("fallback:" + request.pathParam("name").orElseThrow()))
                  .get("/status", (ignored3, response) -> response.text("ok"));
            });
  }
}
