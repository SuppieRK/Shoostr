# Requests, routing and conditional availability

## Run with Docker

Use Docker Engine with Compose v2 or later and Buildx. Run from the repository root;
Java and Gradle are provided by the build image. Start one example at a time:

```sh
docker compose -f examples/03-routing/legacy-requests/compose.yaml up --build -d
```

Startup is asynchronous. Check diagnostics with `docker compose -f examples/03-routing/legacy-requests/compose.yaml logs -f app`.
Open `http://127.0.0.1:8080/greetings/Ada`. Expect Hello, Ada!.
Retry the request if the listener is not ready yet. Set `APP_PORT=8081` before the command if port 8080 is occupied, and use that port in requests.
Compose exposes the app on host loopback. These credentials and unauthenticated fixtures
are for trusted local development. Direct Java runs use the default wildcard listener.

To build with Jib inside Docker instead, stop the stack and export/load its image:

```sh
docker compose -f examples/03-routing/legacy-requests/compose.yaml down --timeout 20
docker buildx build -f examples/03-routing/legacy-requests/Dockerfile --target jib --output type=local,dest=build/requests-example-jib .
docker load --input build/requests-example-jib/jib-image.tar
docker compose -f examples/03-routing/legacy-requests/compose.yaml pull --ignore-buildable
docker compose -f examples/03-routing/legacy-requests/compose.yaml up --no-build --pull never --force-recreate -d
```

Both paths use the same local image tag. Stop and remove the stack with
`docker compose -f examples/03-routing/legacy-requests/compose.yaml down --timeout 20 --volumes`.
Use `docker compose -f examples/03-routing/legacy-requests/compose.yaml logs` for diagnostics.
Optional `./gradlew` commands below require a local Java 21 installation;
container validation is described in [CONTRIBUTING](../../../CONTRIBUTING.md#example-container-validation).

## Recipe and direct-run notes

Maintainer launcher fixture. Consumer recipes belong in the [routing section](../../../site/src/content/docs/routing/methods.mdx).

Run `./gradlew :requests-example:run`.

[RequestExample](src/main/java/io/github/suppierk/shoostr/example/requests/RequestExample.java)
uses `app.routes(...)` to define endpoints inline. It accepts a concurrent-safe
`UnaryOperator<String>` for greetings, with no DI container or dependency lookup.
This is a stateless HTTP demonstration, not a CRUD service: PUT/PATCH echo input,
and DELETE acknowledges a request without deleting a stored resource.

## Seven verbs and one path group

`routes.path("/echo", ...)` creates a prefix. Its no-path `get`, `post`, `put`,
`patch`, `delete`, `head` and `options` registrations all serve `/echo` itself.
Use these commands against `http://127.0.0.1:8080/echo`:

| Method  | curl options                                                 | Observable response                                      |
|---------|--------------------------------------------------------------|----------------------------------------------------------|
| GET     | none                                                         | 200, instructions for submitting a body                  |
| POST    | `-H 'Content-Type: text/plain' --data-binary 'Hello, 世界!'` | 200, the same UTF-8 text                                 |
| PUT     | `-X PUT --data-binary 'raw bytes'`                           | 200, unchanged bytes with `application/octet-stream`     |
| PATCH   | `-X PATCH --data-binary 'partial input'`                     | 200, the same text and `X-Request-Method: PATCH`         |
| DELETE  | `-i -X DELETE`                                               | 204, no body                                             |
| HEAD    | `-I`                                                         | 200, `X-Echo-Mode: stateless`, no body                   |
| OPTIONS | `-i -X OPTIONS`                                              | 204, explicit `Allow` listing all seven methods, no body |

HEAD and OPTIONS are explicit registrations: GET does not imply HEAD, and ordinary
OPTIONS is not generated automatically. An unregistered method on a known path
returns405 with `Allow`; an unknown path returns404.

For other registered methods, use generic registration:
`routes.route(HttpMethods.QUERY, "/query", ...)`. Try
`curl -X QUERY --data-binary 'tag=java' http://127.0.0.1:8080/query` → `tag=java`.
This demonstrates dispatch and body access, not a query language or a proxy tunnel.

## Inputs, response headers and cookies

| Endpoint                              | Input capability                                                   | Expected result                                                             |
|---------------------------------------|--------------------------------------------------------------------|-----------------------------------------------------------------------------|
| `GET /greetings/Ada`                  | Named path parameter and supplied greeting operation               | `Hello, Ada!`                                                               |
| `GET /welcome`                        | Optional query input                                               | `Hello, World!`                                                             |
| `GET /welcome?name=Grace`             | Explicit query value                                               | `Hello, Grace!`; blank values return400                                     |
| `GET /search?tag=java&tag=HTTP%20API` | Ordered, repeated decoded query values                             | `java,HTTP API`; no tags gives empty text                                   |
| `POST /forms`                         | URL-encoded first/repeated form values, separate from query values | Greeting and ordered tags; missing/blank name returns400                    |
| `GET /preferences?theme=light`        | Incoming request header/cookie and outgoing header/cookie          | Echoes `X-Request-Id`, returns incoming `theme`, sets the next theme cookie |
| `GET /items/42`                       | Constrained named segment `{id:[0-9]+}`                            | `42`; `/items/abc` returns404                                               |
| `GET /paths/guides/HTTP%20API`        | Named terminal catch-all `{*tail}`                                 | `guides/HTTP API`; does not access the filesystem                           |

For form decoding:

```sh
curl --data 'name=Ada+Lovelace&tag=java&tag=HTTP%20API' http://127.0.0.1:8080/forms
```

Expected body: `Hello, Ada Lovelace!; tags=java,HTTP API`. curl supplies
`application/x-www-form-urlencoded`; unsupported or missing form media metadata
returns415. Query parameters never substitute for missing form fields.

For headers and cookies:

```sh
curl -i -H 'X-Request-Id: demo-42' --cookie 'theme=dark' 'http://127.0.0.1:8080/preferences?theme=light'
```

Expected body: `dark`, with `X-Request-Id: demo-42` and a `Set-Cookie` for the next
`theme=light`. Without an incoming cookie/header, the body is `unset` and the
response header is `none`. Cookie state belongs to the client, not an app-side store.

## Live availability with both when overloads

`routes.when(BooleanSupplier, ...)` and nested `when(Predicate<Request>, ...)`
protect the same `/preview` registration. Both must return true: the application
flag must be enabled, and the request must have exactly `X-Preview: true`.
The header is only a demonstration condition, never authentication or trusted identity.

The one-argument factory disables preview. Main supplies the flag from a JVM property;
to enable it for a direct run:

```sh
JAVA_TOOL_OPTIONS=-Dshoostr.example.preview=true ./gradlew :requests-example:run
curl -H 'X-Preview: true' http://127.0.0.1:8080/preview
```

Expected body: `Preview enabled`. A false flag, absent header or other header value
returns terminal404 with `Cache-Control: no-store`; there is no route fallback.
Conditions run for each matched request, not during registration. They do not hide
registered methods: `POST /preview` still returns405 with `Allow: GET`.
In tests, the two-argument factory accepts an `AtomicBoolean::get` and toggles it
without restarting the app. Both supplied operations remain caller-owned and must
be safe for concurrent requests.

## Test and container ownership

[RequestExampleTest](src/test/java/io/github/suppierk/shoostr/example/requests/RequestExampleTest.java)
substitutes the greeting operation before starting the actual app and separately
verifies each demonstrated behavior through live HTTP. `TestServer` owns the app/client
lifetime, isolated cookies and an ephemeral loopback port. Requests use only
`test.send(request -> ...)`; assertions are ordinary JUnit. The harness does not reset
or own supplied dependencies. Run `./gradlew :requests-example:test`.

JSON/codec, authentication, static files and streams have their own focused examples;
this module needs none of their integrations. Core owns shutdown; no extra hook is added.

Docker: `./gradlew :requests-example:jibDockerBuild`, then
`docker compose -f examples/03-routing/legacy-requests/compose.yaml up -d`.
Run the same curls; clean up with `docker compose -f examples/03-routing/legacy-requests/compose.yaml down`.
Docker prerequisites and loopback exposure are described above.
Set `APP_PORT=8081` if8080 is occupied.
