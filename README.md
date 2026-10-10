# Shoostr

[Shoostr](https://github.com/SuppieRK/Shoostr) is a small experimental Java 25 HTTP framework with separate request/response arguments. The public API lives in `io.github.suppierk.shoostr`; public signatures are provisional. Register routes through `app.routes(routes -> { ... })`.

```java
var app = new Shoostr();
app.routes(routes -> {
    routes.get("/hello", (request, response) -> response.text("Hello"));
    routes.get("/progress", (request, response) -> response
        .startStream("text/plain; charset=utf-8")
        .write("started\n")
        .flush()
        .write("finished\n"));
}).start();
```

`Shoostr` implements `java.io.Closeable`. `start()` registers its JVM shutdown hook after starting the listener, so standalone servers need no application-managed hook. `close()` stops the listener and executor and removes that hook; repeated and concurrent close calls are harmless. A listener startup failure after route compilation cleans up acquired resources and prevents restart. A rejected route compilation leaves registration open for retry, including when a path callback is still active. Closing before startup ends registration and prevents startup. Shutdown failures from explicit `close()` are reported as `IOException`, and failures in the JVM hook are logged with the JDK logger.

`Closeable` also extends `AutoCloseable`: try-with-resources still closes the app when its block ends. Use it for scoped lifetimes such as tests; normal server setup can use the unscoped example above. Registration is thread-safe across the root and every nested scope and must finish before `start()`. No builder is involved.

For integration tests, add the separate `io.github.suppierk:shoostr-test` artifact as a
test dependency. `TestServer` starts an existing unstarted `Shoostr` on OS-selected loopback
ports and closes that same application at the end of a try-with-resources scope. Configure
routes, extensions, dependencies and native Jetty settings before startup through the ordinary
public API:

```java
var app = new Shoostr();
app.routes().get("/hello", (request, response) -> response.text("hello"));
try (var server = TestServer.start(app)) {
    var reply = server.send(request -> request.path("/hello"));
    // Assert reply.statusCode() and reply.body() (byte[]) with your test framework.
    var text = server.send(request -> request.path("/hello"),
        java.net.http.HttpResponse.BodyHandlers.ofString());
    // Or consume text.body() as a String through an explicit JDK BodyHandler.
}
```

Inside the fixture scope, fetch a response once, then use your testing framework's assertions.
For example, JUnit's ordinary multiple-assertion style needs no harness-specific DSL:

```java
var reply = server.send(request -> request.path("/hello"));
assertAll("hello response",
    () -> assertEquals(200, reply.statusCode()),
    () -> assertArrayEquals("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8), reply.body()));
```

Each fixture owns its supplied app and listener lifetime, not user dependencies or mocks.
Startup failures propagate
after cleanup; `close()` reports Jetty stop failures as `IOException`. For lifecycle
notifications, register Jetty's `LifeCycle.Listener` through `Shoostr.modifyServer` before
startup. No framework-specific lifecycle annotation is required. Run
`./gradlew consumerSmoke` to publish `http`, `core`, `test`, `pac4j`,
`micrometer` and `opentelemetry` into `build/consumer-repository` and execute
the independent Java 25 consumer under `examples/consumer-smoke`; this does not
publish to Maven Local or a remote repository. Its JUnit 5 tests exercise the
published optional integrations over HTTP. The separate base-runtime check
rejects optional integration, JUnit, JSpecify and common codec dependencies
from `core`; the original smoke checks native configuration, start/stop
notifications and error propagation. The example is also the `:consumer-smoke`
Gradle module: `./gradlew :consumer-smoke:test` and the root `build` run its
tests against project dependencies, while `consumerSmoke` verifies published
artifacts from the independent build.

Configure outgoing requests through the single `send(request -> ...)` interface:
`path`, `method`, repeated `header`, raw `body(byte[])` and `timeout(Duration)`.
`method` and `header` also accept existing `HttpMethods` and `HttpHeaders` values.
GET/no body and a ten-second request timeout are defaults. Paths are explicitly required,
relative to the fixture's origin, and may include encoded queries; schemes, authorities
and fragments are rejected. Each request has fresh configuration and copies body bytes.
No JSON conversion, response wrapper or assertion DSL is involved. The fixture owns its
JDK client and cookie state; retain it across test methods if shared app/cookies are desired,
and close it in your testing framework's teardown. It does not reset mocks or dependencies.
Callers own returned streams/subscriptions; close or consume them. Fixture cleanup cancels
outstanding client work before stopping the app instead of waiting indefinitely for a body.
Client termination waits at most three seconds; application shutdown still uses its configured
native drain timeout and reports stop failures. The fixture does not finish caller-owned work,
reset dependencies or replace native shutdown settings to hide a blocked handler.

For a shared fixture, assign `TestServer.start(app)` in your framework's before-all setup
and call `close()` in after-all teardown. The same application, dependency and cookie state
remains available across test methods; resets are your responsibility. See the executable
JUnit 5 [shared fixture example](test/src/test/java/io/github/suppierk/shoostr/testing/SharedFixtureTest.java).
If additional setup fails after startup, close the fixture before propagating the failure.

Shoostr's own ordinary routing tests use this same harness through a core test-only
dependency. The build order is core production → harness production → core tests;
core production and the published `shoostr-core` artifact do not depend on `shoostr-test`.
Native transport, lifecycle and malformed-wire tests retain direct facilities where needed.

The owned client defaults to isolated cookies, redirects disabled, no system proxy and a
three-second connect timeout, with normal JDK protocol negotiation and TLS verification.
Customize it only when needed; the callback runs after these defaults:

```java
try (var server = TestServer.start(app,
        client -> client.followRedirects(java.net.http.HttpClient.Redirect.ALWAYS))) {
    var reply = server.send(request -> request.path("/redirect"));
}
```

Ordinary requests use only `server.send(request -> ...)`. For native async, WebSocket or
advanced body-publisher tests, create and close an independent client and connect it to
`server.baseUri()`. The harness never exposes its owned client. Caller-supplied executors,
external clients and returned streams/subscriptions remain caller-owned. A request timeout
is not an independent deadline for reading a returned streaming body.

Automatic startup binds every supported listener to `127.0.0.1:0`, preserving its native
TLS/HTTP2 configuration. For a stable browser/debugging URL, use
`TestServer.startOnPort(app, 8081)`; an occupied port fails instead of choosing another.
`TestServer.startOnPortRange(app, 8081, 8090)` tries the inclusive range in ascending order,
retains the first successful binding and fails if exhausted. Both named factories require
exactly one unbound Jetty `ServerConnector`, accept ports from 1 through 65535 and offer the
same optional client-builder callback. Automatic startup supports multiple/reordered
listeners; `baseUri()` always reports the application's original default listener.
Fixed ports help browser reuse, not authentication or cookie isolation. The fixture never
widens loopback binding or disables application security settings. Range selection finishes
within one app startup attempt, and reserved sockets are released on failure and shutdown.

Configure Jetty directly before startup, using ordered callbacks similar to Javalin:

The native pool is Jetty's `VirtualThreadPool`, using Shoostr's owned virtual-thread executor
without a concurrent-task cap. Default handlers run on virtual threads; endpoint overloads can
select a caller-owned executor without changing transport execution. Native customizers must not cast the pool
to `QueuedThreadPool` or depend on its platform-thread sizing methods.

```java
app.modifyHttpConfiguration(http -> http.setRequestHeaderSize(16 * 1024));
app.modifyServer(server -> {
    server.setStopTimeout(10_000);
    for (var connector : server.getConnectors()) {
        ((org.eclipse.jetty.server.AbstractConnector) connector).setShutdownIdleTimeout(2_000);
    }
});
```

HTTP callbacks run after HTTP defaults and before constructing the default connector. Server callbacks then run after the connector and application handler are installed, before startup. Each group runs in registration order, so later native settings win. Jetty is an exposed API dependency; there is no second framework property for each Jetty setting. `Options` retains its existing bind and resource-limit conveniences. Configure native connectors, HTTP limits, request logging and the existing thread pool through these callbacks. Additional connectors and wrappers around the installed handler are supported; `app.port()` continues to report the original default connector. Preserve that connector and the installed handler, and leave server start/stop and the JVM shutdown hook to Shoostr. Callback failure closes acquired resources and prevents retry. Separately supplied executors remain caller-owned unless explicitly managed by Jetty.

Shoostr uses Jetty's `GracefulHandler`: requests reaching it after drain begins receive 503, while admitted finite and streaming responses may complete. Native server shutdown also stops acceptance, so new connections may be refused instead of receiving 503. The server grace budget defaults to **5 seconds** (project policy); the connector retains Jetty's native **1-second** shutdown idle timeout. These are independent native settings and both can be overridden as above. Raising only `Server.setStopTimeout` does not raise the connector idle timeout. The connector timeout closes idle keep-alive connections and can fail pending I/O; it does not automatically terminate a running handler. Setting it equal to or above the server grace budget can make idle connections exhaust that budget. A zero server stop timeout skips graceful waiting. Once Jetty stops, Shoostr interrupts remaining owned virtual-thread tasks without waiting indefinitely for code that ignores interruption. Native stop failures, including an exhausted grace budget, surface through `close()` as `IOException`; cleanup still runs. The grace budget is not a hard wall-clock deadline for arbitrary Jetty lifecycle components or application code.

The same Shoostr-owned shutdown applies to TLS and HTTP/2, including clear-text h2c. Active finite and handler-scoped streaming exchanges can drain within the configured budget; a forced stop interrupts remaining owned tasks and closes the connector and in-memory session cache. Applications define their own liveness and readiness routes. In managed deployments, mark the application unready before calling `app.close()` so upstream traffic can stop before the listener does.

The configuration pattern follows [Javalin 7.2.3's native callbacks](https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/main/java/io/javalin/config/JettyConfig.kt). [Jooby 4.5.4's optional GracefulShutdown](https://github.com/jooby-project/jooby/blob/v4.5.4/jooby/src/main/java/io/jooby/GracefulShutdown.java) instead implements an application filter and stop callback, with an indefinite no-argument wait. This framework uses [Jetty 12.1.11's GracefulHandler](https://github.com/jetty/jetty.project/blob/jetty-12.1.11/jetty-core/jetty-server/src/main/java/org/eclipse/jetty/server/handler/GracefulHandler.java) and native stop settings directly.

Java 25 is required; the Gradle wrapper supplies the build tool. Run `cmdshape ./gradlew clean spotlessApply build` to apply formatting, compile, and execute the JUnit 5 test suites and quality checks. Use `gradlew.bat` on Windows. Run end-to-end diagnostics through the manual [benchmark workflow](.github/benchmarks/README.md); each workload publishes k6 and JFR artifacts and an Actions summary.

Run `cmdshape ./gradlew mutationTest` for diagnostic PiTest analysis of `core`, `http`,
`micrometer`, `opentelemetry`, `pac4j` and `test`. It produces native HTML/XML
reports and is independent of `build`, `check` and CI, with no mutation-score gate.
See [mutation testing](core/MUTATION_TESTING.md) for scope, module-specific commands,
report locations and interpretation. The initial full baseline took 54 minutes.

Install a JDK 25 in the environment running the build (Windows and WSL have separate JDK installations). The repository's `gradle/gradle-daemon-jvm.properties` selects Java 25 for Gradle itself, including `buildSrc`, even when the wrapper is launched with Java 21. This uses an installed JDK; automatic JDK downloads are not configured. Run `cmdshape ./gradlew --version` to check the daemon JVM criteria. In IntelliJ IDEA, reload the Gradle project after installing JDK 25 and check that Gradle JVM criteria shows Java 25 under Settings → Build, Execution, Deployment → Build Tools → Gradle (older versions expose a Gradle JVM selector). Setting only the project's language level does not select Gradle's JVM. See [Gradle's daemon JVM documentation](https://docs.gradle.org/current/userguide/gradle_daemon.html#sec:daemon_jvm_criteria) and [IntelliJ Gradle settings](https://www.jetbrains.com/help/idea/gradle-settings.html).

The dependency-free [`http` library](http/README.md) provides `HttpMethods` for typed route registration and `io.github.suppierk.shoostr.http.HttpHeaders`: an immutable value class with 258 documented built-in headers from the IANA HTTP Field Name Registry. `core` depends on and exposes this library. Use `.value()` for a header's wire name, `HttpHeaders.of(String)` for a validated application-defined field name, and `HttpHeaders.httpHeader(String)` for case-insensitive built-in lookup returning an `Optional`; unrecognized names return an empty result. Obsoleted built-ins carry `@Deprecated`. Built-in content-type setters use known header names and reuse common pre-encoded fields; custom header names and all caller-supplied values remain validated.

`cmdshape ./gradlew build` runs the shared Checkstyle configuration (`config/checkstyle/checkstyle.xml`), with `java.xml` for production and benchmark sources and `java-test.xml` for tests, plus Spotless checks for Java and Gradle files. Spotless uses Google Java Format and Groovy-Eclipse defaults. Run `cmdshape ./gradlew spotlessApply` to apply formatting, or `cmdshape ./gradlew check` for verification without assembling distributions. Formatting is checked, not silently applied, by normal builds.

Before final validation and review, use `cmdshape ./gradlew clean spotlessApply build`. Run one Gradle invocation at a time in a checkout; overlapping `clean` and test tasks can invalidate shared outputs. To investigate a slow build, use `cmdshape --raw ./gradlew build --console=plain --profile` for unfiltered progress and a local task-timing report under `build/reports/profile`. HTTP test fixtures close their client before stopping the server, releasing keep-alive connections before graceful shutdown; preserve this cleanup order when adding tests.

Checkstyle requires braces for every `if` and `else` body. A build-only syntax-aware rule requires a blank line before `try` when another statement precedes it in the same block, and after a complete `if`/`else` or `try`/`catch`/`finally` statement when another statement follows. A `try` at the start of a block needs no blank line, including when preceded only by a comment. No extra separator is required before an enclosing closing brace; `else`, `catch` and `finally` remain attached. Spotless applies these spacing rules after Google Java Format, using the same syntax boundaries, so formatting and verification agree. The rule and its JUnit syntax tests are included in `check`; neither is a runtime library dependency.

Outside test sources, every explicitly declared class, interface, enum, record, constructor, and method needs Javadoc, including private/package-private members, overrides, accessors, local types, and anonymous-class methods. These rules cover production, benchmark fixtures, and build logic. Javadoc must explain behavior and relevant ownership/lifecycle constraints, with parameter, return, and thrown-exception tags where applicable; compiler-generated members need no source comments. Test sources must not contain Javadocs: use precise behavior-oriented test names instead. Their separate Checkstyle profile enforces this distinction.

Instance fields must be initialized in constructors, not at their declaration or in instance initializer blocks. This applies to object creation, factory calls, literals, arrays, and lambdas in all checked source sets. Static field initialization, implicit interface constants, local variables, and ordinary later field assignments remain allowed. The rule uses Checkstyle's built-in `MatchXpath` check.

Equality calls must put the known constant on the receiver side. Use `HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)` for a null-safe comparison of a header wire name. Checkstyle rejects variable-first `equals`/`equalsIgnoreCase` calls against string literals, uppercase constants (including qualified and statically imported constants), and their zero-argument `value()`, `name()`, or `toString()` forms. The rule recognizes source syntax and the uppercase constant convention; it does not resolve types or prove that an arbitrary constant/accessor is non-null. Enum identity comparisons with `==` and `!=` remain valid only for enum types, and accessor results used as receivers must be non-null by contract.

Tests use JUnit Jupiter 5.14.4 through Gradle's standard `test` tasks, including the Checkstyle tests in the root project. There are no executable test `main()` methods or custom assertion counters. Run `cmdshape ./gradlew test :core:test :http:test` for all test suites, or `cmdshape ./gradlew build` for tests plus all style checks. HTML test reports are under each project's `build/reports/tests/test/` directory.

Finite output is staged and sent after successful handler return. `startStream` commits headers immediately; full buffers or `flush()` emit during the handler. `Response.input(InputStream, contentType)` reads through the bounded streaming buffer and closes its supplied source on success or failure without staging full content. `Response.file(Path, contentType)` instead stages a Jetty resource transfer, so the framework owns reading and terminal completion after the handler returns. The framework sends remaining buffered data and terminates the stream when the handler returns; bodyless statuses and HEAD requests close supplied input sources without reading them. `Response` implements `AutoCloseable`, but application code must not close it. Request, response, and stream access is confined to the handler's thread and lifetime. A handler failure discards an uncommitted response and sends an error; a committed stream is aborted.

Both `Stream.write` overloads and `flush()` return the same stream for chaining.
Both `EventStream.send` overloads, `comment()` and `heartbeat()` return the same event
writer. Chaining preserves buffering, blocking flushes, and error propagation; the
framework still completes output when the handler returns. Consumers compiled against
the earlier `void` signatures must recompile.

`Routes.sse` registers a GET endpoint and composes under `Routes.path(...)`. It requires the
handler to start a UTF-8 event stream or return 204 to stop browser reconnection:

```java
app.routes().sse("/events", (request, response) -> response
    .startEventStream()
    .send(SseEvent.of("ready").withEvent("update").withId("cursor-1"))
    .heartbeat());
```

The event writer frames multiline data, comments, IDs and retry delays. `send`, `comment`, and
`heartbeat` flush complete frames and backpressure on the response's bounded stream. The handler
must remain active for as long as it wants to publish; its return ends the stream. Applications
own event sources, bounded subscription queues, reconnection policy, and any replay keyed by the
incoming `Last-Event-ID` header. A timed source poll followed by a heartbeat write is the way to
observe an idle disconnect; a buffered successful write does not prove the peer is still present.

`Routes.websocket` composes under `path(...)` and takes a
`BiFunction<Request, ServerUpgradeResponse, Session.Listener>`. The factory runs once per
accepted upgrade attempt, after application and route admission. It can inspect the live
framework request, read Jetty-parsed offers with `request.webSocketProtocols()`, set a
client-offered subprotocol or response header, and return a fresh Jetty listener:

```java
app.routes().websocket("/chat/{room}", (request, upgrade) -> {
    if (request.webSocketProtocols().contains("chat.v1")) {
        upgrade.setAcceptedSubProtocol("chat.v1");
    }
    return new ChatListener(request.pathParam("room").orElseThrow());
});
```

`ChatListener` represents an application-defined, publicly accessible
`Session.Listener` implementation. It owns connection state after upgrade; it must not
retain the HTTP `Request` or `ServerUpgradeResponse`. The factory must return a non-null
listener and must leave successful response writing and callback completion to Jetty.
A factory exception goes through the application's registered exception handler, if
present; otherwise `HttpException` selects its status and other failures or a null
listener produce 500. `afterRequest` records the initiating factory failure. Admission
policies can reject an Origin or unauthenticated request before the
factory runs. An ordinary GET and a WebSocket route may share a path, with first
registration winning among routes of the same kind. Jetty handles framing, limits,
ping/pong, close, and session shutdown through its native listener/container.

With `app.http2()`, the same WebSocket registration also accepts HTTP/2 extended
CONNECT over h2c or TLS/ALPN. HTTP/1.1 still uses GET/101; HTTP/2 uses CONNECT/200.
`Request.method()` retains that wire method, and route callbacks, authentication
and runtime availability run before listener creation on both transports. Other
extended CONNECT protocols remain ordinary CONNECT routes. Jetty owns negotiation
and frame delivery; no separate protocol handler or route registration is needed.

The default Jetty container permits at most **32 pending outgoing frames per session**;
the native `ServerWebSocketContainer.setMaxOutgoingFrames` and
`Session.setMaxOutgoingFrames` override it. Configure the container from
`Shoostr.modifyServer` before startup, or configure an individual session in
`onWebSocketOpen`. Jetty's `Session` also exposes text/binary message and frame-size
limits. An outgoing-frame limit bounds queued frames, not the byte size of each frame;
applications must bound their own producer queues. Excess sends fail their callback.
For ordered asynchronous replies, call `Session.demand()` on open and again only after
each send callback succeeds; on failure, close or disconnect the session. Concurrent
producers must coordinate their own send order. For example, inside a publicly
accessible listener holding its own `session` field:

```java
@Override
public void onWebSocketText(String text) {
    session.sendText(text, Callback.from(session::demand, failure -> session.disconnect()));
}
```

For multiple producer threads, use a bounded queue per session and let each successful
send callback start the next queued send. Stop the queue and disconnect on callback
failure. `WebSocketRoutesTest` exercises that ordering pattern and a peer that stops
reading until Jetty rejects excess queued frames.

See [Jetty's listener API](https://javadoc.jetty.org/jetty-12.1/org/eclipse/jetty/websocket/api/Session.Listener.html)
for demand rules, and [Jetty's configuration source](https://github.com/jetty/jetty.project/blob/jetty-12.1.11/jetty-core/jetty-websocket/jetty-websocket-core-common/src/main/java/org/eclipse/jetty/websocket/core/Configuration.java)
for the native limits.

`Response.file(Path, contentType)` also serves a seekable default-filesystem file representation through Jetty's resource transfer, without loading it into a byte array. It provides file ETag/Last-Modified metadata, `Accept-Ranges: bytes`, conditional `304`/`412` responses, and one `bytes` range (`206`/`416`). Suffix and open-ended ranges are supported. Malformed, unsupported, overflowing, repeated, and multi-range fields deliberately fall back to the full `200` representation; multipart byte ranges are not supported. `HEAD` ignores Range and sends the full representation metadata without file content. `If-Range` accepts only a matching strong ETag; Jetty's generated weak ETag supports cache validation but not range resumption. See [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html#section-13) for the conditional precedence this follows.

Mount static directories through `Routes`, so they compose with `path(...)` and retain the same
matched-route hooks, gates, error handling, logging, limits, completion observers, file validators,
ranges, and HEAD behavior as endpoint files:

```java
app.routes().staticFiles("/assets", Path.of("public"));
app.routes().classpathResources("/ui", "/public");
app.routes().path("/api", routes -> routes.staticFiles("docs", Path.of("docs")));
app.routes().staticFiles(
    "/spa", Path.of("spa"),
    StaticOptions.defaults().withWelcomeFile("index.html").withSpaFallback("index.html"));
```

Explicit endpoint routes take precedence over mounted resources. By default, a mount serves existing
readable regular files only; missing paths and directories return 404. `StaticOptions` can enable
a welcome file at the mount root and in nested directories, and a mount-root SPA fallback for
otherwise missing GET/HEAD resources. Configured names must be simple filenames. These options do
not enable directory listing; a selected file that disappears after route admission returns 404.
Encoded traversal is rejected by Jetty before dispatch; under the default URI policy,
double-encoded traversal, encoded backslashes and NUL return 400 for filesystem and classpath mounts.
Decoded traversal and symlinks outside a filesystem mount are rejected by the mount. Filesystem
mounts require secure directory operations from their filesystem provider and retain an anchored root
descriptor for the app lifetime, so a later replacement of the mounted pathname cannot redirect a
request outside that directory. Static files use filename-based MIME detection with a binary fallback. Filesystem mounts generate
weak descriptor-relative ETags and Last-Modified validators; classpath mounts retain Jetty resource
validators. Both support range
support, and `Cache-Control: no-cache` by default. Set a more specific cache policy in an explicit
endpoint when required.

Use `Response.attachment(path, contentType, filename)` for a download. It sends both an ASCII
`filename` fallback and UTF-8 RFC 8187 `filename*` value, while rejecting control and path-separator
characters in the supplied filename.

Throw an exception from `io.github.suppierk.shoostr.http.exceptions`, such as `new NotFoundException()`, to select its HTTP error status. By default, before commitment, core replaces all staged headers and body with that status and its standard reason phrase as UTF-8 plain text. Custom subclasses of `HttpException`, `HttpClientException`, or `HttpServerException` use the same mapping. Built-in responses never expose exception messages, causes, or stack traces. Other exceptions produce `500 Internal Server Error`; core does not unwrap causes to select a status. Fatal Java `Error` instances fail the request directly.

### Extensions and live availability

Install optional capabilities by instance and configure their use alongside the submitted handler:

```java
app.authentication(security).extensions(devDocs, publicDocs).routes(routes -> {
  routes.path("/users", users -> {
    users.get("/{id}", getUser, e -> e.get(publicDocs).summary("Find one user"));
    users.get("/search", searchUsers);
  }, e -> {
    e.get(security).required();
    e.get(publicDocs).tag("users").summary("Users");
  });
  routes.get("/openapi.json", publicDocs.handler(), e -> e.get(security).required());
});
```

`devDocs` and `publicDocs` illustrate optional library instances; core does not ship OpenAPI.
`extensions(first, additional...)` and `authentication(...)` return the same app. Select the exact
installed instance with `e.get(instance)`; class/name lookup is absent. Authentication has one
dedicated app slot, rejected by ordinary `extensions(...)`. Groups configure once. Each child
receives independent provider configuration, including inherited fields that a local setter does
not override. Reusing a Handler across paths does not merge those endpoint definitions.

Route callbacks may register `onRouteMatched`, `beforeRouteHandler`, `afterRouteHandler`,
`beforeResponseFlush`, `afterResponseFlush`, `afterRequest`, `exception` and `status` directly on
`Extensions`. Match and ordinary lifecycle hooks run local first, then app; a thrown exception
stops the ordinary phase. After matched hooks and known-length body validation, admission runs
managed authentication, inherited/selected extension contributions in first-selection order,
direct local gates, app gates, then availability.
The endpoint follows only on success. Matching local exception/status renderers handle the response
alone; app rendering applies when no local match exists. Terminal observers remain failure-isolated
and run local before app after application cleanup and transport completion. Header admission and
instrumentation stay app-wide because they precede route lookup. Handshake callbacks apply to HTTP
admission, not later WebSocket messages; static mounts and SSE inherit group callbacks too.

Use runtime conditions for feature flags or authenticated request decisions:

```java
routes.when(featureFlag::enabled, enabled -> enabled.get("/preview", previewHandler));
routes.when(request -> countryAvailable(request), regional -> regional.get("/regional", handler));
```

Each registration callback runs once; predicates run per request after admission. Nested conditions
short-circuit outer to inner. False renders terminal 404 with `Cache-Control: no-store` and does not
fall through to another route. Method discovery/405 and CORS preflight remain unchanged, so this is
availability control, not route concealment. Successful response caching remains application-owned;
cached responses never consult a live predicate. Use ordinary Java conditional registration for
deployment-specific absence: `if (development) routes.get("/debug", debugHandler);`.

Extension authors implement `Extension<C>`. Its `configure(Extensions, C inherited)` method returns
a fresh typed configurator, copies parent values when present, and registers its own callbacks.
Select collaborating capabilities in the route/group callback; provider-side nested `get` is rejected.
Repeated `get(instance)` within one scope returns the same configurator. Provider contributions run
in first-selection order, followed by directly registered local callbacks; inherited ordinary hooks
retain outer-to-inner order. Avoid capturing mutable configurators in runtime callbacks: capture
the selected values. Core snapshots callback collections and closes registration surfaces before
traffic; it does not police arbitrary provider-owned setters.

Internally, each endpoint with local runtime behavior receives an immutable `EndpointBehavior`
during registration. It combines inherited and local authentication, hooks, renderers
and availability checks; matching requests reuse it without rebuilding collections or consulting
an extension registry. Plain and metadata-only bindings retain no behavior object. Installed but
inactive extensions still have app installation state, but add no behavior objects to the frozen
router. Registration allocates temporary configurators; active behavior increases retained router
memory and the live graph traversed by GC, rather than recreating its configuration per request.
Hook execution and collection iteration can still allocate, and captured application objects affect
retained memory too.

The existing local JOL fixture estimated identical plain/inactive frozen-router sizes to baseline.
Across 2,000 bindings, its callback configuration added 208,048 retained bytes and authentication
added 112,016 bytes. These are complete graph differences for those fixtures, not record sizes or
universal overheads. Paired JMH detected no material plain/inactive regression, but noisy timings
cannot rule out small costs. These figures are historical local measurements;
their raw recordings are retired during the Actions migration. Renaming this internal type
changes terminology only, not execution logic or object layout.

`install(ApplicationCallbacks)` runs eagerly and preserves written app callback order within
each lifecycle phase. This registration-only collector offers `onRequestHeaders`, `onRouteMatched`,
`beforeRouteHandler`, `afterRouteHandler`, `beforeResponseFlush`, `afterResponseFlush`,
`afterRequest` and `observe`. It exposes no app/router, infrastructure settings or renderers,
and rejects retained registration after installation returns or throws. Installation itself
does not enable app-wide behavior: the extension must explicitly configure that contribution.
Cold
`Extensions.onRoute((method, pathTemplate) -> ...)` notifications arrive after registration freezes
and before `Extension.beforeStart()`. They describe actual ordinary HTTP/SSE bindings, including an
explicitly registered documentation handler's own route, without executing handlers. WebSocket
handshakes and static mounts produce no invented HTTP documentation paths. Generate final metadata
in `beforeStart()`. Explicitly register any serving handler yourself and protect it normally.
The app closes owned extension resources in reverse installation order; borrowed dependencies
remain the application's responsibility. Bad installation batches have no setup effects. Provider
setup/finalization failure closes the app and prevents restart. Native asynchronous protocol
lifetimes, schemas, UI serving and codec integration belong to their respective optional adapters.

Protection belongs to selected extensions, not a core `Routes.protect` method. Matched hooks run
before admission; managed authentication runs first, then extension before-handler contributions
in inherited/first-selection order, direct local callbacks, and application callbacks. Availability
runs after admission and before the endpoint. Repeated selection or configuration does not reorder
extensions. These admission callbacks throw to reject access and cannot start streaming. Public
siblings remain independent; selection on groups is inherited by their HTTP routes, static mounts
and WebSocket handshakes. Shared callbacks must be thread-safe. Header-only authentication does not
read the request body.

The optional `pac4j` module provides real direct-client authentication and authorization with
pac4j 6.5.8. Core has no pac4j dependency. Choose provider artifacts explicitly:

```groovy
implementation project(':pac4j')
implementation 'org.pac4j:pac4j-http:6.5.8'
implementation 'org.pac4j:pac4j-jwt:6.5.8'
```

For a Bearer API, supply an application-configured `JwtAuthenticator` to a pac4j `HeaderClient`
and an optional pac4j `Authorizer`:

```java
var client = new HeaderClient("Authorization", "Bearer ", jwtAuthenticator);
var security = new Pac4j(client, "Bearer", authorizer);
app.authentication(security).routes(routes ->
    routes.get("/me", (request, response) -> response.text(request.principal().orElseThrow().getName()),
        extensions -> extensions.get(security).required()));
```

Import `io.github.suppierk.shoostr.pac4j.Pac4j`. Configure the client, authenticator, and authorizer
before registration and keep them unchanged while serving. The application owns keys, identity
stores, accepted issuers/audiences, credential trust and permission decisions; the adapter does
not invent token validation or add defaults for these. The two-argument `Pac4j(client, challenge)`
constructor requires authentication without an additional authorization check. Basic clients and
explicitly configured parameter clients are supported too; parameter access combines query values
then form values, with existing framework decoding and body limits. Header clients leave bodies
untouched. Provider-specific extractors retain their configured matching semantics.

The adapter runs pac4j extraction, credential validation, and profile creation, rejects expired
profiles, then sets
`Request.principal()`. Authorizers receive the validated pac4j profile. Missing/invalid credentials
and duplicate Authorization fields yield 401; a denied permission yields 403. Provider failures use
the existing global error path without exposing diagnostic messages. Required 401 challenges survive
global error rendering via `AuthenticationRequiredException`, which remains catchable as
`UnauthorizedException`. If a custom renderer changes the status, the challenge is only enforced for
401 responses.

This integration is stateless: it creates no session or login/logout routes. Session-backed indirect
clients, browser redirects/callbacks, and cookie-authentication CSRF handling belong to the separate
session integration. Authenticate over HTTPS; Basic and Bearer credentials themselves provide no
transport encryption. Framework-owned request lifetime and streaming completion still apply.
The public HTTP tests exercise actual Basic and JWT clients, signature/expiry rejection, authorization,
concurrency, bounded form input, and admission without consuming the body.

Register application-wide safety nets before `start()`:

```java
app.exception(UnauthorizedException.class, (exception, request, response) -> {
    response.status(HttpStatusCodes.UNAUTHORIZED.value())
        .header(HttpHeaders.WWW_AUTHENTICATE.value(), "Bearer realm=\"api\"")
        .text("Unauthorized");
});
```

`Shoostr.exception(Class<E>, ExceptionHandler<? super E>)` selects the most specific superclass of the directly thrown exception, regardless of registration order. Duplicate app-level classes are rejected; registration is synchronized with startup and close, and runtime lookup uses an immutable snapshot without locking. Groups and endpoints may configure local renderers through `Extensions.exception(...)`: a matching local renderer handles the error alone, with app-level rendering used only when no local renderer matches. Renderers do not form a handler chain. Recoverable business errors should normally be handled in business logic.

Custom handlers receive the original readable request on the original handler thread. The failed response's body and headers are cleared first; its initial status is the thrown `HttpException` status or 500. The callback may override that status and set its own headers/body or start a stream. It must finish output before returning; the framework still owns closure. These handlers are shared across requests, so captured mutable application state must be safe for concurrent calls. Request access ends after error handling and finalization; retained objects cannot be used later or on another thread.

A failed custom handler or invalid uncommitted error output falls back to a safe 500 without invoking another mapper. Committed output and fatal errors abort. Transport failures after asynchronous finite submission do not dispatch application error handlers. Ordinary router-generated 404/405 responses are not exceptions and do not invoke these callbacks.

Error headers belong in the callback, not in exception metadata or copied request headers. An application returning 401 must supply its authentication challenge, for example through the handler above; the built-in `UnauthorizedException` fallback cannot infer a scheme or realm. See [RFC 9110 §15.5.2](https://www.rfc-editor.org/rfc/rfc9110.html#section-15.5.2). The default remains a bare status/reason response unless an application handler supplies that policy.

If the reason phrase exceeds `Options.maxResponseBytes`, core sends the selected status with an empty body. HEAD responses omit the body. After streaming has committed, an HTTP exception aborts the stream without changing the status, appending an error body, or flushing pending application bytes. Request-body limit failures use the public `ContentTooLargeException` and produce `413 Content Too Large`; known oversized requests are rejected before entering the handler, while limits reached during a body read can be caught by application code. Applications needing a custom body or headers can catch an HTTP exception and set the response explicitly before commitment.

Buffered request-body reads close their input adapter on success or failure. Oversized bodies remain rejected on repeated access, including from an exception handler. Cleanup failures are suppressed under the original read or validation failure. Jetty handles unread content and connection reuse; a client that has not finished sending the request can receive the rejection with its connection closed.

Finite completion hands the owned body buffer to Jetty's asynchronous write callback after closing application access. The HTTP request completes when that write succeeds or fails; the handler thread does not wait through a blocking adapter. Streaming writes still wait for transport completion before reusing their bounded buffer. The defensive copy of caller-supplied finite bodies remains in place.

The default listener uses Jetty 12.1.11 HTTP/1.1 and virtual-thread handlers. Routes match literal and single-segment parameter patterns against Jetty's canonically encoded path, with 404/405 handling. Defaults bind loopback:8080, cap request reads and finite response bodies at 1 MiB, limit each decoded query or form to 1,000 pairs, buffer streaming output in 8 KiB, and use a 30-second connection idle timeout. Override these through `Options` or native Jetty callbacks for transport settings. Idle timeouts do not interrupt arbitrary application work. Shutdown requests interruption after native draining; this slice does not provide per-handler deadlines or global concurrency admission limits.

Applications own serialization/deserialization, either explicitly through bytes and UTF-8 strings or through the opt-in codec handlers below. `Request.input()` is one-shot and mutually exclusive with cached `bodyBytes()` and multipart access; it counts bytes as they are consumed, including chunked input, and the framework closes it at handler completion. `Response.body(MediaType, byte[])` and `startStream(MediaType)` accept the immutable [`MediaType` primitive](http/README.md#media-types), while String overloads remain available for other parameters. Media types label bytes without converting them; `text(String)` and `Stream.write(String)` always encode UTF-8. Built-in serializers and template rendering are outside the current scope. Catch-all paths and wildcard patterns remain deferred. Production code and examples use only standard Java annotations; test sources additionally use JUnit 5 annotations, and the isolated `microbenchmarks` module uses JMH annotations. Minimize custom classes, apply YAGNI/KISS, and order methods from high-level operations down to their helpers.

### Opt-in typed codec handlers

Supply one application-owned, thread-safe `Codec` before startup. Its contract is
`<T> T read(byte[] source, Class<T> type) throws Exception` and
`byte[] write(Object value) throws Exception`; no serializer library is bundled. Configure
the codec before sharing it, and keep any dependencies alive through running handlers.
Shoostr does not close it or infer an OpenAPI schema from it. The configured view avoids
repeating a codec adapter at every handler; sealed inheritance avoids copying the ordinary
request/response interface into standalone facade objects.

```java
var app = new Shoostr();
app.codec(codec).routes(routes -> {
    routes.post("/users", (request, response) -> {
        var user = request.body(User.class);
        response.status(201).body(MediaType.APPLICATION_JSON, user);
    });
    routes.get("/health", (_, response) -> response.text("healthy"));
    routes.get("/text", (_, response) -> response.body(
        MediaType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8), textBytes));
});
app.start();
```

The view registers handlers receiving final `TypedRequest`/`TypedResponse` subclasses of
sealed `Request`/`Response`. Existing byte/text/metadata operations are inherited and retain
the same exchange ownership, state and limits; there is no second request/response pair.
Fluent methods retain typed return values. Ordinary `app.routes()` registrations still
receive the byte-level types. The typed callback form returns the original app, so
`app.codec(codec).routes(registration).start()` uses the existing lifecycle. Configure the
codec once per app, before starting or closing it; keep the view if registering in stages.
Ordinary routes remain available for SSE, WebSocket and static-resource registration.

`request.body(User.class)` reads bounded bytes and invokes the codec on a fresh defensive
copy each time. Decoded objects are not cached; concrete Class decoding does not describe
`List<User>` or other parameterized types. Streaming and multipart input retain their
existing mutual exclusion with buffered reads. Both object output forms are explicit:
`response.body("application/json", user)` and
`response.body(MediaType.APPLICATION_JSON, user)`. A `byte[]` argument selects the existing
byte overload and bypasses the codec; `text(String)` still encodes UTF-8 directly.
Media types label the selected representation without codec discovery, transcoding or
automatic negotiation. An explicit body media type replaces a previously set Content-Type.

The codec's decoded value and encoded bytes must be nonnull. Response output is finite;
the existing size limit is applied after encoding and cannot bound temporary allocations
inside user conversion. Writable phase and thread ownership are checked before encoding.
Codecs may throw checked exceptions: explicit malformed-input failures can use
`BadRequestException` (400), unsupported formats can use `UnsupportedMediaTypeException`
(415), and genuine server failures retain their existing error handling. Unexpected
failures are never automatically reclassified as malformed input. Shared codecs must also
support concurrent requests on caller-owned executors; no per-request codec or context
lookup is performed.

Each HTTP handling path receives a bound `Request`/`Response` pair, constructed and
validated before application hooks run. Both retain their associated peer internally;
response operations use that request without accepting a request argument. Negotiation
uses its immutable header snapshot. The signature is
`negotiate(MediaType first, MediaType... additional)`: at least one media type must be
supplied. Consumers of the former request-taking or array-only signatures must update
their calls and recompile. Null candidates are rejected; a null additional-candidates
array means no additional candidates, just like the empty array supplied by `negotiate(first)`.

For an endpoint that can produce more than one already-encoded representation, call
`response.negotiate(first, additional...)` before setting bytes or starting a stream. It returns
one supplied `MediaType`, adds `Vary: Accept`, and throws `NotAcceptableException` for a valid
explicit preference that excludes every candidate. A missing `Accept` selects the first candidate.
The selector handles repeated fields, comma lists, wildcards, q weights and media parameters; its
strict malformed-field policy produces 400. It never serializes, decodes, or transcodes bytes.

```java
var type = response.negotiate(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN);
if (type.equals(MediaType.APPLICATION_JSON)) {
    response.body(type, jsonBytes);
} else {
    response.body(type, textBytes);
}
```

`q=0` excludes a matching representation, including when a broader range has positive quality.
For a candidate, the most-specific matching range determines its quality; equal quality retains
the caller's candidate order. A range's non-q parameters must match the candidate. Negotiation
must finish before `startStream`, because it changes response headers. Generated 406 responses
retain `Vary: Accept`; malformed Accept responses do not add it. Existing Vary fields are retained,
`Accept` is added once, and `Vary: *` remains unchanged.

This is a runnable starting point, not a production-readiness or performance claim. Jetty is the selected backend; engine comparison research is closed. Existing Jooby comparisons remain historical evidence.

The [JMH fixtures](microbenchmarks/README.md) measure the current radix matcher with composed route patterns, parameter access, literal-first precedence, fallback, and method failures at 8, 800, and 8,000 endpoints. Separate JFR recordings identify hotspots. The earlier literal-route map comparison remains available as a historical fixture; it does not measure the composed API. Normal builds run fixture correctness tests but do not execute timed benchmarks. These are routing microbenchmarks, not comparisons of complete frameworks.

See the [HTTP contract and Javalin compatibility checklist](core/HTTP_CONTRACT.md) for the supported subset, deliberate differences, existing test evidence, and remaining gaps.

Response metadata is available during the live handler through `status()`, `isCommitted()`, `header(name)`, `headers(name)`, and `headerMap()`. Header lookup is case-insensitive; lists and maps are immutable snapshots. `header(name, value)` replaces all values, `addHeader(name, value)` appends a separate field, and `removeHeader(name)` removes all occurrences. These mutations require uncommitted output and cannot change framework-owned framing headers.

```java
app.routes().get("/moved", (request, response) -> {
  response.header("Cache-Control", "no-store");
  response.redirect("/new-location", HttpStatusCodes.TEMPORARY_REDIRECT);
});
```

`redirect(location)` defaults to 302; the explicit overload supports 300/301/302/303/307/308. It replaces the staged body or selected file and clears Content-Type, Content-Length and Content-Range. Replacing a file also removes its ETag, Last-Modified, Accept-Ranges and Content-Disposition fields; unrelated headers remain. The redirect sends when the handler returns. It does not skip later handler code or reject a gate. Destinations must be nonempty URI references; existing escapes survive, Unicode components become ASCII escapes, and network destinations cannot contain user information. Applications control which destinations are allowed.

## Route gates and completion

Register application-wide callbacks before `start()`:

```java
app.beforeRouteHandler((request, response) -> {
    authorize(request); // Application-owned policy; throw a typed HTTP exception on rejection.
});
app.afterRequest(outcome -> {
    metrics.record(outcome.routePattern(), outcome.statusCode(), outcome.durationNanos());
});
```

`authorize` and `metrics` represent application code, not bundled integrations. `Request.routePattern()` exposes the original composed template, such as `/accounts/{accountId}/orders/{id}`, for route-aware policy and bounded metric labels. It returns `Optional.empty()` without a selected endpoint and follows the existing request thread/lifetime rules. The template is retained at registration, not reconstructed per request. Never substitute a concrete request URL when a metric's route template is absent; normalize unfamiliar methods and failure labels in the chosen metrics adapter.

`beforeRouteHandler(Handler)` runs gates in registration order after matching and the known Content-Length limit check. Gates are skipped for unmatched-path 404s and method-mismatch 405s. An availability-denied 404 follows matching, authentication and gates; a false availability check skips later checks, the endpoint and post-route callbacks. Gates may read bounded request data and stage finite output; setting status/body does not skip the endpoint. Throwing an exception skips subsequent gates and the endpoint, then invokes a matching local exception renderer or, if none matches, app-level error handling. Gate headers/body are cleared on failure, so a 401 challenge belongs in its exception callback. Gates cannot start streams or close the response. Error handlers retain their existing streaming capabilities.

The remaining live stages are registered in the same way: `onRequestHeaders`, `onRouteMatched`, `afterRouteHandler`, `beforeResponseFlush`, and `afterResponseFlush`. A successful matched request runs them in this order: request-header callbacks, route-match callbacks, route gates, availability checks, the endpoint, post-route callbacks, then the flush callbacks. Header callbacks also run for unmatched-path 404s and method-mismatch 405s; matched-route, gate, and post-route callbacks do not. Availability-denied 404s retain the earlier matched-route and gate work but skip the endpoint and post-route callbacks. Any live callback can reject the request by throwing; exception selection uses matching local rendering first, then the app fallback. Without a selected endpoint, only app-level rendering applies.

Flush callbacks surround every streaming buffer submission and the terminal stream submission. For finite and file responses, they surround the framework submission that starts the terminal transport write; they do not wait for the asynchronous client acknowledgement. Both stages are read-only: request and response metadata remain available, while output mutation, streaming writes, and closure are rejected to prevent reentry. If a before-flush callback rejects an unsubmitted response, its callbacks are skipped while the existing exception mapper renders the recovery response. `afterResponseFlush` cannot alter submitted output. Neither callback is a streaming handoff: the framework still finishes every stream when its endpoint returns.

`status(code, handler)` installs one application-wide renderer for a router-generated status. It currently applies to generated 404 and 405 responses. A 405 callback retains the framework-generated `Allow` field. Status renderers are not route-scoped exception handlers; application failures still use `exception(...)`, and a committed response is never reopened.

`afterRequest(Consumer<RequestOutcome>)` observes terminal requests, including router misses, auth/body-limit rejections, handled failures, and observed disconnects. One summary is produced after **both** application finalization and transport termination; handler return alone is insufficient for asynchronous finite output. Each registration is invoked once in registration order, including repeated registrations of the same callback. Malformed traffic rejected before framework admission is outside this scope.

The record contains the original wire method, nullable route template, committed status (`0` if no final status was committed), and monotonic duration in nanoseconds from framework admission through completion. `applicationFailure` records the initiating synchronous processing failure even if an error response succeeds. Mapper/fallback failures are attached as suppressed diagnostics when supported by that Throwable. `transportFailure` is the terminal exchange failure, possibly the same cause for an application-triggered abort. These are separate observations, not one success flag. A delivered 404 can have no transport failure; a committed 200 stream can still fail.

Observers receive no live Request/Response and may retain the summary. Failure references remain diagnostic Throwable objects; they are not deeply immutable, safe client messages, or suitable metric labels. Callbacks may run on transport threads and must be quick and nonblocking; shared state must be thread-safe. Ordinary RuntimeException failures are logged and do not prevent later observers or change HTTP output. Fatal Errors are not converted to a second response; cleanup precedes notification, and later-observer delivery is not guaranteed under fatal failure. There is no framework exporter queue or asynchronous streaming handoff. When no completion observers are registered, no completion coordinator, transport listener, or outcome record is created.

Registrations are synchronized with startup/close and frozen for request handling. Null and late registrations are rejected. There is no custom annotation, auth provider, role system, metrics dependency, or per-route exception scope in this slice.

## Request parameters

Query and form access stays explicit and separate:

```java
app.routes().post("/orders", (request, response) -> {
    Optional<String> firstTag = request.queryParam("tag");
    List<String> allTags = request.queryParams("tag");
    Map<String, List<String>> fields = request.formParamMap();
    List<String> requestIds = request.headers("X-Request-Id");
    response.text("Accepted");
});
```

`queryParam` / `formParam` return an Optional containing the first value, or empty when absent; `queryParams` / `formParams` return all values in arrival order or an empty list. `queryParamMap` / `formParamMap` expose deeply immutable snapshots. Parameter names are case-sensitive. Both parsers decode UTF-8 percent escapes once and turn `+` into a space. Empty names and values are retained; empty pairs between `&` separators are ignored. `headers(name)` returns immutable raw field values with case-insensitive name lookup and does not split commas. `header(name)` still returns the first value.

Parsing is lazy and cached. Forms support `application/x-www-form-urlencoded` with absent or UTF-8 charset metadata (including Java's UTF-8 aliases), and multipart text fields. Multipart files use `request.file(name)` or `request.files(name)`; `Upload.content()` is handler-lifetime only and `persistTo(Path)` transfers content to application ownership. On nonmultipart requests, `files()`, `files(name)` and `file(name)` return empty results without reading or claiming the ordinary body, even after body access. Parsing actual multipart and reading the raw body remain mutually exclusive; an empty upload result on multipart input does not relax that rule. Multipart defaults are 10 MiB total, 5 MiB per part, 100 parts, 8 KiB headers and 16 KiB in memory per part; `Options.withMultipart(MultipartOptions)` overrides them. Missing/unsupported Content-Type or charset produces `UnsupportedMediaTypeException` (415) on form access; malformed form data produces `BadRequestException` (400), and size limits produce `ContentTooLargeException` (413). Declared malformed multipart still fails on file access. Failures never expose partial maps.

`Options.maxParameters` defaults to 1,000 and counts every pair, including repeated names, for query and URL-encoded forms. Override it with `Options.defaults().withMaxParameters(200)`; the compatibility constructors retain the defaults for newly added options. Multipart uses its own part limit. Exceeding either limit produces 400 or 413 respectively. The existing request-body byte limit applies to non-multipart forms (413), including unknown-length/chunked bodies and repeated reads after rejection. Multipart forms instead use their aggregate multipart limit. Jetty's HTTP header/request-target limit separately bounds the encoded query. Raw body reads remain repeatable before form parsing; raw body and multipart access are mutually exclusive. Accessors obey the existing request thread/lifetime rules; already returned immutable collections can safely be retained.

## Composing routes

`Shoostr.routes(callback)` receives the pre-created root `Routes` and returns the application for `.start()` chaining. The no-argument `routes()` getter remains available. `path(...)` executes a scoped registration callback immediately. Scopes can nest or be extracted to a method accepting `Routes`:

The maximum is **10 total nested `path`/`when` scopes**; the root is depth zero and endpoints
consume no level. Empty groups still count; flat path segments do not. The eleventh scope fails
before extension configuration or user callbacks. Scope ancestry and active grouping on the same
thread both count, so captured-root recursion cannot bypass the limit. Independent threads do
not share an active depth counter. Exceptions restore depth, allowing later registration/startup.
This is a route-group contract, not a sandbox for arbitrary recursive Java or fresh-thread recursion.

```java
var app = new Shoostr();
app.routes(routes -> {
    routes.path("/api", api -> {
        api.path("/orders", orders -> {
            orders.get((request, response) -> response.text("all orders"));
            orders.post(OrderHandlers::create);
            orders.path("/{id}", order -> {
                order.get((request, response) -> response.text(request.pathParam("id").orElseThrow()));
                order.patch(OrderHandlers::update);
                order.delete(OrderHandlers::delete);
            });
        });
    });

    // A reusable module: static void register(Routes routes) { ... }
    routes.path("/v2/orders", OrderRoutes::register);

    // Explicit methods use the enum from the http module.
    routes.route(HttpMethods.PROPFIND, "/properties", PropertyHandlers::find);

    // A terminal named tail can include multiple path segments.
    routes.get("/files/{*path}", (request, response) ->
        response.text(request.pathParam("path").orElseThrow()));

    // A constrained segment dispatches only when its character class matches.
    routes.get("/users/{id:[0-9]+}", (request, response) ->
        response.text(request.pathParam("id").orElseThrow()));
}).start();
```

`Routes` exposes `get`, `post`, `put`, `patch`, `delete`, and explicit `head`/`options` registration. `Routes` also has a pathless overload for each verb, selecting its own group endpoint. A leading slash inside a group remains relative: nesting `/api` and `/orders` registers `/api/orders`. Group prefixes accept an optional trailing slash. An endpoint `get(handler)` matches `/api/orders`, while `get("/", handler)` explicitly matches `/api/orders/`; trailing slashes on endpoints remain significant. No HEAD or OPTIONS handlers are synthesized.

**Literal segments win over constrained single-segment parameters, then ordinary single-segment parameters, then named catch-all tails.** Overlapping constrained routes that both complete the path and method are selected in registration order. A more-specific branch that cannot complete the full path and method falls back to the next branch. Nested callbacks preserve their ordinary execution order. If no endpoint accepts the method, a matching path produces 405 and the sorted union of allowed methods; otherwise the result is 404.

`{name}` matches one nonempty segment. Names may contain ASCII letters, digits, underscores and hyphens, beginning with a letter or underscore. Parent-group names are available through `request.pathParam(name)` in descendants. Values are extracted on demand and UTF-8 percent-decoded once; plus signs remain literal. Unknown parameter names and access before route selection return `Optional.empty()`; access from another thread or after handler return throws `IllegalStateException`. Matching uses the path provided by Jetty's existing URI handling; the transport's URI validation remains in effect.

`{*name}` captures a nonempty tail, including its internal and optional trailing separators: `/files/{*path}` matches `/files/a` as `a` and `/files/a/b/` as `a/b/`, but not `/files/` or `/files`. It must be the entire final segment. Its value uses the same lazy, single-decode policy as `{name}`; an encoded slash does not bypass Jetty's URI admission.

`{name:[class]+}` is a constrained single segment. The initial expression subset is one nonempty ASCII character class (`[0-9]`, `[A-Za-z0-9_-]`) with no repetition, `+`, `*`, `?`, or bounded `{min}` / `{min,max}` repetition. Only ASCII letters, digits, `_` and `-` may appear inside the class. Route registration rejects alternation, groups, lookarounds, backreferences, multiple atoms and invalid ranges; the expression is compiled before serving requests, not per request. The segment must be nonempty even if the repetition permits zero characters. Constraint matching uses Jetty's existing canonical encoded routing path before `pathParam` performs its lazy decoding: `%31` is canonicalized to `1` and satisfies `[0-9]+`, while `%20` remains encoded and does not satisfy that class. This does not change URI admission.

Duplicate parameter names in a composed path and equivalent patterns for the same method are rejected during registration (`GET /orders/{id}` conflicts with `GET /orders/{name}`, `GET /files/{*path}` conflicts with `GET /files/{*rest}`, and `GET /users/{id:[0-9]+}` conflicts with `GET /users/{other:[0-9]+}`). Different methods may use different names. Partial-segment parameters, arbitrary regex syntax, unnamed wildcards and `<catch-all>` syntax are not supported in this version.

`Shoostr.routes()` returns the same pre-created root `Routes` instance on every call, including inside the callback overload. `Routes` owns registration, validation, composition, compilation, and registration cleanup. Startup builds and publishes an immutable compressed radix tree from the complete registrations. `Routes` implements `Closeable`: `close()` permanently closes registration and clears temporary endpoint entries and duplicate-detection keys. Closing any child scope closes registration for the whole app. It does not stop a running server or remove compiled routes. Startup uses the internal `compile()` operation under the shared registration lock; successful compilation closes registration before releasing that lock, so accepted registrations cannot slip between compilation and closure. Closing an unstarted app also closes its routes. Closing routes directly before startup prevents startup; application code normally leaves this lifecycle to `Shoostr`. Clearing these collections releases entry references, but retained scopes can still retain their backing capacity. The router prefers literal segments when overlapping branches complete; group scopes add no request-time layer. All registration, including retained scopes and empty groups, is rejected after startup begins. Startup is rejected while any group or root callback is active, including callbacks on other threads; this rejection leaves registration open so startup can be retried after the callbacks finish. If compilation itself fails, registration stays open until the app or routes are closed. Callbacks execute without holding the registration lock, so they can wait for registration work on another thread. Concurrent groups may interleave without changing precedence; a registration racing with startup is either included in the frozen router or rejected. Close prevents further registrations without waiting for active callbacks. Callback exceptions propagate and registrations already made remain registered. Request-time lookups remain lock-free.

Route registration accepts `HttpMethods` or a case-sensitive String wire token, for example `routes.route("PROPFIND", "/properties", handler)`. Both forms share duplicate detection and request-time matching. `route(method, handler)` selects a group’s own endpoint. String lookup happens only during registration; unrecognized, lowercase, whitespace-padded, and enum-identifier spellings such as `BASELINE_CONTROL` are rejected with `IllegalArgumentException`, while null raises `NullPointerException`. Custom unregistered verbs are not supported. Incoming unknown method tokens fail matching without enum lookup exceptions. `Request.method()` continues exposing the raw token. Method lookup is case-sensitive, unlike header-name lookup.

## Cross-origin requests

CORS is disabled by default. Enable one immutable application-wide policy before startup:

```java
app.cors(new CorsPolicy(
    Set.of("https://client.example"),
    Set.of(HttpMethods.GET, HttpMethods.PUT),
    Set.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE),
    true,
    Set.of(HttpHeaders.ETAG),
    600));
```

`new CorsPolicy(origins)` allows GET/HEAD, no additional request or exposed response headers,
no credentials, and a five-second preflight cache. The full constructor overrides those defaults;
zero max-age disables reuse. Browsers may cap the configured lifetime. Sets are copied at construction.
Methods are case-sensitive; header names are case-insensitive and support `HttpHeaders.of(name)`.
Only explicit header names are accepted, including `Authorization`; header/exposure wildcards
are not supported.

Origins are exact HTTP(S) origin strings, without userinfo, paths, query or fragment. Supply the
browser's serialized spelling (ASCII host, canonical port); no URL repair or origin normalization
is performed. The literal `null` requires explicit permission unless `*` allows all origins.
Allowing `null` shares responses with unrelated opaque origins. Origin `*` is noncredentialed
only; credentialed policies require an explicit allowlist.

Preflight checks the CORS policy, independently of whether a route exists. After application-wide
`onRequestHeaders` admission, an accepted preflight returns 204 without route hooks, authentication
policies or business handlers. Admission cannot commit a stream while a preflight or CORS rejection
is pending; staged bodies are discarded for successful preflight while ordinary headers survive.
Actual requests follow normal routing and authentication. A successful
preflight promises sharing permission, not authentication or endpoint availability. Put authentication
in route policies or `beforeRouteHandler`; a header admission hook that requires credentials will
also reject browser preflights.

Requests without Origin and same-origin requests retain normal dispatch. Cross-origin methods and
origins outside the policy are rejected with 403; malformed CORS fields receive 400. Repeated Origin
or requested-method fields are rejected; requested-header lists combine and deduplicate. Ordinary
OPTIONS still requires an explicit route; with CORS enabled its cross-origin method must be allowed.

The policy owns CORS response fields, including on errors and streams; application response mutations
cannot broaden its grants. Existing `Vary` values are preserved, with Origin added even when sharing
is denied or Origin is absent; OPTIONS additionally varies on the requested method and headers.
CORS is browser response-sharing permission, not CSRF protection or authentication.

Protocol reference: [Fetch CORS](https://fetch.spec.whatwg.org/#http-cors-protocol).
Regression scenarios were also checked against
[Javalin's CORS tests](https://github.com/javalin/javalin/blob/1ff18e9e03e69cc3759abc57f1f12c32285eded3/javalin/src/test/java/io/javalin/TestCors.kt);
our explicit global permissions and OPTIONS routing remain intentional contracts.

## TLS, HTTP/2 and response compression

The default listener is plain HTTP/1.1. TLS, HTTP/2 and gzip response compression are independent
opt-ins configured before `start()`. Shoostr owns the native Jetty connector and TLS context lifecycle.
The TLS callback supplies key material and may set Jetty's other native TLS properties:

```java
app.tls(tls -> {
  tls.setKeyStorePath("/secure/path/server.p12");
  tls.setKeyStorePassword(System.getenv("SERVER_KEYSTORE_PASSWORD"));
}).http2().compression();

app.routes().get("/secret", (request, response) ->
    response.disableCompression().text("sensitive response"));
```

With TLS, HTTP/2 uses ALPN and retains HTTP/1.1 fallback. Without TLS, `http2()` enables h2c on
the default plain listener alongside HTTP/1.1. `modifyHttpConfiguration` and `modifyServer`
remain available for native Jetty settings; configure them before startup. Missing or invalid
TLS key material fails startup and closes Shoostr-owned resources. `port()` reports the default
connector's actual listening port.

Compression uses Jetty's standard response policy for GET and POST: gzip for an acceptable
`Accept-Encoding`, `Vary: Accept-Encoding` on eligible responses, and native exclusions for
already compressed media and `text/event-stream`. Finite and streaming responses are supported.
An explicit `gzip;q=0` rejection wins over a wildcard encoding preference.
If a request forbids identity and Jetty cannot compress a successful response because of
its active method/path/media policy or minimum-size threshold, the response becomes an empty 406
with `Vary: Accept-Encoding`. Eligible responses still use gzip when the client accepts
gzip and forbids identity; bodyless responses and application-supplied content codings
retain their status.
`response.disableCompression()` must run before the first stream write; it sends explicit
`Content-Encoding: identity`, or an empty 406 if the client forbids identity. Byte ranges retain
their original representation and `Content-Range` when identity is acceptable; otherwise they
return empty 406. Bodyless statuses have no compressed body. With compression enabled, HEAD
omits a possibly incorrect `Content-Length` and still varies on `Accept-Encoding`.
Request decompression is disabled; application code receives encoded request bytes and can
decode them under its own size limits. No compression or protocol change occurs unless opted in.

When HTTPS terminates at a reverse proxy, leave the backend connector plain or configure it
separately for TLS. `trustedProxies(...)` controls which forwarding assertions affect
`effectiveUrl()` and `clientAddress()`. It never changes `isSecure()` or the direct
connection addresses. `scheme()` and `fullUrl()` describe the parsed request URI, so an
absolute request target can supply an HTTPS scheme even over plaintext. Do not use those
URI values as proof of TLS. Configure secure browser cookies explicitly when the proxy
provides the external HTTPS endpoint.

See the [Jetty server guide](https://jetty.org/docs/jetty/12.1/programming-guide/server/http.html)
and [RFC 9110 HEAD semantics](https://www.rfc-editor.org/rfc/rfc9110.html#section-9.3.2).

## Browser sessions and CSRF

Sessions are opt-in. `app.sessions()` installs Jetty 12.1.11's core session handler with an
in-memory, nonpersistent store. `request.session(false)` looks up without creating;
`request.session(true)` creates lazily before response commitment and returns an `Optional<Session>` containing Jetty's `Session`
for attributes, per-session idle expiry and invalidation. Call `request.renewSessionId()` after
login or a privilege change, before response commitment. It preserves attributes and changes the browser cookie. On logout,
invalidate the session and expire the cookie with `response.removeCookie("JSESSIONID")` (or the
configured cookie name and path). Unknown, expired and invalidated IDs are not adopted.

```java
app.sessions(handler -> {
  handler.setSessionCookie("WEBSESSION");
  handler.setSameSite(org.eclipse.jetty.http.HttpCookie.SameSite.STRICT);
  // Supply Jetty's SessionCache/SessionDataStore here for persistence or clustering.
});

var csrf = new Csrf();
app.extensions(csrf).routes().path("/", browser -> {
  browser.get("/form", (request, response) -> response.text(csrf.token(request)));
  browser.post("/submit", (request, response) -> response.text("accepted"));
}, extensions -> extensions.get(csrf).required());
```

The defaults are a 30-minute server-side idle timeout, an HttpOnly, SameSite=Lax session cookie,
root path and no Domain or browser Max-Age. Jetty marks cookies Secure on a directly secure
connection; HTTPS behind a TLS-terminating proxy should set `handler.setSecureCookies(true)`.
The callback overrides defaults and exposes Jetty's native cookie and cache/store settings.
Shoostr owns handler startup and shutdown. The default cache shares one session across concurrent
requests on a node, but compound attribute updates and mutable attribute values still need
application synchronization. A remote store alone does not make cross-node writes atomic.

`Csrf` is a separate opt-in extension for explicitly cookie-authenticated routes. Installation alone
does not protect routes or enable sessions; select `required()` on browser routes/groups and enable
sessions explicitly. Repeated `required()` calls add verification only once per binding. A safe route can
return `csrf.token(request)` in its response body for an HTML form or script. Unsafe requests
must send that session-bound token in exactly one `X-CSRF-Token` header or `_csrf` URL-encoded or
multipart form field. They must also supply a single same-origin `Origin`, or a same-origin
`Referer` when `Origin` is absent; missing, opaque, malformed, duplicated and mismatched sources
are rejected with 403. `new Csrf(Set.of("https://client.example"))` adds exact trusted browser
origins; cross-origin scripts also need an independent CORS policy. Tokens remain stable within a
session, rotate after ID renewal and die with expiration or invalidation. CORS preflight bypasses
route policies, but the actual request still runs CSRF verification. Bearer-token APIs outside a
protected route scope receive no implied CSRF protection. Never put synchronizer tokens in URLs,
cookies or logs.

See [Jetty core sessions](https://jetty.org/docs/jetty/12.1/programming-guide/server/session.html)
and [OWASP CSRF guidance](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html).

## Cookies

Request headers and cookies are parsed into deeply immutable maps during request construction, after native Jetty customization and before application hooks. Accessors share those snapshots and remain handler-confined. `request.cookie(name)` returns an `Optional` containing the first value, or empty when absent; `cookies(name)` returns every occurrence, and `cookieMap()` returns `Map<String, List<String>>` containing all values. Cookie names are case-sensitive; plus signs and percent sequences are literal. Incoming parsing follows Jetty's configured cookie compliance policy; its default RFC6265 policy can discard malformed cookies and remove outer quotes. A configured strict policy rejects malformed cookies before application hooks run, even when the handler never reads cookies. Construction failures are rendered by Jetty and reported to completion observers; application exception handlers require a constructed Request and do not run for these failures.

```java
app.routes().get("/preferences", (request, response) -> {
  var theme = request.cookie("theme");
  response.cookie(new Cookie("theme", theme.orElse("light"))
      .withHttpOnly(true)
      .withSameSite(Cookie.SameSite.LAX));
  response.text("saved");
});
```

Use the dependency-free [`Cookie` value](http/README.md#cookies) from `io.github.suppierk.shoostr.http`. `response.cookie(name, value)` stages a root-path, host-only session cookie. The Cookie overload supports explicit scope, expiry and security attributes. Repeated writes replace matching name/domain/path fields, including parseable raw Set-Cookie fields; other scopes and malformed raw fields remain unchanged. Replacement is appended last. Request cookies carry no path/domain metadata, so these are not inferred for deletion.

`removeCookie(name)` deletes the root/host-only scope. `removeCookie(name, path, domain)` selects an explicit scope; `removeCookie(cookie)` retains security attributes and supports protected prefixes. Deletion emits zero Max-Age plus a past Expires. Cookie mutation requires uncommitted output on the handler thread, and error replacement clears previously staged cookies. Setting cookies adds no response-level cache headers. Cookie support is not a session store or authentication integration.

## Request metadata and local state

Request accessors for potentially absent values return non-null JDK `Optional` objects. This includes named query/path/form values, headers, cookies, uploads, session, principal, attributes, route pattern, URI scheme/authority/server name, and socket/client addresses. Present empty strings remain present; malformed input, null names, and request thread/lifetime violations still throw. Lists and maps remain non-null collections, with empty collections representing absence.

This changes the source and binary interface: use `.orElseThrow()` where a value is required, `.orElse(defaultValue)` for a default, and `.map(...)` or `.ifPresent(...)` for conditional use. No nullable compatibility aliases are provided.

`queryString()` exposes the encoded query without its `?`: `Optional.empty()` when absent, `Optional.of("")` when the target ends in `?`. It does not parse parameter values. `headerMap()` returns the deeply immutable, case-insensitive snapshot captured at request construction, preserving separate repeated values. `header(name)` and `headers(name)` read that same snapshot; later changes made by custom native wrappers do not affect it. `pathParamMap()` snapshots all named captures, including composed path groups, using the same single percent-decoding step as `pathParam(name)`.

| Accessors | Meaning |
| --- | --- |
| `url()`, `fullUrl()` | Transport-assembled URL without query/fragment, or with the original query. Encoded path spelling survives; these are not trusted application origins. |
| `scheme()`, `authority()`, `serverName()`, `serverPort()` | Logical request URI/authority. Jetty normalizes default ports and retains IPv6 brackets. Host/absolute-target values are client supplied; missing legacy authority uses the transport fallback. |
| `protocol()` | HTTP version of the active request. |
| `remoteAddress()`, `localAddress()` | Direct JDK SocketAddress values from the physical transport endpoint, independently of the requested host/port. No reverse DNS lookup is initiated. |
| `isSecure()` | Actual endpoint security. An absolute HTTPS URI over plain TCP still returns false, as does plain TCP behind a TLS-terminating proxy. |

Forwarded/X-Forwarded-* headers remain ordinary input and do not alter URI or connection metadata by default. To opt in, configure `app.trustedProxies(...)` before startup with `io.github.suppierk.shoostr.http.ForwardedHeaders` when selecting the legacy family. `request.isForwarded()`, `clientAddress()` and `effectiveUrl()` then expose selected trusted metadata without rewriting the direct transport accessors. See the [trusted-proxy contract](core/HTTP_CONTRACT.md#trusted-reverse-proxy-metadata) for header-family and trust-boundary rules.

Gates can pass application state and a JDK `Principal` to their handler and global exception callback:

```java
app.beforeRouteHandler((request, response) -> {
  request.principal(authenticate(request));
  request.attribute("request-label", "application-value");
});
app.routes().get("/me", (request, response) ->
    response.text(request.principal().orElseThrow().getName()));
```

`authenticate` is application code that returns an identity or rejects the request; the framework adds no credential parsing or provider here. `principal()` starts empty, and `principal(null)` clears it. `attribute(name)` returns an `Optional<Object>`; `attribute(name, value)` replaces a binding and null removes it. Names are case-sensitive. `attributeMap()` is an immutable snapshot of bindings; values and Principal objects remain application-owned references, not deep copies or automatically thread-safe objects. Attribute storage is allocated only when needed, and framework finalization drops its state references.

All accessors and mutations require the live handler thread, including during global error handling or an active stream. Retained immutable snapshots remain readable after completion; retained Request objects do not. Concurrent requests have independent state.

### Metrics, tracing and access logs

Instrumentation is opt-in. Core has no metrics or tracing provider dependency. The application
owns registries, SDKs, exporters and their shutdown:

```groovy
implementation project(':micrometer')   // Micrometer 1.17.1
implementation project(':opentelemetry') // OpenTelemetry API 1.66.0; configure your SDK separately
```

```java
app.extensions(
    new MicrometerMetrics(registry).allRequests(),
    new OpenTelemetryTracing(openTelemetry).allRequests());
app.afterRequest(new AccessLog()); // System.Logger INFO, or new AccessLog(yourSink)
```

Import `io.github.suppierk.shoostr.micrometer.MicrometerMetrics`,
`io.github.suppierk.shoostr.opentelemetry.OpenTelemetryTracing` and
`io.github.suppierk.shoostr.AccessLog`. Register before `start()`. Supply an OpenTelemetry
instance with your chosen propagators (for example W3C trace context) and exporters;
no global provider is installed. Business code sees the server span through `Span.current()`.
Bare extension installation leaves request instrumentation inactive; call `allRequests()` before
installing. Existing `observe(adapter)` registration remains supported. Distinct activated instances
both run, even when sharing a registry or SDK: metrics may record twice and the last tracing scope
is current until scopes close in reverse order. Do not install duplicates accidentally.
For explicit downstream clients, inject `Context.current()` with your configured propagator.

`http.server.requests` is a Micrometer timer: it supplies completed count and duration, tagged
with normalized method, registered route template (`UNMATCHED` for misses), final status
(`0` when uncommitted), and a finite `error` category (`none`, `application`, `transport`,
`server`). Transport failures take precedence over application failures, then unhandled HTTP5xx.
An explicit4xx response is not an instrumentation failure. Filter the timer by error to obtain
error counts. `http.server.requests.active` is a long-task timer covering admission through
terminal completion, including misses and rejected requests, without per-path labels.
Micrometer registry filters configure histogram/exporter behavior. Unknown methods use `OTHER`.
Multiple apps sharing a registry aggregate these meter names; use registry configuration for
application/service identity. These labels never contain raw paths, IDs, queries or failures.

Tracing creates a SERVER span from the configured incoming propagator. Its context covers
hooks, route handling and error rendering; the invocation thread restores its prior context
before terminal callbacks. The span ends exactly once after application cleanup **and** transport
termination, including asynchronous response writes. Names use the method and registered route
template; misses use the method alone. Unknown methods use `_OTHER`. Attributes contain method,
route (when matched), committed status and a finite error type. Server5xx and transport failures
set ERROR; ordinary4xx responses do not. No header/body capture or exception details are enabled.
The application remains responsible for propagating context into its own separately scheduled work.

Access logs contain normalized method, registered template, status, duration and failure flags.
They omit raw paths, query strings, headers, credentials, bodies and exception messages. Custom
sinks may execute concurrently on completion threads and must not block; sink failures are isolated.

Custom instrumentation can use `Shoostr.observe(request -> observation)`. `RequestObservation.close()`
restores invocation-local resources on the admission thread, in reverse registration order;
`complete(RequestOutcome)` runs once after terminal transport completion and cleanup, potentially
on a transport thread. Never retain live Request/Response objects. Factory failures must clean up
resources they acquired before throwing. Runtime instrumentation failures are logged and do not
change HTTP results or stop later observers. Borrowed registries, SDKs and authentication clients
are never closed by Shoostr.

Rejected worker submission reattaches observation context on the admission thread for error rendering;
the recovered scope closes on that same thread. Successful handoff attaches on the worker.

Endpoint executor selection introduces a handoff between pre-routing and the matched lifecycle.
Extension authors must not assume these
phases share a thread. Before handing off, the framework closes the original observation scope on
its admission thread. `RequestObservation.attach()` can explicitly attach retained context on the
selected execution thread; the returned `AutoCloseable` restores that thread's prior context during
cleanup. Neither scope closure completes the observation. OpenTelemetry reattaches the same server
span, not a second span. Terminal observers retain their separate completion-thread contract.

The default attachment copies no arbitrary thread-local state. MDC, custom security context,
transactions, ORM sessions and thread-confined dependencies established before routing need an
explicit compatible integration or must stay on the existing no-handoff path. Request attributes
and principals keep their object references; that does not make their contents thread-safe.
Instrumentation must not retain live Request/Response objects to implement attachment, and must
not close a scope on a different thread. Preserve state needed for attachment and terminal recording
after the original invocation scope closes.

### Caller-owned endpoint executors

Pass an `ExecutorService` immediately after the path, before the handler. Pathless forms accept
the executor before the handler; generic `route` keeps its HTTP method first. All HTTP verbs,
`route`, SSE and WebSocket registration support the same optional extension configuration:

```java
try (var workers = Executors.newFixedThreadPool(2);
     var app = new Shoostr()) {
    app.routes()
        .get("/health", health)
        .post("/reports", workers, buildReport)
        .path("/exports", group -> group.get(workers, exportReport));
    app.start();
    // Keep the app and executor alive for the application's required lifetime.
}
```

Only final endpoint registrations select execution. `path` and `when` do not inherit or override
executors; there is no execution grouping or nesting rule. Selection is independent per HTTP
method, even at the same path. Unmatched 404/405, CORS preflight and static mounts stay on the
default path. The selected executor runs matched callbacks, authentication, availability checks,
the handler, after/flush hooks, matched error rendering and request cleanup sequentially.
SSE covers handler execution and its synchronous stream writes. WebSocket selection covers the
handshake/listener factory, not later session callbacks. Terminal observers keep their separate
completion-thread contract.

Executors are **caller-owned** and may be shared across apps or with unrelated work. Shoostr never
closes them or interrupts their running workers. `app.close()` uses Jetty's bounded graceful drain,
then cancels unstarted selected requests. Running handlers may outlive close; request cleanup and
observations finish when those handlers unwind. Native connection closure may fail their I/O,
and app-owned extension resources may already be closed after the drain deadline. Coordinate
running-handler completion before closing borrowed dependencies. Canceled queue entries later
run as harmless no-ops without retaining live Shoostr references.

Accepted tasks must run asynchronously, or submission must throw: caller-runs and silent discard
are unsupported. Shoostr eagerly rejects shutdown executors and inspectable thread pools using
the standard `CallerRunsPolicy`, `DiscardPolicy` or `DiscardOldestPolicy` classes; custom policies
(including subclasses) and wrapped executors
must honor that contract themselves. Rejection never falls
back to another executor and uses existing local/app exception rendering (500 when unmapped).
An application can explicitly map `RejectedExecutionException` to 503. If the caller shuts down
an executor or removes accepted tasks, it must coordinate app shutdown; a void `execute` result
cannot detect arbitrary silent task loss.

Platform, virtual and fork/join executors are supported. ForkJoinPool support does not automatically
propagate tracing to user-created child tasks or parallel streams. Explicitly wrap such work with
the captured telemetry context. Unwrapped jobs run through fork/join helping can observe the outer
request's context; Shoostr attaches/restores context for its own matched tasks only. Avoid the
common pool when isolation is required; blocking I/O can occupy fork/join workers without guaranteed
compensation. Executor selection is a scheduling choice, not a guarantee of better performance.

Requests rejected by Jetty before framework admission are outside this instrumentation's scope.
