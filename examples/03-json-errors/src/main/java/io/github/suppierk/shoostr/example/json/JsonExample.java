package io.github.suppierk.shoostr.example.json;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.HttpStatusCodes;
import io.github.suppierk.shoostr.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Wires the codec and handlers without adding serialization to the framework core. */
public final class JsonExample {
  private static final Logger LOGGER = LoggerFactory.getLogger(JsonExample.class);

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
    app.codec(new UserCodec())
        .routes(
            routes ->
                routes.path(
                    "/users",
                    users -> {
                      UserErrors.register(users);
                      users.get(
                          "/help",
                          (_, response) ->
                              response.text("POST a JSON object with a nonblank name"));
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
                    }));
    return app;
  }
}
