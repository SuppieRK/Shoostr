package io.github.suppierk.shoostr.example.resources;

import io.github.suppierk.shoostr.Shoostr;
import java.nio.file.Path;
import java.util.Objects;

/** Serves one fixed file revision with HTTP validators and range handling. */
public final class FileCachingExample {
  /** Prevents construction of the factory. */
  private FileCachingExample() {}

  /**
   * Creates an unstarted application for a file that remains unchanged while served.
   *
   * @param file readable fixed-revision file owned by the caller
   * @return unstarted application
   */
  public static Shoostr application(Path file) {
    Objects.requireNonNull(file);
    return new Shoostr()
        .routes(
            routes ->
                routes.get(
                    "/file",
                    (ignored, response) ->
                        // This ETag identifies the fixed file revision; file() handles conditions
                        // and ranges.
                        response.setHeader("ETag", "\"revision-1\"").file(file, "text/plain")));
  }
}
