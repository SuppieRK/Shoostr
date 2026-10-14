# WebSocket connections and message handling

Run `./gradlew :websocket-example:run`, then open `http://127.0.0.1:8080/`. Use that plain-text page's browser console
for the snippets below. Run one example at a time, or choose the Compose port override below.

[WebSocketExample](src/main/java/io/github/suppierk/shoostr/example/websocket/WebSocketExample.java)
keeps route definitions inline. Shoostr's `routes.websocket(...)` handles HTTP route matching and upgrade;
its configurator registers ordinary checked-exception-capable callbacks. Core's `WebSocketConnection`
owns sends and detached connection data. No native listener, annotations, extension or second server is needed.

Configuration runs once during registration; captured mutable variables are shared across connections.
Each connection has independent path/identity data and sequential callbacks on managed virtual threads.
Sends wait for transport completion, not peer receipt. Binary inputs are owned `byte[]` values, safe to retain;
binary output is copied before transport ownership. Concurrent external sends are serialized in write-lock
admission order. The existing native-listener overload remains available for advanced Jetty operations.

## Endpoints

| Path/input                                | Observable result                                                      |
|-------------------------------------------|------------------------------------------------------------------------|
| `/echo`, text or binary                   | Echoes complete messages; binary bytes are unchanged                   |
| `/rooms/alpha`, text `hello`              | Replies `alpha: hello`; another connection has independent context     |
| `/rooms/{room}` with `room.length() > 32` | HTTP handshake rejected with 400 and safe `Invalid room` body          |
| `/numbers`, text `21`                     | Replies `double=42`, including correct results at integer boundaries   |
| `/numbers`, malformed integer             | Replies `error: integer required`; the same connection stays usable    |
| `/numbers`, text `fail`                   | Deliberate application failure; close 1011, reason `Processing failed` |
| `/numbers`, binary                        | Close 1003, reason `Text messages required`                            |

The room path demonstrates copied per-connection context, not a chat server or broadcast registry. Connections retain
detached path parameters, never Shoostr's handshake request or upgrade response. The `fail` command is explicit
demo fault injection, not a production operation.

## Try browser clients

From the landing page's console, send text and binary only after `open`:

```js
const echo = new WebSocket("ws://" + location.host + "/echo");
echo.binaryType = "arraybuffer";
echo.onmessage = event => console.log(
    typeof event.data === "string" ? event.data : Array.from(new Uint8Array(event.data))
);
echo.onopen = () => {
    echo.send("hello");
    echo.send(new Uint8Array([0, 1, 255]));
};
echo.onclose = event => console.log("Closed:", event.code, event.reason);
echo.onerror = () => console.warn("Connection failed; inspect the Network panel");
// After both echoes: echo.close(1000, "Finished");
```

Expect `hello` and `[0, 1, 255]`. For path-specific context, create another connection to `/rooms/alpha` and send
`hello` from its `onopen` callback; the reply is `alpha: hello`. Change the path to `/rooms/beta` to see an independent
prefix. Close each connection with `close(1000, "Finished")` when done. Browser `close`,
`message` and `error` events are described in
the [WebSocket reference](https://developer.mozilla.org/en-US/docs/Web/API/WebSocket).

Recover from malformed input and then trigger a terminal failure:

```js
const numbers = new WebSocket("ws://" + location.host + "/numbers");
numbers.onmessage = event => console.log(event.data);
numbers.onclose = event => console.log("Closed:", event.code, event.reason);
numbers.onerror = () => console.warn("Connection failed");
numbers.onopen = () => {
    numbers.send("not a number");
    numbers.send("21");
};
// After both replies: numbers.send("fail");
```

Expect `error: integer required`, then `double=42`; sending `fail` subsequently closes with 1011 and the safe reason. To
observe 1003 instead, reload the page, open a new `/numbers` connection and send `new Uint8Array([1])` in its `onopen`.
The browser cannot expose the HTTP response body through `WebSocket.onerror`. Inspect its Network panel for a rejected
handshake, or use curl:

```sh
curl --http1.1 -i \
  -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
  -H 'Sec-WebSocket-Version: 13' \
  -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' \
  http://127.0.0.1:8080/rooms/abcdefghijklmnopqrstuvwxyz123456789
```

This room exceeds the example's length limit and returns HTTP 400, not a close frame: no WebSocket connection was
established. Ordinary HTTP GET to a WebSocket-only path is not an upgrade and receives 404.

## Exceptions, callbacks and ownership

During the handshake, Shoostr's normal route-group/app exception handlers can write HTTP error responses. After upgrade,
managed message callbacks do not run those HTTP handlers. The number callback catches malformed numeric input and
replies safely; other processing failures close with a fixed reason and log private details through SLF4J. WebSocket
close codes such as 1003/1011 are not HTTP status codes. Native protocol/transport errors are logged separately; Jetty
owns their connection closure.

Core admits the next application message after the previous callback returns. Native buffer/close acknowledgments
never depend on application observers. `onError` observes terminal failures; recover inside `onText`/`onBinary` when
the connection should remain usable. `onClose` runs after active work unwinds, but resource cleanup never depends
on it and it may not run during shutdown. `close()` initiates 1000 closure without waiting for the peer.

A busy callback can delay reading peer close or ping frames: immediate disconnect cancellation is not guaranteed.
App shutdown signals 1001 and interrupts managed work before transport drain, without waiting indefinitely for
code that ignores interruption. A supplied route executor controls only the HTTP handshake; subsequent callbacks
remain on managed virtual threads. No preview APIs, background producer or example-owned executor is required.
Core owns app shutdown; close your client connections explicitly while testing.

## Tests and Docker

`./gradlew :websocket-example:test` uses the public app factory and a JDK WebSocket client connected through
`TestServer.baseUri()`. Assertions observe messages, handshake responses and close frames; fragmented client callbacks
are assembled before comparison. Waits are bounded, and clients/connections are closed.

```sh
./gradlew :websocket-example:jibDockerBuild
docker compose -f examples/04-websocket/compose.yaml up -d
docker compose -f examples/04-websocket/compose.yaml down
```

Set `APP_PORT=8081` if 8080 is occupied, and visit that port before using the console snippets. They target this
plain-HTTP demo; use `wss://` if adapting it to HTTPS. Compose publishes on host loopback; direct runs use core's
default listener. These unauthenticated examples are for trusted development only. See [the catalog](../README.md) for
prerequisites and logging.
