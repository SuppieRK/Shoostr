package io.github.suppierk.shoostr.example.lifecycle;

import io.github.suppierk.shoostr.RequestOutcome;
import io.github.suppierk.shoostr.Shoostr;
import java.util.Objects;
import java.util.function.Consumer;

/** Observes request phases without retaining live request objects. */
public final class RequestHooksExample {
  /** Prevents construction of the factory. */
  private RequestHooksExample() {}

  /**
   * Creates an application reporting phases and terminal request summaries.
   *
   * @param phases nonblocking thread-safe phase sink
   * @param completed nonblocking thread-safe terminal sink
   * @return unstarted application
   */
  public static Shoostr application(Consumer<String> phases, Consumer<RequestOutcome> completed) {
    Objects.requireNonNull(phases);
    Objects.requireNonNull(completed);
    return new Shoostr()
        // Header admission can run before an endpoint is selected or for an unmatched request.
        .onRequestHeaders(
            (request, ignored) ->
                phases.accept("headers:" + request.routePattern().orElse("unmatched")))
        .onRouteMatched(
            (request, ignored2) ->
                phases.accept("app:matched:" + request.routePattern().orElseThrow()))
        .beforeRouteHandler((ignored3, ignored4) -> phases.accept("app:before"))
        .afterRouteHandler((ignored5, ignored6) -> phases.accept("app:after"))
        .beforeResponseFlush((ignored7, ignored8) -> phases.accept("app:before-flush"))
        .afterResponseFlush((ignored9, ignored10) -> phases.accept("app:after-flush"))
        .afterRequest(
            outcome -> {
              // This detached terminal summary remains usable after live request access ends.
              phases.accept("app:completed");
              completed.accept(outcome);
            })
        .routes(
            routes ->
                routes.path(
                    "/jobs",
                    jobs ->
                        jobs.get(
                                "/failure",
                                (ignored11, ignored12) -> {
                                  phases.accept("handler");
                                  throw new IllegalStateException("job failed");
                                })
                            .get(
                                "/{id}",
                                (request, response) -> {
                                  phases.accept("handler");
                                  response.text("job " + request.pathParam("id").orElseThrow());
                                }),
                    extensions ->
                        // These local phases compose with the application phases for /jobs only.
                        extensions
                            .lifecycle()
                            .onRouteMatched(
                                (ignored13, ignored14) -> phases.accept("local:matched"))
                            .beforeRouteHandler(
                                (ignored15, ignored16) -> phases.accept("local:before"))
                            .afterRouteHandler(
                                (ignored17, ignored18) -> phases.accept("local:after"))
                            .beforeResponseFlush(
                                (ignored19, ignored20) -> phases.accept("local:before-flush"))
                            .afterResponseFlush(
                                (ignored21, ignored22) -> phases.accept("local:after-flush"))
                            .afterRequest(ignored23 -> phases.accept("local:completed"))));
  }
}
