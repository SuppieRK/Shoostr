package io.github.suppierk.shoostr.example.json;

import io.github.suppierk.shoostr.TypedRoutes;
import io.github.suppierk.shoostr.http.HttpStatusCodes;
import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import java.nio.charset.StandardCharsets;

/** Safe application error rendering, not extension configuration or a serialization subsystem. */
final class UserErrors {
  private static final byte[] INVALID_USER =
      "{\"error\":\"invalid_user\"}".getBytes(StandardCharsets.UTF_8);
  private static final byte[] INTERNAL_ERROR =
      "{\"error\":\"internal_error\"}".getBytes(StandardCharsets.UTF_8);

  /** Prevents construction of the registration function. */
  private UserErrors() {}

  /**
   * Renders matched invalid-input failures without exposing messages or stack traces.
   *
   * @param users user route scope, not the whole application
   */
  static void register(TypedRoutes users) {
    users.exception(
        BadRequestException.class,
        (_, _, response) ->
            response
                .status(HttpStatusCodes.BAD_REQUEST.value())
                .body(MediaType.APPLICATION_JSON, INVALID_USER));
    users.exception(
        IllegalStateException.class,
        (_, _, response) ->
            response
                .status(HttpStatusCodes.INTERNAL_SERVER_ERROR.value())
                .body(MediaType.APPLICATION_JSON, INTERNAL_ERROR));
  }
}
