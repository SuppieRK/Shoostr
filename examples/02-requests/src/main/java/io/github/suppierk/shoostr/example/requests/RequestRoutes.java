package io.github.suppierk.shoostr.example.requests;

import io.github.suppierk.shoostr.Routes;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import java.util.function.Function;

/** Defines input handling separately from application startup and dependency selection. */
final class RequestRoutes {
  /** Prevents construction of the registration function. */
  private RequestRoutes() {}

  /**
   * Registers a decoded path parameter passed to the application-owned function.
   *
   * @param routes application registration scope
   * @param greeting concurrent-safe greeting function
   */
  static void register(Routes routes, Function<String, String> greeting) {
    routes.get(
        "/welcome",
        (request, response) -> {
          var name = request.queryParam("name").orElse("World");
          if (name.isBlank()) {
            throw new BadRequestException("Name must not be blank");
          }

          response.text(greeting.apply(name));
        });
    routes.get(
        "/greetings/{name}",
        (request, response) ->
            response.text(greeting.apply(request.pathParam("name").orElseThrow())));
    routes.get(
        "/search",
        (request, response) -> response.text(String.join(",", request.queryParams("tag"))));
  }
}
