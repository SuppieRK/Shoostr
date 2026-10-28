# 2. Application configuration

## Run with Docker

Use Docker Engine with Compose v2 or later and Buildx. Run from the repository root;
Java and Gradle are provided by the build image. Start one example at a time:

```sh
docker compose -f examples/02-application/compose.yaml up --build -d
```

Startup is asynchronous. Check diagnostics with `docker compose -f examples/02-application/compose.yaml logs -f app`.
POST the text `hello`:

```sh
curl --data-binary 'hello' http://127.0.0.1:8080/echo
```

Expect `hello`.
Retry the request if the listener is not ready yet. Set `APP_PORT=8081` before the command if port 8080 is occupied, and use that port in requests.
Compose exposes the app on host loopback. These credentials and unauthenticated fixtures
are for trusted local development. Direct Java runs use the default wildcard listener.

To build with Jib inside Docker instead, stop the stack and export/load its image:

```sh
docker compose -f examples/02-application/compose.yaml down --timeout 20
docker buildx build -f examples/02-application/Dockerfile --target jib --output type=local,dest=build/application-example-jib .
docker load --input build/application-example-jib/jib-image.tar
docker compose -f examples/02-application/compose.yaml pull --ignore-buildable
docker compose -f examples/02-application/compose.yaml up --no-build --pull never --force-recreate -d
```

Both paths use the same local image tag. Stop and remove the stack with
`docker compose -f examples/02-application/compose.yaml down --timeout 20 --volumes`.
Use `docker compose -f examples/02-application/compose.yaml logs` for diagnostics.
Optional `./gradlew` commands below require a local Java 21 installation;
container validation is described in [CONTRIBUTING](../../CONTRIBUTING.md#example-container-validation).

## Recipe and direct-run notes

Compiled source fixtures for the consumer documentation:

- Shoostr-owned resource limits: [example](src/main/java/io/github/suppierk/shoostr/example/application/ServerOptionsExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/application/ServerOptionsExampleTest.java).
- Native Jetty listener, pool and HTTP settings: [example](src/main/java/io/github/suppierk/shoostr/example/application/NativeConfigurationExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/application/NativeConfigurationExampleTest.java).

Use Java 21. From the repository root, run `./gradlew :application-example:test`.
The factories return unstarted applications; the caller must close them.
