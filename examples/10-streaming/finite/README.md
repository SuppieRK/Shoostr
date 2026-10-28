# Classpath assets and finite HTTP streams

## Run with Docker

Use Docker Engine with Compose v2 or later and Buildx. Run from the repository root;
Java and Gradle are provided by the build image. Start one example at a time:

```sh
docker compose -f examples/10-streaming/finite/compose.yaml up --build -d
```

Startup is asynchronous. Check diagnostics with `docker compose -f examples/10-streaming/finite/compose.yaml logs -f app`.
Open `http://127.0.0.1:8080/download`. Expect the CSV containing Ada and Grace.
Retry the request if the listener is not ready yet. Set `APP_PORT=8081` before the command if port 8080 is occupied, and use that port in requests.
Compose exposes the app on host loopback. These credentials and unauthenticated fixtures
are for trusted local development. Direct Java runs use the default wildcard listener.

To build with Jib inside Docker instead, stop the stack and export/load its image:

```sh
docker compose -f examples/10-streaming/finite/compose.yaml down --timeout 20
docker buildx build -f examples/10-streaming/finite/Dockerfile --target jib --output type=local,dest=build/static-streaming-example-jib .
docker load --input build/static-streaming-example-jib/jib-image.tar
docker compose -f examples/10-streaming/finite/compose.yaml pull --ignore-buildable
docker compose -f examples/10-streaming/finite/compose.yaml up --no-build --pull never --force-recreate -d
```

Both paths use the same local image tag. Stop and remove the stack with
`docker compose -f examples/10-streaming/finite/compose.yaml down --timeout 20 --volumes`.
Use `docker compose -f examples/10-streaming/finite/compose.yaml logs` for diagnostics.
Optional `./gradlew` commands below require a local Java 21 installation;
container validation is described in [CONTRIBUTING](../../../CONTRIBUTING.md#example-container-validation).

## Recipe and direct-run notes

Maintainer runtime fixture. Read the [finite-output recipe](../../../site/src/content/docs/streaming/output.mdx) for consumer code.

The documentation recipe uses [FiniteStreamingExample](src/main/java/io/github/suppierk/shoostr/example/streaming/FiniteStreamingExample.java)
and its [HTTP test](src/test/java/io/github/suppierk/shoostr/example/streaming/FiniteStreamingExampleTest.java).
The launcher below also serves classpath assets.

Run `./gradlew :static-streaming-example:run`:

```sh
curl http://127.0.0.1:8080/assets/greeting.txt
curl -N http://127.0.0.1:8080/download
```

The asset returns `Hello from a classpath asset!`. The download streams three CSV lines:
`name`, `Ada`, `Grace`. For server-sent events and WebSockets, continue with
[SSE fixture](../sse/README.md) and [WebSocket fixture](../../11-websockets/README.md).
Streams are handler-scoped: returning from the handler finishes output. Do not close the
response yourself, write after handler return, or create an unbounded background producer.
The download explicitly flushes its first CSV line before writing the remaining rows.

[StreamingExample](src/main/java/io/github/suppierk/shoostr/example/streaming/StreamingExample.java)
defines its routes inline and mounts only `/public` resources beneath `/assets`.
It does not expose the classpath root or arbitrary filesystem paths. Missing resources
return404; traversal outside the mount is rejected. There is no HTML renderer or UI toolchain.

```sh
./gradlew :static-streaming-example:test
./gradlew :static-streaming-example:jibDockerBuild
docker compose -f examples/10-streaming/finite/compose.yaml up -d
docker compose -f examples/10-streaming/finite/compose.yaml down
```

The live HTTP tests consume complete asset and CSV bodies. Container host
ports are loopback-only and can be changed with `APP_PORT`. Jib uses Java 21 and a nonroot
user; Compose needs Docker Engine and Compose but ordinary tests do not.
