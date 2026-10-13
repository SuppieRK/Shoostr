package io.github.suppierk.shoostr.example.authentication;

import io.github.suppierk.shoostr.Routes;
import io.github.suppierk.shoostr.pac4j.Pac4j;

/** Selects authentication on a group without wrapping each handler manually. */
final class PrivateRoutes {
  /** Prevents construction of the registration function. */
  private PrivateRoutes() {}

  /**
   * Registers authenticated principal access using an already installed identity source.
   *
   * @param routes root registration scope
   * @param auth installed authentication extension
   */
  static void register(Routes routes, Pac4j auth) {
    routes.get("/public", (_, response) -> response.text("public"));
    routes.path(
        "/private",
        group -> {
          group.get(
              "/me",
              (request, response) -> response.text(request.principal().orElseThrow().getName()));
          group.get("/admin", (_, response) -> response.text("admin operation allowed"));
        },
        extensions -> extensions.get(auth).required());
  }
}
