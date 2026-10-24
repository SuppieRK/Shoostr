package io.github.suppierk.shoostr.example.json;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.MediaType;
import java.nio.charset.StandardCharsets;

/** Selects one of two finite response representations. */
public final class NegotiationExample {
  /** Prevents construction of this example factory. */
  private NegotiationExample() {}

  /**
   * Creates an unstarted application.
   *
   * @return application owned by its caller
   */
  public static Shoostr application() {
    return new Shoostr()
        .routes(
            routes ->
                routes.get(
                    "/greeting",
                    (ignored, response) -> {
                      // JSON wins equal preferences; selection also adds Accept to Vary.
                      var selected =
                          response.negotiate(
                              MediaType.APPLICATION_JSON,
                              MediaType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8));
                      // Negotiation selects metadata only, so generate the matching bytes here.
                      var content =
                          MediaType.APPLICATION_JSON.equals(selected)
                              ? "{\"message\":\"café\"}"
                              : "café";
                      response.body(selected, content.getBytes(StandardCharsets.UTF_8));
                    }));
  }
}
