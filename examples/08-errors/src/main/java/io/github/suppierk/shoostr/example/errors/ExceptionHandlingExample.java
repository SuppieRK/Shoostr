package io.github.suppierk.shoostr.example.errors;

import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.Response;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import java.io.IOException;

/** Handles request failures without exposing private diagnostics. */
public final class ExceptionHandlingExample {
  private static final String INPUT_PATH = "/input";

  /** Prevents construction of this example factory. */
  private ExceptionHandlingExample() {}

  /**
   * Creates an unstarted application.
   *
   * @return application owned by its caller
   */
  public static Shoostr application() {
    return new Shoostr()
        // An application renderer is used only when no scoped exception mapping matches.
        .exception(
            IllegalStateException.class,
            (ignored, ignored2, response) -> response.text("Request failed"))
        .routes(
            routes -> {
              routes
                  .get(
                      "/checkout",
                      (ignored3, response) -> {
                        // Failure discards this uncommitted body and header before rendering the
                        // error.
                        response
                            .status(201)
                            .setHeader("X-Diagnostic", "private")
                            .text("Private staged output");
                        throw new IllegalStateException("Private checkout diagnostics");
                      })
                  .get(INPUT_PATH, ExceptionHandlingExample::readName)
                  .get("/number", ExceptionHandlingExample::readNumber)
                  .path(
                      "/group",
                      group -> {
                        // Override the broad family; the inherited number mapping is still
                        // narrower.
                        group
                            .exception(
                                IllegalArgumentException.class,
                                (ignored4, ignored5, response) ->
                                    response.status(409).text("Group input rejected"))
                            .get(INPUT_PATH, ExceptionHandlingExample::readName)
                            .get("/number", ExceptionHandlingExample::readNumber);
                      })
                  // With no local override, this child uses the root scope's input mapping.
                  .path(
                      "/inherited",
                      group -> group.get(INPUT_PATH, ExceptionHandlingExample::readName))
                  // Built-in HTTP exceptions keep their status, but not private diagnostic text.
                  .get(
                      "/builtin",
                      (ignored6, ignored7) -> {
                        throw new BadRequestException("Private request diagnostics");
                      })
                  // An unmapped checked exception produces a safe 500 response.
                  .get(
                      "/unhandled",
                      (ignored8, ignored9) -> {
                        throw new IOException("Private storage diagnostics");
                      })
                  // Selection examines the thrown IOException, not the input error in its cause.
                  .get(
                      "/cause",
                      (ignored10, ignored11) -> {
                        throw new IOException(
                            "Private wrapper diagnostics",
                            new IllegalArgumentException("Private cause"));
                      })
                  // Catch an expected parse error locally instead of invoking a scope renderer.
                  .get(
                      "/recover",
                      (request, response) -> {
                        try {
                          var number = Integer.parseInt(request.queryParam("value").orElse(""));
                          response.text(Integer.toString(number));
                        } catch (NumberFormatException ignored12) {
                          response.status(400).text("Use an integer");
                        }
                      })

                  // These mappings cover the entire scope, including endpoints above and
                  // descendants.
                  // They do not configure just the last endpoint in this fluent chain.
                  .exception(
                      IllegalArgumentException.class,
                      (ignored13, ignored14, response) ->
                          response.status(400).text("Invalid input"))
                  .exception(
                      NumberFormatException.class,
                      (ignored15, ignored16, response) ->
                          response.status(422).text("Integer required"));
            });
  }

  /**
   * Returns a nonblank name or lets the scope renderer reject it.
   *
   * @param request current request
   * @param response current response
   * @throws IllegalArgumentException if the name is absent or blank
   */
  private static void readName(Request request, Response response) {
    var name = request.queryParam("name").orElse("");
    if (name.isBlank()) {
      throw new IllegalArgumentException("Private input diagnostics");
    }

    response.text(name);
  }

  /**
   * Parses an integer without intercepting the scope's specific renderer.
   *
   * @param request current request
   * @param response current response
   * @throws NumberFormatException if the value is not an integer
   */
  private static void readNumber(Request request, Response response) {
    var number = Integer.parseInt(request.queryParam("value").orElse(""));
    response.text(Integer.toString(number));
  }
}
