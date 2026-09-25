package io.github.suppierk.shoostr;

/**
 * Initial HTTP/1.1 configuration; all sizes are bytes.
 *
 * @param host bind address
 * @param port listening port, or zero for an ephemeral port
 * @param maxRequestBytes maximum buffered request-body size
 * @param maxResponseBytes maximum staged finite-response size
 * @param streamBufferBytes capacity of each streaming response buffer
 * @param idleTimeoutMillis connection idle timeout in milliseconds
 * @param maxParameters maximum decoded pairs per query or URL-encoded form
 * @param multipart multipart parser configuration, including its separate part limit
 */
public record Options(
    String host,
    int port,
    int maxRequestBytes,
    int maxResponseBytes,
    int streamBufferBytes,
    long idleTimeoutMillis,
    int maxParameters,
    MultipartOptions multipart) {
  /**
   * Validates bind settings and positive resource limits.
   *
   * @throws IllegalArgumentException if any option is invalid
   */
  public Options {
    if (host == null
        || host.isBlank()
        || port < 0
        || port > 65535
        || maxRequestBytes < 1
        || maxRequestBytes == Integer.MAX_VALUE
        || maxResponseBytes < 1
        || streamBufferBytes < 1
        || idleTimeoutMillis < 1
        || maxParameters < 1
        || multipart == null) {
      throw new IllegalArgumentException("Invalid server options");
    }
  }

  /**
   * Creates options with the default limit of 1000 pairs per query or form.
   *
   * @param host bind address
   * @param port listening port
   * @param maxRequestBytes maximum buffered request-body size
   * @param maxResponseBytes maximum staged response size
   * @param streamBufferBytes streaming buffer capacity
   * @param idleTimeoutMillis connection idle timeout
   */
  public Options(
      String host,
      int port,
      int maxRequestBytes,
      int maxResponseBytes,
      int streamBufferBytes,
      long idleTimeoutMillis) {
    this(
        host,
        port,
        maxRequestBytes,
        maxResponseBytes,
        streamBufferBytes,
        idleTimeoutMillis,
        1000,
        MultipartOptions.defaults());
  }

  /**
   * Creates options with an explicit decoded-pair limit and default multipart configuration.
   *
   * @param host bind address
   * @param port listening port
   * @param maxRequestBytes maximum buffered request-body size
   * @param maxResponseBytes maximum staged response size
   * @param streamBufferBytes streaming buffer capacity
   * @param idleTimeoutMillis connection idle timeout
   * @param maxParameters maximum decoded pairs per query or URL-encoded form
   */
  public Options(
      String host,
      int port,
      int maxRequestBytes,
      int maxResponseBytes,
      int streamBufferBytes,
      long idleTimeoutMillis,
      int maxParameters) {
    this(
        host,
        port,
        maxRequestBytes,
        maxResponseBytes,
        streamBufferBytes,
        idleTimeoutMillis,
        maxParameters,
        MultipartOptions.defaults());
  }

  /**
   * Creates the initial server configuration.
   *
   * @return loopback defaults with bounded input, finite output and stream buffering
   */
  public static Options defaults() {
    return new Options("127.0.0.1", 8080, 1_048_576, 1_048_576, 8192, 30_000);
  }

  /**
   * Copies these options with a different listening port.
   *
   * @param value port, or zero for an ephemeral port
   * @return updated immutable options
   */
  public Options withPort(int value) {
    return new Options(
        host,
        value,
        maxRequestBytes,
        maxResponseBytes,
        streamBufferBytes,
        idleTimeoutMillis,
        maxParameters,
        multipart);
  }

  /**
   * Copies these options with a different decoded pair limit, applied separately to queries and
   * forms.
   *
   * @param value positive maximum number of pairs, including repetitions
   * @return updated immutable options
   */
  public Options withMaxParameters(int value) {
    return new Options(
        host,
        port,
        maxRequestBytes,
        maxResponseBytes,
        streamBufferBytes,
        idleTimeoutMillis,
        value,
        multipart);
  }

  /**
   * Copies these options with a multipart resource configuration.
   *
   * @param value multipart parser limits and temporary storage settings
   * @return copied server options
   */
  public Options withMultipart(MultipartOptions value) {
    return new Options(
        host,
        port,
        maxRequestBytes,
        maxResponseBytes,
        streamBufferBytes,
        idleTimeoutMillis,
        maxParameters,
        value);
  }
}
