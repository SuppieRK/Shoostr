# 17 — MCP

Maintainer interoperability fixture. Consumer recipes cover [MCP catalogs](../../site/src/content/docs/mcp/server.mdx), [operations](../../site/src/content/docs/mcp/operations.mdx), [security](../../site/src/content/docs/mcp/security.mdx) and [client interactions](../../site/src/content/docs/mcp/interactions.mdx).

`McpServerExample` supplies the consumer recipe's synchronous and asynchronous
catalog factories, with real SDK client tests in `McpServerExampleTest`.
`McpSecurityExample` adds the self-contained development credential registry and
live transport-security tests, without claiming external-provider OAuth coverage.
`McpInteractionsExample` covers client elicitation/sampling, targeted notifications
and resource subscriptions, with public SDK and HTTP lifecycle checks.
`McpOperationsExample` demonstrates independently configured admission/control policies
and managed blocking work composed through native async handlers. Its executable tests
cover reply progress, overload, matching cancellation and actual-exit accounting after timeout.
The launcher and official compatibility catalog remain separate fixtures.

This public app uses Shoostr's MCP extension and the official MCP Java SDK
`2.1.0`, targeting protocol `2025-11-25`. Its native tool/resource/prompt catalog
uses the names and behavior required by the official suite, with example-specific
sample content rather than a production business service. The Java integration
has no Node runtime dependency. Inspector runs its
own Node backend in Docker; host Node is needed only for optional verification.

From the repository root, with Java 21, run the app without Inspector:

```sh
cmdshape ./gradlew :mcp-example:run
```

The MCP endpoint is `http://127.0.0.1:8080/mcp`; `/health` is a finite readiness
route. Core owns JVM shutdown cleanup. `McpExample.application()` returns the same
app unstarted for live tests. Pass a listening port to `application(int)` to configure
the native listener and local public origin.

Configuration stays native: `Mcp.sync(security, transport -> McpServer.sync(transport)
...build())`, then `app.extensions(mcp)` and `routes.path("/mcp", mcp::routes)`.
The app owns the native server; `mcp.server()` is borrowed management access, not an
independently closeable resource. `McpExample` defines HTTP routes inline;
`McpConfiguration` owns extension setup, and `CompatibilityFixtures` contains native
SDK callbacks for the official suite.

## Example data

Tool names, resource URIs and a few elicitation defaults follow the pinned official
runner's interface. The other content is local demonstration data: a welcome message,
an 8×8 blue/amber checkerboard, a short PCM pulse, prompts and a parcel-delivery schema.
The schema shows a collection-point requirement for pickup and a postal-destination
requirement for courier delivery. It does not book shipments or persist user data.

The image and audio were generated once with JDK 25 APIs. The app serves their stored
Base64 values; it does not generate media at startup or depend on another media library.
To reproduce the samples, start `cmdshape jshell`, run `/set truncation 10000`
so the Base64 values are not abbreviated, and paste:

```jshell
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import javax.sound.sampled.*;
import java.io.*;
import java.util.Base64;

var badge = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
for (int y = 0; y < 8; y++)
  for (int x = 0; x < 8; x++)
    badge.setRGB(x, y, ((x / 2 + y / 2) % 2 == 0) ? 0x1d4ed8 : 0xffc857);
var png = new ByteArrayOutputStream();
ImageIO.write(badge, "png", png);
Base64.getEncoder().encodeToString(png.toByteArray());

var samples = new byte[128];
for (int frame = 0; frame < 64; frame++) {
  short sample = (short) (6000 * Math.sin(2 * Math.PI * 440 * frame / 8000));
  samples[2 * frame] = (byte) sample;
  samples[2 * frame + 1] = (byte) (sample >> 8);
}
var format = new AudioFormat(8000, 16, 1, true, false);
var wav = new ByteArrayOutputStream();
try (var audio = new AudioInputStream(new ByteArrayInputStream(samples), format, 64)) {
  AudioSystem.write(audio, AudioFileFormat.Type.WAVE, wav);
}
Base64.getEncoder().encodeToString(wav.toByteArray());
```

## Run the app and Inspector with Docker

One Compose file starts both. From the repository root:

```sh
cmdshape ./gradlew :mcp-example:jibDockerBuild
cmdshape docker compose -f examples/17-mcp/compose.yaml up -d --wait --wait-timeout 90
cmdshape curl --fail --retry 30 --retry-delay 1 --retry-connrefused --retry-all-errors \
  --max-time 3 --retry-max-time 60 http://127.0.0.1:8080/health
```

Open **http://localhost:6274**, enable **shoostr-mcp**, and use:

- **Tools:** select `test_simple_text`, then execute it. The result is
  `Welcome to Shoostr's MCP example.` Other tools demonstrate images,
  audio, embedded resources, progress and recoverable errors.
- **Resources:** select **Example guide** to read its content; templates accept an explicit ID.
- **Prompts:** select `test_simple_prompt` to retrieve its message.

No login, certificates, host Node installation or OpenAPI document is needed.
The checked-in `inspector.json` selects Streamable HTTP and the existing
`2025-11-25` initialization protocol (`protocolEra: "legacy"`); it does not upgrade
the MCP Java SDK. The application is public: Origin validation is not authentication.

Both images run nonroot. Host ports 8080 and 6274 publish on loopback only.
`APP_PORT=8081` changes the app's published port and its allowed public Origin.
Inspector shares the app's network namespace, so its internal loopback target
remains unchanged. The default Jetty listener binds wildcard interfaces; the public
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
cmdshape docker compose -f examples/17-mcp/compose.yaml down --timeout 20
```

To restart, recreate both services rather than using `docker compose restart`:

```sh
cmdshape docker compose -f examples/17-mcp/compose.yaml \
  up -d --force-recreate --wait --wait-timeout 90
```

The shared network namespace can leave Inspector attached to an old listener if its
owner is restarted alone. Keep the same `APP_PORT` override when recreating the stack.

CI uses this directory's pinned Playwright tooling to exercise actual tool, resource
and prompt results through Inspector's public UI. With the stack running, use Node 22
and run these commands from `examples/17-mcp`:

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

Use Node 22. Run commands below from `examples/17-mcp`:

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
logs are retained under `examples/17-mcp/build/conformance/`; the normal `build`
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
and mounts Shoostr's adapter; no SDK Servlet server is used. Required identifiers
and fixed form values follow the runner's interface; sample bodies, generated
media and the parcel schema are defined by this example. SDK and tooling
dependencies retain their own licenses.

SDK 2.1.0 does not implement SSE replay/resume. Keep that upstream limitation
visible in reports, rather than adding a broad expected-failure baseline or a
fake reconnection tool. Additional live SDK tests are required for session
isolation, stream cleanup, subscriptions and bidirectional operations.

Historical compatibility evidence: the local run on October 12, 2026 passed all 30 scored server scenarios
(81 successful checks including unscored checks), plus the independently gated
session lifecycle (3/3) and JSON Schema (8/8). The unscored polling scenario
reported two warnings: no event-ID priming frame and no retry field. Its
disconnect/resume check was not exercised; there is no fake `test_reconnection`
tool or expected-failure baseline. This result does not prove replay support.

SDK 2.1.0 removes pending bidirectional correlations on replies and terminal signals;
the older 2.0.1 completed-correlation retention limitation no longer applies.
Session limits and idle expiry remain separate from configurable operation admission
and managed execution bounds. Shoostr handles matching `notifications/cancelled`
in the adapter; closing an HTTP stream alone does not cancel detached work.
Started managed callables remain charged until actual exit, even after interruption.
These count limits do not establish an encoded-message byte budget.
See the [current MCP contracts](../../mcp/README.md) for ownership
and transport limits. Shoostr does not fork the SDK or manipulate its private state.

No external-provider OAuth example or end-to-end fixture is maintained. The official
runner's OAuth client suite grades clients, not Shoostr's resource-server token
verification or HTTP challenges; it does not replace live resource-server OAuth tests.

Sources: [published runner](https://registry.npmjs.org/@modelcontextprotocol/conformance/0.2.0-alpha.12),
[dated requirements](https://github.com/modelcontextprotocol/conformance/blob/f44482ba17df816d3176962a11cdf36aec9bda00/requirements/2025-11-25.yaml),
[runner CLI](https://github.com/modelcontextprotocol/conformance/blob/f44482ba17df816d3176962a11cdf36aec9bda00/src/index.ts),
[SDK session](https://github.com/modelcontextprotocol/java-sdk/blob/v2.1.0/mcp-core/src/main/java/io/modelcontextprotocol/spec/McpStreamableServerSession.java).
