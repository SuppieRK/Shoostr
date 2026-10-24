package io.github.suppierk.shoostr.example.responses;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.HttpHeaders;
import java.util.List;

/** Sets response fields and inspects immutable header snapshots. */
public final class ResponseHeadersExample {
  /** Prevents construction of this example factory. */
  private ResponseHeadersExample() {}

  /**
   * Creates an unstarted application that keeps repeated Link fields separate.
   *
   * @return application owned by its caller
   */
  public static Shoostr application() {
    var mode = HttpHeaders.of("X-Mode");
    return new Shoostr()
        .routes(
            routes ->
                routes.get(
                    "/headers",
                    (ignored, response) -> {
                      // addHeader keeps separate Link fields; it does not split comma members.
                      response
                          .setHeader(mode.value(), "draft")
                          .setHeader("X-Temporary", "discard")
                          .addHeader(
                              HttpHeaders.LINK.value(),
                              "</next>; rel=\"next\", </last>; rel=\"last\"")
                          .addHeader(HttpHeaders.LINK.value(), "</previous>; rel=\"prev\"");
                      // This immutable snapshot keeps the earlier values after later mutations.
                      var before = response.headerMap();
                      response
                          .setHeader("x-mode", "ready")
                          .removeHeader("x-temporary")
                          .addHeader(HttpHeaders.LINK.value(), "</first>; rel=\"first\"");
                      response.text(
                          String.join(
                              "\n",
                              "first=" + response.header("LINK"),
                              "links=" + String.join("|", response.headers("link")),
                              "mode=" + response.header(mode.value()),
                              "snapshot-mode="
                                  + String.join("|", before.getOrDefault("X-MODE", List.of())),
                              "snapshot-links=" + before.getOrDefault("link", List.of()).size()));
                    }));
  }
}
