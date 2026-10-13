package io.github.suppierk.shoostr.example.streaming;

import io.github.suppierk.shoostr.Routes;

/** Keeps asset registration and bounded stream handlers out of startup wiring. */
final class StreamingRoutes {
  /** Prevents construction of the registration function. */
  private StreamingRoutes() {}

  /**
   * Registers a narrow classpath mount and finite streams.
   *
   * @param routes application registration scope
   */
  static void register(Routes routes) {
    routes.classpathResources("/assets", "/public");
    routes.sse(
        "/events",
        (_, response) -> {
          var events = response.startEventStream();
          events.send("starting");
          events.send("complete");
        });
    routes.get(
        "/download",
        (_, response) -> {
          var output = response.startStream("text/csv; charset=utf-8");
          output.write("name\n").flush();
          output.write("Ada\n");
          output.write("Grace\n");
        });
  }
}
