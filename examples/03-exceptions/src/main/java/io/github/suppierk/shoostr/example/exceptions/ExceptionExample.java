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
   * @return application owned by the caller
   */
  public static Shoostr application() {
    return new Shoostr()
        // App-wide fallback handles this exception family when no scope renderer matches.
        .exception(
            IllegalStateException.class,
            (_, _, response) ->
                response
                    .status(HttpStatusCodes.INTERNAL_SERVER_ERROR.value())
                    .text("Application failure"))
        // Generated routing statuses are separate from exceptions thrown by matched handlers.
        .status(
            HttpStatusCodes.NOT_FOUND.value(), (_, response) -> response.text("Route not found"))
        .routes(
            routes -> {
              // Root scope rendering applies to these routes and is inherited by child groups.
              routes.exception(
                  IllegalArgumentException.class,
                  (_, _, response) ->
                      response
                          .status(HttpStatusCodes.BAD_REQUEST.value())
                          .text("Route input error"));

              // Let this failure escape the handler and reach the root scope's renderer.
              routes.get(
                  "/route-failure",
                  (_, _) -> {
                    throw new IllegalArgumentException("Example-only invalid argument details");
                  });

              // Replace the inherited mapping for this exception class only within /group.
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

              // Without an override, this sibling group inherits the root renderer.
              routes.path(
                  "/inherited",
                  group ->
                      group.get(
                          "/input",
                          (_, _) -> {
                            throw new IllegalArgumentException(
                                "Example-only inherited input details");
                          }));

              // Staged output is reset when the uncommitted response becomes an error response.
              routes.get(
                  "/app-failure",
                  (_, response) -> {
                    response.text("Example-only staged details must not reach the client");
                    throw new IllegalStateException("Example-only internal failure details");
                  });

              // Unhandled HTTP exceptions retain their status without exposing diagnostics.
              routes.get(
                  "/builtin/bad-request",
                  (_, _) -> {
                    throw new BadRequestException("Example-only invalid input details");
                  });

              // This matched-handler exception does not invoke the generated-404 status renderer.
              routes.get(
                  "/builtin/not-found",
                  (_, _) -> {
                    throw new NotFoundException("Example-only missing resource details");
                  });

              // Recover from a known parsing failure here, rather than using an error renderer.
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
