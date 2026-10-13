package io.github.suppierk.shoostr.example.websocket;

import java.nio.ByteBuffer;
import java.util.Objects;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Example-local per-connection echo listener using Jetty's public callback methods. */
public final class EchoSocket implements Session.Listener {
  private static final Logger LOGGER = LoggerFactory.getLogger(EchoSocket.class);
  private final String prefix;
  private volatile @Nullable Session session;

  /**
   * Captures only immutable application context, never the handshake request.
   *
   * @param prefix text added to echoed text messages
   * @throws NullPointerException if the prefix is absent
   */
  public EchoSocket(String prefix) {
    this.prefix = Objects.requireNonNull(prefix);
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
    // Session is Jetty's connection; demand allows one callback to proceed.
    opened.demand();
  }

  /**
   * Echoes one complete text message with this connection's prefix.
   *
   * @param message complete text supplied by Jetty
   */
  @Override
  public void onWebSocketText(String message) {
    var opened = Objects.requireNonNull(session);
    // Jetty sends asynchronously; demand the next message only once this send succeeds.
    opened.sendText(
        prefix + message,
        Callback.from(
            () -> {
              if (opened.isOpen()) {
                opened.demand();
              }
            },
            this::sendFailed));
  }

  /**
   * Echoes binary data without releasing the received buffer before send completion.
   *
   * @param data received buffer borrowed from Jetty
   * @param callback receive completion, notified after the send completes
   */
  @Override
  public void onWebSocketBinary(ByteBuffer data, Callback callback) {
    var opened = Objects.requireNonNull(session);
    // Keep Jetty's received buffer until the async echo completes; acknowledge before demanding
    // again.
    opened.sendBinary(
        data,
        Callback.from(
            () -> {
              callback.succeed();
              if (opened.isOpen()) {
                opened.demand();
              }
            },
            failure -> {
              callback.fail(failure);
              sendFailed(failure);
            }));
  }

  /**
   * Logs a connection failure whose closure is managed by Jetty.
   *
   * @param failure native protocol or transport failure
   */
  @Override
  public void onWebSocketError(Throwable failure) {
    // Jetty already handles protocol/transport closure; these are not HTTP handler exceptions.
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
