package io.github.suppierk.shoostr.example.postgres;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Composes persistence functions without a DI framework or framework-owned database. */
public final class PostgresExample {
  private static final Logger LOGGER = LoggerFactory.getLogger(PostgresExample.class);

  /** Prevents construction of the launcher. */
  private PostgresExample() {}

  /**
   * Applies migrations before starting the single HTTP server.
   *
   * @throws Exception if migration or listener startup fails
   */
  static void main() throws Exception {
    // These example-local classes configure JDBC/Flyway and use jOOQ; Shoostr has no database API.
    var notes = DatabaseConfiguration.create();
    var app = application(notes::create, notes::find);
    app.start();
    LOGGER.info("PostgreSQL notes: http://127.0.0.1:{}/notes", app.port());
  }

  /**
   * Builds an unstarted app with application-selected persistence dependencies.
   *
   * @param create note creation, safe for concurrent calls
   * @param find note lookup, safe for concurrent calls
   * @return app owned by its caller; supplied dependencies remain caller-owned
   */
  public static Shoostr application(
      Function<String, Note> create, LongFunction<Optional<Note>> find) {
    // Supply JDK functions explicitly; handlers can use real persistence or test replacements.
    Objects.requireNonNull(create);
    Objects.requireNonNull(find);
    return new Shoostr()
        .routes(
            routes -> {
              routes.get("/health", (_, response) -> response.text("healthy"));
              // Shoostr constrains the path segment to digits; parsing still checks long overflow.
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

                    // Note is this example's record; Location identifies the newly stored resource.
                    var note = create.apply(text);
                    response
                        .status(HttpStatusCodes.CREATED.value())
                        .setHeader(HttpHeaders.LOCATION.value(), "/notes/" + note.id())
                        .text(note.text());
                  });
            });
  }
}
