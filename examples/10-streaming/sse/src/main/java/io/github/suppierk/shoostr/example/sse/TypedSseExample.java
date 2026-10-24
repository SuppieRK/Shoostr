package io.github.suppierk.shoostr.example.sse;

import io.github.suppierk.shoostr.Codec;
import io.github.suppierk.shoostr.Shoostr;
import java.nio.charset.StandardCharsets;

/** Sends progress objects as JSON event data. */
public final class TypedSseExample {
  /** Prevents construction of the example factory. */
  private TypedSseExample() {}

  /**
   * Creates an unstarted application with a finite progress feed.
   *
   * @return application owned by its caller
   */
  public static Shoostr application() {
    return new Shoostr()
        .codec(new ProgressCodec())
        .routes(
            routes ->
                routes.sse(
                    "/progress",
                    (ignored, response) -> {
                      // Starting commits headers; keep input validation before this point.
                      response
                          .startEventStream()
                          .comment("ready")
                          // Only the object payload is encoded; the stream supplies SSE framing.
                          .sendObject(new Progress(10))
                          .heartbeat()
                          .sendObject(new Progress(100));
                      // Returning completes this finite feed; the writer is not a background
                      // producer.
                    }));
  }

  /**
   * Completed work as a percentage.
   *
   * @param completed percentage from zero to one hundred
   */
  public record Progress(int completed) {
    /**
     * Rejects percentages outside the model's range.
     *
     * @param completed percentage from zero to one hundred
     * @throws IllegalArgumentException if the percentage is outside that range
     */
    public Progress {
      if (completed < 0 || completed > 100) {
        throw new IllegalArgumentException("Progress must be between 0 and 100");
      }
    }
  }

  /** Output-only codec for this numeric model, not a general JSON serializer. */
  private static final class ProgressCodec implements Codec {
    /**
     * Rejects decoding, which this output-only endpoint does not use.
     *
     * @param source unused input
     * @param type unused result class
     * @param <T> result type
     * @return no result; always throws
     * @throws UnsupportedOperationException because this codec only writes progress
     */
    @Override
    public <T> T read(byte[] source, Class<T> type) {
      throw new UnsupportedOperationException("This codec only writes progress");
    }

    /**
     * Encodes only the numeric progress representation as UTF-8 JSON.
     *
     * @param value progress to encode
     * @return UTF-8 text suitable for SSE data
     * @throws IllegalArgumentException if the value is not progress
     */
    @Override
    public byte[] write(Object value) {
      if (!(value instanceof Progress progress)) {
        throw new IllegalArgumentException("This codec supports only Progress");
      }

      return ("{\"completed\":" + progress.completed() + "}").getBytes(StandardCharsets.UTF_8);
    }
  }
}
