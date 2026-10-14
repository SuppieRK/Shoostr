package io.github.suppierk.shoostr.example.websocket;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.WebSocketConnection;
import io.github.suppierk.shoostr.http.HttpStatusCodes;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Registers ordinary WebSocket callbacks; core owns delivery, writes and connection state. */
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
   * <p>S2095 is suppressed because the caller owns the returned app and its managed connections;
   * closing it here would return an unusable app.
   *
   * @return configured application
   */
  @SuppressWarnings("java:S2095")
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
              // WebSocketConnection comes from core: send waits for transport completion, and
              // each connection's callbacks run sequentially on managed virtual threads.
              routes.websocket(
                  "/echo",
                  websocket -> {
                    websocket.onText(WebSocketConnection::send);
                    websocket.onBinary(WebSocketConnection::send);
                    // These notifications do not own transport cleanup; they only observe it.
                    websocket.onOpen(connection -> LOGGER.debug("Opened {}", connection.path()));
                    websocket.onClose(
                        (connection, code, _) ->
                            LOGGER.debug("Closed {} ({})", connection.path(), code));
                    websocket.onError(
                        (connection, failure) ->
                            LOGGER.debug("WebSocket failed: {}", connection.path(), failure));
                  });
              // Core copies path values and the initial principal before the handshake ends.
              // Each connection gets its own prefix; this is contextual echo, not shared-room
              // broadcast.
              routes.websocket(
                  "/rooms/{room}",
                  websocket -> {
                    // Throw during beforeUpgrade to use ordinary HTTP exception handling.
                    websocket.beforeUpgrade(
                        (request, _) -> {
                          if (request.pathParam("room").orElseThrow().length() > 32) {
                            throw new BadRequestException("Room name exceeds this example's limit");
                          }
                        });
                    websocket.onText(
                        (connection, text) ->
                            connection.send(
                                connection.pathParam("room").orElseThrow() + ": " + text));
                    websocket.onBinary(WebSocketConnection::send);
                  });
              // Handle recoverable input errors in the message callback; HTTP renderers cannot
              // replace a response after upgrade. Unexpected failures close safely with 1011.
              routes.websocket(
                  "/numbers",
                  websocket -> {
                    websocket.onText(
                        (connection, text) -> {
                          // An explicit demo command injects an unexpected application failure.
                          if ("fail".equals(text)) {
                            throw new IllegalStateException(
                                "Example-only processing failure details");
                          }

                          int value;

                          try {
                            value = Integer.parseInt(text);
                          } catch (NumberFormatException _) {
                            connection.send("error: integer required");
                            return;
                          }

                          connection.send("double=" + 2L * value);
                        });
                    // Jetty's StatusCode constants are WebSocket close codes, not HTTP statuses.
                    websocket.onBinary(
                        (connection, _) ->
                            connection.close(StatusCode.BAD_DATA, "Text messages required"));
                  });
            });
  }
}
