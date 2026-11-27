package io.github.suppierk.shoostr.example.lifecycle;

import io.github.suppierk.shoostr.RequestOutcome;
import io.github.suppierk.shoostr.Shoostr;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Observes request phases without retaining live request objects.
 */
public final class RequestHooksExample {
    /**
     * Creates an application reporting phases and
     * terminal request summaries.
     *
     * @param phases    nonblocking thread-safe phase sink
     * @param completed nonblocking thread-safe terminal sink
     * @return unstarted application
     */
    public static Shoostr application(Consumer<String> phases, Consumer<RequestOutcome> completed) {
        Objects.requireNonNull(phases);
        Objects.requireNonNull(completed);

        return new Shoostr()
            // Header admission can run before an endpoint is selected
            // or for an unmatched request.
            .onRequestHeaders((request, response) -> phases
                .accept("headers:" + request.routePattern()
                    .orElse("unmatched")))
            .onRouteMatched((request, response) -> phases
                .accept("app:matched:" + request.routePattern()
                    .orElseThrow()))
            .beforeRouteHandler((request, response) -> phases
                .accept("app:before"))
            .afterRouteHandler((request, response) -> phases
                .accept("app:after"))
            .beforeResponseFlush((request, response) -> phases
                .accept("app:before-flush"))
            .afterResponseFlush((request, response) -> phases
                .accept("app:after-flush"))
            .afterRequest(outcome -> {
                // This detached terminal summary remains usable
                // after live request access ends.
                phases.accept("app:completed");
                completed.accept(outcome);
            })
            .routes(routes -> routes
                .path(
                    "/jobs",
                    jobs -> jobs
                        .get("/failure", (request, response) -> {
                            phases.accept("handler");
                            throw new IllegalStateException("job failed");
                        })
                        .get("/{id}", (request, response) -> {
                            phases.accept("handler");
                            response.text("job " + request
                                .pathParam("id").orElseThrow());
                        }),
                    lifecycle -> lifecycle
                        // These local phases compose with the application
                        // phases for /jobs only.
                        .onRouteMatched(
                            (request, response) -> phases
                                .accept("local:matched"))
                        .beforeRouteHandler(
                            (request, response) -> phases
                                .accept("local:before"))
                        .afterRouteHandler(
                            (request, response) -> phases
                                .accept("local:after"))
                        .beforeResponseFlush(
                            (request, response) -> phases
                                .accept("local:before-flush"))
                        .afterResponseFlush(
                            (request, response) -> phases
                                .accept("local:after-flush"))
                        .afterRequest(
                            requestOutcome -> phases
                                .accept("local:completed"))));
    }
}
