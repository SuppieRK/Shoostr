# Shoostr diagnostics in Actions

Run **Shoostr benchmarks** manually from the Actions tab. Select the branch,
`steady`, `overload`, `slow-client` or `tracing`, and artifact retention (1–14 days, default 7).
For HTTP modes, set the JVM's logical CPU count (default 1) and total memory cap in MiB (default
1024). The heap remains fixed at 256 MiB; raising the total cap does not raise it.
CPU counts must leave at least one permitted CPU for k6. Memory must exceed the
256 MiB heap and cannot exceed host RAM. Invalid settings fail, never clamp.
The workflow requires its support files on the selected branch. It does not run
benchmarks on pushes or pull requests. Campaigns use Java 21; ordinary dispatch uses
Jetty's default platform-thread pool.

Steady mode runs eight HTTP workloads (plaintext, pre-encoded JSON, 1 KiB echo,
16 KiB flushed stream, literal routing, parameter routing, 404 and 405), three SSE
workloads (burst, paced and callback-delayed large events), and three WebSocket
workloads (text, binary and callback-delayed large frames). Each workload has its
own Linux runner. Its three trials use fresh JVMs, a 30-second warmup, and a
180-second offered-load phase. Up to fourteen workload jobs run concurrently;
GitHub may queue jobs when account capacity is unavailable.

Overload mode runs only the eight HTTP workloads. Each trial offers 1,000,
5,000, 10,000 and 50,000 requests/second in 15-second plateaus with
five-second drains. Threshold breaches appear as **OVERLOAD**, while startup,
tooling, profiling and publication failures fail the workflow. Steady-mode
correctness failures also fail the workflow. The load generator can saturate
before the service; inspect both processes before interpreting a boundary.
Overload warmup permits dropped iterations so saturation does not prevent the
profiled plateaus from running. Warmup setup, response correctness and HTTP
failure checks remain strict; steady warmup also requires zero drops. Per-trial
summaries show warmup completed counts and drops / scheduled total (percentage),
separately from measured results. Native warmup logs and metrics remain retained.
All four plateaus run even if an earlier plateau drops iterations. There is no
100,000-request/second step or adaptive early-stop controller.

Slow-WebSocket steady traffic uses one session/second: the initial hosted trial
at ten sessions/second saturated the two-CPU k6 client and failed during warmup.
Payload validation, callback delay, timeouts and correctness checks are unchanged.

The build job prepares one fixture distribution and one pinned k6 binary with
the SSE extension. Workload jobs reuse those binaries and a 256 MiB fixed G1
heap. The JVM uses the requested number of permitted logical CPUs; k6 uses the
remaining CPUs (normally three with the one-CPU default on a four-CPU runner).
Actual affinity,
CPU model, runner image, JVM/client versions, source revision and hashes accompany
the recordings. This separates the campaign from the developer's machine, but
hosted runner hardware and images can still vary.

Each fresh JVM prints and retains native `jcmd` version, command line, effective
non-default flags and `VM.info` diagnostics before warmup. The complete effective
flag list (`VM.flags -all`) and OS process limits are also retained. Summaries show
actual JVM/client CPU affinity and JVM-visible CPU/RAM/container limits. Affinity
does not reserve exclusive cores. A fresh cgroup v2 group enforces the JVM's
total memory cap, including native and cgroup-accounted cache/kernel memory;
swap is disabled for that group. k6, diagnostic tools and artifact processing
stay outside it. The runner must support these limits and passwordless sudo;
failure to apply them fails the run. Before/after native cgroup limits, peak
memory and OOM events are retained and summarized; an OOM kill fails the run.
The group is removed after JVM shutdown, including ordinary failure cleanup.
The 256 MiB Java heap cap alone does not cap native memory or process RSS.
Historical campaigns retain their prior JVM-diagnostic evidence requirements and
recorded plateau rates. Unrecorded resource limits remain unknown, not invented.
Resource-controlled campaigns require the new requested/effective limit and cgroup
evidence; missing or mismatched limits make their evidence incomplete.
The new one-CPU configuration is not directly comparable to earlier two-CPU runs.

Each workload summary contains all three trial rows and excerpts from native
JFR reports. The final job provides a campaign overview and links to downloadable
outputs. HTTP timings cover complete response bodies; SSE timings cover streams;
WebSocket timings distinguish message round-trip from complete session duration.
Slow-client callbacks pause in user code; they do not throttle the network socket.
Percentiles remain per trial. Overload throughput divides each plateau's completed
request count by its duration; native tagged rates use the whole run duration.
Native k6 `max` latency appears next to `p99`, without pooling trials or plateaus.
Drops include the configured scheduled total (offered rate × load duration,
excluding warmup/drains) and its dropped percentage, displayed to four decimal
places. A dropped iteration never started and is not a failed request. Each HTTP
iteration is one request; each SSE/WebSocket iteration is one session, not an
event or message. For partial runs the total remains the configured full-run
schedule, not a claim that all iterations were accounted for.

Artifacts retain complete JFR recordings, native k6 HTML dashboards, compressed
raw time series, summary JSON, logs, process monitoring and full JFR CLI views.
HTTP recordings enable `jdk.ExecutionSample` at a 10 ms period.
JFR starts after warmup and spans the full measured k6 invocation, including
client initialization/setup, drain and output finalization. Recordings can therefore
last longer than the offered-load phase. JFR is explicitly stopped before JVM shutdown. Cancellation
recovery is best-effort; incomplete evidence stays visibly marked. Artifact sizes
and expiry are shown in summaries. Upload or storage-quota failures block
successful publication; the workflow does not change billing settings.

All measured runs include profiling and raw-output overhead. Thirty seconds is the
chosen warmup duration, not a guarantee of steady JIT state. These measurements
provide diagnostics rather than an automatic latency-regression gate. Inspect
the native data before attributing a change to Shoostr.

The fixture remains the `:benchmarks` Gradle module, now located in this directory.
Normal builds compile and check it; they do not execute timed campaigns. Targeted
JMH diagnostics remain in the separate `microbenchmarks` module.

## Finite slow-client diagnostic

Select `slow-client` with exactly one JVM CPU and 1024 MiB total memory. This
manual-only path is a bounded resource-safety investigation, not deployment
capacity certification, a weekly campaign or a new PR merge gate. It requires
cgroup v2 and passwordless sudo; missing enforcement fails rather than silently
substituting heap-only limits. The heap stays 256 MiB G1 and cgroup swap is disabled.

One loopback-only `SlowClientServer` JVM stays alive across all eight cycles:
slow bodies and cooperative blocked handlers, each at 32 and 128 clients, twice.
The fixture preserves default request-size and native idle/rate policies and
records them through public native configuration. `slowhttptest` 1.9.0 maintains
incomplete 65536-byte POST bodies, admitting up to the selected count per second
and sending randomized follow-up pairs (`-x 8`) each second. Raw CSV must show
all selected connections from seconds 5 through 59, no early closures, and a
sample at or after second 60. Its one-second stop granularity can extend the
nominal 60 seconds; exact wall-clock phase times remain evidence, not rounded
claims. k6 supplies constant-VU blocked requests for 60 seconds; each handler
waits at most five seconds on a cooperative gate so the fixture does not require
cancellation of arbitrary application code. Blocked VUs reuse their HTTP connections
between calls; healthy probes always establish fresh connections. Ending load
disconnects the blocked clients, and the control route releases the gate before a
full 30-second recovery window. This workload investigates persistent blocked
clients, not connection churn. An earlier reconnect-per-call run failed sustained
exposure when native TCP setup took over one second; a passing persistent-client
run would neither resolve nor invalidate that observation. No server default or
exposure, health or recovery criterion is changed to accommodate it.

Blocked raw metrics must also show the selected active VUs throughout the nominal
load (after five seconds of startup), with no missing one-second gauge series
interval beyond 1.5 seconds. Each selected VU's successful native request-duration
intervals must span seconds 5–55, with at most one second between calls; the final
five-second batch can be interrupted at termination. A historical peak or one
successful batch is insufficient. These are exposure-validity bounds, not requests/
second targets. The checker uses k6's optional native `vu` tag and
[end-of-response duration timestamps](https://grafana.com/docs/k6/latest/using-k6/metrics/reference/),
not synthetic server throughput estimates.

A separate k6 process schedules healthy requests at one per second for 100 seconds,
covering load and recovery plus bounded tool-start/output margin. The target is
100 probes with a one-probe native duration-boundary allowance: only complete,
consistent 100- or 101-probe evidence is accepted, including every extra probe.
This does not assert a universal native scheduling maximum. Every status,
body and complete-call duration must pass; no response may exceed one second and
no probe may be missing or dropped. Native raw timestamps must span both phases.
The campaign stops on an observed probe/OOM failure; failed or partial runs are
retained as FAILED, never converted into successful reduced-load measurements.

Public matched/terminal callbacks distinguish active handlers from unfinished
exchanges. Recovery requires zero workload handlers/exchanges, balanced admitted
and terminal counts, the exact selected peak concurrency, and only the current
state-control connection. A qualifying recovery sample must fall within 30 seconds,
not merely after the health process exits. Native connected endpoints include
live control/probe connections; short overlaps are recorded rather than subtracted
by an invented constant. A quiet recovered observation contains one current control
connection. All cycles reuse the same listener and JVM to expose accumulation.

The `slow-client-data` artifact retains k6 compressed time series/summaries/logs,
slowhttptest CSV/HTML/logs, actual tool arguments/versions/hashes and revision,
effective CPU/cgroup/heap configuration, per-process native CPU/RSS observations,
FD inventories, work/connection samples, baseline/load/recovered heap and JSON
thread dumps, and bounded JFR recordings with virtual-thread lifecycle and Java
error events. k6, slowhttptest and profiler overhead stay outside the server cgroup;
their affinity, membership and `/usr/bin/time` reports remain identifiable. The
runner owns its process groups, stops them on exit, and removes only its empty
cgroup after preserving final OOM evidence. Results are never overwritten.

The executable checker validates deterministic timing, exposure, health, work,
connection and OOM invariants. Passing it alone is **not** a leak verdict: inspect
FDs, heap, RSS/cgroup and virtual/platform thread traces across repeats for retained
request resources versus expected native pools/JIT/cache warmup. No byte-exact GC
baseline is required. Classify any failure as fixture, native/configuration,
application or Shoostr adapter before proposing a patch. No quota, scheduler,
absolute deadline or admission feature is installed by this diagnostic.

## Paired tracing JMH

Select `tracing` and supply `baseline_sha` as an immutable full 40-character commit SHA.
The candidate is the selected workflow branch's exact commit. One Ubuntu runner builds the
candidate fixture and baseline adapters serially, then uses the same fixture and dependency jars
for both revisions, substituting only the project's core and OpenTelemetry adapter jars. Classpaths,
checksums, source revisions, hardware, JVM flags, CPU/memory visibility and OS limits are retained.
Choose a baseline that builds and runs on Java 21. An incompatible baseline fails
validation rather than producing a comparison.

The bounded matrix covers `plain`, SDK-installed/no-routes-enabled, selected GET, unselected GET
with selected sibling POST, and app-wide SDK tracing, each with default, single-platform-worker
and single-ForkJoin-worker execution. The SDK records spans but has no exporter. Fifteen paired
cells alternate which revision runs first. Each side uses three fresh JVM forks, three two-second
warmup iterations and five two-second measurement iterations, with JMH's GC profiler. Inspect
native iteration trends for JIT drift; this duration is not a stabilization guarantee. Expect
roughly 30–45 minutes including builds, smoke checks and JVM startup; the job has a 60-minute cap.

JMH's native result tables appear in the Actions summary: average time/error and allocated bytes/op
are not replaced by custom statistics. Separate candidate JFR recordings cover selected/unselected
ForkJoin cases, with native allocation and hot-method views. Artifacts preserve complete logs,
JSON and profiles, including failed/partial runs. These LocalConnector exchanges include parsing,
dispatch and serialization, not network throughput, exporter work or how often ForkJoin helping
occurs. Hosted-runner uncertainty remains; this is diagnostic evidence, not a regression threshold.

Unlike HTTP campaigns, tracing JMH uses a fixed 512 MiB G1 heap per fork and no extra CPU affinity
or total-process RAM cap. HTTP-mode CPU/RAM inputs do not apply. Runner/container limits and
effective JVM settings are recorded explicitly; heap size is not a total-memory limit.

References: [manual workflow execution](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow),
[k6 native dashboard](https://grafana.com/docs/k6/latest/results-output/web-dashboard/),
[k6 metric definitions](https://grafana.com/docs/k6/latest/using-k6/metrics/reference/),
[JDK 21 diagnostic commands](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jcmd.html),
[JDK 21 JFR CLI](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jfr.html).

## Local static-file selection diagnostic

The [HTTP fixture](src/main/java/io/github/suppierk/shoostr/bench/ServerMain.java) accepts
an optional static-root directory after the port and
route-group count. For example, `benchmarks 18080 1 /path/to/corpus` anchors one mount
at `/static`. Without that argument, the fixture retains its existing endpoints.
The application owns the anchored mount and closes it at shutdown.

Prepare 64 files named `file-0.bin` through `file-63.bin` in both the corpus root and
`nested/deeper/`. Use an existing directory on a filesystem provider that supports
secure directory operations. Each file must contain exactly 1,024 ASCII `x` bytes. Corpus creation
is outside the workload; the fixture does not generate files. Select a positive
`STATIC_FILES` count matching the prepared corpus. The script checks every configured
file before timing, including its body and content type, and checks the first response's
static metadata and headers. It also verifies the existing HTTP fixture endpoints and
platform-thread execution.

Build the fixture distribution and start its generated launcher from the repository root:

```sh
cmdshape ./gradlew :benchmarks:installDist
cmdshape .github/benchmarks/build/install/benchmarks/bin/benchmarks 18080 1 /path/to/corpus
```

In a second terminal, run the [k6 script](http.js) against that listener:

```sh
cmdshape k6 run -e BASE_URL=http://127.0.0.1:18080 -e ROUTE_GROUPS=1 -e STATIC_FILES=64 -e WORKLOAD=static-shallow -e RATE=100 -e DURATION=10s -e VUS=128 --summary-export shallow.json .github/benchmarks/http.js
```

The [fixture test](src/test/java/io/github/suppierk/shoostr/bench/ServerMainTest.java)
checks distinct shallow and nested file selection through real HTTP.
Run the script with either `WORKLOAD=static-shallow` or
`WORKLOAD=static-nested`, `STATIC_FILES=64`, and the normal `BASE_URL`, `RATE`,
`DURATION`, and `VUS` inputs. The static workload names remain stable as file indices
vary; both path families use the same mount. These optional workloads are local
diagnostics and are not added to the Actions campaign matrix.

Warm the corpus, retain native k6 JSON and logs, and compare low and increasing
arrival rates. Record offered load, completed requests, latency percentiles, failed
requests, dropped iterations, and both server and client CPU use. k6's scenario-tagged
rates include setup time in their denominator; retain the scheduled duration and
completed counts when assessing achieved load. Record JVM flags, CPU affinity,
filesystem/provider and location, corpus size and depth, and cache assumptions.
A warm loopback workload does not establish deployment capacity or cold-storage latency.

Capture JFR separately from timing runs. Enable monitor-enter events with an explicit
threshold and inspect stacks for both static-file selection critical sections. Native
file-read events do not cover every metadata read, directory operation, or descriptor
open, so they do not isolate filesystem latency. Keep the recording and native reports.
A synchronization change requires evidence of material contention and preservation of
anchored `SecureDirectoryStream`, `NOFOLLOW_LINKS`, revalidation and descriptor cleanup.

The current nested-path selector acquires an independent parent descriptor under the
mount-root monitor, then traverses and reads metadata outside that shared monitor.
Shallow selections retain the borrowed root under the monitor and require no additional
directory acquisition. Shallow work can still delay a nested request's first acquisition;
this reduces nested-path contention without eliminating per-mount contention. Include a
mixed shallow/nested workload when evaluating that interaction, and inspect quiescent
descriptor inventories after successful requests, missing paths and rejections.

## JVM microbenchmarks

`ExtensionRequestBenchmark` includes local and app-wide no-op instrumentation. Its
`unselectedInstrumentation` mode observes POST endpoints but measures GET endpoints in the same
table, exposing completion-bookkeeping overhead on unselected requests. Compare `plain`,
`localInstrumentation`, `unselectedInstrumentation` and `appInstrumentation` with `-prof gc`
for dispatch time and allocated bytes/op; these fixtures do not measure exporter or SDK costs.

The separate `otelInstalled`, `otelSelected`, `otelUnselected` and `otelAppWide` modes use a
recording OpenTelemetry SDK without an exporter. `otelUnselected` enables the sibling POST route
while measuring GET; `otelInstalled` enables no routes. `execution=default,platform,forkJoin`
selects native Jetty platform-thread dispatch or a fixture-owned single-worker platform or
ForkJoin executor for matched handling. Compare all four modes with `plain` under the same execution setting. SDK work is
included in these modes; export/network costs and ForkJoin helping frequency are not.
The manually dispatched [tracing JMH mode](#paired-tracing-jmh)
runs these pairs remotely with native GC measurements and separate JFR recordings.

Targeted JMH experiments remain available locally. Historical recordings are
retired during the migration of end-to-end diagnostics to
[GitHub Actions](#shoostr-diagnostics-in-actions). New JMH results are ignored by
Git; retain or copy them when sharing evidence.

## Extension dispatch

`ExtensionRequestBenchmark` runs complete HTTP/1.1 exchanges through Jetty's `LocalConnector` and
the production Shoostr dispatcher. It includes native parsing, Jetty dispatch, request
and response peers, response serialization and terminal completion; it excludes network sockets
and an HTTP client. Report its microseconds and bytes/op separately from nanosecond router lookup.
The table has GET/POST parameter endpoints in 1 or 1,000 groups. Outcomes cover matches, misses
and 405. Configuration is ordinary routes, installed-but-inactive capability, local before/after
callbacks, or managed authentication. The `plain` fixture uses only pre-extension registration so
the same fixture can run against a pinned baseline core jar. Setup checks expected response status.

```sh
cmdshape ./gradlew :microbenchmarks:installDist
cmdshape microbenchmarks/build/install/microbenchmarks/bin/microbenchmarks ExtensionRequestBenchmark -p configuration=plain,inactive,callbacks,authentication -p groups=1,1000 -p outcome=matched,missed,wrongMethod -prof gc -foe true -rf json -rff benchmark-results/extensions.json
```

Run paired baseline/candidate plain cases on the same JVM with identical settings, and profile JFR
separately. Active-case overhead is expected work and must not be reported as extension-free cost.

## Native scheduling

`SchedulingBenchmark` compares `queued-platform`, a native Jetty `QueuedThreadPool`,
with `queued-virtual`, the same native pool configured with a virtual-thread executor.
These labels identify the current platform default and explicit virtual configuration.

Each invocation submits a batch of 1, 32 or 4096 finite responses and waits for all of
them to complete. Each task constructs a real Shoostr request/response pair against
transport proxies. A blocking readiness queue represents producer waiting; the fixture
excludes sockets, HTTP parsing and the full application dispatcher. Report batch time
separately from complete HTTP latency and retain the selected pool and batch size.

```sh
cmdshape ./gradlew :microbenchmarks:installDist
cmdshape mkdir -p benchmark-results
cmdshape microbenchmarks/build/install/microbenchmarks/bin/microbenchmarks SchedulingBenchmark -p pool=queued-platform,queued-virtual -p batchSize=1,32,4096 -prof gc -foe true -rf json -rff benchmark-results/scheduling.json
```

Earlier scheduling labels describe different executor configurations. Keep their
historical results with their original source revision and parameters; changing the
fixture supplies no new performance evidence.

## Buffered request bodies

`BufferedRequestBenchmark.bodyBytes` measures `Request.bodyBytes()` with a fresh Jetty
`ByteBufferContentSource` behind its `Content.Source.asInputStream` adapter. It
includes the framework request wrapper, body cache and public defensive copy;
the payload and response sink are prepared outside the timed operation. The
declared length is either known or unknown, and body sizes are 0, 1 KiB, 16 KiB
and the default 1 MiB request limit. It does not include HTTP parsing or a client.
The companion candidate-only k6 echo workload measures the complete HTTP path.

```sh
cmdshape ./gradlew :microbenchmarks:test :microbenchmarks:installDist
cmdshape microbenchmarks/build/install/microbenchmarks/bin/microbenchmarks BufferedRequestBenchmark.bodyBytes -prof gc -foe true -rf json -rff benchmark-results/buffered-request.json
```

Compare `gc.alloc.rate.norm` in bytes per operation and average time separately.
Run before and after measurements with the same JVM and JMH settings; a sampled
JFR allocation share is not a substitute for the JMH allocation result. The
historical issue 50 comparison used the same complete-path distinction; its
local recordings are retired during the Actions migration.


`BufferedRequestBenchmark.bodyText` uses the same bounded read and additionally decodes
with an explicit charset. `charset=UTF-8` and `content=ascii` are the defaults.
Supported charset overrides are `UTF-8`, `ISO-8859-1`, and `UTF-16BE`; `content=unicode`
uses repeated `é` characters instead of ASCII `Z`. The payload is encoded during setup,
and `bytes` always denotes encoded bytes, including for UTF-16BE. Each size must contain
whole encoded characters. The byte-read benchmark retains its fixed `Z` payload.

Use fixed headers when comparing decoding, and retain both declared-length modes:

```sh
cmdshape mkdir -p benchmark-results
cmdshape microbenchmarks/build/install/microbenchmarks/bin/microbenchmarks BufferedRequestBenchmark.bodyText -p bytes=0,1024,16384,1048576 -p length=known,unknown -p headers=4 -p charset=UTF-8 -p content=ascii -prof gc -foe true -rf json -rff benchmark-results/body-text-utf8.json
cmdshape microbenchmarks/build/install/microbenchmarks/bin/microbenchmarks BufferedRequestBenchmark.bodyText -p bytes=16384 -p length=known,unknown -p headers=4 -p charset=UTF-8,ISO-8859-1,UTF-16BE -p content=unicode -prof gc -foe true -rf json -rff benchmark-results/body-text-charsets.json
```

These measurements include reading, caching, request construction and decoding; they do
not isolate decoder cost or measure HTTP latency. Compare identical fixtures and JVM
settings before and after a library change. The [fixture tests](../../microbenchmarks/src/test/java/io/github/suppierk/shoostr/BufferedRequestBenchmarkTest.java)
check known byte and text values for the configured shapes.

## Composed pattern workloads

`RoutePatternBenchmark` measures the current production `RadixRoutes`, including the parameter extraction used by `Request.pathParam`. It builds the flattened patterns of nested resource groups once in trial setup. Each group registers eight method/path endpoints in this order:

| Method | Suffix beneath `/api/resources/{group-number}/orders` |
|---|---|
| GET | `/latest` |
| GET | `/{id}` |
| POST | `/{orderId}` |
| GET | `/fixed/details` |
| GET | `/{id}/events` |
| GET | `/{id}/items/{itemId}` |
| GET | `/shadowed` |
| POST | `/latest` |

`groups=1,100,1000` therefore means 8, 800, or 8,000 endpoints. Every measured workload uses this same table, including the overlapping registrations; the table is not tailored to contain only the queried route type. Inputs are a deterministic shuffled corpus of 16,384 requests spread approximately uniformly over groups.

| Workload | Operation |
|---|---|
| `literal` | Match `/latest`, without reading parameters |
| `oneParameter` | Match a varying order identifier and read `id` |
| `twoParameters` | Match an order/item path and read both values |
| `encodedParameter` | Match a percent-encoded UTF-8 value and decode `id` |
| `precedence` | Match literal `/shadowed` despite an earlier parameter route |
| `fallback` | Match `/fixed/events` despite the nonmatching `/fixed/details` branch |
| `notFound` | Fail path lookup, then collect the empty allowed-method set |
| `wrongMethod` | Request DELETE on `/latest`, then collect GET and POST from overlapping matches |
| `catchAllNoRead`, `catchAllRead` | Match a terminal named tail without/with reading `tail` |
| `catchAllFallback` | Fall through a partial literal branch to the catch-all |
| `catchAllWrongMethod`, `catchAllMiss` | Collect methods after a catch-all method mismatch, or miss an empty tail |
| `regexNoRead`, `regexRead` | Match a digit-constrained segment without/with reading `numericId` |
| `regexFallback` | Fall through a nonmatching constraint to a plain parameter route |
| `regexWrongMethod`, `regexMiss` | Collect methods after a constrained method mismatch, or miss an empty segment |

Catch-all and regex workloads add their respective GET/POST routes to each group's base eight endpoints; the ordinary workloads retain exactly the historical eight-route table. A regex workload uses a single ASCII digit class, not arbitrary backtracking patterns. Each operation consumes the selected endpoint and requested values, or the allowed-method set, through JMH. This measures routing and parameter access. It excludes HTTP parsing, wire-token-to-enum lookup, request/response wrappers, handler execution, and formatting the `Allow` header. The failure benchmarks retain the returned sets; escape analysis in a complete HTTP handler may differ. `input=reused` uses existing path strings; `input=fresh` additionally constructs a string inside the timed operation, and its cost/allocations must be reported explicitly.

```sh
cmdshape ./gradlew :microbenchmarks:check :microbenchmarks:installDist
cmdshape microbenchmarks/build/install/microbenchmarks/bin/microbenchmarks RoutePatternBenchmark -prof gc -foe true -rf json -rff benchmark-results/patterns.json
```

Defaults are three forks, five one-second warmups, five one-second measurements, one thread, and a fixed 512 MiB G1 heap. The expanded 54-case default matrix takes roughly half an hour; select a recorded subset for a targeted comparison. The 108 JUnit fixture cases cover all workloads, table sizes, and both input modes before measurement.

Capture JFR **separately** from the timing run. Use one fork per profile directory: JMH 1.37 writes a fixed `profile.jfr` filename per parameter combination, so multiple forks would overwrite that recording. For example:

```sh
cmdshape microbenchmarks/build/install/microbenchmarks/bin/microbenchmarks RoutePatternBenchmark -p groups=1000 -p workload=oneParameter,twoParameters,encodedParameter,notFound,wrongMethod -f 1 -wi 3 -i 5 -w 1s -r 1s -prof jfr:dir=benchmark-results/pattern-jfr -foe true
cmdshape jfr view --width 160 hot-methods path/to/profile.jfr
cmdshape jfr view --width 160 allocation-by-site path/to/profile.jfr
```

The JMH JFR profiler records measurement iterations using the JDK `profile` configuration and enables non-safepoint samples. Use native `jfr view`/`summary` output and keep the recordings. JFR is sampled evidence of hotspots, not an exact count of allocations or a substitute for the unprofiled JMH measurements.

## Literal dictionary baseline

This module uses JMH 1.37 on Java 21 to compare the historical literal-route `HashMap<path, HashMap<HttpMethods, Handler>>` baseline with the package-private `RadixRoutes` implementation in `core`. It does not start an HTTP server. JMH and JOL are benchmark dependencies only; JMH annotation processing is enabled only for this module. End-to-end fixtures are maintained under `.github/benchmarks`.

The current router is a compressed character-edge radix tree built from sorted, precompiled registrations. It supports named segments and literal-first precedence; these existing fixtures still exercise literal paths only. It has sorted child arrays, direct single-child selection with binary search for larger arrays, immutable endpoint maps, and offset-based exact lookup. Allowed-method sets are allocated only after matching a registered endpoint; path misses return the JDK empty set. Construction finishes before any measured read. Neither implementation is mutated during lookup; no concurrent map, update protocol, locks, or runtime resizing are needed. The benchmark uses the same package as `core` to access the internal implementation without adding a public routing API.

## Run

From the repository root:

```sh
cmdshape ./gradlew build :microbenchmarks:installDist
cmdshape mkdir -p benchmark-results/radix-local
cmdshape microbenchmarks/build/install/microbenchmarks/bin/microbenchmarks RouteLookupBenchmark -prof gc -foe true -rf json -rff benchmark-results/radix-local/lookup.json
```

Use a new results directory for each comparison. On Windows, use the generated `.bat` launcher. The launcher uses `JAVA_HOME` or `java` on `PATH`; ensure it selects JDK 21. Alternatively, `cmdshape ./gradlew :microbenchmarks:run --args='RouteLookupBenchmark -prof gc'` uses the configured Java toolchain.

Defaults are three JVM forks, five one-second warmup iterations and five one-second measurements per case, one benchmark thread, a 512 MiB fixed heap, and G1 GC. The default lookup run has 16 cases and takes approximately nine minutes including JVM startup. Run on an otherwise idle machine; retain native JMH JSON and the complete console log. `-foe true` stops on a benchmark error.

The four benchmark methods measure map/radix lookup with either reused or freshly constructed strings. Override parameters using JMH's own CLI:

| Parameter | Default | Other supported values |
|---|---|---|
| `routeCount` | `5,50,500,5000` | Positive counts supported by the fixture |
| `shape` | `shared` | `divergent`, `prefix` |
| `outcome` | `hit` | `earlyMiss`, `lateMiss`, `wrongMethod`, `mixed` |
| `distribution` | `uniform` | `hot` |

For example, measure misses and method failures separately:

```sh
cmdshape microbenchmarks/build/install/microbenchmarks/bin/microbenchmarks RouteLookupBenchmark -p routeCount=5,5000 -p outcome=earlyMiss,lateMiss,wrongMethod -prof gc -foe true -rf json -rff benchmark-results/radix-local/misses.json
```

Expand shapes or hot-route traffic with `-p shape=shared,divergent,prefix` and `-p distribution=uniform,hot`. Each parameter multiplies the run count; do not confuse a shortened smoke run with the default measurements. A complete cross-product is several hours, so select and record a workload subset explicitly.

## What is measured

- The fixture creates two distinct handler identities (GET and POST) per path. Each timed invocation performs one exact path lookup, method lookup when a path exists, and selection of a handler or distinct 404/405 sentinel. JMH consumes the returned object. Handlers, response rendering, `Allow` header construction, URI parsing, and networking are excluded.
- The map retains the earlier two-level layout, now keyed by `HttpMethods`. Radix terminal maps hold immutable endpoint metadata keyed by the same enum. Consequently this compares the two complete index layouts, not just their path traversal algorithms.
- All tables are built once in trial setup. Radix misses also collect allowed methods to distinguish 404 from 405; the map can make that distinction directly. The deterministic, shuffled corpus contains 16,384 queries. `uniform` distributes queries approximately evenly across paths; `hot` directs approximately 90% to four paths. The seed and query order are identical across implementations.
- `shared` uses account/order prefixes. `divergent` branches near the beginning of the path. `prefix` includes registered terminal paths that also prefix longer registered paths. Paths are encoded, case-sensitive strings; no normalization or parameter matching occurs.
- `earlyMiss` changes the character after the leading slash; `lateMiss` appends an unmatched character; `wrongMethod` requests DELETE on a known path. `mixed` combines 70% hits and 10% each of those three failures. Mixed averages do not replace separate outcome measurements.
- Reused query strings are separate objects from registered keys and have their hashes precomputed. Fresh queries use `new String(char[])` inside the measured operation, with no precomputed hash. **Fresh measurements include string construction and its allocation**, not just lookup. This deliberately avoids per-invocation setup overhead and the cached-hash behavior of `new String(existingString)`.
- Report JMH average time and its error interval, plus `gc.alloc.rate.norm` in bytes/op. A value close to zero can include profiler noise. These are operation averages, not HTTP latency percentiles or evidence of a framework-wide speedup.

JUnit fixture tests check all 120 parameter combinations against the map, including every query in each corpus and both string modes. Core tests cover exact terminal boundaries, empty/singleton tables, Unicode and encoding, distinct handlers, missing methods, immutable snapshots, and shuffled registrations. Normal `build` runs these tests; it does not run timed benchmarks.

## Construction and retained memory

Measure the additional radix freeze step independently:

```sh
cmdshape microbenchmarks/build/install/microbenchmarks/bin/microbenchmarks RouteConstructionBenchmark -prof gc -foe true -rf json -rff benchmark-results/radix-local/construction.json
cmdshape ./gradlew :microbenchmarks:footprint
```

Construction starts from precompiled endpoint registrations and includes sorting, edge creation, endpoint tables, and node arrays. Pattern validation and parameter metadata compilation happen before the freeze measurement. The current map needs no equivalent freeze step; this is added startup work, not a comparison against artificially rebuilding the map on every lookup.

The footprint command uses JOL 0.17 to print reachable object graphs for each index independently, including keys, method tables, and synthetic handler objects. It excludes the query corpus and unrelated application objects. This is retained graph size, not peak construction memory. Preserve JOL's VM details and warnings with the output; without instrumentation, object sizes are estimates based on the detected layout. Never interpret GC bytes/op as retained table size.

## Scope after route composition

`Shoostr` now uses the pattern-capable radix matcher to implement nested path groups and single-segment parameters with literal-first precedence. The map is retained only as a literal dictionary benchmark baseline. Earlier timings used the previous literal-only implementation and string method keys; they do not measure this routing API. No timed runs were performed as part of changing precedence. A meaningful next comparison needs equivalent parameter syntax, capture access, literal-first precedence, and failure behavior in each router.

Methodology references: [JMH](https://github.com/openjdk/jmh), [constant-folding pitfalls](https://github.com/openjdk/jmh/blob/1.37/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_10_ConstantFold.java), [per-invocation setup pitfalls](https://github.com/openjdk/jmh/blob/1.37/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_38_PerInvokeSetup.java), [GC profiler interpretation](https://github.com/openjdk/jmh/blob/1.37/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_35_Profilers.java), [JOL](https://github.com/openjdk/jol).
