# Typed JSON and application error handling

## Run with Docker

Use Docker Engine with Compose v2 or later and Buildx. Run from the repository root;
Java and Gradle are provided by the build image. Start one example at a time:

```sh
docker compose -f examples/07-json/legacy-json-errors/compose.yaml up --build -d
```

Startup is asynchronous. Check diagnostics with `docker compose -f examples/07-json/legacy-json-errors/compose.yaml logs -f app`.
Open `http://127.0.0.1:8080/users/help`. Expect the JSON submission instructions.
Retry the request if the listener is not ready yet. Set `APP_PORT=8081` before the command if port 8080 is occupied, and use that port in requests.
Compose exposes the app on host loopback. These credentials and unauthenticated fixtures
are for trusted local development. Direct Java runs use the default wildcard listener.

To build with Jib inside Docker instead, stop the stack and export/load its image:

```sh
docker compose -f examples/07-json/legacy-json-errors/compose.yaml down --timeout 20
docker buildx build -f examples/07-json/legacy-json-errors/Dockerfile --target jib --output type=local,dest=build/json-errors-example-jib .
docker load --input build/json-errors-example-jib/jib-image.tar
docker compose -f examples/07-json/legacy-json-errors/compose.yaml pull --ignore-buildable
docker compose -f examples/07-json/legacy-json-errors/compose.yaml up --no-build --pull never --force-recreate -d
```

Both paths use the same local image tag. Stop and remove the stack with
`docker compose -f examples/07-json/legacy-json-errors/compose.yaml down --timeout 20 --volumes`.
Use `docker compose -f examples/07-json/legacy-json-errors/compose.yaml logs` for diagnostics.
Optional `./gradlew` commands below require a local Java 21 installation;
container validation is described in [CONTRIBUTING](../../../CONTRIBUTING.md#example-container-validation).

## Recipe and direct-run notes

Maintainer launcher fixture. Consumer recipes belong in the [JSON section](../../../site/src/content/docs/json/codec.mdx).

Run `./gradlew :json-errors-example:run`, then:

```sh
curl -i -H 'Content-Type: application/json' -d '{"name":"Ada"}' http://127.0.0.1:8080/users
curl -i -H 'Content-Type: application/json' -d '{"name":" "}' http://127.0.0.1:8080/users
curl -i http://127.0.0.1:8080/users/help
curl -i http://127.0.0.1:8080/users/failure
```

The valid request returns201 and `{"name":"Ada"}`. Invalid input returns400,
`application/json`, and `{"error":"invalid_user"}` without exception details.
`/users/help` returns plain text despite the codec. The deliberate `/users/failure`
returns500 with `{"error":"internal_error"}`, not its internal exception message.

[JsonExample](src/main/java/io/github/suppierk/shoostr/example/json/JsonExample.java)
defines the endpoints inline and installs the codec. The separate
[User](src/main/java/io/github/suppierk/shoostr/example/json/User.java) and
[UserCodec](src/main/java/io/github/suppierk/shoostr/example/json/UserCodec.java)
own validation and explicit JSON trees respectively. Both `users.exception(...)`
renderers are inline beside the `/users` endpoints: input errors become400 JSON,
and `IllegalStateException` becomes500 JSON. Their pre-encoded response bytes do
not require the user codec to support a separate error model.
No application-model scanning, serialization annotations or schema inference occur.

The optional codec makes typed methods available while retaining ordinary byte/text
methods. It does not infer Content-Type or perform HTTP negotiation. Only malformed
JSON/input becomes400; unexpected encoding/I/O failures remain server errors.
`Routes.exception`/`TypedRoutes.exception` are core request handling, not extension
configuration. Unmatched router404s are separate from matched application exceptions.

Verify through live HTTP with `./gradlew :json-errors-example:test`.
Docker: `./gradlew :json-errors-example:jibDockerBuild`, then
`docker compose -f examples/07-json/legacy-json-errors/compose.yaml up -d`; cleanup is the same
command ending in `down`. Set `APP_PORT=8081` to change the host port.
