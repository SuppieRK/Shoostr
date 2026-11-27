package io.github.suppierk.shoostr.testing;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * A real WebSocket connection for functional tests. Receives assemble complete messages and read in
 * wire order. A receive of the wrong kind fails and leaves the pending event available. Consumption
 * requests the next message. Sends wait up to three seconds for transport completion.
 */
public final class TestWebSocket implements Closeable {
  private static final long WRITE_TIMEOUT_SECONDS = 3;

  private final Consumer<TestWebSocket> unregister;
  private final ReentrantLock lock;
  private final Condition changed;
  private final CompletableFuture<WebSocket> closedWhileOpening;
  private @Nullable WebSocket socket;
  private @Nullable Object message;
  private @Nullable CloseFrame peerClose;
  private @Nullable Throwable receiveFailure;
  private boolean closed;

  /**
   * Creates the owner for a pending upgrade.
   *
   * @param unregister releases the fixture's reference after local cleanup
   */
  TestWebSocket(Consumer<TestWebSocket> unregister) {
    this.unregister = unregister;
    this.lock = new ReentrantLock();
    this.changed = lock.newCondition();
    this.closedWhileOpening = new CompletableFuture<>();
  }

  /**
   * Starts an upgrade while the fixture still owns admission.
   *
   * @param builder configured JDK handshake builder
   * @param uri absolute WebSocket target
   * @return pending JDK handshake
   */
  CompletableFuture<WebSocket> start(WebSocket.Builder builder, URI uri) {
    return builder.buildAsync(uri, new Listener());
  }

  /**
   * Waits outside the fixture lock so shutdown can end the wait and abort a late socket.
   *
   * @param handshake pending JDK handshake
   * @throws IOException if the handshake fails or the fixture closes
   * @throws InterruptedException if the caller is interrupted while waiting
   */
  void awaitOpen(CompletableFuture<WebSocket> handshake) throws IOException, InterruptedException {
    try {
      // Cancelling the JDK's dependent future can hide an upgraded transport. End our wait
      // independently and let the listener abort any connection that arrives after close.
      final var connected =
          (WebSocket) CompletableFuture.anyOf(handshake, closedWhileOpening).get();
      if (!registerSocket(connected)) {
        throw new IOException("Test WebSocket closed during the handshake");
      }
    } catch (ExecutionException failure) {
      throw operationFailure("WebSocket handshake failed", failure);
    }
  }

  /**
   * Registers a socket or aborts a late arrival after local closure.
   *
   * @param connected established JDK socket
   * @return whether the fixture still owns the connection
   */
  private boolean registerSocket(WebSocket connected) {
    lock.lock();

    try {
      if (!closed) {
        socket = connected;
        return true;
      }
    } finally {
      lock.unlock();
    }

    connected.abort();
    return false;
  }

  /**
   * Sends one complete text message.
   *
   * @param text message to send
   * @throws IOException if the transport fails, the write times out or the socket is closed
   * @throws InterruptedException if the caller is interrupted while waiting for the write
   */
  public void sendText(String text) throws IOException, InterruptedException {
    sendText(text, true);
  }

  /**
   * Sends text, with {@code last} true for the final fragment of a message. A timed-out or
   * interrupted write closes the test socket.
   *
   * @param text fragment to send
   * @param last whether the fragment completes the message
   * @throws IOException if the transport fails, the write times out or the socket is closed
   * @throws InterruptedException if the caller is interrupted while waiting for the write
   */
  public void sendText(String text, boolean last) throws IOException, InterruptedException {
    awaitWrite(activeSocket().sendText(text, last));
  }

  /**
   * Sends one complete binary message.
   *
   * @param bytes message bytes
   * @throws IOException if the transport fails, the write times out or the socket is closed
   * @throws InterruptedException if the caller is interrupted while waiting for the write
   */
  public void sendBinary(byte[] bytes) throws IOException, InterruptedException {
    sendBinary(bytes, true);
  }

  /**
   * Sends a copy of the supplied bytes, with {@code last} true for the final fragment of a message.
   * A timed-out or interrupted write closes the test socket.
   *
   * @param bytes fragment bytes
   * @param last whether the fragment completes the message
   * @throws IOException if the transport fails, the write times out or the socket is closed
   * @throws InterruptedException if the caller is interrupted while waiting for the write
   */
  public void sendBinary(byte[] bytes, boolean last) throws IOException, InterruptedException {
    awaitWrite(activeSocket().sendBinary(ByteBuffer.wrap(bytes.clone()), last));
  }

  /**
   * Sends a protocol close frame. Use {@link #receiveClose} to check the peer's reply.
   *
   * @param code WebSocket close status code
   * @param reason close reason
   * @throws IOException if the transport fails, the write times out or the socket is closed
   * @throws InterruptedException if the caller is interrupted while waiting for the write
   */
  public void sendClose(int code, String reason) throws IOException, InterruptedException {
    awaitWrite(activeSocket().sendClose(code, reason));
  }

  /**
   * Takes a snapshot of the current connection before starting a write.
   *
   * @return JDK socket owned by this test connection
   * @throws IOException if the test socket is closed
   */
  private WebSocket activeSocket() throws IOException {
    lock.lock();

    try {
      return requireSocket();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Reads the connected socket while holding the connection lock.
   *
   * @return JDK socket owned by this test connection
   * @throws IOException if the test socket is closed
   */
  private WebSocket requireSocket() throws IOException {
    if (socket == null) {
      throw new IOException("Test WebSocket is closed");
    }

    return socket;
  }

  /**
   * Waits up to three seconds for a write. Aborts the socket if a partial write may remain.
   *
   * @param write pending JDK write
   * @throws IOException if the write fails or times out
   * @throws InterruptedException if the caller is interrupted while waiting
   */
  private void awaitWrite(CompletableFuture<WebSocket> write)
      throws IOException, InterruptedException {
    try {
      write.get(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (ExecutionException failure) {
      throw operationFailure("WebSocket send failed", failure);
    } catch (TimeoutException timeout) {
      final var failure = new SocketTimeoutException("Timed out sending a WebSocket message");
      failure.initCause(timeout);

      try (var connection = this) {
        throw failure;
      }
    } catch (InterruptedException interrupted) {
      try (var connection = this) {
        throw interrupted;
      }
    }
  }

  /**
   * Preserves the original I/O error and unchecked failures from the JDK client.
   *
   * @param message context for other failure causes
   * @param failure failed client operation
   * @return I/O failure, preserving a handshake exception's response
   */
  private static IOException operationFailure(String message, ExecutionException failure) {
    final var cause = failure.getCause();
    if (cause instanceof IOException io) {
      return io;
    }

    if (cause instanceof RuntimeException unchecked) {
      throw unchecked;
    }

    if (cause instanceof Error fatal) {
      throw fatal;
    }

    return new IOException(message, cause);
  }

  /**
   * Reads the next complete text message.
   *
   * @param timeout positive wait duration
   * @return assembled text
   * @throws IOException if the wait times out, fails or encounters a different event
   * @throws InterruptedException if the caller is interrupted while waiting
   */
  public String receiveText(Duration timeout) throws IOException, InterruptedException {
    return receive(String.class, "text", timeout);
  }

  /**
   * Reads the next complete binary message.
   *
   * @param timeout positive wait duration
   * @return assembled bytes owned by the caller
   * @throws IOException if the wait times out, fails or encounters a different event
   * @throws InterruptedException if the caller is interrupted while waiting
   */
  public byte[] receiveBinary(Duration timeout) throws IOException, InterruptedException {
    return receive(byte[].class, "binary", timeout);
  }

  /**
   * Reads the peer's close result after the caller consumes any earlier messages.
   *
   * @param timeout positive wait duration
   * @return peer close code and reason
   * @throws IOException if the wait times out, fails or encounters a different event
   * @throws InterruptedException if the caller is interrupted while waiting
   */
  public CloseFrame receiveClose(Duration timeout) throws IOException, InterruptedException {
    return receive(CloseFrame.class, "close", timeout);
  }

  /**
   * Waits for an event, leaving other message kinds available to the caller.
   *
   * @param type expected event type
   * @param kind expected event name for diagnostics
   * @param timeout positive wait duration
   * @param <T> expected event value type
   * @return matching event
   * @throws IOException if the socket closes, fails or encounters a different event
   * @throws SocketTimeoutException if the wait expires
   * @throws InterruptedException if the caller is interrupted while waiting
   * @throws IllegalArgumentException if the timeout is zero or negative
   */
  private <T> T receive(Class<T> type, String kind, Duration timeout)
      throws IOException, InterruptedException {
    var remaining = TimeUnit.NANOSECONDS.convert(Objects.requireNonNull(timeout));
    if (remaining <= 0) {
      throw new IllegalArgumentException("Receive timeout must be positive");
    }

    final WebSocket connected;
    final T result;
    lock.lockInterruptibly();

    try {
      while (!closed && message == null && peerClose == null && receiveFailure == null) {
        if (remaining <= 0) {
          throw new SocketTimeoutException("Timed out waiting for a WebSocket " + kind + " event");
        }

        remaining = changed.awaitNanos(remaining);
      }

      connected = requireSocket();
      if (message != null) {
        result = expect(type, kind, message);
        message = null;
      } else if (peerClose != null) {
        return expect(type, kind, peerClose);
      } else {
        throw new IOException("WebSocket receive failed", receiveFailure);
      }
    } finally {
      lock.unlock();
    }

    connected.request(1);
    return result;
  }

  /**
   * Checks the event type before removing a pending message.
   *
   * @param type expected event type
   * @param kind expected event name
   * @param event event at the head of the receive sequence
   * @param <T> expected event value type
   * @return the matching event
   * @throws IOException if the event has a different type
   */
  private static <T> T expect(Class<T> type, String kind, Object event) throws IOException {
    if (!type.isInstance(event)) {
      throw new IOException(
          "Expected WebSocket " + kind + " event but received " + eventKind(event));
    }

    return type.cast(event);
  }

  /**
   * Labels a received event kind without exposing message contents.
   *
   * @param event assembled message or peer close
   * @return event kind for diagnostics
   */
  private static String eventKind(Object event) {
    return switch (event) {
      case String ignored -> "text";
      case byte[] ignored -> "binary";
      default -> "close";
    };
  }

  /**
   * Ends a pending handshake wait and aborts the connection locally without waiting for the peer.
   */
  @Override
  public void close() {
    final @Nullable WebSocket connected;
    lock.lock();

    try {
      if (closed) {
        return;
      }

      closed = true;
      connected = socket;
      socket = null;
      message = null;
      changed.signalAll();
    } finally {
      lock.unlock();
    }

    try {
      closedWhileOpening.completeExceptionally(
          new IOException("Test WebSocket closed during the handshake"));
      if (connected != null) {
        connected.abort();
      }
    } finally {
      unregister.accept(this);
    }
  }

  /**
   * The WebSocket close outcome reported by the JDK client.
   *
   * @param code WebSocket close status code
   * @param reason close reason
   */
  public record CloseFrame(int code, String reason) {
    /**
     * Keeps the client's reported status code and nonnull reason.
     *
     * @param code WebSocket close status code
     * @param reason close reason
     */
    public CloseFrame {
      Objects.requireNonNull(reason);
    }
  }

  /** Reassembles one message at a time. Consumption admits the next complete message. */
  private final class Listener implements WebSocket.Listener {
    private final StringBuilder text;
    private final ByteArrayOutputStream binary;

    /** Starts with empty fragment buffers. */
    private Listener() {
      this.text = new StringBuilder();
      this.binary = new ByteArrayOutputStream();
    }

    /** {@inheritDoc} */
    @Override
    public void onOpen(WebSocket connection) {
      if (registerSocket(connection)) {
        connection.request(1);
      }
    }

    /** {@inheritDoc} */
    @Override
    public CompletionStage<?> onText(WebSocket connection, CharSequence data, boolean last) {
      text.append(data);
      if (last) {
        acceptMessage(text.toString());
        text.setLength(0);
      } else {
        connection.request(1);
      }

      return CompletableFuture.completedFuture(null);
    }

    /** {@inheritDoc} */
    @Override
    public CompletionStage<?> onBinary(WebSocket connection, ByteBuffer data, boolean last) {
      final var fragment = new byte[data.remaining()];
      data.get(fragment);
      binary.writeBytes(fragment);
      if (last) {
        acceptMessage(binary.toByteArray());
        binary.reset();
      } else {
        connection.request(1);
      }

      return CompletableFuture.completedFuture(null);
    }

    /** {@inheritDoc} */
    @Override
    public CompletionStage<?> onClose(WebSocket connection, int code, String reason) {
      acceptTerminal(new CloseFrame(code, reason), null);
      return CompletableFuture.completedFuture(null);
    }

    /** {@inheritDoc} */
    @Override
    public void onError(WebSocket connection, Throwable failure) {
      acceptTerminal(null, failure);
    }

    /**
     * Makes a complete listener message available to readers.
     *
     * @param value assembled text or owned binary bytes
     */
    private void acceptMessage(Object value) {
      lock.lock();

      try {
        if (!closed) {
          message = value;
          changed.signalAll();
        }
      } finally {
        lock.unlock();
      }
    }

    /**
     * Records the terminal event after any pending message.
     *
     * @param frame peer close result, or null on transport failure
     * @param failure transport failure, or null on peer close
     */
    private void acceptTerminal(@Nullable CloseFrame frame, @Nullable Throwable failure) {
      lock.lock();

      try {
        if (!closed) {
          peerClose = frame;
          receiveFailure = failure;
          changed.signalAll();
        }
      } finally {
        lock.unlock();
      }
    }
  }
}
