package io.github.suppierk.shoostr.mcp;

import io.github.suppierk.shoostr.RequestObservation;
import io.github.suppierk.shoostr.RequestOutcome;
import io.github.suppierk.shoostr.http.exceptions.AuthenticationRequiredException;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Detached deadline bookkeeping for one HTTP exchange, including its framework-owned final write.
 * The timer only signals the mailbox and interrupts its still-owned thread; native callbacks and
 * HTTP access remain on their existing owning paths.
 */
final class McpRequestLifetime implements RequestObservation {
  private final ScheduledThreadPoolExecutor deadlines;
  private @Nullable Instant expiresAt;
  private @Nullable ScheduledFuture<?> deadline;
  private @Nullable Thread owner;
  private @Nullable McpStream stream;
  private boolean interruptedOwner;
  private boolean completed;

  /**
   * Creates detached state before routing without retaining a live request or response.
   *
   * @param deadlines adapter-owned deadline scheduler
   */
  McpRequestLifetime(ScheduledThreadPoolExecutor deadlines) {
    this.deadlines = Objects.requireNonNull(deadlines);
  }

  /**
   * Arms the verified deadline on the actual handler owner before stream admission or commitment. A
   * public/non-token identity has no deadline. Failed scheduling rejects the HTTP exchange.
   *
   * @param transport detached HTTP mailbox
   * @param expiry verified token expiry, or null for a public/non-token identity
   * @throws IllegalStateException if this exchange was already armed or completed
   * @throws AuthenticationRequiredException if the token has already expired
   */
  synchronized void arm(McpStream transport, @Nullable Instant expiry) {
    if (completed || owner != null) {
      throw new IllegalStateException("The MCP HTTP lifetime cannot be armed again");
    }

    stream = Objects.requireNonNull(transport);
    expiresAt = expiry;
    requireFresh();
    owner = Thread.currentThread();
    if (expiry != null) {
      var remaining = Duration.between(Instant.now(), expiry);
      deadline =
          deadlines.schedule(
              this::expire,
              remaining.compareTo(Duration.ofNanos(Long.MAX_VALUE)) > 0
                  ? Long.MAX_VALUE
                  : remaining.toNanos(),
              TimeUnit.NANOSECONDS);
    }
  }

  /**
   * Rejects expired output before each flush, including the framework's final streaming write.
   * Before commitment the framework can render401; afterward it aborts the committed response.
   *
   * @throws AuthenticationRequiredException if the verified deadline has elapsed
   */
  synchronized void requireFresh() {
    if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
      throw new AuthenticationRequiredException("Bearer error=\"invalid_token\"");
    }
  }

  /**
   * Provides scope cleanup on a selected executor without completing this HTTP exchange.
   *
   * @return owner-thread cleanup for the attaching invocation
   */
  @Override
  public AutoCloseable attach() {
    return this;
  }

  /**
   * Revokes only this thread's interrupt target after final writes, before executor worker reuse.
   * The admission scope closes before a handoff and therefore cannot revoke a later worker.
   */
  @Override
  public synchronized void close() {
    if (owner != Thread.currentThread()) {
      return;
    }

    owner = null;
    stream = null;
    if (interruptedOwner) {
      // Clearing the deadline interruption is intentional; this JDK call also returns its old
      // state.
      Thread.interrupted();
      interruptedOwner = false;
    }
  }

  /**
   * Cancels the retained deadline after both invocation cleanup and transport termination.
   *
   * @param outcome immutable completion, not retained
   */
  @Override
  public synchronized void complete(RequestOutcome outcome) {
    completed = true;
    if (deadline != null) {
      deadline.cancel(false);
      deadline = null;
    }
  }

  /** Signals expiry without invoking a native continuation or touching live HTTP objects. */
  private synchronized void expire() {
    if (completed || owner == null) {
      return;
    }

    if (stream != null) {
      stream.fail(new IOException("The MCP HTTP token expired"));
    }

    if (!owner.isInterrupted()) {
      interruptedOwner = true;
      owner.interrupt();
    }
  }
}
