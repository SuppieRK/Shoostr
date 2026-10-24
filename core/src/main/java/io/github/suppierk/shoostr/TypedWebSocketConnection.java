package io.github.suppierk.shoostr;

import java.security.Principal;
import java.util.Map;
import java.util.Objects;
import org.eclipse.jetty.websocket.api.Session;
import org.jspecify.annotations.Nullable;

/** Codec-enabled connection; inherited text and byte sends bypass conversion. */
public final class TypedWebSocketConnection extends WebSocketConnection {
  private final Codec codec;

  /**
   * Initializes one connection over the ordinary transport lifecycle.
   *
   * @param session opened native session
   * @param path detached handshake path
   * @param parameters detached route parameters
   * @param principal detached identity reference
   * @param listener managed delivery owner
   * @param codec shared application conversion
   */
  TypedWebSocketConnection(
      Session session,
      String path,
      Map<String, String> parameters,
      @Nullable Principal principal,
      ManagedWebSocket listener,
      Codec codec) {
    super(session, path, parameters, principal, listener);
    this.codec = Objects.requireNonNull(codec);
  }

  /**
   * Encodes one nonnull object and sends its bytes as a binary message. Waits for native write
   * completion, not peer receipt. Conversion shares ordinary write admission; a closed connection
   * rejects the call before invoking the codec.
   *
   * @param value application representation, including strings or arrays to encode
   * @throws Exception if conversion or transport completion fails
   */
  @SuppressWarnings("java:S112")
  public void sendObject(Object value) throws Exception {
    sendEncoded(codec, value);
  }
}
