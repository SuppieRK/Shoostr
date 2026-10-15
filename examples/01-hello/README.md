# Hello and lifecycle

Start with Java 25: `./gradlew :hello-example:run`, then
`curl http://127.0.0.1:8080/hello` → `Hello, Shoostr!`.

The default listener binds all IPv4 interfaces (`0.0.0.0:8080`), so the same app
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
Docker Engine/Compose and Java 25 are prerequisites; no Dockerfile or registry push is needed.
The image runs as UID1000, binds internally on all interfaces, and Compose exposes
it only on host loopback. Set `APP_PORT=8081` for an occupied host port.

Verify without Docker: `./gradlew :hello-example:test`.
