# Executable examples

Pick one task and read its app factory, where route definitions appear inline,
then run the matching live HTTP test. There is no DI container, annotation routing,
multi-app demonstration or shared example runtime. Java 21 and the repository Gradle
wrapper are required. Docker Engine and Compose are needed only for containers/backends.

Direct runs use Jetty's default wildcard listener, which accepts connections beyond
localhost. Demonstration credentials and public endpoints are not production security;
run examples only in a trusted development environment. `TestServer` always binds
loopback, and the supplied Compose recipes publish user-facing ports on host loopback.
For a loopback-only application, use `modifyServer` to set the default
`ServerConnector` host to `127.0.0.1`. `Options` contains Shoostr-owned resource limits.

Standalone launcher examples use SLF4J with an application-owned Logback backend. Their local
`src/main/resources/logback.xml` sends INFO and higher levels to stderr. The runtime
`slf4j-jdk-platform-logging` bridge routes Shoostr's JDK logger into that same backend;
framework modules do not select a provider or enable per-request logging by default.

## Learning path

| Task | Example / Gradle project | Observable lesson |
| --- | --- | --- |
| Start/stop a server and return text | [01-hello](01-hello/README.md) / `hello-example` | `GET /hello`, plain response and deterministic test ownership |
| Configure a listener, resource limits, and native Jetty settings | [02-application](02-application/README.md) / `application-example` | Shoostr-owned `Options`, native listener and pool settings, header limits, and startup-time configuration |
| Register methods, match paths, and group or condition routes | [03-routing](03-routing/README.md) / `routing-example` | Method discovery, matching precedence, inherited groups, and live availability |
| Read request inputs, metadata, and local state | [04-request](04-request/README.md) / `request-example` | Separate parameter sources, header/cookie semantics, transport metadata, and snapshot ownership |
| Read buffered or streamed bodies and handle uploads | [05-bodies](05-bodies/README.md) / `bodies-example` | Body modes, input limits, multipart fields/files, and retained upload ownership |
| Set response bodies, headers, cookies and redirects | [06-responses](06-responses/README.md) / `responses-example` | Exact encodings, HEAD and empty responses, repeated fields, cookie scope, and redirect method behavior |
| Serialize objects and select a representation | [07-json](07-json/README.md) / `json-example` | Application-provided codec, JSON conversion and input errors, Accept selection and Vary |
| Handle failures and render error responses | [08-errors](08-errors/README.md) / `errors-example` | Exception selection and recovery, generated statuses, commitment limits, and explicit Problem Details JSON |
| Serve public resources, download files, and handle HTTP validators | [09-resources](09-resources/README.md) / `resources-example` | Filesystem/classpath mounts, SPA fallback, attachment filenames, single byte ranges, and conditional requests |
| Stream finite responses and send server-sent events | [10-streaming](10-streaming/README.md) / `static-streaming-example`, `sse-example` | Bounded output, source cleanup, flush visibility, event framing, replay cursors, and finite feed completion |
| Handle managed messages or configure native WebSocket listeners | [11-websockets](11-websockets/README.md) / `websocket-example` | Per-connection state, Origin admission, safe closure, offered subprotocols, explicit demand, borrowed buffers, and incoming limits |
| Coordinate lifecycle and observe request execution | [12-lifecycle](12-lifecycle/README.md) / `lifecycle-example` | Shutdown signalling and cleanup, local/application phases, detached outcomes, borrowed executors, and context attachment |
| Build an extension from route registration facts | [13-extensions](13-extensions/README.md) / `extensions-example` | Installation and selection, independent inherited settings, compiled route metadata, and application ownership |
| Authenticate requests and configure browser security | [14-security](14-security/README.md) / `authentication-example` | Route-selected identity, pac4j roles, session storage, CSRF tokens, CORS preflight, and explicitly trusted proxy metadata |
| Observe requests through metrics and traces | [15-observability](15-observability/README.md) / `micrometer-example`, `opentelemetry-example` | Route-template metrics, active samples, remote trace parents, child spans, selective observation and SDK ownership |
| Describe HTTP APIs and serve a protected local UI | [16-openapi](16-openapi/README.md) / `openapi-example` | Explicit frozen documents, request and response schemas, independent descriptions, and protected local UI resources |
| Expose native MCP features and protect client sessions | [17-mcp](17-mcp/README.md) / `mcp-example` | Synchronous/asynchronous tools, resources and prompts, verified MCP identity and permissions, client interactions and resource subscriptions |

## Maintainer fixtures

These launchers retain broader runtime and interoperability checks. They are not
additional numbered lessons; consumer recipes belong in the [Starlight site](../site/).

| Fixture | Example / Gradle project | Observable behavior |
| --- | --- | --- |
| Route HTTP methods, read inputs and vary availability | [legacy-requests](03-routing/legacy-requests/README.md) / `requests-example` | Seven verbs, generic route, path groups/patterns, both `when` forms, query/form/body/header/cookie inputs, supplied unary operation |
| Send server-sent events and resume a finite feed | [sse](10-streaming/sse/README.md) / `sse-example` | Data/named events, IDs/retry, heartbeats, replay/204 and errors before/after commitment |
| Exchange WebSocket messages and handle failures | [11-websockets](11-websockets/README.md) / `websocket-example` | Text/binary echo, per-connection context, handshake rejection, recoverable input and safe close codes |
| Handle exceptions and generated HTTP statuses | [legacy-exceptions](08-errors/legacy-exceptions/README.md) / `exceptions-example` | Built-in errors, local recovery, app fallback, route/group overrides and inheritance, generated versus thrown 404 |
| Mix typed JSON/text and render scoped errors | [legacy-json-errors](07-json/legacy-json-errors/README.md) / `json-errors-example` | Explicit codec, validated payload, inline scoped JSON 400/500 rendering |
| Authenticate a group and authorize an operation | [14-security](14-security/README.md) / `authentication-example` | Public route, Basic challenge, principal, reader/admin role policy |
| Serve classpath assets and finite HTTP streams | [finite](10-streaming/finite/README.md) / `static-streaming-example` | Narrow asset mount and CSV chunks; SSE/WebSocket have dedicated tutorials |
| Export request metrics to Prometheus and Grafana | [micrometer](15-observability/micrometer/README.md) / `micrometer-example` | Real scrape samples and a default request dashboard |
| Export request traces to Jaeger and Grafana | [opentelemetry](15-observability/opentelemetry/README.md) / `opentelemetry-example` | Explicit SDK/export, W3C parent continuation and a default recent-traces dashboard |
| Describe and try explicitly documented endpoints | [openapi](16-openapi/README.md) / `openapi-example` | Explicit schemas, two documents, codec, protected docs and Swagger authorization |
| Mount a native SDK MCP extension | [mcp](17-mcp/README.md) / `mcp-example` | Native SDK configuration, bundled MCP Inspector and official interoperability checks |

Every server example remains independently runnable and understandable. Ordinary
`./gradlew build` compiles examples and runs Docker-free JUnit HTTP/WebSocket tests through
`shoostr-test`; that fixture owns only the app, not supplied dependencies. Fetch each
response with `test.send(request -> ...)`, then use ordinary JUnit assertions.
The application-configuration, routing, request, body, response, JSON, error, and resource examples are compiled factories rather than standalone launchers;
their tests start and close the applications directly or through `TestServer`.
WebSocket tests use a separately owned JDK client against the fixture's public
`baseUri()`, observing messages and close frames rather than listener internals.

Jib's `jibDockerBuild` creates the local image without a Dockerfile or registry push.
Each Compose recipe publishes user-facing ports only on loopback. Run one example
at a time on 8080, or override its documented port. Ctrl+C/app stop uses Shoostr's
existing shutdown hook. Compose cleanup removes only that example's resources.

Telemetry checks belong to a separately invoked Docker lane: backend readiness,
actual requests, backend ingestion, Grafana provisioning and bounded cleanup.
These are not mocked data, console-only exporters or load/performance benchmarks.

From the repository root:

```sh
./gradlew :hello-example:run
./gradlew :requests-example:test
./gradlew :micrometer-example:dockerTest :opentelemetry-example:dockerTest
```

Use each README's exact requests, ports and cleanup commands. Backend fixtures use unique
Compose project names and remove their own disposable resources; do not run a second copy
on their test ports. CI builds all example images, runs the two real-backend test suites and
verifies MCP tools, resources and prompts through Inspector's public browser UI. Ordinary
`build` needs neither Docker nor external metrics, trace or identity services.

Topic selection follows the focused [Javalin tutorials](https://javalin.io/tutorials/),
[Jooby manual](https://jooby.io/) and [Spring guides](https://spring.io/guides/).
Those catalogs demonstrate common teaching topics, not measured popularity.
