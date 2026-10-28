# 11 — WebSockets

## Run with Docker

Use Docker Engine with Compose v2 or later and Buildx. Run from the repository root;
Java and Gradle are provided by the build image. Start one example at a time:

```sh
docker compose -f examples/11-websockets/compose.yaml up --build -d
```

Startup is asynchronous. Check diagnostics with `docker compose -f examples/11-websockets/compose.yaml logs -f app`.
Open `http://127.0.0.1:8080/`. Expect the endpoint list; use the browser snippets below.
Retry the request if the listener is not ready yet. Set `APP_PORT=8081` before the command if port 8080 is occupied, and use that port in requests.
Compose exposes the app on host loopback. These credentials and unauthenticated fixtures
are for trusted local development. Direct Java runs use the default wildcard listener.

To build with Jib inside Docker instead, stop the stack and export/load its image:

```sh
docker compose -f examples/11-websockets/compose.yaml down --timeout 20
docker buildx build -f examples/11-websockets/Dockerfile --target jib --output type=local,dest=build/websocket-example-jib .
docker load --input build/websocket-example-jib/jib-image.tar
docker compose -f examples/11-websockets/compose.yaml pull --ignore-buildable
docker compose -f examples/11-websockets/compose.yaml up --no-build --pull never --force-recreate -d
```

Both paths use the same local image tag. Stop and remove the stack with
`docker compose -f examples/11-websockets/compose.yaml down --timeout 20 --volumes`.
Use `docker compose -f examples/11-websockets/compose.yaml logs` for diagnostics.
Optional `./gradlew` commands below require a local Java 21 installation;
container validation is described in [CONTRIBUTING](../../CONTRIBUTING.md#example-container-validation).

## Recipe and direct-run notes

Consumer recipes: [managed messages](../../site/src/content/docs/websockets/managed.mdx),
[object messages](../../site/src/content/docs/websockets/objects.mdx),
and [native listeners](../../site/src/content/docs/websockets/native.mdx).

| Recipe | Example | Test |
| --- | --- | --- |
| Managed messages | [ManagedWebSocketExample](src/main/java/io/github/suppierk/shoostr/example/websocket/ManagedWebSocketExample.java) | [Test](src/test/java/io/github/suppierk/shoostr/example/websocket/ManagedWebSocketExampleTest.java) |
| Binary objects | [TypedWebSocketExample](src/main/java/io/github/suppierk/shoostr/example/websocket/TypedWebSocketExample.java) | [Test](src/test/java/io/github/suppierk/shoostr/example/websocket/TypedWebSocketExampleTest.java) |
| Native listeners | [NativeWebSocketExample](src/main/java/io/github/suppierk/shoostr/example/websocket/NativeWebSocketExample.java) | [Test](src/test/java/io/github/suppierk/shoostr/example/websocket/NativeWebSocketExampleTest.java) |

Gradle project: `websocket-example`.

## Standalone WebSocket fixture

The consumer recipes use focused [managed callbacks](src/main/java/io/github/suppierk/shoostr/example/websocket/ManagedWebSocketExample.java)
with a [complete test](src/test/java/io/github/suppierk/shoostr/example/websocket/ManagedWebSocketExampleTest.java),
and a [native listener](src/main/java/io/github/suppierk/shoostr/example/websocket/NativeWebSocketExample.java)
with its [test](src/test/java/io/github/suppierk/shoostr/example/websocket/NativeWebSocketExampleTest.java).
The original launcher and container checks below remain available for repository maintenance.

Run `./gradlew :websocket-example:run`, then open `http://127.0.0.1:8080/`. Use that plain-text page's browser console
for the snippets below. Run one example at a time, or choose the Compose port override below.

[WebSocketExample](src/main/java/io/github/suppierk/shoostr/example/websocket/WebSocketExample.java)
keeps route definitions inline. Shoostr's `routes.websocket(...)` handles HTTP route matching and upgrade;
its configurator registers ordinary checked-exception-capable callbacks. Core's `WebSocketConnection`
owns sends and detached connection data. No native listener, annotations, extension or second server is needed.

Configuration runs once during registration; captured mutable variables are shared across connections.
Each connection has independent path/identity data and sequential callbacks using the application's execution mode.
Platform threads are the default; callbacks can use different threads, and idle connections retain no worker.
Sends wait for transport completion, not peer receipt. Binary inputs are owned `byte[]` values, safe to retain;
binary output is copied before transport ownership. Concurrent external sends are serialized in write-lock
admission order. The existing native-listener overload remains available for advanced Jetty operations.

### Endpoints

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

### Opt-in session-authenticated Origin admission

The default launcher and `application()` keep the development-only endpoints above unchanged.
To register the separate `/session-echo` recipe on your own application:

```java
var app = new Shoostr().sessions();
app.routes(WebSocketExample::sessionEcho);
```

This creates an unstarted, caller-owned app. Configure your trusted authentication flow and
session-cookie policy before startup. Only that authentication flow may set the existing
server-side session attribute `authenticated` to `true`; merely receiving an Authorization
header or a client-supplied flag is not authentication. No login endpoint or SSO flow is included.

The recipe's `beforeUpgrade` callback requires both:

- Exactly one `Origin` header with the literal value `https://app.example`. Replace that literal
  in the example with your trusted browser origin, including its scheme and any explicit port.
  Missing, foreign, malformed, literal `null`, duplicate and comma-combined values are denied.
- An existing session carrying the server-owned `authenticated=true` marker. Missing, unknown
  and anonymous sessions are denied. `request.getSession()` never creates a session during admission.

Denial is HTTP 403 before the managed listener is created or private messages can be exchanged;
it does not issue a new session cookie. The message callback retains neither the handshake
request nor its session. An authorized connection echoes text through the existing managed API.

Origin is not a credential: non-browser clients can forge it. This cookie-authenticated route
still requires Origin when an Authorization header is present. If you need missing-Origin
non-browser access, configure a separate route with independently verified non-cookie
authentication; that variant is deliberately not demonstrated here.

These tests verify server-side admission through real HTTP/WebSocket clients, not browser
cookie delivery or CORS enforcement. Use HTTPS/WSS and appropriate cookie settings in your
deployment. Authentication is checked at upgrade only: this recipe does not close established
connections when a session expires or a user logs out. Those lifecycle requirements remain
separate application work; no connection registry or browser automation is introduced.

### Try browser clients

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

### Exceptions, callbacks and ownership

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
use the application's configured execution mode. No preview APIs, background producer or example-owned executor is required.
Core owns app shutdown; close your client connections explicitly while testing.

### Tests and Docker

`./gradlew :websocket-example:test` uses the public app factory and a JDK WebSocket client connected through
`TestServer.baseUri()`. Assertions observe messages, handshake responses and close frames; fragmented client callbacks
are assembled before comparison. Waits are bounded, and clients/connections are closed.
The opt-in recipe's tests issue authenticated/anonymous fixture sessions through HTTP, exercise
the actual managed endpoint, and reuse its ordinary admission function in a native listener
factory to observe rejection before listener creation. Those session-issuer endpoints exist
only in tests and are not part of `application()` or the recipe.

```sh
./gradlew :websocket-example:jibDockerBuild
docker compose -f examples/11-websockets/compose.yaml up -d
docker compose -f examples/11-websockets/compose.yaml down
```

Set `APP_PORT=8081` if 8080 is occupied, and visit that port before using the console snippets. They target this
plain-HTTP demo; use `wss://` if adapting it to HTTPS. Compose publishes on host loopback; direct runs use core's
default listener. These unauthenticated examples are for trusted development only. The launcher logs through SLF4J with the example’s Logback backend.
