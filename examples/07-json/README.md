# 7. Serialization

## Run with Docker

Use Docker Engine with Compose v2 or later and Buildx. Run from the repository root;
Java and Gradle are provided by the build image. Start one example at a time:

```sh
docker compose -f examples/07-json/compose.yaml up --build -d
```

Startup is asynchronous. Check diagnostics with `docker compose -f examples/07-json/compose.yaml logs -f app`.
Open `http://127.0.0.1:8080/help`. Expect Send a JSON object with a nonblank name.
Retry the request if the listener is not ready yet. Set `APP_PORT=8081` before the command if port 8080 is occupied, and use that port in requests.
Compose exposes the app on host loopback. These credentials and unauthenticated fixtures
are for trusted local development. Direct Java runs use the default wildcard listener.

To build with Jib inside Docker instead, stop the stack and export/load its image:

```sh
docker compose -f examples/07-json/compose.yaml down --timeout 20
docker buildx build -f examples/07-json/Dockerfile --target jib --output type=local,dest=build/json-example-jib .
docker load --input build/json-example-jib/jib-image.tar
docker compose -f examples/07-json/compose.yaml pull --ignore-buildable
docker compose -f examples/07-json/compose.yaml up --no-build --pull never --force-recreate -d
```

Both paths use the same local image tag. Stop and remove the stack with
`docker compose -f examples/07-json/compose.yaml down --timeout 20 --volumes`.
Use `docker compose -f examples/07-json/compose.yaml logs` for diagnostics.
Optional `./gradlew` commands below require a local Java 21 installation;
container validation is described in [CONTRIBUTING](../../CONTRIBUTING.md#example-container-validation).

## Recipe and direct-run notes

Each recipe has a compiled factory and a live HTTP test:

- Codec-backed JSON conversion: [example](src/main/java/io/github/suppierk/shoostr/example/json/JsonCodecExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/json/JsonCodecExampleTest.java).
- Response representation selection: [example](src/main/java/io/github/suppierk/shoostr/example/json/NegotiationExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/json/NegotiationExampleTest.java).

Run `./gradlew :json-example:test` from the repository root with Java 21.

The [combined launcher fixture](legacy-json-errors/README.md) provides a separate runnable demonstration.
