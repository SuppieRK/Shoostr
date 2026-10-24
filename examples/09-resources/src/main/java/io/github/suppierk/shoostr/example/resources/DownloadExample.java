package io.github.suppierk.shoostr.example.resources;

import io.github.suppierk.shoostr.Handler;
import io.github.suppierk.shoostr.Shoostr;
import java.nio.file.Path;
import java.util.Objects;

/** Sends an application-selected report as a file or attachment. */
public final class DownloadExample {
  /** Prevents construction of the factory. */
  private DownloadExample() {}

  /**
   * Creates an unstarted application without taking ownership of its report file.
   *
   * @param report existing report kept available through transfer completion
   * @return unstarted application
   */
  public static Shoostr application(Path report) {
    Objects.requireNonNull(report);
    // Both handlers borrow an application-selected file, not a path taken from request input.
    Handler inline = (ignored, response) -> response.file(report, "text/csv; charset=utf-8");
    // Attachment output adds a download filename while serving the same report bytes.
    Handler attachment =
        (ignored2, response) ->
            response.attachment(report, "text/csv; charset=utf-8", "résumé.csv");
    return new Shoostr()
        .routes(
            routes ->
                // HEAD is registered explicitly and uses the same representation metadata as GET.
                routes
                    .get("/file", inline)
                    .head("/file", inline)
                    .get("/download", attachment)
                    .head("/download", attachment));
  }
}
