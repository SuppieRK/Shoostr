package io.github.suppierk.shoostr;

import java.security.Principal;
import java.util.Map;
import java.util.Objects;
import org.eclipse.jetty.websocket.api.Session;
import org.jspecify.annotations.Nullable;

/** Codec-backed managed callbacks; object and raw binary handlers share one callback slot. */
public final class TypedWebSocket extends WebSocket {
  private final Codec codec;

  /**
   * Creates framework-owned configuration using the application's existing codec.
   *
   * @param codec shared application conversion
   */
  TypedWebSocket(Codec codec) {
    this.codec = Objects.requireNonNull(codec);
  }

  /**
   * Decodes each complete binary message before invoking its sequential callback. A later object or
   * binary registration replaces this handler. Uncaught conversion failures follow ordinary
   * terminal error handling; raw text handling is independent.
   *
   * @param type concrete decode target, not a parameterized type
   * @param handler object callback serialized with this connection's other callbacks
   * @param <T> decoded value type
   * @return this configuration
   */
  public <T> TypedWebSocket onObject(Class<T> type, MessageHandler<T> handler) {
    Objects.requireNonNull(type);
    Objects.requireNonNull(handler);
    super.onBinary(
        (connection, bytes) ->
            handler.handle(
                (TypedWebSocketConnection) connection,
                Objects.requireNonNull(codec.read(bytes, type), "Codec returned null object")));
    return this;
  }

  /**
   * Registers admission with codec-enabled HTTP arguments before the upgrade.
   *
   * @param handler handshake admission, not a retained request or response
   * @return this configuration
   */
  public TypedWebSocket beforeUpgrade(AdmissionHandler handler) {
    super.beforeUpgrade(handler);
    return this;
  }

  /** {@inheritDoc} */
  @Override
  public synchronized TypedWebSocket beforeUpgrade(Handler handler) {
    super.beforeUpgrade(handler);
    return this;
  }

  /**
   * Registers opening work with the codec-enabled connection.
   *
   * @param handler opening callback completed before message delivery
   * @return this configuration
   */
  public TypedWebSocket onOpen(ConnectionHandler handler) {
    super.onOpen(handler);
    return this;
  }

  /** {@inheritDoc} */
  @Override
  public synchronized TypedWebSocket onOpen(WebSocket.ConnectionHandler handler) {
    super.onOpen(handler);
    return this;
  }

  /**
   * Registers raw text handling with the codec-enabled connection.
   *
   * @param handler complete text callback; input bypasses the codec
   * @return this configuration
   */
  public TypedWebSocket onText(MessageHandler<String> handler) {
    super.onText(handler);
    return this;
  }

  /** {@inheritDoc} */
  @Override
  public synchronized TypedWebSocket onText(WebSocket.MessageHandler<String> handler) {
    super.onText(handler);
    return this;
  }

  /**
   * Registers raw binary handling, replacing any object handler.
   *
   * @param handler owned binary callback; input bypasses the codec
   * @return this configuration
   */
  public TypedWebSocket onBinary(MessageHandler<byte[]> handler) {
    super.onBinary(handler);
    return this;
  }

  /** {@inheritDoc} */
  @Override
  public synchronized TypedWebSocket onBinary(WebSocket.MessageHandler<byte[]> handler) {
    super.onBinary(handler);
    return this;
  }

  /**
   * Registers a terminal observer with the same codec-enabled connection.
   *
   * @param handler failure observer, not a recovery callback
   * @return this configuration
   */
  public TypedWebSocket onError(MessageHandler<Throwable> handler) {
    super.onError(handler);
    return this;
  }

  /** {@inheritDoc} */
  @Override
  public synchronized TypedWebSocket onError(WebSocket.MessageHandler<Throwable> handler) {
    super.onError(handler);
    return this;
  }

  /**
   * Registers a close observer with the same codec-enabled connection.
   *
   * @param handler terminal close observer
   * @return this configuration
   */
  public TypedWebSocket onClose(CloseHandler handler) {
    super.onClose(handler);
    return this;
  }

  /** {@inheritDoc} */
  @Override
  public synchronized TypedWebSocket onClose(WebSocket.CloseHandler handler) {
    super.onClose(handler);
    return this;
  }

  /** {@inheritDoc} */
  @Override
  WebSocketConnection connection(
      Session session,
      String path,
      Map<String, String> parameters,
      @Nullable Principal principal,
      ManagedWebSocket listener) {
    return new TypedWebSocketConnection(session, path, parameters, principal, listener, codec);
  }

  /**
   * Callback receiving the codec-enabled connection without wrapping it.
   *
   * @param <T> complete application message type
   */
  @FunctionalInterface
  public interface MessageHandler<T> extends WebSocket.MessageHandler<T> {
    /**
     * Handles a complete application message.
     *
     * @param connection opened codec-enabled connection
     * @param message decoded application message
     * @throws Exception if application handling fails
     */
    @SuppressWarnings("java:S112")
    void handle(TypedWebSocketConnection connection, T message) throws Exception;

    /** {@inheritDoc} */
    @Override
    default void handle(WebSocketConnection connection, T message) throws Exception {
      handle((TypedWebSocketConnection) connection, message);
    }
  }

  /** Admission callback whose typed HTTP arguments remain scoped to the handshake. */
  @FunctionalInterface
  public interface AdmissionHandler extends Handler {
    /**
     * Validates the handshake before upgrading.
     *
     * @param request codec-enabled handshake input
     * @param response codec-enabled handshake output
     * @throws Exception if admission rejects the handshake
     */
    @SuppressWarnings("java:S112")
    void handle(TypedRequest request, TypedResponse response) throws Exception;

    /** {@inheritDoc} */
    @Override
    default void handle(Request request, Response response) throws Exception {
      handle((TypedRequest) request, (TypedResponse) response);
    }
  }

  /** Opening callback over the codec-enabled connection. */
  @FunctionalInterface
  public interface ConnectionHandler extends WebSocket.ConnectionHandler {
    /**
     * Handles connection opening before message delivery.
     *
     * @param connection codec-enabled connection
     * @throws Exception if opening fails
     */
    @SuppressWarnings("java:S112")
    void handle(TypedWebSocketConnection connection) throws Exception;

    /** {@inheritDoc} */
    @Override
    default void handle(WebSocketConnection connection) throws Exception {
      handle((TypedWebSocketConnection) connection);
    }
  }

  /** Close observer over the codec-enabled connection. */
  @FunctionalInterface
  public interface CloseHandler extends WebSocket.CloseHandler {
    /**
     * Observes native termination without participating in transport cleanup.
     *
     * @param connection codec-enabled connection
     * @param code native close code
     * @param reason native close reason
     * @throws Exception if observation fails
     */
    @SuppressWarnings("java:S112")
    void handle(TypedWebSocketConnection connection, int code, String reason) throws Exception;

    /** {@inheritDoc} */
    @Override
    default void handle(WebSocketConnection connection, int code, String reason) throws Exception {
      handle((TypedWebSocketConnection) connection, code, reason);
    }
  }
}
