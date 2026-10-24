package io.github.suppierk.shoostr.example.streaming;

import io.github.suppierk.shoostr.Shoostr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Composes one file-and-stream example without managing a second server. */
public final class StreamingExample {
  private static final Logger LOGGER = LoggerFactory.getLogger(StreamingExample.class);

  /** Prevents construction of the launcher. */
  private StreamingExample() {}

  /**
   * Starts the standalone example using the framework's existing shutdown hook.
   *
   * @param args unused command-line arguments
   * @throws Exception if startup fails
   */
  public static void main(String[] args) throws Exception {
    var app = application();
    app.start();
    LOGGER.info("Assets and streams: http://127.0.0.1:{}/assets/greeting.txt", app.port());
  }

  /**
   * Returns an unstarted app whose lifecycle belongs to its caller.
   *
   * <p>The app owns its installed resources.
   *
   * @return app with file and stream handlers
   */
  public static Shoostr application() {
    return new Shoostr()
        .routes(
            routes -> {
              // Shoostr serves only this classpath directory; /assets is its public URL prefix.
              routes.classpathResources("/assets", "/public");
              routes.get(
                  "/download",
                  (ignored, response) -> {
                    // Shoostr's response stream writes incrementally; flush sends the first chunk.
                    var output = response.startStream("text/csv; charset=utf-8");
                    output.write("name\n").flush();
                    output.write("Ada\n");
                    output.write("Grace\n");
                  });
            });
  }
}
