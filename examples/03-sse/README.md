# Server-sent events

Run `./gradlew :sse-example:run`, then open `http://127.0.0.1:8080/`. The landing page is plain text; use its browser
console for the snippets below. Run one example at a time, or use the Compose port override described below.

[SseExample](src/main/java/io/github/suppierk/shoostr/example/sse/SseExample.java)
registers every route inline. `routes.sse`, `Response.EventStream` and `SseEvent`
come from Shoostr core: no codec, extension or background scheduler is required.

## Endpoints

| Path           | Observable result                                                                                                          |
|----------------|----------------------------------------------------------------------------------------------------------------------------|
| `/events`      | Two unnamed data events: `starting`, then `complete`                                                                       |
| `/progress`    | Comment and heartbeat, named `progress` and `done` events, multiline data, IDs and a 2000 ms retry delay                   |
| `/replay`      | Three fixed entries with IDs 1–3; resumes after `Last-Event-ID`, or returns empty 204 after ID 3                           |
| `/unavailable` | Deliberate failure before streaming; HTTP 500 with `Stream unavailable`                                                    |
| `/reports`     | `progress`, followed by a safe `failure` event in the default demo; a supplied successful report produces `result` instead |

Inspect complete event frames and HTTP headers with curl:

```sh
curl -N -i -H 'Accept: text/event-stream' http://127.0.0.1:8080/events
curl -N -i -H 'Accept: text/event-stream' http://127.0.0.1:8080/progress
curl -N -i -H 'Accept: text/event-stream' -H 'Last-Event-ID: 1' http://127.0.0.1:8080/replay
curl -i -H 'Accept: text/event-stream' -H 'Last-Event-ID: 3' http://127.0.0.1:8080/replay
curl -i -H 'Accept: text/event-stream' -H 'Last-Event-ID: invalid' http://127.0.0.1:8080/replay
curl -i -H 'Accept: text/event-stream' http://127.0.0.1:8080/unavailable
curl -N -i -H 'Accept: text/event-stream' http://127.0.0.1:8080/reports
```

The cursor-1 request sends only entries 2 and 3. Missing cursor means zero; malformed, negative, overflowing or
greater-than-3 cursors return 400 before streaming starts. The replay data is fixed demonstration data, not durable
storage. Comments (`: ...`) and heartbeats (`:`) do not dispatch browser data events.

## Try browser clients

From the landing page's console, receive named events and close the finite feed when `done` arrives. Named events use
`addEventListener`, not `onmessage`:

```js
const progress = new EventSource(new URL("/progress", location.href));
progress.addEventListener("progress", event => console.log(event.lastEventId, event.data));
progress.addEventListener("done", event => {
    console.log(event.lastEventId, event.data);
    progress.close();
});
progress.onerror = () => {
    console.warn("Connection ended");
    progress.close();
};
```

To observe automatic reconnection and replay termination:

```js
const replay = new EventSource(new URL("/replay", location.href));
replay.onmessage = event => console.log(event.lastEventId, event.data);
replay.onerror = () => console.log("Replay connection state:", replay.readyState);
// To stop this demonstration early: replay.close();
```

Expect entries 1–3 once. When the finite response ends, the browser reconnects with the last ID; the server's 204
response stops further reconnection. The other finite feeds must be explicitly closed by their clients to avoid repeated
requests. This follows the [EventSource protocol](https://html.spec.whatwg.org/multipage/server-sent-events.html).

Observe a business failure separately from a transport error:

```js
const report = new EventSource(new URL("/reports", location.href));
report.addEventListener("progress", event => console.log(event.data));
report.addEventListener("result", event => {
    console.log(event.data);
    report.close();
});
report.addEventListener("failure", event => {
    console.warn(event.data);
    report.close();
});
report.onerror = () => {
    console.warn("Transport ended");
    report.close();
};
```

## Exceptions and lifetime

`startEventStream()` commits the HTTP response. Before that point, normal
`app.exception(...)` handling can return a safe HTTP error. After it, the status cannot be replaced with 500: `/reports`
catches only its known business failure and sends a named `failure` event while retaining HTTP 200. That custom event is
different from EventSource's transport `error` event. Private exception details are logged with SLF4J, not sent to the
client.

The default report operation deliberately throws to make the failure observable; it is not a real job backend. Call
`SseExample.application(() -> "Report ready")`
to supply successful work. This JDK `Supplier<String>` remains caller-owned, must support concurrent calls, and must
return nonnull text. It runs on the handler thread after streaming begins, outside the HTTP test harness's ownership.
Transport-write `IOException`s are not caught as business failures; stop writing when the transport fails rather than
attempting another event on it.

Streams belong to the handler thread and finish when the handler returns. Do not retain the response/event writer, close
the response yourself, or write from background tasks. These finite examples do not implement a periodic heartbeat
service or unbounded producer. For general HTTP exception registration, continue
with [05-exceptions](../05-exceptions/README.md).

## Tests and Docker

`./gradlew :sse-example:test` runs live HTTP checks through `TestServer`, including exact frames, replay
validation/termination and both error boundaries.

```sh
./gradlew :sse-example:jibDockerBuild
docker compose -f examples/03-sse/compose.yaml up -d
docker compose -f examples/03-sse/compose.yaml down
```

Set `APP_PORT=8081` when 8080 is occupied, and open that port before running the browser snippets. Compose publishes on
host loopback; direct runs use the core default listener. These public, deliberately failing endpoints are for trusted
development only. See [the catalog](../README.md) for prerequisites and logging.
