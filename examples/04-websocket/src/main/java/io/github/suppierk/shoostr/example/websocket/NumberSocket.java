package io.github.suppierk.shoostr.example.websocket;

import java.nio.ByteBuffer;
import java.util.Objects;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Example-local text processing with recoverable input errors and terminal application failures.
 */
public final class NumberSocket implements Session.Listener {
  private static final Logger LOGGER = LoggerFactory.getLogger(NumberSocket.class);
  private volatile @Nullable Session session;

  /** Creates an unopened, independent connection listener. */
  public NumberSocket() {
    session = null;
  }

  /**
   * Retains the native connection and requests its first message.
   *
   * @param opened connection supplied by Jetty
   */
  @Override
  public void onWebSocketOpen(Session opened) {
    session = opened;
    opened.demand();
  }

  /**
   * Processes an integer, recovers from malformed input or closes after a demo failure.
   *
   * @param message complete text supplied by Jetty
   */
  @Override
  public void onWebSocketText(String message) {
    var opened = Objects.requireNonNull(session);
    String result;

    try {
      // This explicit demo command simulates an unexpected application failure after upgrade.
      if ("fail".equals(message)) {
        throw new IllegalStateException("Example-only processing failure details");
      }

      result = "double=" + 2L * Integer.parseInt(message);
    } catch (NumberFormatException _) {
      // A recoverable input problem is a WebSocket reply, not an HTTP BadRequestException.
      result = "error: integer required";
    } catch (RuntimeException failure) {
      LOGGER.warn("WebSocket processing failed", failure);
      // Jetty's StatusCode values are WebSocket close codes, not HTTP status codes.
      opened.close(StatusCode.SERVER_ERROR, "Processing failed", Callback.NOOP);
      return;
    }

    opened.sendText(
        result,
        Callback.from(
            () -> {
              if (opened.isOpen()) {
                opened.demand();
              }
            },
            this::sendFailed));
  }

  /**
   * Acknowledges unsupported binary data, then sends a 1003 close frame.
   *
   * @param data received buffer borrowed from Jetty
   * @param callback receive completion, notified before closure
   */
  @Override
  public void onWebSocketBinary(ByteBuffer data, Callback callback) {
    var opened = Objects.requireNonNull(session);
    // This is valid WebSocket data but unsupported by this endpoint; release it before closing.
    callback.succeed();
    opened.close(StatusCode.BAD_DATA, "Text messages required", Callback.NOOP);
  }

  /**
   * Logs a connection failure whose closure is managed by Jetty.
   *
   * @param failure native protocol or transport failure
   */
  @Override
  public void onWebSocketError(Throwable failure) {
    LOGGER.warn("WebSocket connection failed", failure);
  }

  /**
   * Releases the connection reference and acknowledges the close notification.
   *
   * @param statusCode WebSocket close code
   * @param reason native close reason
   * @param callback close notification completion
   */
  @Override
  public void onWebSocketClose(int statusCode, String reason, Callback callback) {
    session = null;
    LOGGER.debug("WebSocket closed ({})", statusCode);
    callback.succeed();
  }

  /**
   * Logs a failed asynchronous send and closes a connection that is still open.
   *
   * @param failure send failure
   */
  private void sendFailed(Throwable failure) {
    LOGGER.warn("WebSocket send failed", failure);
    var opened = session;
    if (opened != null && opened.isOpen()) {
      opened.close(StatusCode.SERVER_ERROR, "Send failed", Callback.NOOP);
    }
  }
}
