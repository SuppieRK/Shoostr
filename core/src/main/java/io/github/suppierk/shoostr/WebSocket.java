package io.github.suppierk.shoostr;

import java.util.Objects;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.server.ServerUpgradeResponse;
import org.jspecify.annotations.Nullable;

/**
 * Eager route configuration for complete WebSocket messages on managed virtual threads. Each event
 * has one callback; later configuration replaces the earlier callback before registration freezes.
 * Captured application state is shared across this route's connections and must be thread-safe.
 */
public final class WebSocket {
  private @Nullable MessageHandler<String> text;
  private @Nullable Handler beforeUpgrade;
  private @Nullable ConnectionHandler open;
  private @Nullable MessageHandler<byte[]> binary;
  private @Nullable CloseHandler close;
  private @Nullable MessageHandler<Throwable> error;
  private boolean frozen;

  /** Creates a configuration owned by route registration. */
  WebSocket() {}

  /**
   * Validates admission before upgrading. Throw to reject through ordinary HTTP exception handling;
   * do not stage a body or change status. Header configuration is permitted.
   *
   * @param handler checked-exception-capable HTTP admission callback
   * @return this configuration
   */
  public synchronized WebSocket beforeUpgrade(Handler handler) {
    requireMutable();
    beforeUpgrade = Objects.requireNonNull(handler);
    return this;
  }

  /**
   * Registers an opening callback, completed before admitting application messages.
   *
   * @param handler callback on a managed virtual thread
   * @return this configuration
   */
  public synchronized WebSocket onOpen(ConnectionHandler handler) {
    requireMutable();
    open = Objects.requireNonNull(handler);
    return this;
  }

  /**
   * Registers complete binary handling with an owned array safe to retain after callback return.
   *
   * @param handler sequential callback on a managed virtual thread
   * @return this configuration
   */
  public synchronized WebSocket onBinary(MessageHandler<byte[]> handler) {
    requireMutable();
    binary = Objects.requireNonNull(handler);
    return this;
  }

  /**
   * Registers a terminal notification after active application work has unwound. Native resource
   * release does not depend on this observer, which may not run during shutdown or rejected
   * dispatch.
   *
   * @param handler close code and reason observer on the connection's virtual worker
   * @return this configuration
   */
  public synchronized WebSocket onClose(CloseHandler handler) {
    requireMutable();
    close = Objects.requireNonNull(handler);
    return this;
  }

  /**
   * Registers a terminal failure observer, not a recovery handler or HTTP exception renderer.
   * Recoverable message failures should be caught inside the message callback. Observer failures
   * are logged without recursion, and native cleanup never waits for this observer.
   *
   * @param handler first unexpected application or transport failure observer
   * @return this configuration
   */
  public synchronized WebSocket onError(MessageHandler<Throwable> handler) {
    requireMutable();
    error = Objects.requireNonNull(handler);
    return this;
  }

  /**
   * Registers complete text handling. Captured state is shared by this route's connections.
   *
   * @param handler callback whose return permits the next message
   * @return this configuration
   */
  public synchronized WebSocket onText(MessageHandler<String> handler) {
    requireMutable();
    text = Objects.requireNonNull(handler);
    return this;
  }

  /** Prevents changes after the route configuration callback returns. */
  synchronized void freeze() {
    frozen = true;
  }

  /**
   * Runs HTTP admission without permitting streaming or file commitment.
   *
   * @param request live handshake input
   * @param response live handshake output
   * @throws Exception if admission rejects the request
   */
  void admit(Request request, Response response) throws Exception {
    response.gating(true);

    try {
      if (beforeUpgrade != null) {
        beforeUpgrade.handle(request, response);
      }
    } finally {
      response.gating(false);
    }

    response.requireWebSocketUpgrade();
  }

  /**
   * Reads the registered text callback after configuration has frozen.
   *
   * @return registered handler, or null
   */
  @Nullable MessageHandler<String> text() {
    return text;
  }

  /**
   * Reads the frozen opening callback.
   *
   * @return registered handler, or null
   */
  @Nullable ConnectionHandler open() {
    return open;
  }

  /**
   * Reads the frozen binary callback.
   *
   * @return registered handler, or null
   */
  @Nullable MessageHandler<byte[]> binary() {
    return binary;
  }

  /**
   * Reads the frozen close observer.
   *
   * @return registered observer, or null
   */
  @Nullable CloseHandler close() {
    return close;
  }

  /**
   * Reads the frozen error observer.
   *
   * @return registered observer, or null
   */
  @Nullable MessageHandler<Throwable> error() {
    return error;
  }

  /**
   * Rejects modification of a published configuration.
   *
   * @throws IllegalStateException if route registration has finished
   */
  private void requireMutable() {
    if (frozen) {
      throw new IllegalStateException("WebSocket configuration is closed");
    }
  }

  /**
   * Application handling of one complete message; failures close the connection safely.
   *
   * @param <T> delivered message type
   */
  @FunctionalInterface
  public interface MessageHandler<T> {
    /**
     * Handles a message on a virtual thread, serialized with this connection's other callbacks.
     *
     * @param connection opened connection
     * @param message owned application message
     * @throws Exception if application handling fails
     */
    void handle(WebSocketConnection connection, T message) throws Exception;
  }

  /** Checked-exception-capable work on an opened connection. */
  @FunctionalInterface
  public interface ConnectionHandler {
    /**
     * Handles one opening or internal delivery operation.
     *
     * @param connection opened connection
     * @throws Exception if application handling fails
     */
    void handle(WebSocketConnection connection) throws Exception;
  }

  /** Checked-exception-capable observation of native connection closure. */
  @FunctionalInterface
  public interface CloseHandler {
    /**
     * Observes termination without participating in native resource cleanup.
     *
     * @param connection terminated connection
     * @param code native close code
     * @param reason native close reason
     * @throws Exception if observation fails; the failure is logged, not recursively delivered
     */
    void handle(WebSocketConnection connection, int code, String reason) throws Exception;
  }

  /** Internal creation seam shared by managed and native listeners. */
  @FunctionalInterface
  interface Factory {
    /**
     * Creates a fresh listener after HTTP admission.
     *
     * @param request live handshake input, not retained
     * @param response native upgrade output
     * @param runtime app-owned message dispatch and shutdown
     * @return per-connection listener
     * @throws Exception if creation fails
     */
    Session.Listener create(
        Request request, ServerUpgradeResponse response, WebSocketRuntime runtime) throws Exception;
  }
}
