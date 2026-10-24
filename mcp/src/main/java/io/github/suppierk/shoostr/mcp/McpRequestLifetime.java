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
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Detached deadline bookkeeping for one HTTP exchange, including its framework-owned final write.
 * The timer only signals the mailbox and interrupts its still-owned thread; native callbacks and
 * HTTP access remain on their existing owning paths.
 */
final class McpRequestLifetime implements RequestObservation {
  private final ScheduledThreadPoolExecutor deadlines;
  private final String expiredTokenChallenge;
  private @Nullable Instant expiresAt;
  private @Nullable ScheduledFuture<?> deadline;
  private @Nullable Thread owner;
  private @Nullable McpStream stream;
  private boolean interruptedOwner;
  private boolean completed;
  private boolean expired;
  private boolean expiryRejected;
  private @Nullable Consumer<RequestOutcome> initialization;

  /**
   * Creates detached state before routing without retaining a live request or response.
   *
   * @param deadlines adapter-owned deadline scheduler
   * @param expiredTokenChallenge adapter-configured authentication challenge
   */
  McpRequestLifetime(ScheduledThreadPoolExecutor deadlines, String expiredTokenChallenge) {
    this.deadlines = Objects.requireNonNull(deadlines);
    this.expiredTokenChallenge = Objects.requireNonNull(expiredTokenChallenge);
  }

  /**
   * Arms the verified deadline on the actual handler owner before body reads or native admission. A
   * public/non-token identity has no deadline. Failed scheduling rejects the HTTP exchange.
   *
   * @param expiry verified token expiry, or null for a public/non-token identity
   * @throws IllegalStateException if this exchange was already armed or completed
   * @throws AuthenticationRequiredException if the token has already expired
   */
  synchronized void arm(@Nullable Instant expiry) {
    if (completed || owner != null) {
      throw new IllegalStateException("The MCP HTTP lifetime cannot be armed again");
    }

    requireFresh(expiry);
    expiresAt = expiry;
    owner = Thread.currentThread();
    if (expiry != null) {
      scheduleExpiry(Duration.between(Instant.now(), expiry));
    }
  }

  /**
   * Attaches a streaming mailbox to the already authenticated HTTP lifetime.
   *
   * @param transport detached HTTP mailbox
   * @throws IllegalStateException if this handler does not own an unattached lifetime
   * @throws AuthenticationRequiredException if the token has expired
   */
  synchronized void stream(McpStream transport) {
    if (completed || owner != Thread.currentThread() || stream != null) {
      throw new IllegalStateException("The MCP HTTP lifetime cannot attach this stream");
    }

    requireFresh();
    stream = Objects.requireNonNull(transport);
  }

  /**
   * Permits rendering the authentication rejection produced by this lifetime.
   *
   * @param authenticationResponse whether the staged status is401
   * @throws AuthenticationRequiredException if expired output is not its authentication rejection
   */
  synchronized void beforeFlush(boolean authenticationResponse) {
    if (authenticationResponse && expiryRejected) {
      return;
    }

    requireFresh();
  }

  /**
   * Rejects expired output before each flush, including the framework's final streaming write.
   * Before commitment the framework can render401; afterward it aborts the committed response.
   *
   * @throws AuthenticationRequiredException if the verified deadline has elapsed
   */
  synchronized void requireFresh() {
    requireFresh(expiresAt);
  }

  /**
   * Validates a candidate deadline before publishing admission state or writing output.
   *
   * @param expiry verified token expiry, or null for a public/non-token identity
   * @throws AuthenticationRequiredException if the verified deadline has elapsed
   */
  private void requireFresh(@Nullable Instant expiry) {
    if (expired || (expiry != null && !expiry.isAfter(Instant.now()))) {
      expiryRejected = true;
      clearOwnedInterruption();
      throw new AuthenticationRequiredException(expiredTokenChallenge);
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
    clearOwnedInterruption();
  }

  /** Clears only the interruption introduced by this lifetime on its current handler. */
  private void clearOwnedInterruption() {
    if (interruptedOwner) {
      // Clearing the deadline interruption is intentional; this JDK call also returns its old
      // state.
      Thread.interrupted();
      interruptedOwner = false;
    }
  }

  /**
   * Owns detached initialization cleanup until final transport completion.
   *
   * @param completion nonblocking terminal initialization callback
   * @throws IllegalStateException if initialization ownership was already assigned or completed
   */
  synchronized void initialization(Consumer<RequestOutcome> completion) {
    if (completed || initialization != null) {
      throw new IllegalStateException("The MCP initialization lifetime cannot be assigned again");
    }

    initialization = Objects.requireNonNull(completion);
  }

  /**
   * Cancels the retained deadline after both invocation cleanup and transport termination.
   *
   * @param outcome immutable completion, not retained
   */
  @Override
  public void complete(RequestOutcome outcome) {
    Consumer<RequestOutcome> terminal;
    synchronized (this) {
      completed = true;
      if (deadline != null) {
        deadline.cancel(false);
        deadline = null;
      }

      terminal = initialization;
      initialization = null;
    }
    if (terminal != null) {
      terminal.accept(outcome);
    }
  }

  /** Rechecks the verified expiry before signaling the mailbox and interrupting its owner. */
  private synchronized void expire() {
    if (completed || owner == null || expiryRejected) {
      return;
    }

    var remaining = Duration.between(Instant.now(), Objects.requireNonNull(expiresAt));
    if (remaining.isPositive()) {
      // Relative timer delays and wall-clock token expiry can diverge after a clock adjustment.
      scheduleExpiry(remaining);
      return;
    }

    expired = true;
    if (stream != null) {
      stream.fail(new IOException("The MCP HTTP token expired"));
    }

    if (!owner.isInterrupted()) {
      interruptedOwner = true;
      owner.interrupt();
    }
  }

  /**
   * Retains the current timer so completion also cancels a rescheduled early wake-up.
   *
   * @param remaining delay until the verified expiry, capped to the scheduler's nanosecond range
   */
  private void scheduleExpiry(Duration remaining) {
    deadline =
        deadlines.schedule(
            this::expire,
            remaining.compareTo(Duration.ofNanos(Long.MAX_VALUE)) > 0
                ? Long.MAX_VALUE
                : remaining.toNanos(),
            TimeUnit.NANOSECONDS);
  }
}
