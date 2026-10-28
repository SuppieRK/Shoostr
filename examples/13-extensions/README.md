# 13. Extensions

## Run with Docker

Use Docker Engine with Compose v2 or later and Buildx. Run from the repository root;
Java and Gradle are provided by the build image. Start one example at a time:

```sh
docker compose -f examples/13-extensions/compose.yaml up --build -d
```

Startup is asynchronous. Check diagnostics with `docker compose -f examples/13-extensions/compose.yaml logs -f app`.
Open `http://127.0.0.1:8080/health`. Expect ok.
Retry the request if the listener is not ready yet. Set `APP_PORT=8081` before the command if port 8080 is occupied, and use that port in requests.
Compose exposes the app on host loopback. These credentials and unauthenticated fixtures
are for trusted local development. Direct Java runs use the default wildcard listener.

To build with Jib inside Docker instead, stop the stack and export/load its image:

```sh
docker compose -f examples/13-extensions/compose.yaml down --timeout 20
docker buildx build -f examples/13-extensions/Dockerfile --target jib --output type=local,dest=build/extensions-example-jib .
docker load --input build/extensions-example-jib/jib-image.tar
docker compose -f examples/13-extensions/compose.yaml pull --ignore-buildable
docker compose -f examples/13-extensions/compose.yaml up --no-build --pull never --force-recreate -d
```

Both paths use the same local image tag. Stop and remove the stack with
`docker compose -f examples/13-extensions/compose.yaml down --timeout 20 --volumes`.
Use `docker compose -f examples/13-extensions/compose.yaml logs` for diagnostics.
Optional `./gradlew` commands below require a local Java 21 installation;
container validation is described in [CONTRIBUTING](../../CONTRIBUTING.md#example-container-validation).

## Recipe and direct-run notes

The [extension recipe](../../site/src/content/docs/extensions/route-metadata.mdx)
builds a route-description catalog with independent settings for each route or group.
Its complete [example](src/main/java/io/github/suppierk/shoostr/example/extensions/RouteCatalogExample.java)
and [test](src/test/java/io/github/suppierk/shoostr/example/extensions/RouteCatalogExampleTest.java)
cover registration facts, inherited overrides, application ownership and cleanup.

Run `./gradlew :extensions-example:test` from the repository root with Java 21.
The tests use real HTTP requests and public extension interfaces without external services.
