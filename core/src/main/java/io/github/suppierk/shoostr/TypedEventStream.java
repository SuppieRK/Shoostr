package io.github.suppierk.shoostr;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Codec-backed SSE output over the response's single handler-owned stream. */
public final class TypedEventStream extends Response.EventStream {
  private final Codec codec;

  /**
   * Shares ordinary event framing and stream ownership.
   *
   * @param output existing response stream
   * @param codec shared application conversion
   */
  TypedEventStream(Response.Stream output, Codec codec) {
    super(output);
    this.codec = Objects.requireNonNull(codec);
  }

  /**
   * Encodes one object as UTF-8 text event data and flushes the event. The codec must produce a
   * UTF-8 textual representation. Malformed UTF-8 and conversion failures write no part of this
   * event, but headers and earlier events are already committed. Validates stream ownership and
   * lifetime before invoking the codec; does not bound codec allocations.
   *
   * @param value nonnull application representation
   * @return this event stream
   * @throws Exception if conversion, strict UTF-8 decoding or transport output fails
   */
  @SuppressWarnings("java:S112")
  public TypedEventStream sendObject(Object value) throws Exception {
    requireWritable();
    Objects.requireNonNull(value);
    var bytes = Objects.requireNonNull(codec.write(value), "Codec returned null bytes");
    // Reporting decoding rejects malformed output before even a data prefix is written.
    var text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    super.send(text);
    return this;
  }

  /** {@inheritDoc} */
  @Override
  public TypedEventStream send(String data) throws IOException {
    super.send(data);
    return this;
  }

  /** {@inheritDoc} */
  @Override
  public TypedEventStream send(SseEvent event) throws IOException {
    super.send(event);
    return this;
  }

  /** {@inheritDoc} */
  @Override
  public TypedEventStream comment(String value) throws IOException {
    super.comment(value);
    return this;
  }

  /** {@inheritDoc} */
  @Override
  public TypedEventStream heartbeat() throws IOException {
    super.heartbeat();
    return this;
  }
}
