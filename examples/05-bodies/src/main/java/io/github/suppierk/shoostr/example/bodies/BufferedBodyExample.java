package io.github.suppierk.shoostr.example.bodies;

import io.github.suppierk.shoostr.MultipartOptions;
import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.MediaType;
import java.nio.charset.StandardCharsets;

/** Reads finite request text and bytes under an explicit byte limit. */
public final class BufferedBodyExample {
  /** Prevents construction of this example factory. */
  private BufferedBodyExample() {}

  /**
   * Creates an unstarted application with a 64-byte body limit and four-pair parameter limit.
   *
   * @return application owned by its caller
   */
  public static Shoostr application() {
    var options = options();

    return new Shoostr(options)
        .routes(
            routes ->
                routes
                    .post("/text", (request, response) -> response.text(request.bodyText()))
                    // Decode Latin-1 input explicitly; text(...) still encodes the reply as UTF-8.
                    .post(
                        "/latin1",
                        (request, response) ->
                            response.text(request.bodyText(StandardCharsets.ISO_8859_1)))
                    .post(
                        "/bytes",
                        (request, response) ->
                            response.body(MediaType.APPLICATION_OCTET_STREAM, request.bodyBytes()))
                    .post(
                        "/form",
                        (request, response) -> {
                          // Buffered text and form decoding reuse the body without consuming it
                          // twice.
                          var raw = request.bodyText();

                          response.text(
                              String.join(
                                  "\n",
                                  "form=" + request.formParam("tag").orElse("(missing)"),
                                  "query=" + request.queryParam("tag").orElse("(missing)"),
                                  "raw=" + raw));
                        }));
  }

  /**
   * Configures the listener and resource policy used by this application.
   *
   * @return detached serving options
   */
  private static Options options() {
    var options = new Options();
    options.setMaxRequestBytes(64);
    options.setMaxResponseBytes(4096);
    options.setStreamBufferBytes(512);
    options.setMaxParameters(4);
    options.setMultipart(new MultipartOptions());

    return options;
  }
}
