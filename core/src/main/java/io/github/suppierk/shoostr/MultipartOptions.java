package io.github.suppierk.shoostr;

import java.nio.file.Path;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Mutable resource limits and temporary storage settings for multipart request parsing. Configure
 * these settings before application construction; {@link Shoostr} captures a private copy through
 * {@link Options}. Later mutations affect only subsequent applications. Do not mutate these
 * settings concurrently with application construction.
 *
 * <p>Limits apply to each parsed request: {@code maxBytes} bounds the aggregate body, {@code
 * maxFileBytes} bounds each part, {@code maxParts} bounds all fields and files, and {@code
 * maxPartHeadersBytes} bounds one part's headers. Parts above {@code maxMemoryPartBytes} use
 * temporary storage. A null temporary directory uses the JVM temporary-file directory.
 *
 * <p>Null setter inputs restore property defaults. Every mutation must preserve {@code 1 <=
 * maxFileBytes <= maxBytes}. Lower the file limit before lowering the aggregate below it; raise the
 * aggregate before raising the file limit above it. Invalid changes, including incompatible default
 * resets, leave the previous value intact.
 */
public final class MultipartOptions {
  private Long maxBytes;
  private Long maxFileBytes;
  private Integer maxParts;
  private Integer maxPartHeadersBytes;
  private Long maxMemoryPartBytes;
  private @Nullable Path temporaryDirectory;

  /**
   * Creates defaults of 10 MiB aggregate, 5 MiB per file, 100 parts, 8 KiB part headers and 16 KiB
   * in memory, using the JVM temporary-file directory.
   */
  public MultipartOptions() {
    this.maxBytes = 10_485_760L;
    this.maxFileBytes = 5_242_880L;
    this.maxParts = 100;
    this.maxPartHeadersBytes = 8192;
    this.maxMemoryPartBytes = 16_384L;
    this.temporaryDirectory = null;
  }

  /**
   * Captures settings without retaining a caller's mutable configuration.
   *
   * @param source validated settings copied during application construction
   */
  MultipartOptions(MultipartOptions source) {
    this.maxBytes = source.getMaxBytes();
    this.maxFileBytes = source.getMaxFileBytes();
    this.maxParts = source.getMaxParts();
    this.maxPartHeadersBytes = source.getMaxPartHeadersBytes();
    this.maxMemoryPartBytes = source.getMaxMemoryPartBytes();
    this.temporaryDirectory = source.getTemporaryDirectory();
  }

  /**
   * Reads the aggregate multipart request limit.
   *
   * @return positive maximum aggregate bytes, at least the per-file limit
   */
  public Long getMaxBytes() {
    return maxBytes;
  }

  /**
   * Sets the aggregate multipart request limit for subsequent application construction.
   *
   * @param value positive maximum aggregate bytes, at least the per-file limit; null restores 10
   *     MiB
   * @throws IllegalArgumentException if the resolved limit is invalid; the previous value is
   *     retained
   */
  public void setMaxBytes(@Nullable Long value) {
    final var resolved = value == null ? 10_485_760L : value;
    if (resolved < 1 || resolved < maxFileBytes) {
      throw new IllegalArgumentException("Invalid multipart options");
    }

    maxBytes = resolved;
  }

  /**
   * Reads the multipart per-file limit.
   *
   * @return positive maximum bytes per part, no greater than the aggregate limit
   */
  public Long getMaxFileBytes() {
    return maxFileBytes;
  }

  /**
   * Sets the multipart per-file limit for subsequent application construction.
   *
   * @param value positive maximum bytes per part, no greater than the aggregate limit; null
   *     restores 5 MiB
   * @throws IllegalArgumentException if the resolved limit is invalid; the previous value is
   *     retained
   */
  public void setMaxFileBytes(@Nullable Long value) {
    final var resolved = value == null ? 5_242_880L : value;
    if (resolved < 1 || resolved > maxBytes) {
      throw new IllegalArgumentException("Invalid multipart options");
    }

    maxFileBytes = resolved;
  }

  /**
   * Reads the combined multipart field and file count limit.
   *
   * @return positive maximum part count
   */
  public Integer getMaxParts() {
    return maxParts;
  }

  /**
   * Sets the combined multipart field and file count limit for subsequent application construction.
   *
   * @param value positive maximum part count; null restores 100
   * @throws IllegalArgumentException if the value is invalid; the previous value is retained
   */
  public void setMaxParts(@Nullable Integer value) {
    final var resolved = value == null ? 100 : value;
    if (resolved < 1) {
      throw new IllegalArgumentException("Invalid multipart options");
    }

    maxParts = resolved;
  }

  /**
   * Reads the multipart header limit.
   *
   * @return positive maximum header bytes per part
   */
  public Integer getMaxPartHeadersBytes() {
    return maxPartHeadersBytes;
  }

  /**
   * Sets the multipart header limit for subsequent application construction.
   *
   * @param value positive maximum header bytes per part; null restores 8 KiB
   * @throws IllegalArgumentException if the value is invalid; the previous value is retained
   */
  public void setMaxPartHeadersBytes(@Nullable Integer value) {
    final var resolved = value == null ? 8192 : value;
    if (resolved < 1) {
      throw new IllegalArgumentException("Invalid multipart options");
    }

    maxPartHeadersBytes = resolved;
  }

  /**
   * Reads the multipart memory threshold.
   *
   * @return nonnegative maximum bytes held in memory per part
   */
  public Long getMaxMemoryPartBytes() {
    return maxMemoryPartBytes;
  }

  /**
   * Sets the multipart memory threshold for subsequent application construction.
   *
   * @param value nonnegative maximum bytes held in memory per part; null restores 16 KiB
   * @throws IllegalArgumentException if the value is invalid; the previous value is retained
   */
  public void setMaxMemoryPartBytes(@Nullable Long value) {
    final var resolved = value == null ? 16_384L : value;
    if (resolved < 0) {
      throw new IllegalArgumentException("Invalid multipart options");
    }

    maxMemoryPartBytes = resolved;
  }

  /**
   * Reads the temporary storage directory.
   *
   * @return temporary-file directory, or null for the JVM directory
   */
  public @Nullable Path getTemporaryDirectory() {
    return temporaryDirectory;
  }

  /**
   * Sets the temporary storage directory for subsequent application construction.
   *
   * @param value temporary-file directory, or null for the JVM directory
   */
  public void setTemporaryDirectory(@Nullable Path value) {
    temporaryDirectory = value;
  }

  /**
   * Compares all current multipart limits and the temporary directory.
   *
   * @param value object to compare
   * @return whether both objects contain equal settings
   */
  @Override
  public boolean equals(@Nullable Object value) {
    if (this == value) {
      return true;
    }

    if (!(value instanceof MultipartOptions other)) {
      return false;
    }

    return Objects.equals(maxBytes, other.maxBytes)
        && Objects.equals(maxFileBytes, other.maxFileBytes)
        && Objects.equals(maxParts, other.maxParts)
        && Objects.equals(maxPartHeadersBytes, other.maxPartHeadersBytes)
        && Objects.equals(maxMemoryPartBytes, other.maxMemoryPartBytes)
        && Objects.equals(temporaryDirectory, other.temporaryDirectory);
  }

  /**
   * Hashes all current multipart limits and the temporary directory.
   *
   * @return hash of the current settings
   */
  @Override
  public int hashCode() {
    return Objects.hash(
        maxBytes,
        maxFileBytes,
        maxParts,
        maxPartHeadersBytes,
        maxMemoryPartBytes,
        temporaryDirectory);
  }
}
