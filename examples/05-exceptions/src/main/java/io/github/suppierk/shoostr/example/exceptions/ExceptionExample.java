package io.github.suppierk.shoostr.example.exceptions;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.HttpStatusCodes;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import io.github.suppierk.shoostr.http.exceptions.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Demonstrates explicit error handling without a codec or extension. */
public final class ExceptionExample {
  private static final Logger LOGGER = LoggerFactory.getLogger(ExceptionExample.class);

  /** Prevents construction of the launcher. */
  private ExceptionExample() {}

  /**
   * Starts the app and leaves shutdown ownership to Shoostr.
   *
   * @throws Exception if startup fails
   */
  static void main() throws Exception {
    var app = application();
    app.start();
    LOGGER.info("Errors: http://127.0.0.1:{}/builtin/bad-request", app.port());
  }

  /**
   * Creates an unstarted app with inline error-handling demonstrations.
   *
   * <p>S2095 is suppressed because the caller owns the returned app's lifecycle; closing it here
   * would return an unusable app.
   *
   * @return application owned by the caller
   */
  @SuppressWarnings("java:S2095")
  public static Shoostr application() {
    return new Shoostr()
        // Handle IllegalStateException when no matching exception handler is registered on routes.
        .exception(
            IllegalStateException.class,
            (_, _, response) ->
                response
                    .status(HttpStatusCodes.INTERNAL_SERVER_ERROR.value())
                    .text("Application failure"))
        // Customize 404 when no route matches; this does not handle thrown exceptions.
        .status(
            HttpStatusCodes.NOT_FOUND.value(), (_, response) -> response.text("Route not found"))
        .routes(
            routes -> {
              // Handle IllegalArgumentException for these routes and their nested route groups.
              routes.exception(
                  IllegalArgumentException.class,
                  (_, _, response) ->
                      response
                          .status(HttpStatusCodes.BAD_REQUEST.value())
                          .text("Route input error"));

              // The exception handler registered on routes handles this failure.
              routes.get(
                  "/route-failure",
                  (_, _) -> {
                    throw new IllegalArgumentException("Example-only invalid argument details");
                  });

              // Register a different IllegalArgumentException handler for the /group routes.
              routes.path(
                  "/group",
                  group -> {
                    group.exception(
                        IllegalArgumentException.class,
                        (_, _, response) ->
                            response
                                .status(HttpStatusCodes.BAD_REQUEST.value())
                                .text("Group input error"));
                    group.get(
                        "/input",
                        (_, _) -> {
                          throw new IllegalArgumentException("Example-only group input details");
                        });
                  });

              // This route group uses the IllegalArgumentException handler registered on routes.
              routes.path(
                  "/inherited",
                  group ->
                      group.get(
                          "/input",
                          (_, _) -> {
                            throw new IllegalArgumentException(
                                "Example-only inherited input details");
                          }));

              // Discard the unsent response body before invoking the app's exception handler.
              routes.get(
                  "/app-failure",
                  (_, response) -> {
                    response.text("Example-only staged details must not reach the client");
                    throw new IllegalStateException("Example-only internal failure details");
                  });

              // BadRequestException comes from Shoostr's http module.
              // Default handling returns 400 without exposing the exception message.
              routes.get(
                  "/builtin/bad-request",
                  (_, _) -> {
                    throw new BadRequestException("Example-only invalid input details");
                  });

              // Throwing NotFoundException returns the default 404, not the app.status response.
              routes.get(
                  "/builtin/not-found",
                  (_, _) -> {
                    throw new NotFoundException("Example-only missing resource details");
                  });

              // Catch invalid numbers here instead of invoking a registered exception handler.
              routes.get(
                  "/number",
                  (request, response) -> {
                    try {
                      var value = Integer.parseInt(request.queryParam("value").orElse(""));
                      response.text(Integer.toString(value));
                    } catch (NumberFormatException _) {
                      response.status(HttpStatusCodes.BAD_REQUEST.value()).text("Integer required");
                    }
                  });
            });
  }
}
