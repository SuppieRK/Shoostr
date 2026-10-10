package io.github.suppierk.shoostr;

/**
 * Application-provided finite byte/object conversion. Configure before registration and share
 * safely between concurrent requests, including requests on caller-owned executors. Shoostr neither
 * configures nor closes the codec and does not infer media types or schemas. Checked failures
 * retain their original identity for the application's existing exception handling.
 */
public interface Codec {
  /**
   * Decodes bounded, caller-owned bytes to the requested concrete class. The source is independent
   * of the framework's cached body; changing it cannot change a later body read. Each typed read
   * invokes this method again; decoded objects are not cached.
   *
   * @param source request body bytes
   * @param type concrete result class; parameterized types are not described by Class
   * @param <T> decoded result type
   * @return nonnull decoded value
   * @throws Exception if decoding fails; classify malformed input explicitly rather than turning
   *     unexpected failures into client errors
   */
  @SuppressWarnings("java:S112") // Match Handler's application-defined checked exception contract.
  <T> T read(byte[] source, Class<T> type) throws Exception;

  /**
   * Encodes a nonnull value to finite bytes. The framework copies accepted output and applies its
   * response-byte limit after this call; that limit cannot bound allocations inside the codec.
   *
   * @param value application representation
   * @return nonnull encoded bytes
   * @throws Exception if encoding fails, without automatic conversion to BadRequestException
   */
  @SuppressWarnings("java:S112") // Match Handler's application-defined checked exception contract.
  byte[] write(Object value) throws Exception;
}
