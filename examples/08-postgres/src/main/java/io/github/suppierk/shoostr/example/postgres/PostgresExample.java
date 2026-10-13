package io.github.suppierk.shoostr.example.postgres;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongFunction;
import org.eclipse.jetty.server.ServerConnector;

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
    app.modifyServer(
        server ->
            ((ServerConnector) server.getConnectors()[0])
                .setHost(System.getProperty("shoostr.host", "127.0.0.1")));
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
    return new Shoostr(options).routes(routes -> NoteRoutes.register(routes, create, find));
  }
}
