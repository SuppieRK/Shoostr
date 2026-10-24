package io.github.suppierk.shoostr.example.websocket;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import io.github.suppierk.shoostr.http.exceptions.ForbiddenException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;

/** Native listeners with offered-subprotocol selection and bounded complete messages. */
public final class NativeWebSocketExample {
  /** Prevents utility-class construction. */
  private NativeWebSocketExample() {}

  /**
   * Creates an endpoint whose native listeners own their demand and outgoing binary buffers.
   *
   * @return unstarted, caller-owned application
   */
  public static Shoostr application() {
    return new Shoostr()
        .http2()
        .routes(
            routes ->
                routes.websocket(
                    "/native/{room}",
                    (request, upgrade) -> {
                      // HTTP CORS does not protect this handshake. Admit browser origins before
                      // creating a listener or accepting an offered subprotocol.
                      if (!request
                          .headers(HttpHeaders.ORIGIN.value())
                          .equals(List.of("https://app.example"))) {
                        throw new ForbiddenException();
                      }

                      // Accept only an offered protocol and retain a detached prefix, not the
                      // request.
                      if (!request.webSocketProtocols().contains("chat.v2")) {
                        throw new BadRequestException();
                      }

                      upgrade.setAcceptedSubProtocol("chat.v2");
                      return new RoomListener(request.pathParam("room").orElseThrow() + ":");
                    }));
  }

  /** Per-connection listener that admits the next event after its outgoing write completes. */
  public static final class RoomListener implements Session.Listener {
    private final String prefix;
    private Optional<Session> session;

    /**
     * Captures a detached prefix without retaining the handshake request.
     *
     * @param prefix non-null text prepended to each text reply
     */
    public RoomListener(String prefix) {
      this.prefix = Objects.requireNonNull(prefix);
      this.session = Optional.empty();
    }

    /**
     * Sets session limits and admits the first incoming event.
     *
     * @param opened native connection
     */
    @Override
    public void onWebSocketOpen(Session opened) {
      session = Optional.of(opened);
      opened.setMaxTextMessageSize(64);
      opened.setMaxBinaryMessageSize(64);
      opened.setMaxOutgoingFrames(2);
      // Native listeners must explicitly admit the first and every subsequent event.
      opened.demand();
    }

    /**
     * Sends a prefixed reply without blocking the native callback thread.
     *
     * @param message complete incoming text
     */
    @Override
    public void onWebSocketText(String message) {
      var opened = session.orElseThrow();
      // Admit more input only after this send completes; a failed write closes the connection.
      opened.sendText(
          prefix + message, Callback.from(opened::demand, ignored -> opened.disconnect()));
    }

    /**
     * Copies input before releasing the borrowed buffer, then echoes the owned bytes.
     *
     * @param data borrowed incoming binary message
     * @param receive completion that releases the incoming buffer
     */
    @Override
    public void onWebSocketBinary(ByteBuffer data, Callback receive) {
      var opened = session.orElseThrow();
      // Copy borrowed input before acknowledging it; the outgoing send can outlive this callback.
      var bytes = new byte[data.remaining()];
      data.get(bytes);
      receive.succeed();
      opened.sendBinary(
          ByteBuffer.wrap(bytes), Callback.from(opened::demand, ignored2 -> opened.disconnect()));
    }
  }
}
