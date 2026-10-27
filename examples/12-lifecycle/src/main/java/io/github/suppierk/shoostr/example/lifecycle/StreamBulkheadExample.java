package io.github.suppierk.shoostr.example.lifecycle;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.suppierk.shoostr.Shoostr;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/** Composes application-selected stream admission with HTTP execution. */
public final class StreamBulkheadExample {
  private static final String ADMISSION = StreamBulkheadExample.class.getName();

  /** Prevents construction of this example factory. */
  private StreamBulkheadExample() {}

  /**
   * Creates a public event endpoint and an independently available health endpoint.
   *
   * @param streams application-configured semaphore bulkhead
   * @param next application producer, invoked concurrently on admitted stream owners
   * @return unstarted application
   */
  public static Shoostr application(Bulkhead streams, Callable<String> next) {
    Objects.requireNonNull(streams);
    Objects.requireNonNull(next);
    return new Shoostr()
        .virtualThreads()
        .exception(
            BulkheadFullException.class,
            (failure, request, response) -> response.status(503).text("Stream capacity is busy"))
        .routes(
            routes ->
                routes
                    .get("/health", (request, response) -> response.text("healthy"))
                    .sse(
                        "/events",
                        (request, response) -> {
                          var output = response.startEventStream();
                          output.send("ready");
                          output.send(next.call());
                        },
                        extensions ->
                            extensions
                                .lifecycle()
                                .observe(
                                    request -> {
                                      var acquired = new AtomicBoolean();
                                      request.attribute(ADMISSION, acquired);
                                      // Completion includes the final framework write, beyond
                                      // handler return.
                                      return outcome -> {
                                        if (acquired.getAndSet(false)) {
                                          streams.onComplete();
                                        }
                                      };
                                    })
                                .beforeRouteHandler(
                                    (request, response) -> {
                                      var acquired =
                                          (AtomicBoolean)
                                              request.attribute(ADMISSION).orElseThrow();
                                      // Configure zero wait in the application to reject without
                                      // occupying a waiter.
                                      streams.acquirePermission();
                                      acquired.set(true);
                                    })));
  }
}
