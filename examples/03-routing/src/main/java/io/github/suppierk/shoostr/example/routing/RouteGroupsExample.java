package io.github.suppierk.shoostr.example.routing;

import io.github.suppierk.shoostr.Routes;
import io.github.suppierk.shoostr.Shoostr;

/** Groups and reuses endpoint definitions under relative prefixes. */
public final class RouteGroupsExample {
  /** Prevents construction of this example factory. */
  private RouteGroupsExample() {}

  /**
   * Creates an unstarted application with nested, reusable, and pathless groups.
   *
   * @return application owned by its caller
   */
  public static Shoostr application() {
    return new Shoostr()
        .routes(
            routes ->
                routes
                    .path(
                        "/api",
                        api -> {
                          // Reuse the same relative endpoint definitions under two version
                          // prefixes.
                          api.path("/v1", v1 -> v1.path("/notes", RouteGroupsExample::notes))
                              .path(
                                  "/v2/notes",
                                  RouteGroupsExample::notes,
                                  configuration ->
                                      configuration.beforeRouteHandler(
                                          (ignored, response) ->
                                              response.setHeader("X-Version", "2")))
                              // The final handler can read captures from both levels of the group.
                              .path(
                                  "/accounts/{accountId}",
                                  account ->
                                      account.path(
                                          "/notes/{id}",
                                          note ->
                                              note.get(
                                                  (request, response) ->
                                                      response.text(
                                                          request
                                                                  .pathParam("accountId")
                                                                  .orElseThrow()
                                                              + ":"
                                                              + request
                                                                  .pathParam("id")
                                                                  .orElseThrow()))));
                        },
                        configuration ->
                            configuration.beforeRouteHandler(
                                (ignoredRequest, response) -> response.setHeader("X-API", "yes")))
                    // A pathless group adds inherited settings without adding a URL prefix.
                    .path(
                        "",
                        status ->
                            status.get(
                                "/status", (ignoredRequest, response) -> response.text("ready")),
                        configuration ->
                            configuration.beforeRouteHandler(
                                (ignoredRequest, response) ->
                                    response.setHeader("X-Group", "status")))
                    // path(...) returns the parent scope, so this endpoint stays outside /api.
                    .get("/public", (ignoredRequest, response) -> response.text("public")));
  }

  /**
   * Registers reusable endpoints relative to the supplied group.
   *
   * @param notes current route group
   */
  private static void notes(Routes notes) {
    // Empty and slash paths are separate endpoints relative to the supplied prefix.
    notes
        .get((ignoredRequest, response) -> response.text("notes"))
        .get("/", (ignoredRequest, response) -> response.text("notes with slash"))
        .get(
            "/{id}",
            (request, response) -> response.text("note:" + request.pathParam("id").orElseThrow()));
  }
}
