package io.github.suppierk.shoostr.example.lifecycle;

import io.github.suppierk.shoostr.Shoostr;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Signals waiting requests before disposing application-owned resources. */
public final class ShutdownExample {
  /** Prevents construction of the factory. */
  private ShutdownExample() {}

  /**
   * Creates an unstarted application with a final resource cleanup callback.
   *
   * @param dispose final cleanup after framework shutdown
   * @return unstarted application
   */
  public static Shoostr application(Runnable dispose) {
    Objects.requireNonNull(dispose);
    var stopping = new CountDownLatch(1);
    return new Shoostr()
        // Wake waiting handlers before shutdown waits for their exchanges to finish.
        .beforeClose(stopping::countDown)
        // Run final application cleanup after framework shutdown, not during request draining.
        .afterClose(dispose)
        .routes(
            routes ->
                routes
                    .get("/ready", (ignored, response) -> response.text("ready"))
                    .get(
                        "/wait-for-shutdown",
                        (ignored2, response) -> {
                          if (stopping.await(30, TimeUnit.SECONDS)) {
                            response.text("stopping");
                          } else {
                            response.status(204);
                          }
                        }));
  }
}
