# Request inputs and dependency substitution

Run `./gradlew :requests-example:run`.

- `curl http://127.0.0.1:8080/greetings/Ada` → `Hello, Ada!`.
- `curl 'http://127.0.0.1:8080/search?tag=java&tag=HTTP%20API'` → `java,HTTP API`.
- `curl http://127.0.0.1:8080/welcome` → `Hello, World!`.
- `curl 'http://127.0.0.1:8080/welcome?name=Grace'` → `Hello, Grace!`.
- An explicitly blank name returns400; a missing name uses the default instead.

[RequestExample](src/main/java/io/github/suppierk/shoostr/example/requests/RequestExample.java)
supplies a concurrent-safe JDK function explicitly and defines routes inline for
decoded path and ordered repeated query values. There is no DI container or
dependency lookup. Empty search returns empty text.

[RequestExampleTest](src/test/java/io/github/suppierk/shoostr/example/requests/RequestExampleTest.java)
substitutes the greeting function before starting the actual app. `TestServer` owns
the app/client lifetime and uses an ephemeral loopback port. Requests use only
`test.send(request -> ...)`; assertions are ordinary JUnit. The harness does not
reset or own supplied dependencies. Run `./gradlew :requests-example:test`.

Docker: `./gradlew :requests-example:jibDockerBuild`, then
`docker compose -f examples/02-requests/compose.yaml up -d`.
Run the same curls; clean up with `docker compose -f examples/02-requests/compose.yaml down`.
Prerequisites and local/nonroot binding follow [the catalog](../README.md).
Set `APP_PORT=8081` if8080 is occupied.
