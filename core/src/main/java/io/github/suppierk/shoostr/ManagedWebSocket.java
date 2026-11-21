package io.github.suppierk.shoostr;

import static io.github.suppierk.shoostr.Locks.getUnderLock;
import static io.github.suppierk.shoostr.Locks.runUnderLock;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Engine-facing listener created by route registration. Public visibility permits Jetty's method
 * handles to invoke native callbacks; applications configure {@link WebSocket} instead.
 */
public final class ManagedWebSocket implements Session.Listener {
  private static final Logger LOGGER = LoggerFactory.getLogger(ManagedWebSocket.class);

  private final WebSocket configuration;
  private final WebSocketRuntime runtime;
  private final ReentrantLock events;
  private final String path;
  private final Map<String, String> parameters;
  private final @Nullable Principal principal;

  private @Nullable Session session;
  private @Nullable WebSocketConnection connection;
  private @Nullable DispatchTask task;
  private @Nullable Thread worker;
  private @Nullable Object message;
  private DemandState demandState;
  private DeliveryState deliveryState;
  private TerminalNotification terminalNotification;
  private boolean dispatchClosed;
  private boolean stopping;
  private CloseProgress closeProgress;
  private int closeCode;
  private String closeReason;
  private @Nullable Throwable error;

  /**
   * Captures route configuration and available runtime before native publication.
   *
   * @param configuration frozen route callbacks
   * @param request live handshake input whose detached values are captured immediately
   * @param runtime managed dispatch
   */
  ManagedWebSocket(WebSocket configuration, Request request, WebSocketRuntime runtime) {
    this.configuration = Objects.requireNonNull(configuration);
    this.runtime = Objects.requireNonNull(runtime);
    this.path = Objects.requireNonNull(request).path();
    this.parameters = request.pathParamMap();
    this.principal = request.principal().orElse(null);
    this.events = new ReentrantLock();
    this.demandState = DemandState.IDLE;
    this.deliveryState = DeliveryState.READY;
    this.terminalNotification = TerminalNotification.PENDING;
    this.closeProgress = CloseProgress.NONE;
    this.closeReason = "";
  }

  /**
   * Creates the public connection and starts managed delivery.
   *
   * @param opened native session
   */
  @Override
  public void onWebSocketOpen(Session opened) {
    if (!register(opened)) {
      stop();
      return;
    }

    schedule();
  }

  /**
   * Initializes opening state and records runtime admission under one event lock.
   *
   * @param opened native session
   * @return whether runtime admission succeeded
   */
  private boolean register(Session opened) {
    return Objects.requireNonNull(
        Locks.getUnderLock(
            events,
            () -> {
              session = opened;
              connection = configuration.connection(opened, path, parameters, principal, this);
              if (deliveryState != DeliveryState.CLOSED) {
                deliveryState = DeliveryState.OPEN_PENDING;
              }

              final var registered = runtime.register(this);
              if (!registered && terminalNotification == TerminalNotification.PENDING) {
                terminalNotification = TerminalNotification.SUPPRESSED;
              }

              return registered;
            }));
  }

  /**
   * Publishes one complete text message; further delivery awaits managed completion.
   *
   * @param text complete message
   */
  @Override
  public void onWebSocketText(String text) {
    if (acceptMessage(text)) {
      schedule();
    } else {
      demand();
    }
  }

  /**
   * Copies complete binary input before releasing its native receive buffer.
   *
   * @param data borrowed native message
   * @param callback receive completion
   */
  @Override
  public void onWebSocketBinary(ByteBuffer data, Callback callback) {
    byte[] bytes;
    var copied = false;

    try {
      bytes = new byte[data.remaining()];
      data.get(bytes);
      copied = true;
    } finally {
      if (copied) {
        callback.succeed();
      } else {
        callback.fail(new IOException("Could not copy WebSocket binary input"));
      }
    }

    if (acceptMessage(bytes)) {
      schedule();
    } else {
      demand();
    }
  }

  /**
   * Consumes native demand and publishes a complete message only while application delivery is
   * open.
   *
   * @param received complete text or owned binary data
   * @return whether the message was admitted
   */
  private boolean acceptMessage(Object received) {
    return Objects.requireNonNull(
        Locks.getUnderLock(
            events,
            () -> {
              demandState = DemandState.IDLE;
              if (deliveryState == DeliveryState.CLOSED) {
                return false;
              }

              message = received;
              return true;
            }));
  }

  /**
   * Settles managed work independently of application cleanup.
   *
   * @param failure native transport failure
   */
  @Override
  public void onWebSocketError(Throwable failure) {
    runUnderLock(
        events,
        () -> {
          if (closeProgress == CloseProgress.NONE && !stopping && error == null) {
            error = failure;
          }
        });

    terminate(failure);
    schedule();
  }

  /**
   * Acknowledges native closure before admitting managed terminal observers.
   *
   * @param code native close code
   * @param reason native close reason
   * @param callback native close completion
   */
  @Override
  public void onWebSocketClose(int code, @Nullable String reason, Callback callback) {
    final var first = recordClose(code, reason);
    if (first) {
      terminate(new IOException("WebSocket closed"));
    }

    callback.succeed();
    if (first) {
      runUnderLock(
          events,
          () -> {
            closeProgress = CloseProgress.ACKNOWLEDGED;
          });
    }

    schedule();
    releaseIfFinished();
  }

  /**
   * Records the first native close without admitting observers before acknowledgment.
   *
   * @param code native close code
   * @param reason native close reason
   * @return whether this is the first native close
   */
  private boolean recordClose(int code, @Nullable String reason) {
    return Objects.requireNonNull(
        Locks.getUnderLock(
            events,
            () -> {
              final var first = closeProgress == CloseProgress.NONE;
              if (first) {
                closeProgress = CloseProgress.RECEIVED;
                closeCode = code;
                closeReason = reason == null ? "" : reason;
              }

              return first;
            }));
  }

  /** Schedules actual work once; idle connections retain state without occupying a worker. */
  private void schedule() {
    final var pending = claimTask();
    if (pending == null) {
      return;
    }

    try {
      runtime.execute(pending);
    } catch (RejectedExecutionException ignored) {
      if (!rejectTask(pending)) {
        return;
      }

      stop();
      runtime.remove(this);
    }
  }

  /**
   * Claims one task while pending work is still eligible for dispatch.
   *
   * @return claimed task, or null when no work can be admitted
   */
  private @Nullable DispatchTask claimTask() {
    return Locks.getUnderLock(
        events,
        () -> {
          if (task != null
              || dispatchClosed
              || terminalNotification != TerminalNotification.PENDING
              || !hasWork()) {
            return null;
          }

          final var pending = new DispatchTask(this);
          task = pending;
          return pending;
        });
  }

  /**
   * Cancels the rejected task and clears connection task state only if that task is still current.
   *
   * @param pending rejected task
   * @return whether the rejected task was this connection's current task
   */
  private boolean rejectTask(DispatchTask pending) {
    return Objects.requireNonNull(
        Locks.getUnderLock(
            events,
            () -> {
              pending.cancel();
              if (!pending.equals(task)) {
                return false;
              }

              task = null;
              if (terminalNotification == TerminalNotification.PENDING) {
                terminalNotification = TerminalNotification.SUPPRESSED;
              }

              return true;
            }));
  }

  /**
   * Reads pending work only while the event lock is held.
   *
   * @return whether an application callback is ready
   */
  private boolean hasWork() {
    return deliveryState == DeliveryState.CLOSED
        ? closeProgress == CloseProgress.ACKNOWLEDGED
        : deliveryState == DeliveryState.OPEN_PENDING || message != null;
  }

  /**
   * Runs one admitted callback and releases executor ownership before another native event.
   *
   * <p>S1181 and S2139 are suppressed because {@link FutureTask} captures fatal errors without
   * reporting them to the worker's uncaught exception handler. Logging and rethrowing the original
   * error preserves both diagnostics and the task's failed state.
   *
   * @param pending task whose ownership must still match this connection
   */
  @SuppressWarnings({"java:S1181", "java:S2139"})
  private void run(DispatchTask pending) {
    if (!claimWorker(pending)) {
      return;
    }

    try {
      deliverAndFinish(pending);
    } catch (Error failure) {
      // FutureTask captures Errors; report them instead of silently discarding its result.
      LOGGER.error("WebSocket application work failed", failure);
      throw failure;
    }
  }

  /**
   * Records worker ownership only while the task is still eligible to run.
   *
   * @param pending task attempting to run
   * @return whether this worker claimed the task
   */
  private boolean claimWorker(DispatchTask pending) {
    return Objects.requireNonNull(
        Locks.getUnderLock(
            events,
            () -> {
              if (!pending.equals(task) || dispatchClosed) {
                return false;
              }

              worker = Thread.currentThread();
              return true;
            }));
  }

  /**
   * Delivers one callback and releases its task ownership even when delivery or closure fails.
   *
   * @param pending task whose worker ownership must be released
   */
  private void deliverAndFinish(DispatchTask pending) {
    var failureCloseCode = CloseStatus.SERVER_ERROR;

    try {
      deliverPending();
      failureCloseCode = CloseStatus.NO_CODE;
    } catch (InterruptedException ignored) {
      failureCloseCode = CloseStatus.SHUTDOWN;
    } catch (Exception failure) {
      runUnderLock(
          events,
          () -> {
            if (deliveryState != DeliveryState.CLOSED && error == null) {
              error = failure;
              LOGGER.warn("WebSocket application callback failed", failure);
            }
          });
    } finally {
      try {
        if (failureCloseCode != CloseStatus.NO_CODE) {
          final var reason =
              failureCloseCode == CloseStatus.SHUTDOWN
                  ? "Processing interrupted"
                  : "Processing failed";
          Objects.requireNonNull(connection).close(failureCloseCode, reason);

          if (failureCloseCode == CloseStatus.SHUTDOWN) {
            Thread.currentThread().interrupt();
          }
        }
      } finally {
        finish(pending);
      }
    }
  }

  /**
   * Delivers the pending opening or complete message without waiting for later native events.
   *
   * @throws Exception if application handling fails
   */
  private void deliverPending() throws Exception {
    final var delivery = claimDelivery();
    if (delivery == null) {
      return;
    }

    delivery.call();

    demand();
  }

  /**
   * Claims pending work atomically for delivery after releasing the event lock.
   *
   * @return claimed operation even when no handler is configured, or null if admission is closed
   * @throws Exception if claiming the operation fails
   */
  private @Nullable Callable<@Nullable Void> claimDelivery() throws Exception {
    return getUnderLock(
        events,
        () -> {
          if (deliveryState == DeliveryState.CLOSED || dispatchClosed) {
            return null;
          }

          final var opening = deliveryState == DeliveryState.OPEN_PENDING;
          deliveryState = DeliveryState.READY;

          final var next = message;
          message = null;

          return () -> {
            if (opening) {
              var open = configuration.open();
              if (open != null) {
                open.handle(Objects.requireNonNull(connection));
              }
            } else if (next != null) {
              deliver(next);
            }

            return null;
          };
        });
  }

  /**
   * Dispatches the native message type to its configured application handler.
   *
   * @param next complete text or owned binary data
   * @throws Exception if application handling fails
   */
  private void deliver(Object next) throws Exception {
    if (next instanceof String text) {
      final var handler = configuration.text();
      if (handler != null) {
        handler.handle(Objects.requireNonNull(connection), text);
      }
    } else if (next instanceof byte[] bytes) {
      final var handler = configuration.binary();
      if (handler != null) {
        handler.handle(Objects.requireNonNull(connection), bytes);
      }
    }
  }

  /**
   * Completes active ownership before another item or terminal observer can be admitted.
   *
   * @param pending task completing its actual application work
   */
  private void finish(DispatchTask pending) {
    final var notify = claimTerminalNotification();

    try {
      if (notify) {
        notifyTerminal();
      }
    } finally {
      runUnderLock(
          events,
          () -> {
            // Cancellation uses this same lock; no task interrupt can arrive after ownership
            // clears.
            Thread.interrupted();
            worker = null;
            if (pending.equals(task)) {
              task = null;
            }
          });

      schedule();
      releaseIfFinished();
    }
  }

  /**
   * Claims ready terminal notification before another callback can observe it.
   *
   * @return whether this callback claimed terminal notification
   */
  private boolean claimTerminalNotification() {
    return Objects.requireNonNull(
        Locks.getUnderLock(
            events,
            () -> {
              final var notify =
                  deliveryState == DeliveryState.CLOSED
                      && closeProgress == CloseProgress.ACKNOWLEDGED
                      && !dispatchClosed
                      && terminalNotification == TerminalNotification.PENDING;
              if (notify) {
                terminalNotification = TerminalNotification.CLAIMED;
              }

              return notify;
            }));
  }

  /** Releases registry ownership only after queued and active managed work has ended. */
  private void releaseIfFinished() {
    runUnderLock(
        events,
        () -> {
          if (task == null
              && (dispatchClosed
                  || (closeProgress == CloseProgress.ACKNOWLEDGED
                      && terminalNotification != TerminalNotification.PENDING))) {
            runtime.remove(this);
          }
        });
  }

  /**
   * Delivers ready terminal observers without waiting for a future native acknowledgment.
   * Remembered interruption is restored after both observers, including exceptional exits.
   */
  private void notifyTerminal() {
    var restore = Thread.interrupted();

    try {
      final var terminal = snapshotTerminal();
      final var failure = terminal.failure();

      try {
        final var observer = configuration.error();
        if (observer != null && failure != null) {
          observer.handle(Objects.requireNonNull(connection), failure);
        }
      } catch (Exception observerFailure) {
        if (observerFailure instanceof InterruptedException) {
          restore = true;
        }

        LOGGER.warn("WebSocket error observer failed", observerFailure);
      }

      try {
        final var handler = configuration.close();
        if (handler != null && terminal.ready()) {
          handler.handle(Objects.requireNonNull(connection), terminal.code(), terminal.reason());
        }
      } catch (Exception observerFailure) {
        if (observerFailure instanceof InterruptedException) {
          restore = true;
        }

        LOGGER.warn("WebSocket close observer failed", observerFailure);
      }
    } finally {
      if (restore) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Captures terminal values together for observer delivery after releasing the event lock.
   *
   * @return terminal state at the time of acquisition
   */
  private TerminalEvent snapshotTerminal() {
    return Objects.requireNonNull(
        Locks.getUnderLock(
            events,
            () ->
                new TerminalEvent(
                    closeCode, closeReason, error, closeProgress == CloseProgress.ACKNOWLEDGED)));
  }

  /** Closes this connection and interrupts only its managed worker before native drain. */
  void stop() {
    final var opened = beginStop();

    terminate(new IOException("Server stopping"));

    try {
      opened.close(CloseStatus.SHUTDOWN, "Server stopping", Callback.NOOP);
    } catch (RuntimeException ignoredFailure) {
      opened.disconnect();
    }

    schedule();
    releaseIfFinished();
  }

  /**
   * Rejects further demand and captures the native session before shutdown signaling.
   *
   * @return opened native session
   */
  private Session beginStop() {
    return Objects.requireNonNull(
        Locks.getUnderLock(
            events,
            () -> {
              stopping = true;
              return Objects.requireNonNull(session);
            }));
  }

  /** Cancels final queued work without mistaking cancellation for a running callback's return. */
  void finishStop() {
    runUnderLock(events, () -> dispatchClosed = true);

    try {
      terminate(new IOException("Server stopped"));
    } finally {
      releaseIfFinished();
    }
  }

  /** Stops application admission while allowing native close-handshake processing. */
  void closing() {
    runUnderLock(
        events,
        () -> {
          deliveryState = DeliveryState.CLOSED;
          message = null;
          cancelQueued();
        });
  }

  /** Advances only protocol processing after a public close request. */
  void demandClosing() {
    demand();
  }

  /** Requests native delivery once, without holding the application event lock. */
  private void demand() {
    final var opened = claimDemand();
    if (opened == null) {
      return;
    }

    try {
      opened.demand();
    } catch (RuntimeException failure) {
      if (!clearDemand()) {
        onWebSocketError(failure);
      }

      opened.disconnect();
    }
  }

  /**
   * Claims native delivery once while the connection still accepts demand.
   *
   * @return native session, or null when delivery cannot be requested
   */
  private @Nullable Session claimDemand() {
    return Locks.getUnderLock(
        events,
        () -> {
          if (closeProgress != CloseProgress.NONE
              || stopping
              || demandState == DemandState.OUTSTANDING) {
            return null;
          }

          demandState = DemandState.OUTSTANDING;
          return Objects.requireNonNull(session);
        });
  }

  /**
   * Clears failed native demand and snapshots whether native close was received or shutdown
   * started.
   *
   * @return whether native close was received or shutdown started
   */
  private boolean clearDemand() {
    return Objects.requireNonNull(
        Locks.getUnderLock(
            events,
            () -> {
              demandState = DemandState.IDLE;
              return closeProgress != CloseProgress.NONE || stopping;
            }));
  }

  /**
   * Stops application admission and settles writes before interrupting user work.
   *
   * @param failure terminal transport cause
   */
  private void terminate(Throwable failure) {
    runUnderLock(
        events,
        () -> {
          deliveryState = DeliveryState.CLOSED;
          message = null;
          final var opened = connection;
          if (opened != null) {
            // Settle sends independently of application completion or cancellation.
            opened.terminate(failure);
          }

          if (worker == null) {
            cancelQueued();
          } else if (worker != Thread.currentThread()) {
            worker.interrupt();
          }
        });
  }

  /** Cancels unclaimed work and clears its captured connection before native queue removal. */
  private void cancelQueued() {
    if (worker == null && task != null) {
      task.cancel();
      task = null;
    }
  }

  /**
   * Captures terminal state for observer delivery outside the event lock.
   *
   * @param code recorded native close code
   * @param reason recorded native close reason
   * @param failure recorded failure, or null if none was recorded
   * @param ready whether native close acknowledgment has completed
   */
  private record TerminalEvent(
      int code, String reason, @Nullable Throwable failure, boolean ready) {
    /**
     * Validates the captured reason.
     *
     * @param code recorded native close code
     * @param reason recorded native close reason
     * @param failure recorded failure, or null if none was recorded
     * @param ready whether native close acknowledgment has completed
     * @throws NullPointerException if reason is null
     */
    private TerminalEvent {
      Objects.requireNonNull(reason);
    }
  }

  /** Tracks the recorded native demand reservation independently of transport liveness. */
  private enum DemandState {
    /**
     * No demand reservation is recorded; other guards still determine whether demand is allowed.
     */
    IDLE,
    /** Demand was claimed and has not been cleared by message receipt or failed demand. */
    OUTSTANDING
  }

  /** Tracks application admission and pending opening work independently of worker ownership. */
  private enum DeliveryState {
    /** Admission is open with no opening work pending; a callback may still be running. */
    READY,
    /** Admission is open and the opening callback has not been claimed. */
    OPEN_PENDING,
    /** Application admission is closed; transport processing and active callbacks may continue. */
    CLOSED
  }

  /** Tracks terminal notification ownership independently of observer completion. */
  private enum TerminalNotification {
    /** Notification is unclaimed; delivery still depends on the other admission guards. */
    PENDING,
    /** Notification was claimed; observers may be running, finished or failed. */
    CLAIMED,
    /** Registration or dispatch rejection prevented notification from being claimed. */
    SUPPRESSED
  }

  /** Tracks native close receipt and acknowledgment independently of application observers. */
  private enum CloseProgress {
    /** No native close has been recorded. */
    NONE,
    /**
     * The first native close is recorded, but its completion callback has not returned
     * successfully.
     */
    RECEIVED,
    /** The first native close completion callback returned successfully. */
    ACKNOWLEDGED
  }

  /** A dispatch identity whose future owns execution and cancellation. */
  private static final class DispatchTask implements Runnable {
    private final FutureTask<@Nullable Void> future;

    /**
     * Captures the listener only in the future's callable so cancellation can release it.
     *
     * @param owner listener receiving this task's managed callback
     * @throws NullPointerException if owner is null
     */
    private DispatchTask(ManagedWebSocket owner) {
      Objects.requireNonNull(owner);
      this.future = new FutureTask<>(() -> owner.run(this), null);
    }

    /** Runs the future unless cancellation or previous execution prevents it. */
    @Override
    public void run() {
      future.run();
    }

    /** Cancels the future without interrupting an active managed callback. */
    private void cancel() {
      future.cancel(false);
    }
  }
}
