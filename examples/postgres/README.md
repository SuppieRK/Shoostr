# PostgreSQL persistence with Flyway and jOOQ

This intentionally small create/read API teaches persistence, not a CRUD framework.
[PostgresExample](src/main/java/io/github/suppierk/shoostr/example/postgres/PostgresExample.java)
wires ordinary creation/lookup functions. `DatabaseConfiguration` applies the checked-in
Flyway migration before listener startup; `JdbcNotes` uses the jOOQ runtime DSL with bound
values and a closed JDBC connection per operation. `NoteRoutes` owns HTTP validation;
`Note` establishes data invariants. No DI, ORM, generated classes or connection pool is needed.

```sh
./gradlew :postgres-example:jibDockerBuild
docker compose -f examples/postgres/compose.yaml up -d
curl --fail --retry 60 --retry-delay 2 --retry-connrefused --retry-all-errors \
  --max-time 3 --retry-max-time 180 http://127.0.0.1:8080/health
curl -i -H 'Content-Type: text/plain; charset=utf-8' --data 'Read the docs' \
  http://127.0.0.1:8080/notes
curl http://127.0.0.1:8080/notes/1
docker compose -f examples/postgres/compose.yaml restart app
curl --fail --retry 30 --retry-delay 1 --retry-connrefused --retry-all-errors \
  --max-time 3 --retry-max-time 60 http://127.0.0.1:8080/health
curl http://127.0.0.1:8080/notes/1
```

Wait for app startup (`GET /health` returns `healthy`). A fresh database returns201,
`Location: /notes/1`, and the submitted text. GET returns that text after restart;
the database-generated identity continues advancing. Missing notes return404; blank
input and numeric identities beyond the long range return400. Unexpected database
failures remain500 and do not expose SQL details in the default HTTP body.

Compose waits for PostgreSQL readiness; application startup validates/applies migrationV1.
PostgreSQL18 persists under `/var/lib/postgresql`, backed by this project's `notes` volume.
Normal `down` retains it; the explicitly destructive command below removes only this
example's data. The database has no published host port. App HTTP is loopback-only;
`APP_PORT=8081` changes the host port. `demo / local-only` is a disposable database fixture
with broad local privileges, not a production database/user security configuration.

```sh
./gradlew :postgres-example:test
./gradlew :postgres-example:dockerTest
docker compose -f examples/postgres/compose.yaml down --volumes
```

Ordinary live HTTP tests supply JDK functions before `TestServer.start`, without Docker,
H2 or dependency lifecycle magic. The separate Docker check builds with Jib, creates and
reads a real PostgreSQL-backed note, restarts the app, verifies that note and the next
identity, and removes only its uniquely named stack/volume. Logs: `build/docker/compose.log`;
JUnit: `build/test-results/dockerTest/`.

To run on the host against your own database, set `JDBC_URL`, `JDBC_USER` and `JDBC_PASSWORD`
before `./gradlew :postgres-example:run`. Defaults target `127.0.0.1:5432/shoostr`; the
provided Compose recipe intentionally does not publish that database port.
