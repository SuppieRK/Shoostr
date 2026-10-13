# Typed JSON and application error handling

Run `./gradlew :json-errors-example:run`, then:

```sh
curl -i -H 'Content-Type: application/json' -d '{"name":"Ada"}' http://127.0.0.1:8080/users
curl -i -H 'Content-Type: application/json' -d '{"name":" "}' http://127.0.0.1:8080/users
curl -i http://127.0.0.1:8080/users/help
curl -i http://127.0.0.1:8080/users/failure
```

The valid request returns201 and `{"name":"Ada"}`. Invalid input returns400,
`application/json`, and `{"error":"invalid_user"}` without exception details.
`/users/help` returns plain text despite the codec. The deliberate `/users/failure`
returns500 with `{"error":"internal_error"}`, not its internal exception message.

[JsonExample](src/main/java/io/github/suppierk/shoostr/example/json/JsonExample.java)
defines the endpoints inline and installs the codec. The separate
[User](src/main/java/io/github/suppierk/shoostr/example/json/User.java),
[UserCodec](src/main/java/io/github/suppierk/shoostr/example/json/UserCodec.java),
and
[UserErrors](src/main/java/io/github/suppierk/shoostr/example/json/UserErrors.java)
own validation, explicit JSON trees and scoped rendering respectively.
No application-model scanning, serialization annotations or schema inference occur.

The optional codec makes typed methods available while retaining ordinary byte/text
methods. It does not infer Content-Type or perform HTTP negotiation. Only malformed
JSON/input becomes400; unexpected encoding/I/O failures remain server errors.
`Routes.exception`/`TypedRoutes.exception` are core request handling, not extension
configuration. Unmatched router404s are separate from matched application exceptions.

Verify through live HTTP with `./gradlew :json-errors-example:test`.
Docker: `./gradlew :json-errors-example:jibDockerBuild`, then
`docker compose -f examples/03-json-errors/compose.yaml up -d`; cleanup is the same
command ending in `down`. Set `APP_PORT=8081` to change the host port.
