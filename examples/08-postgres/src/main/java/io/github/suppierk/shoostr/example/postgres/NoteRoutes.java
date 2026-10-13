package io.github.suppierk.shoostr.example.postgres;

import io.github.suppierk.shoostr.Routes;
import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpStatusCodes;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import io.github.suppierk.shoostr.http.exceptions.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongFunction;

/** Keeps HTTP input validation and responses separate from JDBC and startup migration. */
final class NoteRoutes {
  /** Prevents construction of the registration function. */
  private NoteRoutes() {}

  /**
   * Registers a small create/read API with explicit storage dependencies.
   *
   * @param routes application route scope
   * @param create concurrent-safe persistence function
   * @param find concurrent-safe lookup function
   */
  static void register(
      Routes routes, Function<String, Note> create, LongFunction<Optional<Note>> find) {
    routes.get("/health", (_, response) -> response.text("healthy"));
    routes.get(
        "/notes/{id:[0-9]+}",
        (request, response) -> {
          long id;

          try {
            id = Long.parseLong(request.pathParam("id").orElseThrow());
          } catch (NumberFormatException failure) {
            throw new BadRequestException("Note identity exceeds the supported range", failure);
          }

          response.text(find.apply(id).orElseThrow(NotFoundException::new).text());
        });
    routes.post(
        "/notes",
        (request, response) -> {
          var text = new String(request.bodyBytes(), StandardCharsets.UTF_8);
          if (text.isBlank()) {
            throw new BadRequestException("Note text must not be blank");
          }

          var note = create.apply(text);
          response
              .status(HttpStatusCodes.CREATED.value())
              .setHeader(HttpHeaders.LOCATION.value(), "/notes/" + note.id())
              .text(note.text());
        });
  }
}
