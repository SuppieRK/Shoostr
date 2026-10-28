# 12. Lifecycle

## Run with Docker

Use Docker Engine with Compose v2 or later and Buildx. Run from the repository root;
Java and Gradle are provided by the build image. Start one example at a time:

```sh
docker compose -f examples/12-lifecycle/compose.yaml up --build -d
```

Startup is asynchronous. Check diagnostics with `docker compose -f examples/12-lifecycle/compose.yaml logs -f app`.
Open `http://127.0.0.1:8080/ready`. Expect ready.
Retry the request if the listener is not ready yet. Set `APP_PORT=8081` before the command if port 8080 is occupied, and use that port in requests.
Compose exposes the app on host loopback. These credentials and unauthenticated fixtures
are for trusted local development. Direct Java runs use the default wildcard listener.

To build with Jib inside Docker instead, stop the stack and export/load its image:

```sh
docker compose -f examples/12-lifecycle/compose.yaml down --timeout 20
docker buildx build -f examples/12-lifecycle/Dockerfile --target jib --output type=local,dest=build/lifecycle-example-jib .
docker load --input build/lifecycle-example-jib/jib-image.tar
docker compose -f examples/12-lifecycle/compose.yaml pull --ignore-buildable
docker compose -f examples/12-lifecycle/compose.yaml up --no-build --pull never --force-recreate -d
```

Both paths use the same local image tag. Stop and remove the stack with
`docker compose -f examples/12-lifecycle/compose.yaml down --timeout 20 --volumes`.
Use `docker compose -f examples/12-lifecycle/compose.yaml logs` for diagnostics.
Optional `./gradlew` commands below require a local Java 21 installation;
container validation is described in [CONTRIBUTING](../../CONTRIBUTING.md#example-container-validation).

## Recipe and direct-run notes

These factories demonstrate application shutdown, request hooks and executor ownership.
Each factory has a complete test class beside the other tests in this module:

- [ShutdownExample](src/main/java/io/github/suppierk/shoostr/example/lifecycle/ShutdownExample.java)
- [RequestHooksExample](src/main/java/io/github/suppierk/shoostr/example/lifecycle/RequestHooksExample.java)
- [ExecutorExample](src/main/java/io/github/suppierk/shoostr/example/lifecycle/ExecutorExample.java)

Run `./gradlew :lifecycle-example:test` from the repository root with Java 21.
Tests use real HTTP requests and public lifecycle interfaces without external services.

Library composition: [StreamBulkheadExample](src/main/java/io/github/suppierk/shoostr/example/lifecycle/StreamBulkheadExample.java)
and [its HTTP test](src/test/java/io/github/suppierk/shoostr/example/lifecycle/StreamBulkheadExampleTest.java)
use caller-configured Resilience4j admission through existing lifecycle hooks.
See the [consumer recipe](../../site/src/content/docs/lifecycle/executors.mdx#compose-stream-admission).
