package io.github.suppierk.shoostr.mcp;

import io.github.suppierk.shoostr.Response;
import io.github.suppierk.shoostr.SseEvent;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerTransport;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

/**
 * Detached, bounded native-message mailbox. Only the owning HTTP thread pumps the response; native
 * SDK threads enqueue frames and resume after their frame is flushed.
 */
final class McpStream implements McpStreamableServerTransport {
  private static final int CAPACITY = 64;
  private static final Duration HEARTBEAT = Duration.ofSeconds(15);
  private static final int OPEN = 0;
  private static final int RESPONDED = 1;
  private static final int TIMED_OUT = 2;
  private static final int CANCELLED = 3;
  private final McpJsonMapper mapper;
  private final ArrayDeque<Frame> frames;
  private final @Nullable Instant expiresAt;
  private final ReentrantLock lock;
  private final Condition changed;
  private final AtomicInteger outcome;
  private boolean finished;
  private @Nullable Throwable failure;

  /**
   * Creates one independently owned HTTP stream mailbox.
   *
   * @param mapper native serialization and decoding
   * @param expiresAt verified token expiry, or null for public/non-token traffic
   */
  McpStream(McpJsonMapper mapper, @Nullable Instant expiresAt) {
    this.mapper = Objects.requireNonNull(mapper);
    this.expiresAt = expiresAt;
    frames = new ArrayDeque<>();
    lock = new ReentrantLock();
    changed = lock.newCondition();
    outcome = new AtomicInteger(OPEN);
  }

  /**
   * Writes queued frames exclusively through the response's owner-thread interface.
   *
   * @param response owner-thread HTTP output
   * @throws IOException if dispatch, transport writing or interruption ends the stream
   */
  void pump(Response response) throws IOException {
    try {
      response.setHeader("Cache-Control", "no-cache");
      var output = response.startEventStream();
      output.heartbeat();
      while (true) {
        Frame frame;
        boolean done;
        lock.lock();

        try {
          var remaining = HEARTBEAT.toNanos();
          while (frames.isEmpty() && !finished && remaining > 0) {
            // Unlike Object.wait(), a condition can release an idle Java 21 virtual thread's
            // carrier.
            remaining = changed.awaitNanos(remaining);
          }

          if (failure != null) {
            throw new IOException("MCP stream failed", failure);
          }

          frame = frames.pollFirst();
          done = frame == null && finished;
        } finally {
          lock.unlock();
        }

        if (done) {
          return;
        }

        if (frame == null) {
          output.heartbeat();
        } else {
          send(output, frame);
        }
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IOException("MCP stream interrupted", error);
    } finally {
      discardPending();
    }
  }

  /**
   * Acknowledges a native frame only after the owner-thread HTTP write succeeds.
   *
   * @param output owner-thread event stream
   * @param frame detached queued frame
   * @throws IOException if the HTTP write fails
   */
  private static void send(Response.EventStream output, Frame frame) throws IOException {
    try {
      output.send(frame.event());
      frame.acknowledgment().success();
    } catch (IOException | RuntimeException error) {
      frame.acknowledgment().error(error);
      throw error;
    }
  }

  /** Signals normal stream completion without touching the HTTP response. */
  void finish() {
    lock.lock();

    try {
      finished = true;
      changed.signalAll();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Signals native dispatch failure without touching the HTTP response.
   *
   * @param error native dispatch failure
   */
  void fail(Throwable error) {
    lock.lock();

    try {
      failure = Objects.requireNonNull(error);
      finished = true;
      changed.signalAll();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Serializes one native message into a named MCP SSE event.
   *
   * @param message native JSON-RPC message
   * @return completion after the owning thread flushes the frame
   */
  @Override
  public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
    return sendMessage(message, null);
  }

  /**
   * Enqueues eagerly serialized data; acknowledgment follows owner-thread flush.
   *
   * @param message native JSON-RPC message
   * @param messageId optional native event ID
   * @return completion after the owning thread flushes the frame
   */
  @Override
  public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message, @Nullable String messageId) {
    Objects.requireNonNull(message);
    return Mono.defer(
        () -> {
          if (message instanceof McpSchema.JSONRPCResponse) {
            if (!outcome.compareAndSet(OPEN, RESPONDED)) {
              return Mono.empty();
            }
          } else if (outcome.get() != OPEN) {
            return Mono.error(new IOException("MCP operation has ended"));
          }

          return enqueue(message, messageId);
        });
  }

  /**
   * Claims one terminal timeout response without invoking native callbacks or waiting for I/O.
   *
   * @param id originating request identifier
   * @return whether native cancellation remains necessary after claiming or aborting the response
   */
  boolean timeout(Object id) {
    if (!outcome.compareAndSet(OPEN, TIMED_OUT)) {
      if (outcome.get() == RESPONDED) {
        fail(new IOException("MCP response deadline exceeded"));
        return true;
      }

      return false;
    }

    enqueue(
            McpSchema.JSONRPCResponse.error(
                id,
                new McpSchema.JSONRPCResponse.JSONRPCError(
                    -32001, "MCP operation timed out", null)),
            null)
        .subscribe(ignored -> {}, this::fail, this::finish);
    return true;
  }

  /**
   * Cancels a matching operation without producing a JSON-RPC response.
   *
   * @return whether cancellation won before another terminal response was claimed
   */
  boolean cancelResponse() {
    if (!outcome.compareAndSet(OPEN, CANCELLED)) {
      return false;
    }

    finish();
    return true;
  }

  /**
   * Serializes and queues one already-authorized frame within the finite mailbox.
   *
   * @param message native request, notification or claimed terminal response
   * @param messageId optional native SSE event identifier
   * @return acknowledgment after the owner flushes the frame, or a delivery failure
   */
  private Mono<Void> enqueue(McpSchema.JSONRPCMessage message, @Nullable String messageId) {
    return Mono.create(
        sink -> {
          if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            sink.error(new IOException("The MCP HTTP token expired"));
            return;
          }

          SseEvent event;

          try {
            event = new SseEvent(mapper.writeValueAsString(message), "message", messageId, null);
          } catch (IOException | RuntimeException error) {
            sink.error(error);
            return;
          }

          boolean accepted;
          lock.lock();

          try {
            accepted =
                !finished
                    && frames.size() < CAPACITY
                    && (expiresAt == null || expiresAt.isAfter(Instant.now()));
            if (accepted) {
              frames.addLast(new Frame(event, sink));
              changed.signalAll();
            }
          } finally {
            lock.unlock();
          }

          if (!accepted) {
            sink.error(new IOException("MCP stream is closed or its frame mailbox is full"));
          }
        });
  }

  /**
   * Signals transport closure on subscription; the HTTP owner drains accepted frames.
   *
   * @return completion of the detached closure signal
   */
  @Override
  public Mono<Void> closeGracefully() {
    return Mono.fromRunnable(this::finish);
  }

  /**
   * Delegates native exchange decoding without retaining a request.
   *
   * @param <T> decoded value type
   * @param data native JSON-compatible data
   * @param typeRef native target type
   * @return decoded value
   */
  @Override
  public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
    return mapper.convertValue(data, typeRef);
  }

  /** Releases pending acknowledgments outside mailbox synchronization. */
  private void discardPending() {
    Frame frame;
    while (true) {
      lock.lock();

      try {
        finished = true;
        frame = frames.pollFirst();
        changed.signalAll();
      } finally {
        lock.unlock();
      }

      if (frame == null) {
        return;
      }

      frame.acknowledgment().error(new IOException("MCP HTTP stream ended"));
    }
  }

  /** One validated immutable wire frame and its native completion. */
  private record Frame(SseEvent event, MonoSink<Void> acknowledgment) {
    /**
     * Validates one accepted frame and its native acknowledgment.
     *
     * @param event immutable SSE frame
     * @param acknowledgment native completion after flushing
     */
    private Frame {
      Objects.requireNonNull(event);
      Objects.requireNonNull(acknowledgment);
    }
  }
}
