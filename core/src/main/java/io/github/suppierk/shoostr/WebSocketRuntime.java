package io.github.suppierk.shoostr;

import static io.github.suppierk.shoostr.Locks.getUnderLock;
import static io.github.suppierk.shoostr.Locks.runUnderLock;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.ReentrantLock;

/** Owns managed connections without taking ownership of the shared application executor. */
final class WebSocketRuntime {
  private final Executor executor;
  private final Set<ManagedWebSocket> connections;
  private final ReentrantLock lifecycleLock;
  private boolean stopping;
  private boolean stopped;

  /**
   * Retains the application's already-owned execution policy.
   *
   * @param executor managed dispatch
   */
  WebSocketRuntime(Executor executor) {
    this.executor = Objects.requireNonNull(executor);
    this.connections = new HashSet<>();
    this.lifecycleLock = new ReentrantLock();
  }

  /**
   * Atomically admits an opened connection or rejects it during shutdown.
   *
   * @param connection opened adapter
   * @return whether dispatch was accepted
   */
  boolean register(ManagedWebSocket connection) {
    return Boolean.TRUE.equals(
        getUnderLock(
            lifecycleLock,
            () -> {
              if (stopping) {
                return false;
              }

              connections.add(connection);

              return true;
            }));
  }

  /**
   * Releases registry ownership after transport closure or rejected dispatch.
   *
   * @param connection terminated adapter
   */
  void remove(ManagedWebSocket connection) {
    runUnderLock(lifecycleLock, () -> connections.remove(connection));
  }

  /**
   * Submits one work item without taking ownership of the application's executor.
   *
   * @param task admitted application work
   * @throws RejectedExecutionException if managed dispatch has finished or the executor rejects
   *     work
   */
  void execute(Runnable task) {
    runUnderLock(
        lifecycleLock,
        () -> {
          if (stopped) {
            throw new RejectedExecutionException("Managed WebSocket dispatch is closed");
          }
        });

    executor.execute(task);
  }

  /**
   * Rejects new connections and signals managed work before Jetty's native drain.
   *
   * @throws IOException if signaling fails; every registered connection is still attempted
   */
  void stop() throws IOException {
    signal(false);
  }

  /**
   * Closes dispatch and cancels unclaimed work after native stop, including failed native stop.
   *
   * @throws IOException if cleanup fails; every registered connection is still attempted
   */
  void finishStop() throws IOException {
    signal(true);
  }

  /**
   * Snapshots ownership under the runtime lock, then signals connections without holding it.
   *
   * @param finished whether native stop has ended and dispatch must close
   * @throws IOException if any connection fails to settle
   */
  private void signal(boolean finished) throws IOException {
    final var active = prepareSignal(finished);
    IOException failure = null;
    for (final var connection : active) {
      try {
        if (finished) {
          connection.finishStop();
        } else {
          connection.stop();
        }
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

  /**
   * Updates shutdown state and snapshots connections while holding the runtime lock.
   *
   * @param finished whether native stop has ended and dispatch must close
   * @return registered connections, or an empty list if dispatch has already closed
   */
  private List<ManagedWebSocket> prepareSignal(boolean finished) {
    return Objects.requireNonNull(
        getUnderLock(
            lifecycleLock,
            () -> {
              if (stopped) {
                return List.of();
              }

              stopping = true;
              stopped = finished;

              return List.copyOf(connections);
            }));
  }
}
