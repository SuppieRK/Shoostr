package io.github.suppierk.shoostr;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Mutable application serving settings with defaults initialized on each field. Null setter inputs
 * restore property defaults. Configure an instance before passing it to {@link Shoostr}; the
 * application captures a private snapshot at construction. Later mutations affect only subsequent
 * applications. Do not mutate options or their multipart settings concurrently with application
 * construction. Native callbacks override the initial transport settings.
 *
 * <p>Host names are resolved at listener startup. The idle timeout measures connection inactivity;
 * the shutdown timeout controls server drain, not total application shutdown. HTTP/2 uses ALPN with
 * TLS and h2c without TLS. Endpoint executors and native pool configuration take precedence over
 * the virtual-thread selection. The application owns its virtual executor; supplied executors stay
 * caller-owned. Response gzip does not enable request decompression.
 */
public final class Options {
  private String host;
  private Integer port;
  private Integer maxRequestBytes;
  private Integer maxResponseBytes;
  private Integer maxRequestHeaderBytes;
  private Duration idleTimeout;
  private Duration shutdownTimeout;
  private Boolean http2;
  private Boolean compression;
  private Integer maxParameters;
  private MultipartOptions multipart;
  private Integer streamBufferBytes;
  private Boolean virtualThreads;

  /** Creates settings with the default listener, limits, protocols and execution mode. */
  public Options() {
    this.host = "0.0.0.0";
    this.port = 8080;
    this.maxRequestBytes = 1_048_576;
    this.maxResponseBytes = 1_048_576;
    this.maxRequestHeaderBytes = 8192;
    this.idleTimeout = Duration.ofSeconds(30);
    this.shutdownTimeout = Duration.ofSeconds(5);
    this.http2 = false;
    this.compression = false;
    this.maxParameters = 1000;
    this.multipart = new MultipartOptions();
    this.streamBufferBytes = 8192;
    this.virtualThreads = false;
  }

  /**
   * Captures validated settings without retaining a caller's mutable configuration.
   *
   * @param source settings copied before application construction proceeds
   */
  Options(Options source) {
    this.host = source.getHost();
    this.port = source.getPort();
    this.maxRequestBytes = source.getMaxRequestBytes();
    this.maxResponseBytes = source.getMaxResponseBytes();
    this.maxRequestHeaderBytes = source.getMaxRequestHeaderBytes();
    this.idleTimeout = source.getIdleTimeout();
    this.shutdownTimeout = source.getShutdownTimeout();
    this.http2 = source.getHttp2();
    this.compression = source.getCompression();
    this.maxParameters = source.getMaxParameters();
    this.multipart = new MultipartOptions(source.getMultipart());
    this.streamBufferBytes = source.getStreamBufferBytes();
    this.virtualThreads = source.getVirtualThreads();
  }

  /**
   * Rejects durations that cannot represent a positive whole-millisecond transport timeout.
   *
   * @param value resolved nonnull timeout
   * @throws IllegalArgumentException if precision, sign or range is invalid
   */
  private static void validateTimeout(Duration value) {
    if (!value.equals(value.truncatedTo(ChronoUnit.MILLIS))) {
      throw new IllegalArgumentException("Timeouts must be positive whole milliseconds");
    }

    try {
      if (value.toMillis() < 1) {
        throw new IllegalArgumentException("Timeouts must be positive whole milliseconds");
      }
    } catch (ArithmeticException failure) {
      throw new IllegalArgumentException(
          "Timeout exceeds the supported millisecond range", failure);
    }
  }

  /**
   * Reads the host setting.
   *
   * @return listener bind address or host name
   */
  public String getHost() {
    return host;
  }

  /**
   * Sets the host setting for subsequent application construction.
   *
   * @param value listener bind address or host name; null restores the default
   * @throws IllegalArgumentException if the value is invalid; the previous value is retained
   */
  public void setHost(@Nullable String value) {
    final var resolved = value == null ? "0.0.0.0" : value;
    if (resolved.isBlank()) {
      throw new IllegalArgumentException("Listener host must be nonblank");
    }

    host = resolved;
  }

  /**
   * Reads the port setting.
   *
   * @return listener port, from 0 to 65535; zero selects an available port
   */
  public Integer getPort() {
    return port;
  }

  /**
   * Sets the port setting for subsequent application construction.
   *
   * @param value listener port, from 0 to 65535; zero selects an available port; null restores the
   *     default
   * @throws IllegalArgumentException if the value is invalid; the previous value is retained
   */
  public void setPort(@Nullable Integer value) {
    final var resolved = value == null ? 8080 : value;
    if (resolved < 0 || resolved > 65535) {
      throw new IllegalArgumentException("Listener port must be between 0 and 65535");
    }

    port = resolved;
  }

  /**
   * Reads the maxRequestBytes setting.
   *
   * @return maximum buffered request-body bytes, positive and below Integer.MAX_VALUE
   */
  public Integer getMaxRequestBytes() {
    return maxRequestBytes;
  }

  /**
   * Sets the maxRequestBytes setting for subsequent application construction.
   *
   * @param value maximum buffered request-body bytes, positive and below Integer.MAX_VALUE; null
   *     restores the default
   * @throws IllegalArgumentException if the value is invalid; the previous value is retained
   */
  public void setMaxRequestBytes(@Nullable Integer value) {
    final var resolved = value == null ? 1_048_576 : value;
    if (resolved < 1 || resolved == Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Invalid buffered request limit");
    }

    maxRequestBytes = resolved;
  }

  /**
   * Reads the maxResponseBytes setting.
   *
   * @return positive maximum staged finite-response bytes
   */
  public Integer getMaxResponseBytes() {
    return maxResponseBytes;
  }

  /**
   * Sets the maxResponseBytes setting for subsequent application construction.
   *
   * @param value positive maximum staged finite-response bytes; null restores the default
   * @throws IllegalArgumentException if the value is invalid; the previous value is retained
   */
  public void setMaxResponseBytes(@Nullable Integer value) {
    final var resolved = value == null ? 1_048_576 : value;
    if (resolved < 1) {
      throw new IllegalArgumentException("Maximum response bytes must be positive");
    }

    maxResponseBytes = resolved;
  }

  /**
   * Reads the maxRequestHeaderBytes setting.
   *
   * @return positive maximum request-header bytes enforced by the HTTP parser
   */
  public Integer getMaxRequestHeaderBytes() {
    return maxRequestHeaderBytes;
  }

  /**
   * Sets the maxRequestHeaderBytes setting for subsequent application construction.
   *
   * @param value positive maximum request-header bytes enforced by the HTTP parser; null restores
   *     the default
   * @throws IllegalArgumentException if the value is invalid; the previous value is retained
   */
  public void setMaxRequestHeaderBytes(@Nullable Integer value) {
    final var resolved = value == null ? 8192 : value;
    if (resolved < 1) {
      throw new IllegalArgumentException("Maximum request header bytes must be positive");
    }

    maxRequestHeaderBytes = resolved;
  }

  /**
   * Reads the idleTimeout setting.
   *
   * @return positive whole-millisecond connection inactivity timeout
   */
  public Duration getIdleTimeout() {
    return idleTimeout;
  }

  /**
   * Sets the idleTimeout setting for subsequent application construction.
   *
   * @param value positive whole-millisecond connection inactivity timeout; null restores the
   *     default
   * @throws IllegalArgumentException if the value is invalid; the previous value is retained
   */
  public void setIdleTimeout(@Nullable Duration value) {
    final var resolved = value == null ? Duration.ofSeconds(30) : value;
    validateTimeout(resolved);

    idleTimeout = resolved;
  }

  /**
   * Reads the shutdownTimeout setting.
   *
   * @return positive whole-millisecond server drain timeout
   */
  public Duration getShutdownTimeout() {
    return shutdownTimeout;
  }

  /**
   * Sets the shutdownTimeout setting for subsequent application construction.
   *
   * @param value positive whole-millisecond server drain timeout; null restores the default
   * @throws IllegalArgumentException if the value is invalid; the previous value is retained
   */
  public void setShutdownTimeout(@Nullable Duration value) {
    final var resolved = value == null ? Duration.ofSeconds(5) : value;
    validateTimeout(resolved);

    shutdownTimeout = resolved;
  }

  /**
   * Reads the http2 setting.
   *
   * @return whether to support HTTP/2 with HTTP/1.1 fallback
   */
  public Boolean getHttp2() {
    return http2;
  }

  /**
   * Sets the http2 setting for subsequent application construction.
   *
   * @param value whether to support HTTP/2 with HTTP/1.1 fallback; null restores the default
   */
  public void setHttp2(@Nullable Boolean value) {
    http2 = value != null && value;
  }

  /**
   * Reads the compression setting.
   *
   * @return whether eligible responses use gzip, without request decompression
   */
  public Boolean getCompression() {
    return compression;
  }

  /**
   * Sets the compression setting for subsequent application construction.
   *
   * @param value whether eligible responses use gzip, without request decompression; null restores
   *     the default
   */
  public void setCompression(@Nullable Boolean value) {
    compression = value != null && value;
  }

  /**
   * Reads the maxParameters setting.
   *
   * @return positive decoded pair limit per query or URL-encoded form
   */
  public Integer getMaxParameters() {
    return maxParameters;
  }

  /**
   * Sets the maxParameters setting for subsequent application construction.
   *
   * @param value positive decoded pair limit per query or URL-encoded form; null restores the
   *     default
   * @throws IllegalArgumentException if the value is invalid; the previous value is retained
   */
  public void setMaxParameters(@Nullable Integer value) {
    final var resolved = value == null ? 1000 : value;
    if (resolved < 1) {
      throw new IllegalArgumentException("Maximum parameter count must be positive");
    }

    maxParameters = resolved;
  }

  /**
   * Reads the multipart setting.
   *
   * @return mutable multipart settings used by subsequent application construction
   */
  public MultipartOptions getMultipart() {
    return multipart;
  }

  /**
   * Sets the multipart setting for subsequent application construction.
   *
   * @param value caller-owned mutable multipart settings; null creates fresh defaults
   * @throws IllegalArgumentException if the value is invalid; the previous value is retained
   */
  public void setMultipart(@Nullable MultipartOptions value) {
    multipart = value == null ? new MultipartOptions() : value;
  }

  /**
   * Reads the streamBufferBytes setting.
   *
   * @return positive capacity of each streaming response buffer
   */
  public Integer getStreamBufferBytes() {
    return streamBufferBytes;
  }

  /**
   * Sets the streamBufferBytes setting for subsequent application construction.
   *
   * @param value positive capacity of each streaming response buffer; null restores the default
   * @throws IllegalArgumentException if the value is invalid; the previous value is retained
   */
  public void setStreamBufferBytes(@Nullable Integer value) {
    final var resolved = value == null ? 8192 : value;
    if (resolved < 1) {
      throw new IllegalArgumentException("Stream buffer bytes must be positive");
    }

    streamBufferBytes = resolved;
  }

  /**
   * Reads the virtualThreads setting.
   *
   * @return whether handlers and managed WebSocket callbacks use virtual threads
   */
  public Boolean getVirtualThreads() {
    return virtualThreads;
  }

  /**
   * Sets the virtualThreads setting for subsequent application construction.
   *
   * @param value whether handlers and managed WebSocket callbacks use virtual threads; null
   *     restores the default
   */
  public void setVirtualThreads(@Nullable Boolean value) {
    virtualThreads = value != null && value;
  }

  /**
   * Compares all current settings, including the multipart settings.
   *
   * @param value object to compare
   * @return whether both objects contain equal settings
   */
  @Override
  public boolean equals(@Nullable Object value) {
    if (this == value) {
      return true;
    }

    if (!(value instanceof Options other)) {
      return false;
    }

    return Objects.equals(host, other.host)
        && Objects.equals(port, other.port)
        && Objects.equals(maxRequestBytes, other.maxRequestBytes)
        && Objects.equals(maxResponseBytes, other.maxResponseBytes)
        && Objects.equals(maxRequestHeaderBytes, other.maxRequestHeaderBytes)
        && Objects.equals(idleTimeout, other.idleTimeout)
        && Objects.equals(shutdownTimeout, other.shutdownTimeout)
        && Objects.equals(http2, other.http2)
        && Objects.equals(compression, other.compression)
        && Objects.equals(maxParameters, other.maxParameters)
        && Objects.equals(multipart, other.multipart)
        && Objects.equals(streamBufferBytes, other.streamBufferBytes)
        && Objects.equals(virtualThreads, other.virtualThreads);
  }

  /**
   * Hashes all current settings, including the multipart settings.
   *
   * @return hash of the current settings
   */
  @Override
  public int hashCode() {
    return Objects.hash(
        host,
        port,
        maxRequestBytes,
        maxResponseBytes,
        maxRequestHeaderBytes,
        idleTimeout,
        shutdownTimeout,
        http2,
        compression,
        maxParameters,
        multipart,
        streamBufferBytes,
        virtualThreads);
  }
}
