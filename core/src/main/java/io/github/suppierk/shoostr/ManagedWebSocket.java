package io.github.suppierk.shoostr;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
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
  private @Nullable Thread worker;
  private @Nullable Object message;
  private boolean demanded;
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
      connection = new WebSocketConnection(opened, path, parameters, principal, this);
    }
    if (!runtime.register(this)) {
      stop();
      return;
    }

    try {
      runtime.execute(this);
    } catch (RejectedExecutionException _) {
      stop();
      runtime.remove(this);
    }
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

      events.notifyAll();
    }
    if (discard) {
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

      events.notifyAll();
    }
    if (discard) {
      demand();
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
  }

  /**
   * Acknowledges native closure before waking the managed worker.
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
        events.notifyAll();
      }
    }

    runtime.remove(this);
  }

  /** Runs all application work for this connection on one managed virtual thread. */
  void run() {
    synchronized (events) {
      worker = Thread.currentThread();
      if (stopping) {
        worker = null;
        return;
      }
    }

    try {
      var open = configuration.open();
      if (open != null) {
        open.handle(Objects.requireNonNull(connection));
      }

      receiveMessages();
    } catch (InterruptedException _) {
      Objects.requireNonNull(connection).close(1001, "Processing interrupted");
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

      opened.close(1011, "Processing failed");
    } finally {
      // Also close after an unchecked Error escapes application work, without swallowing it.
      Objects.requireNonNull(connection).close(1011, "Processing failed");
      notifyTerminal();
      synchronized (events) {
        worker = null;
      }
    }
  }

  /**
   * Delivers complete messages one at a time until application admission closes.
   *
   * @throws Exception if message handling fails or the worker is interrupted
   */
  private void receiveMessages() throws Exception {
    demand();
    while (true) {
      var next = nextMessage();
      if (next == null) {
        return;
      }

      deliver(next);
      demand();
    }
  }

  /**
   * Takes the single pending message without introducing an application mailbox.
   *
   * @return complete message, or null after closing begins
   * @throws InterruptedException if managed work is canceled
   */
  private @Nullable Object nextMessage() throws InterruptedException {
    synchronized (events) {
      while (message == null && !closing) {
        events.wait();
      }
      if (closing) {
        return null;
      }

      var next = Objects.requireNonNull(message);
      message = null;
      return next;
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
      opened.close(1001, "Server stopping", Callback.NOOP);
    } catch (RuntimeException _) {
      opened.disconnect();
    }
  }

  /** Stops application admission while allowing native close-handshake processing. */
  void closing() {
    synchronized (events) {
      closing = true;
      message = null;
      events.notifyAll();
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
    Thread running;
    WebSocketConnection opened;
    synchronized (events) {
      closing = true;
      message = null;
      running = worker;
      opened = connection;
      events.notifyAll();
    }
    if (opened != null) {
      opened.terminate(failure);
    }

    if (running != null && running != Thread.currentThread()) {
      running.interrupt();
    }
  }

  /**
   * Waits for native acknowledgment without treating cancellation as application completion.
   * Remembered interruption is restored after both observers, including exceptional exits.
   */
  private void notifyTerminal() {
    boolean interrupted = Thread.interrupted();

    try {
      int code;
      String reason;
      Throwable failure;
      boolean ready;
      synchronized (events) {
        while (!closeReady && !stopping) {
          try {
            events.wait();
          } catch (InterruptedException _) {
            interrupted = true;
          }
        }
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
          interrupted = true;
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
          interrupted = true;
        }

        LOGGER.warn("WebSocket close observer failed", observerFailure);
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
