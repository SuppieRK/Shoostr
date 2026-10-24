package io.github.suppierk.shoostr.example.resources;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.StaticOptions;
import java.nio.file.Path;

/** Serves application-selected public resource directories. */
public final class StaticResourcesExample {
  /** Prevents construction of the example factory. */
  private StaticResourcesExample() {}

  /**
   * Creates an unstarted application that borrows the public directory's files.
   *
   * @param directory existing public filesystem directory
   * @return unstarted application
   */
  public static Shoostr application(Path directory) {
    return new Shoostr()
        .routes(
            routes ->
                // Mount only directories whose complete contents are safe to publish.
                routes
                    .staticFiles("/files", directory)
                    .classpathResources("/bundled", "/recipe-public")
                    .staticFiles(
                        "/app",
                        directory,
                        StaticOptions.defaults()
                            .withWelcomeFile("index.html")
                            .withSpaFallback("index.html"))
                    // This explicit endpoint wins over the resource mount and its SPA fallback.
                    .get("/app/version", (ignored, response) -> response.text("v1")));
  }
}
