# Classpath assets and finite HTTP streams

Run `./gradlew :static-streaming-example:run`:

```sh
curl http://127.0.0.1:8080/assets/greeting.txt
curl -N http://127.0.0.1:8080/download
```

The asset returns `Hello from a classpath asset!`. The download streams three CSV lines:
`name`, `Ada`, `Grace`. For server-sent events and WebSockets, continue with
[03-sse](../03-sse/README.md) and [04-websocket](../04-websocket/README.md).
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
docker compose -f examples/08-static-streaming/compose.yaml up -d
docker compose -f examples/08-static-streaming/compose.yaml down
```

The live HTTP tests consume complete asset and CSV bodies. Container host
ports are loopback-only and can be changed with `APP_PORT`. Jib uses Java25 and a nonroot
user; Compose needs Docker Engine and Compose but ordinary tests do not.
