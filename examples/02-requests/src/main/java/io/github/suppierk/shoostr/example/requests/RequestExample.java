package io.github.suppierk.shoostr.example.requests;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import java.util.Objects;
import java.util.function.Function;
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
    var app = application(name -> "Hello, " + name + "!");
    app.start();
    LOGGER.info("Inputs: http://127.0.0.1:{}/greetings/Ada", app.port());
  }

  /**
   * Configures an unstarted app using a caller-owned greeting dependency.
   *
   * @param greeting concurrent-safe application function, replaceable in tests
   * @return app owned by its eventual caller
   */
  public static Shoostr application(Function<String, String> greeting) {
    Objects.requireNonNull(greeting);
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
            });
  }
}
