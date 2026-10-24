package io.github.suppierk.shoostr.example.websocket;

import io.github.suppierk.shoostr.Codec;
import io.github.suppierk.shoostr.Shoostr;
import java.nio.ByteBuffer;

/** Exchanges counter objects using an explicit binary format. */
public final class TypedWebSocketExample {
  /** Prevents construction of the example factory. */
  private TypedWebSocketExample() {}

  /**
   * Creates an unstarted application.
   *
   * @return application owned by its caller
   */
  public static Shoostr application() {
    return new Shoostr()
        .codec(new CounterCodec())
        .routes(
            routes ->
                routes.websocket(
                    "/counter",
                    socket ->
                        socket
                            // Object handlers decode complete binary messages, including fragmented
                            // input.
                            .onObject(
                                Counter.class,
                                (connection, counter) ->
                                    connection.sendObject(
                                        new Counter(Math.incrementExact(counter.value()))))
                            // Raw text handling is independent and does not invoke the codec.
                            .onText((connection, text) -> connection.send("text: " + text))));
  }

  /**
   * A signed counter value.
   *
   * @param value counter value
   */
  public record Counter(int value) {}

  /** Uses exactly four bytes per counter: a signed integer, most significant byte first. */
  private static final class CounterCodec implements Codec {
    /**
     * Decodes the agreed binary representation rather than inferring a format.
     *
     * @param source complete binary message
     * @param type supported counter class
     * @param <T> decoded value type
     * @return decoded counter
     * @throws IllegalArgumentException if the class or binary length is unsupported
     */
    @Override
    public <T> T read(byte[] source, Class<T> type) {
      if (type != Counter.class || source.length != Integer.BYTES) {
        throw new IllegalArgumentException("Expected one four-byte counter");
      }

      return type.cast(new Counter(ByteBuffer.wrap(source).getInt()));
    }

    /**
     * Encodes a counter for a binary WebSocket message.
     *
     * @param value counter to encode
     * @return four-byte representation
     * @throws IllegalArgumentException if the value is not a counter
     */
    @Override
    public byte[] write(Object value) {
      if (!(value instanceof Counter counter)) {
        throw new IllegalArgumentException("This codec supports only Counter");
      }

      return ByteBuffer.allocate(Integer.BYTES).putInt(counter.value()).array();
    }
  }
}
