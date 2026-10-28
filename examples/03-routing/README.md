# 3. Routing

## Run with Docker

Use Docker Engine with Compose v2 or later and Buildx. Run from the repository root;
Java and Gradle are provided by the build image. Start one example at a time:

```sh
docker compose -f examples/03-routing/compose.yaml up --build -d
```

Startup is asynchronous. Check diagnostics with `docker compose -f examples/03-routing/compose.yaml logs -f app`.
Open `http://127.0.0.1:8080/notes`. Expect notes.
Retry the request if the listener is not ready yet. Set `APP_PORT=8081` before the command if port 8080 is occupied, and use that port in requests.
Compose exposes the app on host loopback. These credentials and unauthenticated fixtures
are for trusted local development. Direct Java runs use the default wildcard listener.

To build with Jib inside Docker instead, stop the stack and export/load its image:

```sh
docker compose -f examples/03-routing/compose.yaml down --timeout 20
docker buildx build -f examples/03-routing/Dockerfile --target jib --output type=local,dest=build/routing-example-jib .
docker load --input build/routing-example-jib/jib-image.tar
docker compose -f examples/03-routing/compose.yaml pull --ignore-buildable
docker compose -f examples/03-routing/compose.yaml up --no-build --pull never --force-recreate -d
```

Both paths use the same local image tag. Stop and remove the stack with
`docker compose -f examples/03-routing/compose.yaml down --timeout 20 --volumes`.
Use `docker compose -f examples/03-routing/compose.yaml logs` for diagnostics.
Optional `./gradlew` commands below require a local Java 21 installation;
container validation is described in [CONTRIBUTING](../../CONTRIBUTING.md#example-container-validation).

## Recipe and direct-run notes

These compiled factories support the routing recipes, with a complete test beside each example:

- HTTP methods: [example](src/main/java/io/github/suppierk/shoostr/example/routing/MethodsExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/routing/MethodsExampleTest.java).
- Path patterns: [example](src/main/java/io/github/suppierk/shoostr/example/routing/PathPatternsExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/routing/PathPatternsExampleTest.java).
- Route groups: [example](src/main/java/io/github/suppierk/shoostr/example/routing/RouteGroupsExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/routing/RouteGroupsExampleTest.java).
- Conditional routes: [example](src/main/java/io/github/suppierk/shoostr/example/routing/ConditionalRoutesExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/routing/ConditionalRoutesExampleTest.java).

Run `./gradlew :routing-example:test` from the repository root with Java 21.

The [combined launcher fixture](legacy-requests/README.md) provides a separate runnable demonstration.
