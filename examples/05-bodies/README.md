# 5. Bodies, forms and uploads

## Run with Docker

Use Docker Engine with Compose v2 or later and Buildx. Run from the repository root;
Java and Gradle are provided by the build image. Start one example at a time:

```sh
docker compose -f examples/05-bodies/compose.yaml up --build -d
```

Startup is asynchronous. Check diagnostics with `docker compose -f examples/05-bodies/compose.yaml logs -f app`.
POST the text `hello`:

```sh
curl --data-binary 'hello' http://127.0.0.1:8080/text
```

Expect `hello`.
Retry the request if the listener is not ready yet. Set `APP_PORT=8081` before the command if port 8080 is occupied, and use that port in requests.
Compose exposes the app on host loopback. These credentials and unauthenticated fixtures
are for trusted local development. Direct Java runs use the default wildcard listener.

To build with Jib inside Docker instead, stop the stack and export/load its image:

```sh
docker compose -f examples/05-bodies/compose.yaml down --timeout 20
docker buildx build -f examples/05-bodies/Dockerfile --target jib --output type=local,dest=build/bodies-example-jib .
docker load --input build/bodies-example-jib/jib-image.tar
docker compose -f examples/05-bodies/compose.yaml pull --ignore-buildable
docker compose -f examples/05-bodies/compose.yaml up --no-build --pull never --force-recreate -d
```

Both paths use the same local image tag. Stop and remove the stack with
`docker compose -f examples/05-bodies/compose.yaml down --timeout 20 --volumes`.
Use `docker compose -f examples/05-bodies/compose.yaml logs` for diagnostics.
Optional `./gradlew` commands below require a local Java 21 installation;
container validation is described in [CONTRIBUTING](../../CONTRIBUTING.md#example-container-validation).

## Recipe and direct-run notes

These compiled factories support request-body and upload recipes:

- Buffered input: [example](src/main/java/io/github/suppierk/shoostr/example/bodies/BufferedBodyExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/bodies/BufferedBodyExampleTest.java).
- Streamed input: [example](src/main/java/io/github/suppierk/shoostr/example/bodies/StreamingInputExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/bodies/StreamingInputExampleTest.java).
- Multipart fields and files: [example](src/main/java/io/github/suppierk/shoostr/example/bodies/MultipartExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/bodies/MultipartExampleTest.java).
- Saved uploads: [example](src/main/java/io/github/suppierk/shoostr/example/bodies/PersistUploadExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/bodies/PersistUploadExampleTest.java).

Run `./gradlew :bodies-example:test` from the repository root with Java 21.
