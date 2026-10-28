# 6. Responses and HTTP primitives

## Run with Docker

Use Docker Engine with Compose v2 or later and Buildx. Run from the repository root;
Java and Gradle are provided by the build image. Start one example at a time:

```sh
docker compose -f examples/06-responses/compose.yaml up --build -d
```

Startup is asynchronous. Check diagnostics with `docker compose -f examples/06-responses/compose.yaml logs -f app`.
Open `http://127.0.0.1:8080/message`. Expect café.
Retry the request if the listener is not ready yet. Set `APP_PORT=8081` before the command if port 8080 is occupied, and use that port in requests.
Compose exposes the app on host loopback. These credentials and unauthenticated fixtures
are for trusted local development. Direct Java runs use the default wildcard listener.

To build with Jib inside Docker instead, stop the stack and export/load its image:

```sh
docker compose -f examples/06-responses/compose.yaml down --timeout 20
docker buildx build -f examples/06-responses/Dockerfile --target jib --output type=local,dest=build/responses-example-jib .
docker load --input build/responses-example-jib/jib-image.tar
docker compose -f examples/06-responses/compose.yaml pull --ignore-buildable
docker compose -f examples/06-responses/compose.yaml up --no-build --pull never --force-recreate -d
```

Both paths use the same local image tag. Stop and remove the stack with
`docker compose -f examples/06-responses/compose.yaml down --timeout 20 --volumes`.
Use `docker compose -f examples/06-responses/compose.yaml logs` for diagnostics.
Optional `./gradlew` commands below require a local Java 21 installation;
container validation is described in [CONTRIBUTING](../../CONTRIBUTING.md#example-container-validation).

## Recipe and direct-run notes

Each response recipe has a compiled factory and a live HTTP test:

- Text, bytes and empty responses: [example](src/main/java/io/github/suppierk/shoostr/example/responses/ResponseBodyExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/responses/ResponseBodyExampleTest.java).
- Response headers: [example](src/main/java/io/github/suppierk/shoostr/example/responses/ResponseHeadersExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/responses/ResponseHeadersExampleTest.java).
- Cookies and deletion: [example](src/main/java/io/github/suppierk/shoostr/example/responses/ResponseCookiesExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/responses/ResponseCookiesExampleTest.java).
- Redirects: [example](src/main/java/io/github/suppierk/shoostr/example/responses/RedirectExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/responses/RedirectExampleTest.java).

Run `./gradlew :responses-example:test` from the repository root with Java 21.
