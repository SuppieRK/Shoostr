# Hello and lifecycle

Start with Java 25: `./gradlew :hello-example:run`, then
`curl http://127.0.0.1:8080/hello` → `Hello, Shoostr!`.

[HelloExample](src/main/java/io/github/suppierk/shoostr/example/hello/HelloExample.java)
owns startup and defines its route inline. Shoostr installs its shutdown hook:
Ctrl+C stops the listener without another hook, latch or polling loop.
Use try-with-resources for bounded lifetimes, as the
[JUnit test](src/test/java/io/github/suppierk/shoostr/example/hello/HelloExampleTest.java)
does with `TestServer`. It chooses an ephemeral loopback port and closes the app.

Docker: `./gradlew :hello-example:jibDockerBuild`, then
`docker compose -f examples/01-hello/compose.yaml up -d`.
The same curl works. Stop with `docker compose -f examples/01-hello/compose.yaml down`.
Docker Engine/Compose and Java 25 are prerequisites; no Dockerfile or registry push is needed.
The image runs as UID1000, binds internally on all interfaces, and Compose exposes
it only on host loopback. Set `APP_PORT=8081` for an occupied host port.

Verify without Docker: `./gradlew :hello-example:test`.
