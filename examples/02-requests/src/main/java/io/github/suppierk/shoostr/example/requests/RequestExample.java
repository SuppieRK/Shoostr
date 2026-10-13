package io.github.suppierk.shoostr.example.requests;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.http.HttpStatusCodes;
import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Wires explicit dependencies and starts the request-input demonstration. */
public final class RequestExample {
  private static final Logger LOGGER = LoggerFactory.getLogger(RequestExample.class);

  /** Prevents construction of the launcher. */
  private RequestExample() {}

  /**
   * Starts the app; no DI container or user-managed shutdown hook is needed.
   *
   * @throws Exception if startup fails
   */
  static void main() throws Exception {
    var app =
        application(
            name -> "Hello, " + name + "!", () -> Boolean.getBoolean("shoostr.example.preview"));
    app.start();
    LOGGER.info("Inputs: http://127.0.0.1:{}/greetings/Ada", app.port());
  }

  /**
   * Configures an unstarted app using a caller-owned greeting dependency.
   *
   * @param greeting concurrent-safe application function, replaceable in tests
   * @return app owned by its eventual caller
   */
  public static Shoostr application(UnaryOperator<String> greeting) {
    return application(greeting, () -> false);
  }

  /**
   * Configures request demonstrations with a live, caller-owned availability flag.
   *
   * @param greeting concurrent-safe greeting operation, replaceable in tests
   * @param previewEnabled concurrent-safe flag evaluated for each matched preview request
   * @return app owned by its eventual caller
   */
  public static Shoostr application(
      UnaryOperator<String> greeting, BooleanSupplier previewEnabled) {
    // These JDK functions are caller-supplied dependencies, not framework-managed services.
    Objects.requireNonNull(greeting);
    Objects.requireNonNull(previewEnabled);
    return new Shoostr()
        .routes(
            routes -> {
              // Read an optional query parameter, default it, and reject a blank value.
              routes.get(
                  "/welcome",
                  (request, response) -> {
                    var name = request.queryParam("name").orElse("World");
                    if (name.isBlank()) {
                      throw new BadRequestException("Name must not be blank");
                    }

                    response.text(greeting.apply(name));
                  });

              // Capture a path parameter and pass it to the supplied greeting function.
              routes.get(
                  "/greetings/{name}",
                  (request, response) ->
                      response.text(greeting.apply(request.pathParam("name").orElseThrow())));

              // Read every occurrence of a repeated query parameter in order.
              routes.get(
                  "/search",
                  (request, response) ->
                      response.text(String.join(",", request.queryParams("tag"))));

              // Group all seven convenience verbs at one path; these echoes store no state.
              // Shoostr's path method prefixes each nested registration with /echo.
              // Method, status, header and media-type constants come from Shoostr's http module.
              routes.path(
                  "/echo",
                  echo -> {
                    // GET returns usage guidance without consuming a body.
                    echo.get(
                        (_, response) ->
                            response.text("Send text with POST or PATCH, or bytes with PUT"));

                    // POST reads and returns a text body.
                    echo.post((request, response) -> response.text(request.bodyText()));

                    // PUT preserves the exact bytes and returns a binary content type.
                    echo.put(
                        (request, response) ->
                            response.body(MediaType.APPLICATION_OCTET_STREAM, request.bodyBytes()));

                    // PATCH echoes text and reports the request method in a response header.
                    echo.patch(
                        (request, response) ->
                            response
                                .setHeader("X-Request-Method", request.method())
                                .text(request.bodyText()));

                    // DELETE demonstrates a 204 response, not deletion from a backing store.
                    echo.delete(
                        (_, response) -> response.status(HttpStatusCodes.NO_CONTENT.value()));

                    // HEAD returns metadata with no response body.
                    echo.head((_, response) -> response.setHeader("X-Echo-Mode", "stateless"));

                    // OPTIONS explicitly advertises the methods supported at this path.
                    echo.options(
                        (_, response) ->
                            response
                                .status(HttpStatusCodes.NO_CONTENT.value())
                                .setHeader(
                                    HttpHeaders.ALLOW.value(),
                                    String.join(
                                        ", ",
                                        HttpMethods.GET.value(),
                                        HttpMethods.POST.value(),
                                        HttpMethods.PUT.value(),
                                        HttpMethods.PATCH.value(),
                                        HttpMethods.DELETE.value(),
                                        HttpMethods.HEAD.value(),
                                        HttpMethods.OPTIONS.value())));
                  });

              // Register a method without a dedicated convenience method and echo its text body.
              routes.route(
                  HttpMethods.QUERY,
                  "/query",
                  (request, response) -> response.text(request.bodyText()));

              // Decode URL-encoded form fields, validate the name, and retain repeated tags.
              routes.post(
                  "/forms",
                  (request, response) -> {
                    var name =
                        request
                            .formParam("name")
                            .filter(value -> !value.isBlank())
                            .orElseThrow(
                                () -> new BadRequestException("Form name must not be blank"));
                    response.text(
                        greeting.apply(name)
                            + "; tags="
                            + String.join(",", request.formParams("tag")));
                  });

              // Echo an incoming header/cookie and set the theme cookie for the next request.
              routes.get(
                  "/preferences",
                  (request, response) ->
                      response
                          .setHeader("X-Request-Id", request.header("X-Request-Id").orElse("none"))
                          .cookie("theme", request.queryParam("theme").orElse("light"))
                          .text(request.cookie("theme").orElse("unset")));

              // Match a named segment only when it satisfies the numeric constraint.
              routes.get(
                  "/items/{id:[0-9]+}",
                  (request, response) -> response.text(request.pathParam("id").orElseThrow()));

              // Capture the remaining path as a decoded value, without accessing the filesystem.
              routes.get(
                  "/paths/{*tail}",
                  (request, response) -> response.text(request.pathParam("tail").orElseThrow()));

              // Require both a live flag and a request predicate; this is availability, not auth.
              // Shoostr evaluates when conditions per matched request; false produces a 404.
              routes.when(
                  previewEnabled,
                  preview ->
                      preview.when(
                          request -> request.header("X-Preview").filter("true"::equals).isPresent(),
                          available ->
                              available.get(
                                  "/preview", (_, response) -> response.text("Preview enabled"))));
            });
  }
}
