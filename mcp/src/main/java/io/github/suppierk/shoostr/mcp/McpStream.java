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
  private final McpJsonMapper mapper;
  private final ArrayDeque<Frame> frames;
  private final @Nullable Instant expiresAt;
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
        synchronized (this) {
          if (frames.isEmpty() && !finished) {
            wait(HEARTBEAT.toMillis());
          }

          if (failure != null) {
            throw new IOException("MCP stream failed", failure);
          }

          frame = frames.pollFirst();
          if (frame == null && finished) {
            return;
          }
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
  synchronized void finish() {
    finished = true;
    notifyAll();
  }

  /**
   * Signals native dispatch failure without touching the HTTP response.
   *
   * @param error native dispatch failure
   */
  synchronized void fail(Throwable error) {
    failure = Objects.requireNonNull(error);
    finish();
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
          synchronized (this) {
            accepted =
                !finished
                    && frames.size() < CAPACITY
                    && (expiresAt == null || expiresAt.isAfter(Instant.now()));
            if (accepted) {
              frames.addLast(new Frame(event, sink));
              notifyAll();
            }
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

  /** Releases pending acknowledgments outside the mailbox monitor. */
  private void discardPending() {
    Frame frame;
    while (true) {
      synchronized (this) {
        finished = true;
        frame = frames.pollFirst();
        notifyAll();
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
