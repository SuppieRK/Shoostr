package io.github.suppierk.shoostr.example.sse;

import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.SseEvent;
import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpStatusCodes;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Demonstrates server-sent events using Shoostr's core streaming methods. */
public final class SseExample {
  private static final Logger LOGGER = LoggerFactory.getLogger(SseExample.class);

  /** Prevents construction of the launcher. */
  private SseExample() {}

  /**
   * Starts the example; Shoostr owns its JVM shutdown hook.
   *
   * @throws Exception if startup fails
   */
  static void main() throws Exception {
    var app = application();
    app.start();
    LOGGER.info("SSE examples: http://127.0.0.1:{}/", app.port());
  }

  /**
   * Returns an unstarted app owned by its caller.
   *
   * @return configured application
   */
  public static Shoostr application() {
    // This example-local dependency deliberately fails to demonstrate an error after commitment.
    return application(
        () -> {
          throw new IllegalStateException("Example-only report failure details");
        });
  }

  /**
   * Configures the app with caller-supplied report work, not a framework-managed job.
   *
   * <p>S2095 is suppressed because the returned app transfers to the caller, who owns its
   * lifecycle; closing it here would return an unusable app. The report operation remains
   * caller-owned.
   *
   * @param report caller-owned, concurrent-safe operation returning nonnull text
   * @return unstarted app owned by the caller
   * @throws NullPointerException if the operation is absent
   */
  @SuppressWarnings("java:S2095")
  public static Shoostr application(Supplier<String> report) {
    Objects.requireNonNull(report);
    return new Shoostr()
        // App-wide exception handling can return an HTTP error only before streaming is committed.
        .exception(
            IllegalStateException.class,
            (_, _, response) ->
                response
                    .status(HttpStatusCodes.INTERNAL_SERVER_ERROR.value())
                    .text("Stream unavailable"))
        .routes(
            routes -> {
              // This plain-text page gives browser-console clients the server's own origin.
              routes.get(
                  "/",
                  (_, response) ->
                      response.text(
                          "SSE endpoints: /events, /progress, /replay, /unavailable, /reports"));
              // Shoostr's core SSE route commits an event stream; returning finishes this finite
              // feed.
              routes.sse(
                  "/events",
                  (_, response) -> {
                    var events = response.startEventStream();
                    events.send("starting");
                    events.send("complete");
                  });
              // SseEvent is a core value type for names, IDs and retry hints, not a JSON codec.
              routes.sse(
                  "/progress",
                  (_, response) -> {
                    var events = response.startEventStream();
                    // Comments and heartbeats keep the connection active without dispatching data
                    // events.
                    events.comment("connection ready");
                    events.heartbeat();
                    events.send(
                        SseEvent.of("Preparing\nWaiting")
                            .withEvent("progress")
                            .withId("1")
                            .withRetry(Duration.ofSeconds(2)));
                    events.send(SseEvent.of("Complete").withEvent("done").withId("2"));
                  });
              // This fixed three-entry feed demonstrates replay, not durable message storage.
              routes.sse(
                  "/replay",
                  (request, response) -> {
                    // Last-Event-ID comes from the reconnecting client; validate before committing
                    // output.
                    int last = lastEventId(request);

                    // HTTP 204 tells EventSource not to reconnect after this fixed feed is
                    // exhausted.
                    if (last == 3) {
                      response.status(HttpStatusCodes.NO_CONTENT.value());
                      return;
                    }

                    var events = response.startEventStream();
                    for (int entry = last + 1; entry <= 3; entry++) {
                      events.send(
                          SseEvent.of("Entry " + entry)
                              .withId(Integer.toString(entry))
                              .withRetry(Duration.ofSeconds(1)));
                    }
                  });
              // Deliberately fail before opening a stream; the app's exception handler returns 500.
              routes.sse(
                  "/unavailable",
                  (_, _) -> {
                    throw new IllegalStateException("Example-only pre-stream failure details");
                  });
              // Once streaming starts, known business failures become events, not replacement HTTP
              // errors.
              routes.sse(
                  "/reports",
                  (_, response) -> {
                    var events = response.startEventStream();
                    events.send(SseEvent.of("starting").withEvent("progress"));
                    String result;

                    try {
                      result = report.get();
                    } catch (IllegalStateException failure) {
                      LOGGER.warn("Example report failed", failure);
                      // Do not catch IOException from send: a disconnected transport cannot receive
                      // an error event.
                      events.send(SseEvent.of("Report unavailable").withEvent("failure"));
                      return;
                    }

                    events.send(SseEvent.of(result).withEvent("result"));
                  });
            });
  }

  /**
   * Validates the client's replay position before committing the fixed three-entry feed.
   *
   * @param request reconnecting client's input
   * @return last received event ID, or zero on the first connection
   * @throws BadRequestException if the ID is not an integer within this feed
   */
  private static int lastEventId(Request request) {
    int last;

    try {
      last = Integer.parseInt(request.header(HttpHeaders.LAST_EVENT_ID.value()).orElse("0"));
    } catch (NumberFormatException failure) {
      throw new BadRequestException("Invalid event ID", failure);
    }

    if (last < 0 || last > 3) {
      throw new BadRequestException("Event ID is outside this example's feed");
    }

    return last;
  }
}
