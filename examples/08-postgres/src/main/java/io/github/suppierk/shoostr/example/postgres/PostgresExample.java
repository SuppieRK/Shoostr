package io.github.suppierk.shoostr.example.postgres;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpStatusCodes;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import io.github.suppierk.shoostr.http.exceptions.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongFunction;

/** Composes persistence functions without a DI framework or framework-owned database. */
public final class PostgresExample {
  /** Prevents construction of the launcher. */
  private PostgresExample() {}

  /**
   * Applies migrations before starting the single HTTP server.
   *
   * @throws Exception if migration or listener startup fails
   */
  static void main() throws Exception {
    var notes = DatabaseConfiguration.create();
    var app = application(Options.defaults(), notes::create, notes::find);
    app.start();
  }

  /**
   * Builds an unstarted app with application-selected persistence dependencies.
   *
   * @param options listener settings
   * @param create note creation, safe for concurrent calls
   * @param find note lookup, safe for concurrent calls
   * @return app owned by its caller; supplied dependencies remain caller-owned
   */
  public static Shoostr application(
      Options options, Function<String, Note> create, LongFunction<Optional<Note>> find) {
    Objects.requireNonNull(create);
    Objects.requireNonNull(find);
    return new Shoostr(options)
        .routes(
            routes -> {
              routes.get("/health", (_, response) -> response.text("healthy"));
              routes.get(
                  "/notes/{id:[0-9]+}",
                  (request, response) -> {
                    long id;

                    try {
                      id = Long.parseLong(request.pathParam("id").orElseThrow());
                    } catch (NumberFormatException failure) {
                      throw new BadRequestException(
                          "Note identity exceeds the supported range", failure);
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
            });
  }
}
