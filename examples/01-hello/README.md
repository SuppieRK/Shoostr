# Hello and lifecycle

## Run with Docker

Use Docker Engine with Compose v2 or later and Buildx. Run from the repository root;
Java and Gradle are provided by the build image. Start one example at a time:

```sh
docker compose -f examples/01-hello/compose.yaml up --build -d
```

Startup is asynchronous. Check diagnostics with `docker compose -f examples/01-hello/compose.yaml logs -f app`.
Open `http://127.0.0.1:8080/hello`. Expect Hello, Shoostr!.
Retry the request if the listener is not ready yet. Set `APP_PORT=8081` before the command if port 8080 is occupied, and use that port in requests.
Compose exposes the app on host loopback. These credentials and unauthenticated fixtures
are for trusted local development. Direct Java runs use the default wildcard listener.

To build with Jib inside Docker instead, stop the stack and export/load its image:

```sh
docker compose -f examples/01-hello/compose.yaml down --timeout 20
docker buildx build -f examples/01-hello/Dockerfile --target jib --output type=local,dest=build/hello-example-jib .
docker load --input build/hello-example-jib/jib-image.tar
docker compose -f examples/01-hello/compose.yaml pull --ignore-buildable
docker compose -f examples/01-hello/compose.yaml up --no-build --pull never --force-recreate -d
```

Both paths use the same local image tag. Stop and remove the stack with
`docker compose -f examples/01-hello/compose.yaml down --timeout 20 --volumes`.
Use `docker compose -f examples/01-hello/compose.yaml logs` for diagnostics.
Optional `./gradlew` commands below require a local Java 21 installation;
container validation is described in [CONTRIBUTING](../../CONTRIBUTING.md#example-container-validation).

## Recipe and direct-run notes

Start with Java 21: `./gradlew :hello-example:run`, then
`curl http://127.0.0.1:8080/hello` → `Hello, Shoostr!`.

Jetty's default listener binds wildcard interfaces on port 8080, so the same app
works directly and in Docker without a Jetty connector override or host property.

[HelloExample](src/main/java/io/github/suppierk/shoostr/example/hello/HelloExample.java)
owns startup and defines its route inline. Shoostr installs its shutdown hook:
Ctrl+C stops the listener without another hook, latch or polling loop.
Use try-with-resources for bounded lifetimes, as the
[JUnit test](src/test/java/io/github/suppierk/shoostr/example/hello/HelloExampleTest.java)
does with `TestServer`. It chooses an ephemeral loopback port and closes the app.

The application logs through SLF4J and selects Logback as its runtime backend.
[logback.xml](src/main/resources/logback.xml) sends INFO and higher levels to stderr.
The runtime `slf4j-jdk-platform-logging` bridge sends Shoostr's existing
`System.Logger` events to the same backend as the application and Jetty.
The framework itself does not impose a logging backend or log every request.

Docker: `./gradlew :hello-example:jibDockerBuild`, then
`docker compose -f examples/01-hello/compose.yaml up -d`.
The same curl works. Stop with `docker compose -f examples/01-hello/compose.yaml down`.
The Docker-build procedures in “Run with Docker” need no host Java or registry push.
This direct Gradle alternative requires Java 21.
The image runs as UID1000, binds internally on all interfaces, and Compose exposes
it only on host loopback. Set `APP_PORT=8081` for an occupied host port.

Verify without Docker: `./gradlew :hello-example:test`.
