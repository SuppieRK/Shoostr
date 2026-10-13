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
    Objects.requireNonNull(greeting);
    Objects.requireNonNull(previewEnabled);
    return new Shoostr()
        .routes(
            routes -> {
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
                  (request, response) ->
                      response.text(String.join(",", request.queryParams("tag"))));
              routes.path(
                  "/echo",
                  echo -> {
                    echo.get(
                        (_, response) ->
                            response.text("Send text with POST or PATCH, or bytes with PUT"));
                    echo.post((request, response) -> response.text(request.bodyText()));
                    echo.put(
                        (request, response) ->
                            response.body(MediaType.APPLICATION_OCTET_STREAM, request.bodyBytes()));
                    echo.patch(
                        (request, response) ->
                            response
                                .setHeader("X-Request-Method", request.method())
                                .text(request.bodyText()));
                    echo.delete(
                        (_, response) -> response.status(HttpStatusCodes.NO_CONTENT.value()));
                    echo.head((_, response) -> response.setHeader("X-Echo-Mode", "stateless"));
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
              routes.route(
                  HttpMethods.QUERY,
                  "/query",
                  (request, response) -> response.text(request.bodyText()));
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
              routes.get(
                  "/preferences",
                  (request, response) ->
                      response
                          .setHeader("X-Request-Id", request.header("X-Request-Id").orElse("none"))
                          .cookie("theme", request.queryParam("theme").orElse("light"))
                          .text(request.cookie("theme").orElse("unset")));
              routes.get(
                  "/items/{id:[0-9]+}",
                  (request, response) -> response.text(request.pathParam("id").orElseThrow()));
              routes.get(
                  "/paths/{*tail}",
                  (request, response) -> response.text(request.pathParam("tail").orElseThrow()));
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
