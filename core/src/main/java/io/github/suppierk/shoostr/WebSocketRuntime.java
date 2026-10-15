package io.github.suppierk.shoostr;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/** Owns managed connections without taking ownership of the shared application executor. */
final class WebSocketRuntime {
  private final ExecutorService executor;
  private final Set<ManagedWebSocket> connections;
  private boolean stopping;

  /**
   * Retains the application's already-owned virtual-thread executor.
   *
   * @param executor managed virtual-thread dispatch
   */
  WebSocketRuntime(ExecutorService executor) {
    this.executor = Objects.requireNonNull(executor);
    connections = new HashSet<>();
  }

  /**
   * Atomically admits an opened connection or rejects it during shutdown.
   *
   * @param connection opened adapter
   * @return whether dispatch was accepted
   */
  synchronized boolean register(ManagedWebSocket connection) {
    if (stopping) {
      return false;
    }

    connections.add(connection);
    return true;
  }

  /**
   * Starts one worker that serializes this connection's application callbacks.
   *
   * @param connection admitted adapter
   */
  void execute(ManagedWebSocket connection) {
    executor.execute(connection::run);
  }

  /**
   * Releases registry ownership after transport closure or rejected dispatch.
   *
   * @param connection terminated adapter
   */
  synchronized void remove(ManagedWebSocket connection) {
    connections.remove(connection);
  }

  /**
   * Rejects new connections and signals managed work before Jetty's native drain.
   *
   * @throws IOException if signalling fails; every registered connection is still attempted
   */
  void stop() throws IOException {
    List<ManagedWebSocket> active;
    synchronized (this) {
      stopping = true;
      active = List.copyOf(connections);
    }
    IOException failure = null;
    for (var connection : active) {
      try {
        connection.stop();
      } catch (RuntimeException stopFailure) {
        if (failure == null) {
          failure = new IOException("Could not signal managed WebSockets", stopFailure);
        } else {
          failure.addSuppressed(stopFailure);
        }
      }
    }
    if (failure != null) {
      throw failure;
    }
  }
}
