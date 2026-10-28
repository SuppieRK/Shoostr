package io.github.suppierk.shoostr.example.responses;

import io.github.suppierk.shoostr.MultipartOptions;
import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.http.HttpStatusCodes;
import io.github.suppierk.shoostr.http.MediaType;
import java.nio.charset.StandardCharsets;

/** Sends finite responses with explicit statuses, encodings and body rules. */
public final class ResponseBodyExample {
  /** Prevents construction of this example factory. */
  private ResponseBodyExample() {}

  /**
   * Creates an unstarted application with a 64-byte finite response limit.
   *
   * @return application owned by its caller
   */
  public static Shoostr application() {
    var options = new Options(1024, 64, 512, 1000, MultipartOptions.defaults());
    return new Shoostr(options)
        .routes(
            routes ->
                routes
                    .post(
                        "/messages",
                        (ignored, response) ->
                            response.status(HttpStatusCodes.CREATED.value()).text("café"))
                    .get(
                        "/bytes",
                        (ignored2, response) ->
                            response.body(
                                MediaType.APPLICATION_OCTET_STREAM,
                                new byte[] {0, 1, 127, (byte) 0xff}))
                    // The selected charset controls both text encoding and Content-Type metadata.
                    .get(
                        "/latin1",
                        (ignored3, response) -> response.text("café", StandardCharsets.ISO_8859_1))
                    .get("/message", (ignored4, response) -> response.text("café"))
                    // Reuse GET's representation; HEAD keeps its headers but sends no body bytes.
                    .route(
                        HttpMethods.HEAD, "/message", (ignored5, response) -> response.text("café"))
                    // Leave the body unset for 204 instead of asking completion to discard content.
                    .delete(
                        "/messages",
                        (ignored6, response) ->
                            response.status(HttpStatusCodes.NO_CONTENT.value())));
  }
}
