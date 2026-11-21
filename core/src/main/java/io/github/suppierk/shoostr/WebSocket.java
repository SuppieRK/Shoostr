package io.github.suppierk.shoostr;

import static io.github.suppierk.shoostr.Locks.runUnderLock;

import java.security.Principal;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.server.ServerUpgradeResponse;
import org.jspecify.annotations.Nullable;

/**
 * Eager route configuration for complete WebSocket messages with serialized managed callbacks. Each
 * event has one callback; later configuration replaces the earlier callback before registration
 * freezes. Captured application state is shared across this route's connections and must be
 * thread-safe. Callbacks for one connection can use different threads; idle connections retain no
 * worker.
 */
public sealed class WebSocket {
  private final ReentrantLock registrationLock;
  private WebSocket.@Nullable MessageHandler<String> text;
  private @Nullable Handler beforeUpgrade;
  private WebSocket.@Nullable ConnectionHandler open;
  private WebSocket.@Nullable MessageHandler<byte[]> binary;
  private WebSocket.@Nullable CloseHandler close;
  private WebSocket.@Nullable MessageHandler<Throwable> error;
  private boolean frozen;

  /** Creates a configuration owned by route registration. */
  WebSocket() {
    this.registrationLock = new ReentrantLock();
  }

  /**
   * Creates the one public connection used by every managed callback.
   *
   * @param session opened native session
   * @param path detached handshake path
   * @param parameters detached route parameters
   * @param principal detached identity reference
   * @param listener managed delivery owner
   * @return connection sharing the existing transport lifecycle
   */
  WebSocketConnection connection(
      Session session,
      String path,
      Map<String, String> parameters,
      @Nullable Principal principal,
      ManagedWebSocket listener) {
    return new WebSocketConnection(session, path, parameters, principal, listener);
  }

  /**
   * Validates admission before upgrading. Throw to reject through ordinary HTTP exception handling;
   * do not stage a body or change status. Header configuration is permitted.
   *
   * @param handler checked-exception-capable HTTP admission callback
   * @return this configuration
   */
  public WebSocket beforeUpgrade(Handler handler) {
    runUnderLock(
        registrationLock,
        () -> {
          requireMutable();
          beforeUpgrade = Objects.requireNonNull(handler);
        });
    return this;
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
   * Registers an opening callback, completed before admitting application messages.
   *
   * @param handler callback using the application's configured execution mode
   * @return this configuration
   */
  public WebSocket onOpen(WebSocket.ConnectionHandler handler) {
    runUnderLock(
        registrationLock,
        () -> {
          requireMutable();
          open = Objects.requireNonNull(handler);
        });
    return this;
  }

  /**
   * Reads the frozen opening callback.
   *
   * @return registered handler, or null
   */
  WebSocket.@Nullable ConnectionHandler open() {
    return open;
  }

  /**
   * Registers complete binary handling with an owned array safe to retain after callback return.
   *
   * @param handler sequential callback using the application's configured execution mode
   * @return this configuration
   */
  public WebSocket onBinary(WebSocket.MessageHandler<byte[]> handler) {
    runUnderLock(
        registrationLock,
        () -> {
          requireMutable();
          binary = Objects.requireNonNull(handler);
        });
    return this;
  }

  /**
   * Reads the frozen binary callback.
   *
   * @return registered handler, or null
   */
  WebSocket.@Nullable MessageHandler<byte[]> binary() {
    return binary;
  }

  /**
   * Registers a terminal notification after active application work has unwound. Native resource
   * release does not depend on this observer, which may not run during shutdown or rejected
   * dispatch.
   *
   * @param handler close code and reason observer serialized with the connection's other callbacks
   * @return this configuration
   */
  public WebSocket onClose(WebSocket.CloseHandler handler) {
    runUnderLock(
        registrationLock,
        () -> {
          requireMutable();
          close = Objects.requireNonNull(handler);
        });
    return this;
  }

  /**
   * Reads the frozen close observer.
   *
   * @return registered observer, or null
   */
  WebSocket.@Nullable CloseHandler close() {
    return close;
  }

  /**
   * Registers a terminal failure observer, not a recovery handler or HTTP exception renderer.
   * Recoverable message failures should be caught inside the message callback. Observer failures
   * are logged without recursion, and native cleanup never waits for this observer.
   *
   * @param handler first unexpected application or transport failure observer
   * @return this configuration
   */
  public WebSocket onError(WebSocket.MessageHandler<Throwable> handler) {
    runUnderLock(
        registrationLock,
        () -> {
          requireMutable();
          error = Objects.requireNonNull(handler);
        });
    return this;
  }

  /**
   * Reads the frozen error observer.
   *
   * @return registered observer, or null
   */
  WebSocket.@Nullable MessageHandler<Throwable> error() {
    return error;
  }

  /**
   * Registers complete text handling. Captured state is shared by this route's connections.
   *
   * @param handler callback whose return permits the next message
   * @return this configuration
   */
  public WebSocket onText(WebSocket.MessageHandler<String> handler) {
    runUnderLock(
        registrationLock,
        () -> {
          requireMutable();
          text = Objects.requireNonNull(handler);
        });
    return this;
  }

  /**
   * Reads the registered text callback after configuration has frozen.
   *
   * @return registered handler, or null
   */
  WebSocket.@Nullable MessageHandler<String> text() {
    return text;
  }

  /** Prevents changes after the route configuration callback returns. */
  void freeze() {
    runUnderLock(registrationLock, () -> frozen = true);
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
     * Handles a message using the configured execution mode, serialized with this connection's
     * other callbacks.
     *
     * <p>S112 is suppressed because application callbacks may propagate checked failures from their
     * own dependencies without wrapping them or declaring a framework-specific exception.
     *
     * @param connection opened connection
     * @param message owned application message
     * @throws Exception if application handling fails
     */
    @SuppressWarnings("java:S112")
    void handle(WebSocketConnection connection, T message) throws Exception;
  }

  /** Checked-exception-capable work on an opened connection. */
  @FunctionalInterface
  public interface ConnectionHandler {
    /**
     * Handles one opening or internal delivery operation.
     *
     * <p>S112 is suppressed because application callbacks may propagate checked failures from their
     * own dependencies without wrapping them or declaring a framework-specific exception.
     *
     * @param connection opened connection
     * @throws Exception if application handling fails
     */
    @SuppressWarnings("java:S112")
    void handle(WebSocketConnection connection) throws Exception;
  }

  /** Checked-exception-capable observation of native connection closure. */
  @FunctionalInterface
  public interface CloseHandler {
    /**
     * Observes termination without participating in native resource cleanup.
     *
     * <p>S112 is suppressed because application observers may propagate checked failures from their
     * own dependencies without wrapping them or declaring a framework-specific exception.
     *
     * @param connection terminated connection
     * @param code native close code
     * @param reason native close reason
     * @throws Exception if observation fails; the failure is logged, not recursively delivered
     */
    @SuppressWarnings("java:S112")
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
     */
    Session.Listener create(
        Request request, ServerUpgradeResponse response, WebSocketRuntime runtime);
  }

  /** Codec-backed managed callbacks; object and raw binary handlers share one callback slot. */
  public static final class Typed extends WebSocket {
    private final Codec codec;

    /**
     * Creates framework-owned configuration using the application's existing codec.
     *
     * @param codec shared application conversion
     */
    Typed(Codec codec) {
      this.codec = Objects.requireNonNull(codec);
    }

    /**
     * Registers admission with codec-enabled HTTP arguments before the upgrade.
     *
     * @param handler handshake admission, not a retained request or response
     * @return this configuration
     */
    public Typed beforeUpgrade(AdmissionHandler handler) {
      super.beforeUpgrade(handler);
      return this;
    }

    /** {@inheritDoc} */
    @Override
    public Typed beforeUpgrade(Handler handler) {
      super.beforeUpgrade(handler);
      return this;
    }

    /**
     * Registers opening work with the codec-enabled connection.
     *
     * <p>S2177 is suppressed because this overload accepts a callback for the typed connection; the
     * base callback signature is overridden separately.
     *
     * @param handler opening callback completed before message delivery
     * @return this configuration
     */
    @SuppressWarnings("java:S2177")
    public Typed onOpen(WebSocket.Typed.ConnectionHandler handler) {
      super.onOpen(handler);
      return this;
    }

    /** {@inheritDoc} */
    @Override
    public Typed onOpen(WebSocket.ConnectionHandler handler) {
      super.onOpen(handler);
      return this;
    }

    /**
     * Registers raw text handling with the codec-enabled connection.
     *
     * <p>S2177 is suppressed because this overload accepts a callback for the typed connection; the
     * base callback signature is overridden separately.
     *
     * @param handler complete text callback; input bypasses the codec
     * @return this configuration
     */
    @SuppressWarnings("java:S2177")
    public Typed onText(WebSocket.Typed.MessageHandler<String> handler) {
      super.onText(handler);
      return this;
    }

    /** {@inheritDoc} */
    @Override
    public Typed onText(WebSocket.MessageHandler<String> handler) {
      super.onText(handler);
      return this;
    }

    /**
     * Registers raw binary handling, replacing any object handler.
     *
     * <p>S2177 is suppressed because this overload accepts a callback for the typed connection; the
     * base callback signature is overridden separately.
     *
     * @param handler owned binary callback; input bypasses the codec
     * @return this configuration
     */
    @SuppressWarnings("java:S2177")
    public Typed onBinary(WebSocket.Typed.MessageHandler<byte[]> handler) {
      super.onBinary(handler);
      return this;
    }

    /** {@inheritDoc} */
    @Override
    public Typed onBinary(WebSocket.MessageHandler<byte[]> handler) {
      super.onBinary(handler);
      return this;
    }

    /**
     * Decodes each complete binary message before invoking its sequential callback. A later object
     * or binary registration replaces this handler. Uncaught conversion failures follow ordinary
     * terminal error handling; raw text handling is independent.
     *
     * @param type concrete decode target, not a parameterized type
     * @param handler object callback serialized with this connection's other callbacks
     * @param <T> decoded value type
     * @return this configuration
     */
    public <T> Typed onObject(Class<T> type, WebSocket.Typed.MessageHandler<T> handler) {
      Objects.requireNonNull(type);
      Objects.requireNonNull(handler);
      super.onBinary((connection, bytes) ->
          handler.handle(
              (WebSocketConnection.Typed) connection,
              Objects.requireNonNull(codec.read(bytes, type), "Codec returned null object")));
      return this;
    }

    /**
     * Registers a terminal observer with the same codec-enabled connection.
     *
     * <p>S2177 is suppressed because this overload accepts a callback for the typed connection; the
     * base callback signature is overridden separately.
     *
     * @param handler failure observer, not a recovery callback
     * @return this configuration
     */
    @SuppressWarnings("java:S2177")
    public Typed onError(WebSocket.Typed.MessageHandler<Throwable> handler) {
      super.onError(handler);
      return this;
    }

    /** {@inheritDoc} */
    @Override
    public Typed onError(WebSocket.MessageHandler<Throwable> handler) {
      super.onError(handler);
      return this;
    }

    /**
     * Registers a close observer with the same codec-enabled connection.
     *
     * <p>S2177 is suppressed because this overload accepts a callback for the typed connection; the
     * base callback signature is overridden separately.
     *
     * @param handler terminal close observer
     * @return this configuration
     */
    @SuppressWarnings("java:S2177")
    public Typed onClose(WebSocket.Typed.CloseHandler handler) {
      super.onClose(handler);
      return this;
    }

    /** {@inheritDoc} */
    @Override
    public Typed onClose(WebSocket.CloseHandler handler) {
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
      return new WebSocketConnection.Typed(session, path, parameters, principal, listener, codec);
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
      void handle(WebSocketConnection.Typed connection, T message) throws Exception;

      /** {@inheritDoc} */
      @Override
      default void handle(WebSocketConnection connection, T message) throws Exception {
        handle((WebSocketConnection.Typed) connection, message);
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
      void handle(Request.Typed request, Response.Typed response) throws Exception;

      /** {@inheritDoc} */
      @Override
      default void handle(Request request, Response response) throws Exception {
        handle((Request.Typed) request, (Response.Typed) response);
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
      void handle(WebSocketConnection.Typed connection) throws Exception;

      /** {@inheritDoc} */
      @Override
      default void handle(WebSocketConnection connection) throws Exception {
        handle((WebSocketConnection.Typed) connection);
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
      void handle(WebSocketConnection.Typed connection, int code, String reason) throws Exception;

      /** {@inheritDoc} */
      @Override
      default void handle(WebSocketConnection connection, int code, String reason)
          throws Exception {
        handle((WebSocketConnection.Typed) connection, code, reason);
      }
    }
  }
}
