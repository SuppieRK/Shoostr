package io.github.suppierk.shoostr;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.jspecify.annotations.Nullable;

/** A connection with serialized, interruptible, transport-completing writes. */
public sealed class WebSocketConnection implements AutoCloseable permits TypedWebSocketConnection {
  private final Session session;
  private final String path;
  private final Map<String, String> parameters;
  private final @Nullable Principal principal;
  private final ManagedWebSocket listener;
  private final Object lifecycle;
  private final ReentrantLock writes;
  private @Nullable CompletableFuture<@Nullable Void> pending;
  private boolean terminal;

  /**
   * Owns the opened native session without exposing borrowed native buffers.
   *
   * @param session opened transport
   * @param path detached canonical encoded path
   * @param parameters detached decoded path parameters
   * @param principal initial authentication reference, or null
   * @param listener native delivery owner
   */
  WebSocketConnection(
      Session session,
      String path,
      Map<String, String> parameters,
      @Nullable Principal principal,
      ManagedWebSocket listener) {
    this.session = Objects.requireNonNull(session);
    this.path = Objects.requireNonNull(path);
    this.parameters = Map.copyOf(parameters);
    this.principal = principal;
    this.listener = Objects.requireNonNull(listener);
    lifecycle = new Object();
    writes = new ReentrantLock();
  }

  /**
   * Returns the canonical encoded handshake path without retaining the HTTP request.
   *
   * @return connection path
   */
  public String path() {
    return path;
  }

  /**
   * Reads a decoded parameter captured after HTTP admission and before request finalization.
   *
   * @param name parameter name
   * @return decoded value, or empty when absent
   */
  public Optional<String> pathParam(String name) {
    return Optional.ofNullable(parameters.get(Objects.requireNonNull(name)));
  }

  /**
   * Returns the initial authentication identity. Retaining the reference does not make the
   * application's Principal immutable, thread-safe or automatically revalidated.
   *
   * @return handshake identity, or empty for an unauthenticated connection
   */
  public Optional<Principal> principal() {
    return Optional.ofNullable(principal);
  }

  /**
   * Takes an open-state snapshot, not a guarantee that a subsequent send will succeed.
   *
   * @return whether application writes remain open
   */
  public boolean isOpen() {
    synchronized (lifecycle) {
      return !terminal && session.isOpen();
    }
  }

  /** Initiates normal 1000 closure without waiting for the peer's acknowledgment. */
  @Override
  public void close() {
    close(CloseStatus.NORMAL, "");
  }

  /**
   * Initiates closure and rejects subsequent writes. Repeated valid closure is harmless. Completion
   * of the native close frame does not mean the peer has acknowledged it.
   *
   * @param code transmittable WebSocket close code
   * @param reason valid UTF-8 reason of at most 123 encoded bytes
   * @throws IllegalArgumentException if the code or reason is not transmittable
   */
  public void close(int code, String reason) {
    Objects.requireNonNull(reason);
    if (!CloseStatus.isTransmittableStatusCode(code)
        || reason.length() > 123
        || !StandardCharsets.UTF_8.newEncoder().canEncode(reason)
        || reason.getBytes(StandardCharsets.UTF_8).length > 123) {
      throw new IllegalArgumentException("Invalid WebSocket close code or reason");
    }

    synchronized (lifecycle) {
      if (terminal) {
        return;
      }

      terminal = true;
      if (pending != null) {
        pending.completeExceptionally(new IOException("WebSocket is closing"));
      }
    }
    listener.closing();

    try {
      session.close(
          code,
          reason,
          Callback.from(
              () -> {},
              failure -> {
                listener.onWebSocketError(failure);
                session.disconnect();
              }));
    } catch (RuntimeException failure) {
      listener.onWebSocketError(failure);
      session.disconnect();
    }

    listener.demandClosing();
  }

  /**
   * Sends a complete text message, waiting for native write completion, not peer receipt.
   * Concurrent producers are ordered by write-lock admission. Interruption after submission stops
   * further writes because the message may already have been transmitted.
   *
   * @param text complete text message
   * @throws IOException if transport rejects or fails the write
   * @throws InterruptedException if interrupted while waiting; delivery cannot be rolled back
   */
  public void send(String text) throws IOException, InterruptedException {
    Objects.requireNonNull(text);
    write(callback -> session.sendText(text, callback));
  }

  /**
   * Sends owned binary output, copying the caller's bytes before asynchronous transport ownership.
   * Like text sends, this waits for native completion, not peer receipt.
   *
   * @param bytes complete binary message
   * @throws IOException if transport rejects or fails the write
   * @throws InterruptedException if interrupted; an already submitted message cannot be retracted
   */
  public void send(byte[] bytes) throws IOException, InterruptedException {
    Objects.requireNonNull(bytes);
    write(callback -> session.sendBinary(ByteBuffer.wrap(bytes.clone()), callback));
  }

  /**
   * Rejects an already closed connection before interruptible admission, then encodes within
   * existing write admission without blocking independent closure.
   *
   * @param codec configured finite conversion
   * @param value nonnull application value
   * @throws IOException if the connection has closed
   * @throws Exception if encoding or transport completion fails
   */
  @SuppressWarnings("java:S112")
  final void sendEncoded(Codec codec, Object value) throws Exception {
    Objects.requireNonNull(value);
    requireOpen();
    writes.lockInterruptibly();

    try {
      requireOpen();
      // The raw send rechecks closure after conversion and owns the copied binary bytes.
      send(Objects.requireNonNull(codec.write(value), "Codec returned null bytes"));
    } finally {
      writes.unlock();
    }
  }

  /**
   * Serializes write submission through completion without blocking lifecycle bookkeeping.
   *
   * <p>ReferenceEquality is suppressed because write ownership compares futures by identity, not
   * their results.
   *
   * @param submit native complete-message write
   * @throws IOException if transport rejects or fails the write
   * @throws InterruptedException if this producer is interrupted
   */
  @SuppressWarnings("ReferenceEquality")
  private void write(Consumer<Callback> submit) throws IOException, InterruptedException {
    writes.lockInterruptibly();
    var completion = new CompletableFuture<@Nullable Void>();

    try {
      synchronized (lifecycle) {
        if (terminal) {
          throw new IOException("WebSocket is closed");
        }

        pending = completion;
      }

      try {
        submit.accept(
            Callback.from(() -> completion.complete(null), completion::completeExceptionally));
      } catch (RuntimeException failure) {
        completion.completeExceptionally(failure);
      }

      try {
        completion.get();
      } catch (ExecutionException failure) {
        throw new IOException("WebSocket send failed", failure.getCause());
      } catch (InterruptedException failure) {
        close(CloseStatus.SHUTDOWN, "Send interrupted");
        throw failure;
      }
    } finally {
      synchronized (lifecycle) {
        if (pending == completion) {
          pending = null;
        }
      }
      writes.unlock();
    }
  }

  /**
   * Checks connection state without waiting for write admission or clearing interruption.
   *
   * @throws IOException if this connection has closed
   */
  private void requireOpen() throws IOException {
    synchronized (lifecycle) {
      if (terminal || !session.isOpen()) {
        throw new IOException("WebSocket is closed");
      }
    }
  }

  /**
   * Rejects future sends and settles an outstanding write independently of its write lock.
   *
   * @param failure observed transport termination
   */
  void terminate(Throwable failure) {
    synchronized (lifecycle) {
      terminal = true;
      if (pending != null) {
        pending.completeExceptionally(failure);
      }
    }
  }
}
