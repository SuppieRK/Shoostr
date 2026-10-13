package io.github.suppierk.shoostr.example.postgres;

import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

/** Configures one database and migrates it before starting the HTTP listener. */
final class DatabaseConfiguration {
  /** Prevents construction of the configuration factory. */
  private DatabaseConfiguration() {}

  /**
   * Creates explicit JDBC configuration and applies the checked-in Flyway migration.
   *
   * @return storage opening and closing a connection per operation, without a pool
   */
  static JdbcNotes create() {
    var settings = System.getenv();
    var source = new PGSimpleDataSource();
    source.setURL(settings.getOrDefault("JDBC_URL", "jdbc:postgresql://127.0.0.1:5432/shoostr"));
    source.setUser(settings.getOrDefault("JDBC_USER", "demo"));
    source.setPassword(settings.getOrDefault("JDBC_PASSWORD", "local-only"));
    Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
    return new JdbcNotes(source);
  }
}
