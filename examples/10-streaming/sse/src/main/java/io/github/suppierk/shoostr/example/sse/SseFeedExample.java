package io.github.suppierk.shoostr.example.sse;

import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.SseEvent;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import java.time.Duration;

/** Sends a finite event feed with explicit application replay. */
public final class SseFeedExample {
  /** Prevents construction of the factory. */
  private SseFeedExample() {}

  /**
   * Creates an unstarted application with a fixed, non-durable feed.
   *
   * @return unstarted application
   */
  public static Shoostr application() {
    return new Shoostr()
        .routes(
            routes ->
                routes
                    .sse(
                        "/events",
                        (ignored, response) -> {
                          var events = response.startEventStream();
                          events.send("starting");
                          // Comments and heartbeats keep activity without delivering data events.
                          events.comment("ready");
                          events.heartbeat();
                          // IDs and retry values guide reconnects; replay remains
                          // application-owned.
                          events.send(
                              SseEvent.of("Preparing\nWaiting")
                                  .withEvent("progress")
                                  .withId("1")
                                  .withRetry(Duration.ofSeconds(2)));
                          events.send(SseEvent.of("Complete").withEvent("done").withId("2"));
                        })
                    .sse(
                        "/replay",
                        (request, response) -> {
                          // Validate before streaming so invalid cursors can still return 400.
                          int last = lastEventId(request);
                          if (last == 3) {
                            // 204 tells an EventSource client not to reconnect to this exhausted
                            // feed.
                            response.status(204);
                            return;
                          }

                          var events = response.startEventStream();
                          for (int entry = last + 1; entry <= 3; entry++) {
                            events.send(
                                SseEvent.of("Entry " + entry)
                                    .withId(Integer.toString(entry))
                                    .withRetry(Duration.ofSeconds(1)));
                          }
                        }));
  }

  /**
   * Validates the replay position before any event output is committed.
   *
   * @param request reconnecting client's request
   * @return last accepted event ID, or zero for a new client
   * @throws BadRequestException if the cursor is invalid for this feed
   */
  private static int lastEventId(Request request) {
    int last;

    try {
      last = Integer.parseInt(request.header("Last-Event-ID").orElse("0"));
    } catch (NumberFormatException failure) {
      throw new BadRequestException("Invalid replay cursor", failure);
    }

    if (last < 0 || last > 3) {
      throw new BadRequestException("Replay cursor is outside this feed");
    }

    return last;
  }
}
