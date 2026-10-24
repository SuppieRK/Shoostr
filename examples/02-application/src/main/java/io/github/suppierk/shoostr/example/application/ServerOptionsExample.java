package io.github.suppierk.shoostr.example.application;

import io.github.suppierk.shoostr.MultipartOptions;
import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;

/** Configures the resource limits owned by Shoostr. */
public final class ServerOptionsExample {
  /** Prevents construction of this example factory. */
  private ServerOptionsExample() {}

  /**
   * Creates an unstarted application owned by its caller.
   *
   * @return configured application
   */
  public static Shoostr application() {
    // Limit buffered request bytes to 1024 and finite response bytes to 2048.
    var options = new Options(1024, 2048, 4096, 1000, MultipartOptions.defaults());

    return new Shoostr(options)
        .routes(
            routes ->
                routes.post("/echo", (request, response) -> response.text(request.bodyText())));
  }
}
