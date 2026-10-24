package io.github.suppierk.shoostr.example.streaming;

import io.github.suppierk.shoostr.Shoostr;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Sends finite output through the response's bounded stream. */
public final class FiniteStreamingExample {
  /** Prevents construction of the factory. */
  private FiniteStreamingExample() {}

  /**
   * Creates an unstarted application that borrows a caller-owned report.
   *
   * @param report readable report file owned by the caller
   * @return unstarted application
   */
  public static Shoostr application(Path report) {
    Objects.requireNonNull(report);
    return new Shoostr()
        .routes(
            routes ->
                routes
                    .get(
                        "/chunks",
                        (ignored, response) -> {
                          var output = response.startStream("text/csv; charset=utf-8");
                          // startStream commits headers; flush sends the first buffered chunk.
                          output.write("name\n").flush();
                          output.write("Ada\n");
                          output.write("Grace\n");
                        })
                    // response.input owns the newly opened stream through transfer completion.
                    .get(
                        "/input",
                        (ignored2, response) ->
                            response.input(
                                Files.newInputStream(report), "text/csv; charset=utf-8")));
  }
}
