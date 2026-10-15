# Runnable MCP example and official compatibility tests

This public app uses Shoostr's MCP extension and the official Java SDK
`2.0.1`, targeting protocol `2025-11-25`. Its fixed native tool/resource/prompt
catalog is the fixture expected by the official suite, not a production business
service. The Java integration has no Node runtime dependency. Inspector runs its
own Node backend in Docker; host Node is needed only for optional verification.

From the repository root, with Java 25, run the app without Inspector:

```sh
cmdshape ./gradlew :mcp-example:run
```

The MCP endpoint is `http://127.0.0.1:8080/mcp`; `/health` is a finite readiness
route. Core owns JVM shutdown cleanup. `McpExample.application()` returns the same
app unstarted for live tests. Pass explicit `Options` only for custom listener settings.

Configuration stays native: `Mcp.sync(security, transport -> McpServer.sync(transport)
...build())`, then `app.extensions(mcp)` and `routes.path("/mcp", mcp::routes)`.
The app owns the native server; `mcp.server()` is borrowed management access, not an
independently closeable resource. `McpExample` defines HTTP routes inline;
`McpConfiguration` owns extension setup, and `CompatibilityFixtures` contains native
SDK callbacks for the official suite.

## Run the app and Inspector with Docker

One Compose file starts both. From the repository root:

```sh
cmdshape ./gradlew :mcp-example:jibDockerBuild
cmdshape docker compose -f examples/12-mcp/compose.yaml up -d --wait --wait-timeout 90
cmdshape curl --fail --retry 30 --retry-delay 1 --retry-connrefused --retry-all-errors \
  --max-time 3 --retry-max-time 60 http://127.0.0.1:8080/health
```

Open **http://localhost:6274**, enable **shoostr-mcp**, and use:

- **Tools:** select `test_simple_text`, then execute it. The result is
  `This is a simple text response for testing.` Other tools demonstrate images,
  audio, structured output, progress and recoverable errors.
- **Resources:** select **Static text** to read its content; templates accept an explicit ID.
- **Prompts:** select `test_simple_prompt` to retrieve its message.

No login, certificates, host Node installation or OpenAPI document is needed.
The checked-in `inspector.json` selects Streamable HTTP and the existing
`2025-11-25` initialization protocol (`protocolEra: "legacy"`); it does not upgrade
the Java SDK. The application is public: Origin validation is not authentication.

Both images run nonroot. Host ports 8080 and 6274 publish on loopback only.
`APP_PORT=8081` changes the app's published port and its allowed public Origin.
Inspector shares the app's network namespace, so its internal loopback target
remains unchanged. The default Java listener binds all IPv4 interfaces; the public
Origin is configured separately, never inferred from the wildcard bind address.
Absent Origin is allowed for non-browser clients; present Origin must match the
configured allowlist.

Inspector is pinned to 2.11.0 and its multiarchitecture digest. Its backend authentication
and exact browser origin remain enabled. Server configuration is read-only; the backend
uses a memory secret store and temporary runtime filesystems, not a persistent volume.
Do not expose this process-capable development tool publicly or mount a repository,
Docker socket or private keys into it.

Stop both services with the same file:

```sh
cmdshape docker compose -f examples/12-mcp/compose.yaml down --timeout 20
```

To restart, recreate both services rather than using `docker compose restart`:

```sh
cmdshape docker compose -f examples/12-mcp/compose.yaml \
  up -d --force-recreate --wait --wait-timeout 90
```

The shared network namespace can leave Inspector attached to an old listener if its
owner is restarted alone. Keep the same `APP_PORT` override when recreating the stack.

CI uses this directory's pinned Playwright tooling to exercise actual tool, resource
and prompt results through Inspector's public UI. With the stack running, use Node 22
and run these commands from `examples/12-mcp`:

```sh
cmdshape npm ci --ignore-scripts
cmdshape node node_modules/playwright/cli.js install --with-deps firefox
cmdshape node --test inspector-test.mjs
```

This verification tooling is not required for manual Inspector use. CI does not upload
Inspector's token-bearing startup logs. See the
[official Inspector guide](https://modelcontextprotocol.io/docs/tools/inspector)
and [pinned container instructions](https://github.com/modelcontextprotocol/inspector/blob/2.11.0/docs/docker.md).

## Official compatibility tests

Use Node 22. Run commands below from `examples/12-mcp`:

```bash
cmdshape npm ci --ignore-scripts
cmdshape npm exec -- conformance list --requirements 2025-11-25
```

Then, from the repository root:

```bash
cmdshape ./gradlew :mcp-example:conformanceTest
```

The task starts and closes the same application through `TestServer`, invokes the
installed official CLI directly, and gates all three selections below. It uses
fixed loopback port `8766`; override with `-PconformancePort=8767` if needed. It
does not contact or leave running a separately started app. Reports and runner
logs are retained under `examples/12-mcp/build/conformance/`; the normal `build`
runs the live Java smoke test without requiring Node. CI runs the official task
separately and preserves its reports even on failure.

The exact official runner is `@modelcontextprotocol/conformance@0.2.0-alpha.12`,
published from source commit `f44482ba17df816d3176962a11cdf36aec9bda00`.
The lockfile pins its transitive dependencies and package integrity checks.
Do not use an unversioned `npx` command or upgrade the runner during a comparison.

For manual execution against the separately running example, use:

```bash
cmdshape npm exec -- conformance server \
  --url http://127.0.0.1:8080/mcp \
  --requirements 2025-11-25 \
  --timeout 30000 \
  --output-dir build/conformance/requirements

cmdshape npm exec -- conformance server \
  --url http://127.0.0.1:8080/mcp \
  --spec-version 2025-11-25 \
  --scenario server-session-lifecycle \
  --output-dir build/conformance/session

cmdshape npm exec -- conformance server \
  --url http://127.0.0.1:8080/mcp \
  --spec-version 2025-11-25 \
  --scenario json-schema-2020-12 \
  --output-dir build/conformance/schema
```

Run all three gates. The requirements command selects both scenario membership
and wire revision, but does not fail for every unscored scenario. In particular,
session lifecycle and JSON Schema need their separate commands above. Preserve
the detailed reports, including warnings, skipped checks and unscored failures;
an exit code of zero alone does not mean every reported scenario passed.

Running the suite against an arbitrary business server is not equivalent
validation. This example supplies the required fixture through the native SDK
and mounts Shoostr's adapter; no SDK Servlet server is used. See
[fixture attribution and license](THIRD-PARTY-NOTICES.md).

SDK 2.0.1 does not implement SSE replay/polling. Keep that upstream limitation
visible in reports, rather than adding a broad expected-failure baseline or a
fake reconnection tool. Additional live SDK tests are required for session
isolation, stream cleanup, subscriptions and bidirectional operations.

The fresh local run on October 12, 2026 passed all 30 scored server scenarios
(81 successful checks including unscored checks), plus the independently gated
session lifecycle (3/3) and JSON Schema (8/8). The unscored polling scenario
reported two warnings: no event-ID priming frame and no retry field. Its
disconnect/resume check was not exercised; there is no fake `test_reconnection`
tool or expected-failure baseline. This result does not prove replay support.

The pinned SDK also retains completed bidirectional exchange references while
their session remains owned. Shoostr does not fork the SDK or manipulate its
private state; native operations remain supported, and session deletion/shutdown
ends adapter ownership. Do not infer bounded native per-exchange retention from
successful compatibility tests.

No external-provider OAuth example or end-to-end fixture is maintained. The official
runner's OAuth client suite grades clients, not Shoostr's resource-server token
verification or HTTP challenges; it does not replace live resource-server OAuth tests.

Sources: [published runner](https://registry.npmjs.org/@modelcontextprotocol/conformance/0.2.0-alpha.12),
[dated requirements](https://github.com/modelcontextprotocol/conformance/blob/f44482ba17df816d3176962a11cdf36aec9bda00/requirements/2025-11-25.yaml),
[runner CLI](https://github.com/modelcontextprotocol/conformance/blob/f44482ba17df816d3176962a11cdf36aec9bda00/src/index.ts),
[SDK session](https://github.com/modelcontextprotocol/java-sdk/blob/v2.0.1/mcp-core/src/main/java/io/modelcontextprotocol/spec/McpStreamableServerSession.java).
