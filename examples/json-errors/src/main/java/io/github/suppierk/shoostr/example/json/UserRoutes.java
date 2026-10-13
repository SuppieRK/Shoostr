package io.github.suppierk.shoostr.example.json;

import io.github.suppierk.shoostr.TypedRoutes;
import io.github.suppierk.shoostr.http.HttpStatusCodes;
import io.github.suppierk.shoostr.http.MediaType;

/** Typed user handling stays separate from codec implementation and application startup. */
final class UserRoutes {
  /** Prevents construction of the registration function. */
  private UserRoutes() {}

  /**
   * Registers one typed JSON operation without assuming that all responses are JSON.
   *
   * @param routes codec-enabled registration scope
   */
  static void register(TypedRoutes routes) {
    routes.path(
        "/users",
        users -> {
          UserErrors.register(users);
          users.get(
              "/help", (_, response) -> response.text("POST a JSON object with a nonblank name"));
          users.get(
              "/failure",
              (_, _) -> {
                throw new IllegalStateException(
                    "Example-only internal details must not reach the client");
              });
          users.post(
              (request, response) ->
                  response
                      .status(HttpStatusCodes.CREATED.value())
                      .body(MediaType.APPLICATION_JSON, request.body(User.class)));
        });
  }
}
