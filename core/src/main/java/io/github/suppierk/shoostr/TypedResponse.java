package io.github.suppierk.shoostr;

import io.github.suppierk.shoostr.http.Cookie;
import io.github.suppierk.shoostr.http.HttpStatusCodes;
import io.github.suppierk.shoostr.http.MediaType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;
import org.eclipse.jetty.util.Callback;
import org.jspecify.annotations.Nullable;

/** Codec-enabled response; inherited byte arrays and text never invoke the codec. */
public final class TypedResponse extends Response {
  private final Codec codec;

  /**
   * Initializes the response paired with its one request, using the ordinary response state.
   *
   * @param delegate native output
   * @param options exchange limits
   * @param completion transport completion
   * @param head whether only representation metadata is sent
   * @param request paired request
   * @param codec shared application conversion
   */
  TypedResponse(
      org.eclipse.jetty.server.Response delegate,
      Options options,
      Callback completion,
      boolean head,
      Request request,
      Codec codec) {
    super(delegate, options, completion, head, request);
    this.codec = Objects.requireNonNull(codec);
  }

  /**
   * Stages finite codec output with explicit media metadata. Validates writable state before
   * invoking the codec; conversion failure leaves the previous staged representation untouched.
   *
   * @param contentType response media type, including any explicit charset
   * @param value nonnull application representation
   * @return this response
   * @throws Exception if conversion fails; response limits and state rules remain unchanged
   */
  @SuppressWarnings("java:S112") // User codecs share Handler's checked exception contract.
  public TypedResponse body(String contentType, Object value) throws Exception {
    requireEncodedBody(contentType);
    Objects.requireNonNull(value);
    super.body(
        contentType, Objects.requireNonNull(codec.write(value), "Codec returned null bytes"));
    return this;
  }

  /**
   * Stages finite codec output without interpreting the selected media type or transcoding bytes.
   *
   * @param contentType response media metadata
   * @param value nonnull application representation
   * @return this response
   * @throws Exception if conversion fails
   */
  @SuppressWarnings("java:S112") // User codecs share Handler's checked exception contract.
  public TypedResponse body(MediaType contentType, Object value) throws Exception {
    return body(Objects.requireNonNull(contentType).value(), value);
  }

  /** {@inheritDoc} */
  @Override
  public TypedResponse status(int code) {
    super.status(code);
    return this;
  }

  /** {@inheritDoc} */
  @Override
  public TypedResponse setHeader(String name, String value) {
    super.setHeader(name, value);
    return this;
  }

  /** {@inheritDoc} */
  @Override
  public TypedResponse text(String value) {
    super.text(value);
    return this;
  }

  /** {@inheritDoc} */
  @Override
  public TypedResponse body(String contentType, byte[] value) {
    super.body(contentType, value);
    return this;
  }

  /** {@inheritDoc} */
  @Override
  public TypedResponse body(MediaType contentType, byte[] value) {
    super.body(contentType, value);
    return this;
  }

  /** {@inheritDoc} */
  @Override
  public TypedResponse input(InputStream source, String contentType) throws IOException {
    super.input(source, contentType);
    return this;
  }

  /** {@inheritDoc} */
  @Override
  public TypedResponse file(Path source, String contentType) throws IOException {
    super.file(source, contentType);
    return this;
  }

  /** {@inheritDoc} */
  @Override
  public TypedResponse attachment(Path source, String contentType, String filename)
      throws IOException {
    super.attachment(source, contentType, filename);
    return this;
  }

  /** {@inheritDoc} */
  @Override
  public TypedResponse disableCompression() {
    super.disableCompression();
    return this;
  }

  /** {@inheritDoc} */
  @Override
  public TypedResponse addHeader(String name, String value) {
    super.addHeader(name, value);
    return this;
  }

  /** {@inheritDoc} */
  @Override
  public TypedResponse removeHeader(String name) {
    super.removeHeader(name);
    return this;
  }

  /** {@inheritDoc} */
  @Override
  public TypedResponse cookie(String name, String value) {
    super.cookie(name, value);
    return this;
  }

  /** {@inheritDoc} */
  @Override
  public TypedResponse cookie(Cookie cookie) {
    super.cookie(cookie);
    return this;
  }

  /** {@inheritDoc} */
  @Override
  public TypedResponse removeCookie(String name) {
    super.removeCookie(name);
    return this;
  }

  /** {@inheritDoc} */
  @Override
  public TypedResponse removeCookie(String name, String path, @Nullable String domain) {
    super.removeCookie(name, path, domain);
    return this;
  }

  /** {@inheritDoc} */
  @Override
  public TypedResponse removeCookie(Cookie cookie) {
    super.removeCookie(cookie);
    return this;
  }

  /** {@inheritDoc} */
  @Override
  public TypedResponse redirect(String location) {
    super.redirect(location);
    return this;
  }

  /** {@inheritDoc} */
  @Override
  public TypedResponse redirect(String location, HttpStatusCodes code) {
    super.redirect(location, code);
    return this;
  }
}
