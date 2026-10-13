# Executable examples

Pick one task and read its app factory, where route definitions appear inline,
then run the matching live HTTP test. There is no DI container, annotation routing,
multi-app demonstration or shared example runtime. Java 25 and the repository Gradle
wrapper are required. Docker Engine and Compose are needed only for containers/backends.

Direct runs use Shoostr's default `0.0.0.0` listener: all IPv4 interfaces, not only
localhost. Demonstration credentials and public endpoints are not production security;
run examples only in a trusted development environment. `TestServer` always binds
loopback, and the supplied Compose recipes publish user-facing ports on host loopback.
Choose an explicit `Options` host of `127.0.0.1` for a loopback-only application.

Each example uses SLF4J with an application-owned Logback backend. Its local
`src/main/resources/logback.xml` sends INFO and higher levels to stderr. The runtime
`slf4j-jdk-platform-logging` bridge routes Shoostr's JDK logger into that same backend;
framework modules do not select a provider or enable per-request logging by default.

## Learning path

| Task | Example / Gradle project | Observable lesson |
| --- | --- | --- |
| Start/stop a server and return text | [01-hello](01-hello/README.md) / `hello-example` | `GET /hello`, plain response and deterministic test ownership |
| Read inputs and substitute a dependency in a test | [02-requests](02-requests/README.md) / `requests-example` | Decoded path/query inputs, optional defaults, repeated values, supplied JDK function |
| Mix typed JSON/text and render scoped errors | [03-json-errors](03-json-errors/README.md) / `json-errors-example` | Explicit codec, validated payload, scoped 400/500 rendering |
| Authenticate a group and authorize an operation | [04-authentication](04-authentication/README.md) / `authentication-example` | Public route, Basic challenge, principal, reader/admin role policy |
| Serve classpath assets and finite HTTP/SSE streams | [05-static-streaming](05-static-streaming/README.md) / `static-streaming-example` | Narrow asset mount, CSV chunks, exact completed event frames |
| Export request metrics to Prometheus and Grafana | [06-micrometer](06-micrometer/README.md) / `micrometer-example` | Real scrape samples and a provisioned request dashboard |
| Export request traces to Jaeger and Grafana | [07-opentelemetry](07-opentelemetry/README.md) / `opentelemetry-example` | Explicit SDK/export, W3C parent continuation and Grafana Explore |
| Persist a resource with PostgreSQL/Flyway/jOOQ | [08-postgres](08-postgres/README.md) / `postgres-example` | Startup migration, create/read and retained data across app restart |
| Describe and try explicitly documented endpoints | [09-openapi](09-openapi/README.md) / `openapi-example` | Explicit schemas, two documents, codec, protected docs and Swagger authorization |
| Mount a native SDK MCP extension | [10-mcp](10-mcp/README.md) / `mcp-example` | Separate native SDK configuration and official interoperability checks |
| Use an external OAuth authorization server | [11-mcp-oauth](11-mcp-oauth/README.md) / `mcp-oauth-example` | Real TLS/Keycloak, PKCE/scope escalation and distinct REST/MCP authentication |

Every server example remains independently runnable and understandable. Ordinary
`./gradlew build` compiles examples and runs Docker-free JUnit HTTP tests through
`shoostr-test`; that fixture owns only the app, not supplied dependencies. Fetch each
response with `test.send(request -> ...)`, then use ordinary JUnit assertions.

Jib's `jibDockerBuild` creates the local image without a Dockerfile or registry push.
Each Compose recipe publishes user-facing ports only on loopback. Run one example
at a time on 8080, or override its documented port. Ctrl+C/app stop uses Shoostr's
existing shutdown hook. Compose cleanup removes only that example's resources.

Telemetry/database checks belong to a separately invoked Docker lane: backend readiness,
actual requests, backend ingestion/persistence, Grafana provisioning and bounded cleanup.
These are not mocked data, console-only exporters or load/performance benchmarks.

From the repository root:

```sh
./gradlew :hello-example:run
./gradlew :requests-example:test
./gradlew :micrometer-example:dockerTest :opentelemetry-example:dockerTest :postgres-example:dockerTest
```

Use each README's exact requests, ports and cleanup commands. Backend fixtures use unique
Compose project names and remove their own disposable resources; do not run a second copy
on their test ports. CI builds all example images, runs the three real-backend tests and
verifies the OAuth container with the same official browser/client driver. Ordinary
`build` needs neither Docker nor external metrics, trace, database or identity services.

Topic selection follows the focused [Javalin tutorials](https://javalin.io/tutorials/),
[Jooby manual](https://jooby.io/) and [Spring guides](https://spring.io/guides/).
Those catalogs demonstrate common teaching topics, not measured popularity.
