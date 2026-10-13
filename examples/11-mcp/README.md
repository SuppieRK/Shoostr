# Runnable MCP example and official compatibility tests

This public app uses Shoostr's MCP extension and the official Java SDK
`2.0.1`, targeting protocol `2025-11-25`. Its fixed native tool/resource/prompt
catalog is the fixture expected by the official suite, not a production business
service. The protected OAuth example is separate. Node is test tooling only,
not a runtime dependency of the Java integration.

From the repository root, with Java 25:

```bash
cmdshape ./gradlew :mcp-example:run
```

Connect an MCP client to `http://127.0.0.1:8080/mcp`. `/health` is a finite readiness
route. Core owns JVM shutdown cleanup; the example does not install another hook.
`McpExample.application()` also returns the same app unstarted for live tests.
Pass explicit `Options` only when selecting custom listener settings; its port determines
the default allowed browser Origin.
The listener binds all IPv4 interfaces. The separately configured public URL is the
only allowed present browser Origin; absent
Origin is allowed for non-browser clients. Use the exact `127.0.0.1` URL printed
by the example, rather than a different browser-origin alias.

Configuration stays native: `Mcp.sync(security, transport -> McpServer.sync(transport)
...build())`, then `app.extensions(mcp)` and `routes.path("/mcp", mcp::routes)`.
The app owns the native server; `mcp.server()` is borrowed management access, not
an independently closeable resource. Fixture callbacks use native SDK content,
sampling, elicitation, logging, progress and resource-template descriptors.

`McpExample` defines HTTP routes inline and owns startup; `McpConfiguration` owns native extension
setup, and `CompatibilityFixtures` contains the SDK
callbacks required by the official suite.

```sh
cmdshape ./gradlew :mcp-example:jibDockerBuild
cmdshape docker compose -f examples/11-mcp/compose.yaml up -d
cmdshape curl --fail --retry 30 --retry-delay 1 --retry-connrefused --retry-all-errors \
  --max-time 3 --retry-max-time 60 http://127.0.0.1:8080/health
cmdshape docker compose -f examples/11-mcp/compose.yaml down
```

The Java25 image is nonroot. Compose publishes loopback only and sets `MCP_PUBLIC_ORIGIN`
to that public URL, not the container listener address. `APP_PORT=8081` changes both published
port and allowed origin. For host-run execution the origin defaults to
`http://127.0.0.1:<Options.port()>`, independently of the bind address; override
`MCP_PUBLIC_ORIGIN` only when you intentionally expose a different canonical origin.

Use Node 22. Run commands below from `examples/11-mcp`:

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
logs are retained under `examples/11-mcp/build/conformance/`; the normal `build`
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

The public compatibility fixture must remain separate from the protected OAuth
example. The official runner's OAuth client suite grades clients, not Shoostr's
resource-server token verification or HTTP challenges. Those need separate live
OAuth tests.

Sources: [published runner](https://registry.npmjs.org/@modelcontextprotocol/conformance/0.2.0-alpha.12),
[dated requirements](https://github.com/modelcontextprotocol/conformance/blob/f44482ba17df816d3176962a11cdf36aec9bda00/requirements/2025-11-25.yaml),
[runner CLI](https://github.com/modelcontextprotocol/conformance/blob/f44482ba17df816d3176962a11cdf36aec9bda00/src/index.ts),
[SDK session](https://github.com/modelcontextprotocol/java-sdk/blob/v2.0.1/mcp-core/src/main/java/io/modelcontextprotocol/spec/McpStreamableServerSession.java).
