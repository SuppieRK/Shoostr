package io.github.suppierk.shoostr;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.jspecify.annotations.Nullable;

/**
 * Mutable application serving settings with defaults initialized on each field. Null setter inputs
 * restore property defaults. Configure an instance before passing it to {@link Shoostr}; the
 * application captures a private snapshot at construction. Later mutations affect only subsequent
 * applications. Do not mutate options concurrently with application construction. Native callbacks
 * override the initial transport settings.
 *
 * <p>Host names are resolved at listener startup. The idle timeout measures connection inactivity;
 * the shutdown timeout controls server drain, not total application shutdown. HTTP/2 uses ALPN with
 * TLS and h2c without TLS. Endpoint executors and native pool configuration take precedence over
 * the virtual-thread selection. The application owns its virtual executor; supplied executors stay
 * caller-owned. Response gzip does not enable request decompression.
 */
public final class Options {
  private String host = "0.0.0.0";
  private Integer port = 8080;
  private Integer maxRequestBytes = 1_048_576;
  private Integer maxResponseBytes = 1_048_576;
  private Integer maxRequestHeaderBytes = 8192;
  private Duration idleTimeout = Duration.ofSeconds(30);
  private Duration shutdownTimeout = Duration.ofSeconds(5);
  private Boolean http2 = false;
  private Boolean compression = false;
  private Integer maxParameters = 1000;
  private MultipartOptions multipart = MultipartOptions.defaults();
  private Integer streamBufferBytes = 8192;
  private Boolean virtualThreads = false;

  /** Creates settings with the default listener, limits, protocols and execution mode. */
  public Options() {}

  /**
   * Captures validated settings without retaining a caller's mutable configuration.
   *
   * @param source settings copied before application construction proceeds
   */
  Options(Options source) {
    setHost(source.getHost());
    setPort(source.getPort());
    setMaxRequestBytes(source.getMaxRequestBytes());
    setMaxResponseBytes(source.getMaxResponseBytes());
    setMaxRequestHeaderBytes(source.getMaxRequestHeaderBytes());
    setIdleTimeout(source.getIdleTimeout());
    setShutdownTimeout(source.getShutdownTimeout());
    setHttp2(source.getHttp2());
    setCompression(source.getCompression());
    setMaxParameters(source.getMaxParameters());
    setMultipart(source.getMultipart());
    setStreamBufferBytes(source.getStreamBufferBytes());
    setVirtualThreads(source.getVirtualThreads());
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
    String resolved = value == null ? "0.0.0.0" : value;
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
    int resolved = value == null ? 8080 : value;
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
    int resolved = value == null ? 1_048_576 : value;
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
    int resolved = value == null ? 1_048_576 : value;
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
    int resolved = value == null ? 8192 : value;
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
    Duration resolved = value == null ? Duration.ofSeconds(30) : value;
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
    Duration resolved = value == null ? Duration.ofSeconds(5) : value;
    validateTimeout(resolved);

    shutdownTimeout = resolved;
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
    int resolved = value == null ? 1000 : value;
    if (resolved < 1) {
      throw new IllegalArgumentException("Maximum parameter count must be positive");
    }

    maxParameters = resolved;
  }

  /**
   * Reads the multipart setting.
   *
   * @return multipart parser and temporary storage settings
   */
  public MultipartOptions getMultipart() {
    return multipart;
  }

  /**
   * Sets the multipart setting for subsequent application construction.
   *
   * @param value multipart parser and temporary storage settings; null restores the default
   * @throws IllegalArgumentException if the value is invalid; the previous value is retained
   */
  public void setMultipart(@Nullable MultipartOptions value) {
    multipart = value == null ? MultipartOptions.defaults() : value;
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
    int resolved = value == null ? 8192 : value;
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
}
