# 4. Request metadata and state

## Run with Docker

Use Docker Engine with Compose v2 or later and Buildx. Run from the repository root;
Java and Gradle are provided by the build image. Start one example at a time:

```sh
docker compose -f examples/04-request/compose.yaml up --build -d
```

Startup is asynchronous. Check diagnostics with `docker compose -f examples/04-request/compose.yaml logs -f app`.
Open `http://127.0.0.1:8080/search/books?tag=java`. Expect category=books and tags=java.
Retry the request if the listener is not ready yet. Set `APP_PORT=8081` before the command if port 8080 is occupied, and use that port in requests.
Compose exposes the app on host loopback. These credentials and unauthenticated fixtures
are for trusted local development. Direct Java runs use the default wildcard listener.

To build with Jib inside Docker instead, stop the stack and export/load its image:

```sh
docker compose -f examples/04-request/compose.yaml down --timeout 20
docker buildx build -f examples/04-request/Dockerfile --target jib --output type=local,dest=build/request-example-jib .
docker load --input build/request-example-jib/jib-image.tar
docker compose -f examples/04-request/compose.yaml pull --ignore-buildable
docker compose -f examples/04-request/compose.yaml up --no-build --pull never --force-recreate -d
```

Both paths use the same local image tag. Stop and remove the stack with
`docker compose -f examples/04-request/compose.yaml down --timeout 20 --volumes`.
Use `docker compose -f examples/04-request/compose.yaml logs` for diagnostics.
Optional `./gradlew` commands below require a local Java 21 installation;
container validation is described in [CONTRIBUTING](../../CONTRIBUTING.md#example-container-validation).

## Recipe and direct-run notes

Each request recipe has a compiled factory and a live HTTP test:

- Parameters: [example](src/main/java/io/github/suppierk/shoostr/example/request/ParametersExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/request/ParametersExampleTest.java).
- Headers and cookies: [example](src/main/java/io/github/suppierk/shoostr/example/request/HeadersCookiesExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/request/HeadersCookiesExampleTest.java).
- Request metadata: [example](src/main/java/io/github/suppierk/shoostr/example/request/MetadataExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/request/MetadataExampleTest.java).
- Request-local state: [example](src/main/java/io/github/suppierk/shoostr/example/request/RequestStateExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/request/RequestStateExampleTest.java).

Run `./gradlew :request-example:test` from the repository root with Java 21.
