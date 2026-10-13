# Explicit exception and status handling

Run `./gradlew :exceptions-example:run`.

[ExceptionExample](src/main/java/io/github/suppierk/shoostr/example/exceptions/ExceptionExample.java)
keeps every handler and renderer inline. It needs no codec, extension, custom
exception class or registration helper. All responses are plain text.

## Choose the appropriate scope

| Request | Mechanism | Status and body |
| --- | --- | --- |
| `GET /builtin/bad-request` | Throw an HTTP exception; use built-in rendering | 400, `Bad Request` |
| `GET /number?value=42` | Successful parsing inside the handler | 200, `42` |
| `GET /number?value=abc` | Catch a recoverable `NumberFormatException` inside the handler | 400, `Integer required` |
| `GET /app-failure` | `app.exception(IllegalStateException.class, ...)` fallback | 500, `Application failure` |
| `GET /route-failure` | Root `routes.exception(IllegalArgumentException.class, ...)` | 400, `Route input error` |
| `GET /group/input` | Child group replaces the inherited renderer for the same class | 400, `Group input error` |
| `GET /inherited/input` | Child group inherits the root renderer unchanged | 400, `Route input error` |
| `GET /unknown` | `app.status(404, ...)` for a router-generated status | 404, `Route not found` |
| `GET /builtin/not-found` | Matched handler throws `NotFoundException`, not a routing miss | 404, `Not Found` |

Try these against the running app:

```sh
curl -i http://127.0.0.1:8080/builtin/bad-request
curl -i 'http://127.0.0.1:8080/number?value=42'
curl -i 'http://127.0.0.1:8080/number?value=abc'
curl -i http://127.0.0.1:8080/app-failure
curl -i http://127.0.0.1:8080/route-failure
curl -i http://127.0.0.1:8080/group/input
curl -i http://127.0.0.1:8080/inherited/input
curl -i http://127.0.0.1:8080/unknown
curl -i http://127.0.0.1:8080/builtin/not-found
```

A missing, invalid or out-of-range integer returns400 with `Integer required`.
The catch handles only `NumberFormatException`; it does not swallow unexpected
failures or response-writing errors. Without that catch, the exception is a subtype
of `IllegalArgumentException` and could reach the root scope's renderer instead.

## Rendering and inheritance

Recoverable business failures belong in the handler. For failures that escape,
Shoostr selects a matching scoped renderer before checking the application fallback.
Within the effective scope mappings, the most specific exception class wins.
A child replaces an inherited mapping for the same class; otherwise it inherits
the parent's mappings. Scope registrations cover the entire scope and its
descendants, including endpoints registered earlier—not just the next endpoint.

The app fallback in this example handles the `IllegalStateException` family,
not every possible server failure. No broad catch turns HTTP input errors into500.
The deliberate failure messages are private diagnostics: neither built-in rendering
nor these custom responses expose them or stack traces.

Custom exception rendering receives a clean, uncommitted response; previously
staged output is reset. It cannot replace bytes already committed to the client.
Router-generated statuses are a different path: `app.status(404, ...)` customizes
a routing miss, not an exception thrown by a matched endpoint. The two404 responses
above make that distinction visible.

For scoped JSON responses and typed bodies, continue with
[04-json-errors](../04-json-errors/README.md). Exception handling is core request
functionality, not extension configuration.

## Verify and run in Docker

Run `./gradlew :exceptions-example:test`. The tests use the public app factory and
live HTTP through `TestServer`; each test verifies one observable behavior with
ordinary JUnit assertions. The harness owns the app/client lifetime and an ephemeral
loopback port. Core owns shutdown for a direct run; no extra shutdown hook is added.

Build `./gradlew :exceptions-example:jibDockerBuild`, then run
`docker compose -f examples/03-exceptions/compose.yaml up -d`.
Use the same curls, then clean up with
`docker compose -f examples/03-exceptions/compose.yaml down`.
Set `APP_PORT=8081` if8080 is occupied. See [the catalog](../README.md) for prerequisites
and the direct-run versus Docker listening-address distinction.
