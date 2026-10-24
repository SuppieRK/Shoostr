package io.github.suppierk.shoostr.example.bodies;

import io.github.suppierk.shoostr.Shoostr;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Calculates request checksums without retaining the complete body. */
public final class StreamingInputExample {
  /** Prevents construction of this example factory. */
  private StreamingInputExample() {}

  /**
   * Creates an unstarted application with an incremental SHA-256 endpoint.
   *
   * @return application owned by its caller
   */
  public static Shoostr application() {
    return new Shoostr()
        .routes(
            routes ->
                routes.post(
                    "/checksum",
                    (request, response) -> {
                      var digest = MessageDigest.getInstance("SHA-256");

                      // Selecting input() excludes buffered-body access and hashes incrementally.

                      try (var input = new DigestInputStream(request.input(), digest)) {
                        input.transferTo(OutputStream.nullOutputStream());
                      }

                      response.text(HexFormat.of().formatHex(digest.digest()));
                    }));
  }
}
