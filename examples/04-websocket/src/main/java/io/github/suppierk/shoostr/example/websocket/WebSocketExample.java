package io.github.suppierk.shoostr.example.websocket;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.HttpStatusCodes;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Registers WebSocket routes inline and leaves connection behavior to separate listeners. */
public final class WebSocketExample {
  private static final Logger LOGGER = LoggerFactory.getLogger(WebSocketExample.class);

  /** Prevents construction of the launcher. */
  private WebSocketExample() {}

  /**
   * Starts the example; Shoostr owns its JVM shutdown hook.
   *
   * @throws Exception if startup fails
   */
  static void main() throws Exception {
    var app = application();
    app.start();
    LOGGER.info("WebSocket examples: http://127.0.0.1:{}/", app.port());
  }

  /**
   * Returns an unstarted app owned by its caller.
   *
   * @return configured application
   */
  public static Shoostr application() {
    return new Shoostr()
        .routes(
            routes -> {
              // Core exception handling can reject a handshake before it becomes a WebSocket
              // connection.
              routes.exception(
                  BadRequestException.class,
                  (_, _, response) ->
                      response.status(HttpStatusCodes.BAD_REQUEST.value()).text("Invalid room"));
              // Open this plain-text page before using the same-origin browser-console recipes.
              routes.get(
                  "/",
                  (_, response) ->
                      response.text("WebSocket endpoints: /echo, /rooms/{room}, /numbers"));
              // Shoostr registers the handshake; EchoSocket is our listener, not a framework class.
              // Jetty's listener methods are available through core's transitive WebSocket
              // dependency.
              routes.websocket("/echo", (_, _) -> new EchoSocket(""));
              // Copy the path value during the handshake; never retain its Request in a listener.
              // Each connection gets its own prefix; this is contextual echo, not shared-room
              // broadcast.
              routes.websocket(
                  "/rooms/{room}",
                  (request, _) -> {
                    var room = request.pathParam("room").orElseThrow();
                    if (room.length() > 32) {
                      throw new BadRequestException("Room name exceeds this example's limit");
                    }

                    return new EchoSocket(room + ": ");
                  });
              // NumberSocket owns message processing after upgrade; HTTP exception handlers do not
              // run here.
              routes.websocket("/numbers", (_, _) -> new NumberSocket());
            });
  }
}
