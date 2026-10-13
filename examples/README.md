# Executable examples

Pick one task, read its small composition root and separate route/handler configuration,
then run the matching live HTTP test. There is no DI container, annotation routing,
multi-app demonstration or shared example runtime. Java 25 and the repository Gradle
wrapper are required. Docker Engine and Compose are needed only for containers/backends.

## Learning path

| Task | Example / Gradle project | Observable lesson |
| --- | --- | --- |
| Start/stop a server and return text | [hello](hello/README.md) / `hello-example` | `GET /hello`, plain response and deterministic test ownership |
| Read inputs and substitute a dependency in a test | [requests](requests/README.md) / `requests-example` | Decoded path/query inputs, optional defaults, repeated values, supplied JDK function |
| Mix typed JSON/text and render scoped errors | [json-errors](json-errors/README.md) / `json-errors-example` | Explicit codec, validated payload, scoped 400/500 rendering |
| Authenticate a group and authorize an operation | [authentication](authentication/README.md) / `authentication-example` | Public route, Basic challenge, principal, reader/admin role policy |
| Serve classpath assets and finite HTTP/SSE streams | [static-streaming](static-streaming/README.md) / `static-streaming-example` | Narrow asset mount, CSV chunks, exact completed event frames |
| Export request metrics to Prometheus and Grafana | [micrometer](micrometer/README.md) / `micrometer-example` | Real scrape samples and a provisioned request dashboard |
| Export request traces to Jaeger and Grafana | [opentelemetry](opentelemetry/README.md) / `opentelemetry-example` | Explicit SDK/export, W3C parent continuation and Grafana Explore |
| Persist a resource with PostgreSQL/Flyway/jOOQ | [postgres](postgres/README.md) / `postgres-example` | Startup migration, create/read and retained data across app restart |
| Describe and try explicitly documented endpoints | [openapi](openapi/README.md) / `openapi-example` | Explicit schemas, two documents, codec, protected docs and Swagger authorization |
| Mount a native SDK MCP extension | [mcp](mcp/README.md) / `mcp-example` | Separate native SDK configuration and official interoperability checks |
| Use an external OAuth authorization server | [mcp-oauth](mcp-oauth/README.md) / `mcp-oauth-example` | Real TLS/Keycloak, PKCE/scope escalation and distinct REST/MCP authentication |

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

Existing [consumer-smoke](consumer-smoke/) checks locally published artifacts; it is
not a beginner server tutorial. Root `./gradlew consumerSmoke` keeps that packaging gate.

Topic selection follows the focused [Javalin tutorials](https://javalin.io/tutorials/),
[Jooby manual](https://jooby.io/) and [Spring guides](https://spring.io/guides/).
Those catalogs demonstrate common teaching topics, not measured popularity.
