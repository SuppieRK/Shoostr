# Explicit exception and status handling

Run `./gradlew :exceptions-example:run`.

[ExceptionExample](src/main/java/io/github/suppierk/shoostr/example/exceptions/ExceptionExample.java)
keeps every request, exception and status handler inline. It needs no codec, extension, custom
exception class or registration helper. All responses are plain text.

## Choose where to handle the exception

| Request | Mechanism | Status and body |
| --- | --- | --- |
| `GET /builtin/bad-request` | Throw an HTTP exception; use the default response | 400, `Bad Request` |
| `GET /number?value=42` | Successful parsing inside the handler | 200, `42` |
| `GET /number?value=abc` | Catch a recoverable `NumberFormatException` inside the handler | 400, `Integer required` |
| `GET /app-failure` | Exception handler registered with `app.exception(IllegalStateException.class, ...)` | 500, `Application failure` |
| `GET /route-failure` | Exception handler registered on `routes` | 400, `Route input error` |
| `GET /group/input` | Exception handler registered on `/group` replaces the inherited handler for the same class | 400, `Group input error` |
| `GET /inherited/input` | Route group uses the exception handler inherited from `routes` | 400, `Route input error` |
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

A missing, invalid or out-of-range integer returns 400 with `Integer required`.
The catch handles only `NumberFormatException`; it does not swallow unexpected
failures or response-writing errors. Without that catch, the exception is a subtype
of `IllegalArgumentException` and would be handled by the exception handler
registered on `routes` instead.

## Registered exception handlers and route groups

Catch failures inside the request handler when it can recover from them. For
exceptions that leave the request handler, Shoostr first checks exception handlers
registered on its routes or inherited from a parent route group. If none matches,
it checks app-wide exception handlers. Among the handlers available to the route
group, the one registered for the most specific matching exception class wins.
A nested route group can replace an inherited handler by registering another for
the same exception class. Registrations apply to every endpoint in that route
group and its nested groups, including endpoints registered earlier—not just the
next endpoint.

The app-wide exception handler in this example handles `IllegalStateException`
and its subclasses, not every possible server failure. No broad catch turns HTTP
input errors into 500.
The deliberate exception messages are private diagnostics: neither default handling
nor these custom responses expose them or stack traces.

Before invoking a registered exception handler, Shoostr clears any unsent response
body. The exception handler cannot replace bytes already sent to the client.
Router-generated statuses are a different path: `app.status(404, ...)` customizes
a routing miss, not an exception thrown by a matched endpoint. The two 404 responses
above make that distinction visible.

For JSON exception responses registered on a route group and typed bodies, continue with
[06-json-errors](../06-json-errors/README.md). Exception handling is core request
functionality, not extension configuration.

## Verify and run in Docker

Run `./gradlew :exceptions-example:test`. The tests use the public app factory and
live HTTP through `TestServer`; each test verifies one observable behavior with
ordinary JUnit assertions. The harness owns the app/client lifetime and an ephemeral
loopback port. Core owns shutdown for a direct run; no extra shutdown hook is added.

Build `./gradlew :exceptions-example:jibDockerBuild`, then run
`docker compose -f examples/05-exceptions/compose.yaml up -d`.
Use the same curls, then clean up with
`docker compose -f examples/05-exceptions/compose.yaml down`.
Set `APP_PORT=8081` if 8080 is occupied. See [the catalog](../README.md) for prerequisites
and the direct-run versus Docker listening-address distinction.
