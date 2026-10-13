package io.github.suppierk.shoostr.example.postgres;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

/** Uses bound values and short-lived connections; no ORM, generated models or DI is needed. */
final class JdbcNotes {
  private final DataSource source;

  /**
   * Borrows an application-configured source without taking ownership of any connection pool.
   *
   * @param source validated database configuration
   */
  JdbcNotes(DataSource source) {
    this.source = Objects.requireNonNull(source);
  }

  /**
   * Persists text and returns the database-generated identity through the jOOQ runtime DSL.
   *
   * @param text validated note text
   * @return stored note
   * @throws IllegalStateException if connection establishment or cleanup fails
   */
  Note create(String text) {
    try (var connection = source.getConnection()) {
      var id = DSL.field("id", Long.class);
      var content = DSL.field("text", String.class);
      var record =
          DSL.using(connection, SQLDialect.POSTGRES)
              .insertInto(DSL.table("notes"))
              .columns(content)
              .values(text)
              .returning(id, content)
              .fetchOne();
      var stored = Objects.requireNonNull(record, "INSERT did not return its generated identity");
      return new Note(
          Objects.requireNonNull(stored.get(id)), Objects.requireNonNull(stored.get(content)));
    } catch (SQLException failure) {
      throw new IllegalStateException("Database connection failed", failure);
    }
  }

  /**
   * Looks up one note using a bound identity, closing the operation's JDBC connection.
   *
   * @param identity requested database identity
   * @return stored note or empty when absent
   * @throws IllegalStateException if connection establishment or cleanup fails
   */
  Optional<Note> find(long identity) {
    try (var connection = source.getConnection()) {
      var id = DSL.field("id", Long.class);
      var content = DSL.field("text", String.class);
      return DSL.using(connection, SQLDialect.POSTGRES)
          .select(id, content)
          .from(DSL.table("notes"))
          .where(id.eq(identity))
          .fetchOptional()
          .map(
              record ->
                  new Note(
                      Objects.requireNonNull(record.get(id)),
                      Objects.requireNonNull(record.get(content))));
    } catch (SQLException failure) {
      throw new IllegalStateException("Database connection failed", failure);
    }
  }
}
