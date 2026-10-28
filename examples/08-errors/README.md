# 8. Exceptions, statuses and problem details

## Run with Docker

Use Docker Engine with Compose v2 or later and Buildx. Run from the repository root;
Java and Gradle are provided by the build image. Start one example at a time:

```sh
docker compose -f examples/08-errors/compose.yaml up --build -d
```

Startup is asynchronous. Check diagnostics with `docker compose -f examples/08-errors/compose.yaml logs -f app`.
Open `http://127.0.0.1:8080/input`. Expect HTTP 400 with Invalid input.
Retry the request if the listener is not ready yet. Set `APP_PORT=8081` before the command if port 8080 is occupied, and use that port in requests.
Compose exposes the app on host loopback. These credentials and unauthenticated fixtures
are for trusted local development. Direct Java runs use the default wildcard listener.

To build with Jib inside Docker instead, stop the stack and export/load its image:

```sh
docker compose -f examples/08-errors/compose.yaml down --timeout 20
docker buildx build -f examples/08-errors/Dockerfile --target jib --output type=local,dest=build/errors-example-jib .
docker load --input build/errors-example-jib/jib-image.tar
docker compose -f examples/08-errors/compose.yaml pull --ignore-buildable
docker compose -f examples/08-errors/compose.yaml up --no-build --pull never --force-recreate -d
```

Both paths use the same local image tag. Stop and remove the stack with
`docker compose -f examples/08-errors/compose.yaml down --timeout 20 --volumes`.
Use `docker compose -f examples/08-errors/compose.yaml logs` for diagnostics.
Optional `./gradlew` commands below require a local Java 21 installation;
container validation is described in [CONTRIBUTING](../../CONTRIBUTING.md#example-container-validation).

## Recipe and direct-run notes

Each error-handling recipe has a compiled factory and a live HTTP test:

- Thrown exceptions and recovery: [example](src/main/java/io/github/suppierk/shoostr/example/errors/ExceptionHandlingExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/errors/ExceptionHandlingExampleTest.java).
- Generated status responses: [example](src/main/java/io/github/suppierk/shoostr/example/errors/GeneratedStatusExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/errors/GeneratedStatusExampleTest.java).
- Problem Details JSON: [example](src/main/java/io/github/suppierk/shoostr/example/errors/ProblemDetailsExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/errors/ProblemDetailsExampleTest.java).

Run `./gradlew :errors-example:test` from the repository root with Java 21.

The [combined launcher fixture](legacy-exceptions/README.md) provides a separate runnable demonstration.
