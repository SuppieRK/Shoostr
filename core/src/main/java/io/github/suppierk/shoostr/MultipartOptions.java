package io.github.suppierk.shoostr;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Finite resource limits and temporary storage settings for multipart request parsing.
 *
 * <p>Limits apply to each parsed request: {@code maxBytes} bounds the aggregate body, {@code
 * maxFileBytes} bounds each part, {@code maxParts} bounds all fields and files, and {@code
 * maxPartHeadersBytes} bounds one part's headers. Parts above {@code maxMemoryPartBytes} use
 * temporary storage. A null temporary directory uses the JVM temporary-file directory.
 *
 * @param maxBytes maximum aggregate request bytes
 * @param maxFileBytes maximum bytes per file part
 * @param maxParts maximum number of fields and files
 * @param maxPartHeadersBytes maximum header bytes per part
 * @param maxMemoryPartBytes maximum bytes held in memory per part
 * @param temporaryDirectory optional directory for spilled parts
 */
public record MultipartOptions(
    long maxBytes,
    long maxFileBytes,
    int maxParts,
    int maxPartHeadersBytes,
    long maxMemoryPartBytes,
    @Nullable Path temporaryDirectory) {
  /** Validates finite multipart resource limits. */
  public MultipartOptions {
    if (maxBytes < 1
        || maxFileBytes < 1
        || maxFileBytes > maxBytes
        || maxParts < 1
        || maxPartHeadersBytes < 1
        || maxMemoryPartBytes < 0) {
      throw new IllegalArgumentException("Invalid multipart options");
    }
  }

  /**
   * Creates the finite multipart defaults: 10 MiB aggregate, 5 MiB per file, 100 parts, 8 KiB part
   * headers, and 16 KiB in memory.
   *
   * @return finite default parser limits
   */
  public static MultipartOptions defaults() {
    return new MultipartOptions(10_485_760, 5_242_880, 100, 8192, 16_384, null);
  }

  /**
   * Copies these options with a temporary file directory. A null value restores the JVM directory.
   *
   * @param value temporary-file directory, or null for the JVM directory
   * @return copied multipart options
   */
  public MultipartOptions withTemporaryDirectory(@Nullable Path value) {
    return new MultipartOptions(
        maxBytes, maxFileBytes, maxParts, maxPartHeadersBytes, maxMemoryPartBytes, value);
  }
}
