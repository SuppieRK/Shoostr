package io.github.suppierk.shoostr.example.json;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.HttpStatusCodes;
import io.github.suppierk.shoostr.http.MediaType;
import org.eclipse.jetty.server.ServerConnector;

/** Wires the codec and handlers without adding serialization to the framework core. */
public final class JsonExample {
  /** Prevents construction of the launcher. */
  private JsonExample() {}

  /**
   * Starts the app and leaves shutdown ownership to Shoostr.
   *
   * @throws Exception if startup fails
   */
  static void main() throws Exception {
    var app = application(Options.defaults());
    app.modifyServer(
        server ->
            ((ServerConnector) server.getConnectors()[0])
                .setHost(System.getProperty("shoostr.host", "127.0.0.1")));
    app.start();
    System.out.println("JSON: http://127.0.0.1:" + app.port() + "/users");
  }

  /**
   * Creates the unstarted JSON app; tests use its actual HTTP interface.
   *
   * @param options listener settings
   * @return application owned by the caller
   */
  public static Shoostr application(Options options) {
    var app = new Shoostr(options);
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
