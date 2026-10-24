package io.github.suppierk.shoostr.example.metrics;

import io.github.suppierk.shoostr.AccessLog;
import io.github.suppierk.shoostr.Shoostr;
import java.util.function.Consumer;

/** Emits safe terminal access summaries through an application-owned sink. */
public final class AccessLoggingExample {
  /** Prevents construction of the factory. */
  private AccessLoggingExample() {}

  /**
   * Creates an unstarted application with explicitly enabled access logging.
   *
   * @param sink nonblocking thread-safe log destination
   * @return unstarted application
   */
  public static Shoostr application(Consumer<String> sink) {
    var accessLog = new AccessLog(sink);
    return new Shoostr()
        // Terminal outcomes include failed and unmatched requests, not only successful handlers.
        .afterRequest(accessLog)
        .routes(
            routes ->
                routes
                    .get(
                        "/orders/failure",
                        (ignored, ignored2) -> {
                          throw new IllegalStateException("private internal failure");
                        })
                    .get("/orders/{id}", (ignored3, response) -> response.text("ready")));
  }
}
