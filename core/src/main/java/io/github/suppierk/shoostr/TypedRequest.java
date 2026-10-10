package io.github.suppierk.shoostr;

import java.security.Principal;
import java.util.Objects;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.jspecify.annotations.Nullable;

/**
 * Codec-enabled request inheriting ordinary bytes, text, parameters and handler-scoped ownership.
 */
public final class TypedRequest extends Request {
  private final Codec codec;

  /**
   * Creates the actual exchange and its typed response; no independent request state is copied.
   *
   * @param delegate native input
   * @param response native output
   * @param options exchange limits
   * @param completion transport completion
   * @param codec shared application conversion
   */
  TypedRequest(
      org.eclipse.jetty.server.Request delegate,
      Response response,
      Options options,
      Callback completion,
      Codec codec) {
    super(delegate, response, options, completion, Objects.requireNonNull(codec));
    this.codec = codec;
  }

  /**
   * Decodes the bounded body using the configured codec. Buffered, streaming and multipart access
   * retain their existing mutual exclusion. Each call decodes a fresh defensive copy.
   *
   * @param type concrete target class
   * @param <T> decoded type
   * @return nonnull decoded value
   * @throws Exception if bounded reading or conversion fails, without changing the failure type
   */
  @SuppressWarnings("java:S112") // User codecs share Handler's checked exception contract.
  public <T> T body(Class<T> type) throws Exception {
    check();
    Objects.requireNonNull(type);
    return Objects.requireNonNull(codec.read(bodyBytes(), type), "Codec returned a null value");
  }

  /** {@inheritDoc} */
  @Override
  public TypedRequest principal(@Nullable Principal value) {
    super.principal(value);
    return this;
  }

  /** {@inheritDoc} */
  @Override
  public TypedRequest attribute(String name, @Nullable Object value) {
    super.attribute(name, value);
    return this;
  }
}
