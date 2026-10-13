package io.github.suppierk.shoostr.example.json;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.HttpStatusCodes;
import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Wires the codec and handlers without adding serialization to the framework core. */
public final class JsonExample {
  private static final Logger LOGGER = LoggerFactory.getLogger(JsonExample.class);
  private static final byte[] INVALID_USER =
      "{\"error\":\"invalid_user\"}".getBytes(StandardCharsets.UTF_8);
  private static final byte[] INTERNAL_ERROR =
      "{\"error\":\"internal_error\"}".getBytes(StandardCharsets.UTF_8);

  /** Prevents construction of the launcher. */
  private JsonExample() {}

  /**
   * Starts the app and leaves shutdown ownership to Shoostr.
   *
   * @throws Exception if startup fails
   */
  static void main() throws Exception {
    var app = application();
    app.start();
    LOGGER.info("JSON: http://127.0.0.1:{}/users", app.port());
  }

  /**
   * Creates the unstarted JSON app; tests use its actual HTTP interface.
   *
   * @return application owned by the caller
   */
  public static Shoostr application() {
    var app = new Shoostr();
    // UserCodec is this example's Jackson-backed implementation of core's Codec interface.
    // Register it to enable typed body methods; byte[] and text methods remain available.
    app.codec(new UserCodec())
        .routes(
            routes ->
                routes.path(
                    "/users",
                    users -> {
                      // Return JSON for invalid input only within this route group.
                      users.exception(
                          BadRequestException.class,
                          (_, _, response) ->
                              response
                                  .status(HttpStatusCodes.BAD_REQUEST.value())
                                  .body(MediaType.APPLICATION_JSON, INVALID_USER));

                      // Keep internal details out of the deliberate server-error response.
                      users.exception(
                          IllegalStateException.class,
                          (_, _, response) ->
                              response
                                  .status(HttpStatusCodes.INTERNAL_SERVER_ERROR.value())
                                  .body(MediaType.APPLICATION_JSON, INTERNAL_ERROR));

                      // Ordinary text responses remain available alongside typed JSON.
                      users.get(
                          "/help",
                          (_, response) ->
                              response.text("POST a JSON object with a nonblank name"));

                      // Throw a failure handled by this group's JSON exception handler.
                      users.get(
                          "/failure",
                          (_, _) -> {
                            throw new IllegalStateException(
                                "Example-only internal details must not reach the client");
                          });

                      // Decode, validate and return a typed user through the installed codec.
                      users.post(
                          (request, response) ->
                              response
                                  .status(HttpStatusCodes.CREATED.value())
                                  .body(MediaType.APPLICATION_JSON, request.body(User.class)));
                    }));
    return app;
  }
}
