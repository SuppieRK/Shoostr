package io.github.suppierk.shoostr.example.resources;

import java.nio.file.Files;
import java.nio.file.Path;

/** Starts the standalone resources recipe. */
public final class ExampleApplication {
  /** Prevents construction of the launcher. */
  private ExampleApplication() {}

  /**
   * Starts the application; Shoostr owns its JVM shutdown hook.
   *
   * @param args unused command-line arguments
   * @throws IllegalArgumentException if the report override is blank or unreadable
   * @throws Exception if configuration or startup fails
   */
  public static void main(String[] args) throws Exception {
    // The image owns the default fixture; an override remains caller-owned.
    var configured = System.getenv("EXAMPLE_REPORT");
    if (configured != null && configured.isBlank()) {
      throw new IllegalArgumentException("EXAMPLE_REPORT must not be blank");
    }

    var report = Path.of(configured == null ? "/app/report.csv" : configured);
    if (!Files.isRegularFile(report) || !Files.isReadable(report)) {
      throw new IllegalArgumentException("Report must be a readable regular file: " + report);
    }

    DownloadExample.application(report).start();
  }
}
