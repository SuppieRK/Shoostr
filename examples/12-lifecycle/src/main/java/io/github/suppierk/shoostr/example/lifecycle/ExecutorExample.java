package io.github.suppierk.shoostr.example.lifecycle;

import io.github.suppierk.shoostr.RequestObservation;
import io.github.suppierk.shoostr.RequestOutcome;
import io.github.suppierk.shoostr.Shoostr;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** Selects a caller-owned executor and explicitly carries observation context. */
public final class ExecutorExample {
  /** Prevents construction of the factory. */
  private ExecutorExample() {}

  /**
   * Creates an application with an explicit execution mode and a borrowed endpoint worker pool.
   *
   * @param virtual whether to opt into virtual execution for handlers without an endpoint executor
   * @param workers asynchronous executor kept alive by its caller
   * @param context context slot restored on each owning thread
   * @param completed nonblocking thread-safe terminal sink
   * @return unstarted application
   */
  public static Shoostr application(
      boolean virtual,
      ExecutorService workers,
      ThreadLocal<String> context,
      Consumer<RequestOutcome> completed) {
    Objects.requireNonNull(workers);
    Objects.requireNonNull(context);
    Objects.requireNonNull(completed);
    var app =
        new Shoostr()
            .observe(
                request -> {
                  // Capture the request value before dispatching work to a selected executor.
                  var previous = context.get();
                  var captured = request.header("X-Request-Id").orElse("unassigned");
                  context.set(captured);
                  return new RequestObservation() {
                    /** {@inheritDoc} */
                    @Override
                    public AutoCloseable attach() {
                      // Restore each worker's own previous value when its attachment closes.
                      var workerPrevious = context.get();
                      context.set(captured);
                      return () -> restore(context, workerPrevious);
                    }

                    /** {@inheritDoc} */
                    @Override
                    public void close() {
                      // The observation's original thread has a separate context lifetime.
                      restore(context, previous);
                    }

                    /** {@inheritDoc} */
                    @Override
                    public void complete(RequestOutcome outcome) {
                      // Retain only the detached outcome, not the live request or response.
                      completed.accept(outcome);
                    }
                  };
                })
            .exception(
                RejectedExecutionException.class,
                (ignored, ignored2, response) -> response.status(503).text("busy:" + context.get()))
            .routes(
                routes ->
                    routes
                        .get(
                            "/default",
                            (ignored3, response) ->
                                response.text("virtual=" + Thread.currentThread().isVirtual()))
                        // Shoostr borrows this pool; keep it alive until the application has
                        // stopped.
                        .get(
                            "/selected",
                            workers,
                            (ignored4, response) ->
                                response.text(
                                    context.get() + "|" + Thread.currentThread().getName())));

    if (virtual) {
      // Opt in for ordinary handlers; the selected endpoint still uses its borrowed pool.
      app.virtualThreads();
    }

    return app;
  }

  /**
   * Restores a thread's previous context without leaving an empty thread-local entry.
   *
   * @param context context slot on the current thread
   * @param previous value read before this scope attached, possibly null
   */
  private static void restore(ThreadLocal<String> context, String previous) {
    if (previous == null) {
      context.remove();
    } else {
      context.set(previous);
    }
  }
}
