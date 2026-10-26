package io.github.suppierk.shoostr;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
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
  private final Object events;
  private final String path;
  private final Map<String, String> parameters;
  private final @Nullable Principal principal;
  private @Nullable Session session;
  private @Nullable WebSocketConnection connection;
  private @Nullable FutureTask<Void> task;
  private @Nullable Thread worker;
  private @Nullable Object message;
  private boolean demanded;
  private boolean openingPending;
  private boolean terminalNotified;
  private boolean dispatchClosed;
  private boolean stopping;
  private boolean closing;
  private boolean closeReceived;
  private boolean closeReady;
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
    events = new Object();
    path = request.path();
    parameters = request.pathParamMap();
    principal = request.principal().orElse(null);
    closeReason = "";
  }

  /**
   * Creates the public connection and starts managed delivery.
   *
   * @param opened native session
   */
  @Override
  public void onWebSocketOpen(Session opened) {
    synchronized (events) {
      session = opened;
      connection = configuration.connection(opened, path, parameters, principal, this);
      openingPending = true;
    }
    if (!runtime.register(this)) {
      synchronized (events) {
        terminalNotified = true;
      }
      stop();
      return;
    }

    schedule();
  }

  /**
   * Publishes one complete text message; further delivery awaits managed completion.
   *
   * @param text complete message
   */
  @Override
  public void onWebSocketText(String text) {
    boolean discard;
    synchronized (events) {
      demanded = false;
      discard = closing;
      if (!closing) {
        message = text;
      }
    }
    if (discard) {
      demand();
    } else {
      schedule();
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
    boolean copied = false;

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

    boolean discard;
    synchronized (events) {
      demanded = false;
      discard = closing;
      if (!closing) {
        message = bytes;
      }
    }
    if (discard) {
      demand();
    } else {
      schedule();
    }
  }

  /**
   * Settles managed work independently of application cleanup.
   *
   * @param failure native transport failure
   */
  @Override
  public void onWebSocketError(Throwable failure) {
    synchronized (events) {
      if (!closeReceived && !stopping && error == null) {
        error = failure;
      }
    }
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
    boolean first;
    synchronized (events) {
      first = !closeReceived;
      if (first) {
        closeReceived = true;
        closeCode = code;
        closeReason = reason == null ? "" : reason;
      }
    }
    if (first) {
      terminate(new IOException("WebSocket closed"));
    }

    callback.succeed();
    if (first) {
      synchronized (events) {
        closeReady = true;
      }
    }

    schedule();
    releaseIfFinished();
  }

  /** Schedules actual work once; idle connections retain state without occupying a worker. */
  private void schedule() {
    FutureTask<Void> pending;
    synchronized (events) {
      if (task != null || dispatchClosed || terminalNotified || !hasWork()) {
        return;
      }

      var reference = new AtomicReference<FutureTask<Void>>();
      pending = new FutureTask<>(() -> run(Objects.requireNonNull(reference.get())), null);
      reference.set(pending);
      task = pending;
    }

    try {
      runtime.execute(pending);
    } catch (RejectedExecutionException ignored) {
      synchronized (events) {
        pending.cancel(false);
        if (!pending.equals(task)) {
          return;
        }

        task = null;
        terminalNotified = true;
      }
      stop();
      runtime.remove(this);
    }
  }

  /**
   * Reads pending work only while the event lock is held.
   *
   * @return whether an application callback is ready
   */
  private boolean hasWork() {
    return closing ? closeReady : openingPending || message != null;
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
  private void run(FutureTask<Void> pending) {
    synchronized (events) {
      if (!pending.equals(task) || dispatchClosed) {
        return;
      }

      worker = Thread.currentThread();
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
   * Delivers one callback and releases its task ownership even when delivery or closure fails.
   *
   * @param pending task whose worker ownership must be released
   */
  private void deliverAndFinish(FutureTask<Void> pending) {
    boolean completed = false;

    try {
      deliverPending();
      completed = true;
    } catch (InterruptedException ignored) {
      Objects.requireNonNull(connection).close(CloseStatus.SHUTDOWN, "Processing interrupted");
      Thread.currentThread().interrupt();
    } catch (Exception failure) {
      WebSocketConnection opened;
      boolean unexpected;
      synchronized (events) {
        opened = Objects.requireNonNull(connection);
        unexpected = !closing && error == null;
        if (unexpected) {
          error = failure;
        }
      }
      if (unexpected) {
        LOGGER.warn("WebSocket application callback failed", failure);
      }

      opened.close(CloseStatus.SERVER_ERROR, "Processing failed");
    } finally {
      try {
        if (!completed) {
          Objects.requireNonNull(connection).close(CloseStatus.SERVER_ERROR, "Processing failed");
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
    boolean opening;
    Object next;
    synchronized (events) {
      if (closing || dispatchClosed) {
        return;
      }

      opening = openingPending;
      openingPending = false;
      next = message;
      message = null;
    }

    if (opening) {
      var open = configuration.open();
      if (open != null) {
        open.handle(Objects.requireNonNull(connection));
      }
    } else if (next != null) {
      deliver(next);
    }

    demand();
  }

  /**
   * Completes active ownership before another item or terminal observer can be admitted.
   *
   * @param pending task completing its actual application work
   */
  private void finish(FutureTask<Void> pending) {
    boolean notify;
    synchronized (events) {
      notify = closing && closeReady && !dispatchClosed && !terminalNotified;
      if (notify) {
        terminalNotified = true;
      }
    }

    try {
      if (notify) {
        notifyTerminal();
      }
    } finally {
      synchronized (events) {
        // Cancellation uses this same lock; no task interrupt can arrive after ownership clears.
        Thread.interrupted();
        worker = null;
        if (pending.equals(task)) {
          task = null;
        }
      }
      schedule();
      releaseIfFinished();
    }
  }

  /** Releases registry ownership only after queued and active managed work has ended. */
  private void releaseIfFinished() {
    synchronized (events) {
      if (task == null && (dispatchClosed || (closeReady && terminalNotified))) {
        runtime.remove(this);
      }
    }
  }

  /**
   * Dispatches the native message type to its configured application handler.
   *
   * @param next complete text or owned binary data
   * @throws Exception if application handling fails
   */
  private void deliver(Object next) throws Exception {
    if (next instanceof String text) {
      var handler = configuration.text();
      if (handler != null) {
        handler.handle(Objects.requireNonNull(connection), text);
      }
    } else if (next instanceof byte[] bytes) {
      var handler = configuration.binary();
      if (handler != null) {
        handler.handle(Objects.requireNonNull(connection), bytes);
      }
    }
  }

  /** Closes this connection and interrupts only its managed worker before native drain. */
  void stop() {
    Session opened;
    synchronized (events) {
      stopping = true;
      opened = Objects.requireNonNull(session);
    }
    terminate(new IOException("Server stopping"));

    try {
      opened.close(CloseStatus.SHUTDOWN, "Server stopping", Callback.NOOP);
    } catch (RuntimeException ignored3) {
      opened.disconnect();
    }

    schedule();
    releaseIfFinished();
  }

  /** Cancels final queued work without mistaking cancellation for a running callback's return. */
  void finishStop() {
    synchronized (events) {
      dispatchClosed = true;
    }

    try {
      terminate(new IOException("Server stopped"));
    } finally {
      releaseIfFinished();
    }
  }

  /** Stops application admission while allowing native close-handshake processing. */
  void closing() {
    synchronized (events) {
      closing = true;
      openingPending = false;
      message = null;
      cancelQueued();
    }
  }

  /** Advances only protocol processing after a public close request. */
  void demandClosing() {
    demand();
  }

  /** Requests native delivery once, without holding the application event lock. */
  private void demand() {
    Session opened;
    synchronized (events) {
      if (closeReceived || stopping || demanded) {
        return;
      }

      demanded = true;
      opened = Objects.requireNonNull(session);
    }

    try {
      opened.demand();
    } catch (RuntimeException failure) {
      boolean closed;
      synchronized (events) {
        demanded = false;
        closed = closeReceived || stopping;
      }
      if (!closed) {
        onWebSocketError(failure);
      }

      opened.disconnect();
    }
  }

  /**
   * Stops application admission and settles writes before interrupting user work.
   *
   * @param failure terminal transport cause
   */
  private void terminate(Throwable failure) {
    synchronized (events) {
      closing = true;
      openingPending = false;
      message = null;
      var opened = connection;
      if (opened != null) {
        // Settle sends independently of application completion or cancellation.
        opened.terminate(failure);
      }

      if (worker == null) {
        cancelQueued();
      } else if (worker != Thread.currentThread()) {
        worker.interrupt();
      }
    }
  }

  /** Cancels unclaimed work and clears its captured connection before native queue removal. */
  private void cancelQueued() {
    if (worker == null && task != null) {
      task.cancel(false);
      task = null;
    }
  }

  /**
   * Delivers ready terminal observers without waiting for a future native acknowledgment.
   * Remembered interruption is restored after both observers, including exceptional exits.
   */
  private void notifyTerminal() {
    boolean restore = Thread.interrupted();

    try {
      int code;
      String reason;
      Throwable failure;
      boolean ready;
      synchronized (events) {
        code = closeCode;
        reason = closeReason;
        failure = error;
        ready = closeReady;
      }

      try {
        var observer = configuration.error();
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
        var handler = configuration.close();
        if (handler != null && ready) {
          handler.handle(Objects.requireNonNull(connection), code, reason);
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
}
