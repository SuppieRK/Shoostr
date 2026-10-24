package io.github.suppierk.shoostr;

/**
 * Immutable limits enforced by Shoostr; all sizes are bytes. Configure the listener and native
 * transport through {@link Shoostr#modifyServer(java.util.function.Consumer)} and {@link
 * Shoostr#modifyHttpConfiguration(java.util.function.Consumer)}.
 *
 * @param maxRequestBytes maximum buffered request-body size
 * @param maxResponseBytes maximum staged finite-response size
 * @param streamBufferBytes capacity of each streaming response buffer
 * @param maxParameters maximum decoded pairs per query or URL-encoded form
 * @param multipart multipart parser configuration, including its separate part limit
 */
public record Options(
    int maxRequestBytes,
    int maxResponseBytes,
    int streamBufferBytes,
    int maxParameters,
    MultipartOptions multipart) {
  /**
   * Validates positive resource limits and the multipart configuration.
   *
   * @throws IllegalArgumentException if any limit is invalid
   */
  public Options {
    if (maxRequestBytes < 1
        || maxRequestBytes == Integer.MAX_VALUE
        || maxResponseBytes < 1
        || streamBufferBytes < 1
        || maxParameters < 1
        || multipart == null) {
      throw new IllegalArgumentException("Invalid application limits");
    }
  }

  /**
   * Creates the default request, response, streaming, parameter and multipart limits.
   *
   * @return immutable default limits
   */
  public static Options defaults() {
    return new Options(1_048_576, 1_048_576, 8192, 1000, MultipartOptions.defaults());
  }

  /**
   * Copies these limits with a different buffered request-body limit.
   *
   * @param value positive maximum request bytes, below {@link Integer#MAX_VALUE}
   * @return copied application limits
   */
  public Options withMaxRequestBytes(int value) {
    return new Options(value, maxResponseBytes, streamBufferBytes, maxParameters, multipart);
  }

  /**
   * Copies these limits with a different staged finite-response limit.
   *
   * @param value positive maximum staged response bytes
   * @return copied application limits
   */
  public Options withMaxResponseBytes(int value) {
    return new Options(maxRequestBytes, value, streamBufferBytes, maxParameters, multipart);
  }

  /**
   * Copies these limits with a different streaming response buffer capacity.
   *
   * @param value positive buffer capacity in bytes
   * @return copied application limits
   */
  public Options withStreamBufferBytes(int value) {
    return new Options(maxRequestBytes, maxResponseBytes, value, maxParameters, multipart);
  }

  /**
   * Copies these limits with a different decoded pair limit, applied separately to queries and
   * forms.
   *
   * @param value positive maximum number of pairs, including repetitions
   * @return copied application limits
   */
  public Options withMaxParameters(int value) {
    return new Options(maxRequestBytes, maxResponseBytes, streamBufferBytes, value, multipart);
  }

  /**
   * Copies these limits with a multipart resource configuration.
   *
   * @param value multipart parser limits and temporary storage settings
   * @return copied application limits
   */
  public Options withMultipart(MultipartOptions value) {
    return new Options(maxRequestBytes, maxResponseBytes, streamBufferBytes, maxParameters, value);
  }
}
