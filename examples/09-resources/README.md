# 9. Files and resources

## Run with Docker

Use Docker Engine with Compose v2 or later and Buildx. Run from the repository root;
Java and Gradle are provided by the build image. Start one example at a time:

```sh
docker compose -f examples/09-resources/compose.yaml up --build -d
```

Startup is asynchronous. Check diagnostics with `docker compose -f examples/09-resources/compose.yaml logs -f app`.
Open `http://127.0.0.1:8080/download`. Expect the CSV attachment résumé.csv.
Retry the request if the listener is not ready yet. Set `APP_PORT=8081` before the command if port 8080 is occupied, and use that port in requests.
Compose exposes the app on host loopback. These credentials and unauthenticated fixtures
are for trusted local development. Direct Java runs use the default wildcard listener.

To build with Jib inside Docker instead, stop the stack and export/load its image:

```sh
docker compose -f examples/09-resources/compose.yaml down --timeout 20
docker buildx build -f examples/09-resources/Dockerfile --target jib --output type=local,dest=build/resources-example-jib .
docker load --input build/resources-example-jib/jib-image.tar
docker compose -f examples/09-resources/compose.yaml pull --ignore-buildable
docker compose -f examples/09-resources/compose.yaml up --no-build --pull never --force-recreate -d
```

Both paths use the same local image tag. Stop and remove the stack with
`docker compose -f examples/09-resources/compose.yaml down --timeout 20 --volumes`.
Use `docker compose -f examples/09-resources/compose.yaml logs` for diagnostics.
Optional `./gradlew` commands below require a local Java 21 installation;
container validation is described in [CONTRIBUTING](../../CONTRIBUTING.md#example-container-validation).

## Recipe and direct-run notes

Each recipe has a compiled factory and a live HTTP test:

- Static mounts and SPA fallback: [example](src/main/java/io/github/suppierk/shoostr/example/resources/StaticResourcesExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/resources/StaticResourcesExampleTest.java).
- File downloads: [example](src/main/java/io/github/suppierk/shoostr/example/resources/DownloadExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/resources/DownloadExampleTest.java).
- Ranges and validators: [example](src/main/java/io/github/suppierk/shoostr/example/resources/FileCachingExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/resources/FileCachingExampleTest.java).

Run `./gradlew :resources-example:test` from the repository root with Java 21.
Filesystem mounts use Jetty resources without requiring secure directory streams.
Keep the publication directory and its ancestors controlled by the deployment owner.
The external-directory test uses a Windows junction or a symbolic link on other systems.
The tests create their own files and classpath resources in temporary directories.

The image includes immutable `/app/report.csv`. Set `EXAMPLE_REPORT` to a readable regular
file available inside the container to replace it; a blank or unreadable path fails before startup.
Mount an override read-only and keep it available for the application lifetime. The launcher
never deletes caller-owned files. Direct Java runs need `EXAMPLE_REPORT` unless that default path exists.
