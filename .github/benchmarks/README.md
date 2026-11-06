# Shoostr diagnostics in Actions

Run **Shoostr benchmarks** manually from the Actions tab. Select the branch, mode
and artifact retention (1–14 days, default 7). Campaigns use Java 21 and Jetty's
default platform-thread pool. Pushes and pull requests run fixture tests, not timed
campaigns.

## HTTP and finite SSE

`steady` runs eight HTTP workloads: plaintext, pre-encoded JSON, 1 KiB echo,
16 KiB buffered stream, literal routing, parameter routing, 404 and 405. It also runs
`sse-burst` and `sse-paced` as **finite SSE whole-response scheduled latency** measurements.
Each workload gets a runner and three fresh JVM trials, each with a 30-second
warmup and 180-second measurement. Up to ten workload jobs run concurrently.

The stream fixture writes sixteen 1 KiB pieces through Shoostr’s default 8 KiB
buffer, without explicit per-piece flushes. The configured buffer fills and flushes
automatically; handler completion finishes the response. Application callers can
change this boundary with `Options.withStreamBufferBytes(...)`, or call `flush()`
when earlier transport submission is required. This workload replaces the historical
fixture that explicitly flushed every 1 KiB write; its results need a new baseline.

`overload` selects the eight ordinary HTTP workloads. Each measurement uses the
continuous linear ramp `-R1000:25000` over 180 seconds. Warmup stays at 1,000 requests
per second. Steady HTTP load is 1,000 requests per second; finite SSE is 50 responses
per second. [workloads.json](workloads.json) owns connection counts and ramp eligibility.

The [Lua validator](http.lua) checks exact status, body and content type, required
framing/metadata, and the GET/POST `Allow` union for 405. Routing paths vary across
1,000 groups. Startup checks validate all ten fixtures and platform-thread execution.
Finite SSE must contain all sixteen ordered events and terminate. Its latency includes
the complete HTTP response and the paced fixture's final sleep; it does not measure
individual event delivery or client callbacks. WebSocket and callback-delayed SSE load
jobs are outside this suite. Java protocol tests remain correctness coverage.

The workflow downloads pinned zrk 3.0.0 and verifies its archive SHA-256 before
extracting it. It records the executable and fixture JAR checksums separately.
[Executable tests](zrk.test.mjs) exercise validators, bad archives, native reports,
ramp configuration and interrupted output. Native tool or correctness failures fail
the workflow. A successful collection is not a capacity or latency acceptance gate.

### Resources and evidence

Set the JVM's logical CPU count (default 1) and total memory cap in MiB (default
1024). The heap stays 256 MiB with G1. The client uses the remaining permitted CPUs;
CPU selection must leave it at least one. CPU affinity does not reserve exclusive cores.
Memory must exceed the heap and fit host RAM. Invalid settings fail rather than clamp.

A fresh cgroup v2 group limits total JVM memory, including native and cgroup-accounted
cache/kernel memory, with swap disabled. zrk, profiling tools and artifact processing
stay outside it. Passwordless sudo and an enforceable memory controller are required.
The suite retains effective limits, peak memory and OOM events and removes the group
after shutdown. An OOM kill fails the run. Heap size alone does not cap process RSS.

Each artifact contains three trial directories. Open the native outputs directly:

- `measured-summary.json`: configured and actual duration, completed responses,
  offered/achieved rates, errors, schedule lag, latency percentiles and histogram.
- `measured-timeseries.ndjson`: per-interval rates, errors and latency histograms.
- `measured-latency.hgrm`: native percentile distribution.
- Logs, warmup/parity reports, CPU/RSS monitoring and `/usr/bin/time` resource reports.
- Complete `server.jfr`, allocation/hot-method/GC views, JVM flags, limits, affinity,
  image/CPU/version/revision details, and hashes.

JSON latency fields are microseconds; HDR text uses milliseconds. Fixed-rate latency
starts at the scheduled send, so it includes scheduling backlog. Initial connection
setup precedes that anchor. Lua validation runs after latency recording and adds
client CPU cost. Response hooks retain entire bodies; check client RSS and CPU before
attributing saturation to the server.

In a ramp, top-level `achieved_rate` and `rate_ratio` describe the final native window;
`end_window_s` identifies it. Latency percentiles cover the whole run. Use the interval
series to compare offered and achieved load over time. Keep counts, configured duration,
actual duration, errors and schedule lag separate. The tool can stop with unsent or
in-flight work; it does not provide a complete count of omitted scheduled requests.
Do not infer zero omissions from zero transport errors.

The expected 404/405 responses remain native status errors even when Lua validates
them successfully. The checker verifies the expected status class and response-hook
count separately from connect/read/write/timeout/deadline errors. It does not rewrite
the native error rate. Trials remain separate; do not pool their percentiles.

JFR uses profile settings with execution samples every 10 ms and starts after warmup.
It spans client startup, measurement and output finalization, then stops before JVM
shutdown. Profiling, validation and report generation add overhead. Thirty seconds
of warmup does not guarantee stable JIT compilation. Available artifacts upload even
on failure; interrupted/partial output is not a successful run. Hosted hardware and
images vary, and a changed tool, resource budget or timing boundary needs a new
baseline. The suite does not generate combined statistics or campaign tables.

## Finite slow-client diagnostic

Select `slow-client` with one JVM CPU and 1024 MiB total memory. One loopback
`SlowClientServer` JVM stays alive across eight cycles: slow bodies and blocked
handlers, each at 32 and 128 clients, twice. This manual investigation checks bounded
resource recovery. It is not deployment-capacity certification or a PR merge gate.

`slowhttptest` 1.9.0 maintains incomplete 65,536-byte POST bodies for 60 seconds.
Its CSV must show the full population, no pending/closed connections and service
availability from seconds 5 through 59, plus a sample at or after second 60.
Blocked requests use zrk closed-loop connections for 60 seconds, reusing connections
between calls. Each handler waits at most five seconds on a cooperative gate.
Native configuration and all launched connections must match the selected population;
completed response status/body checks and all native error counts must pass.

The control route polls server state nominally once per second, using monotonic
phase timestamps. During seconds 5–55, observation gaps and gaps between full
blocked-population observations must not exceed 3,500 ms. Brief turnover dips are
allowed, but sampled counters must remain valid and balanced. This is **sampled
blocked-population exposure**; it cannot prove continuous occupancy of each client
between observations. The diagnostic does not replace individual event or WebSocket session measurements.

A separate zrk process offers one health request per second for 100 seconds, with
one connection, fresh connections per response and a one-second wire timeout.
Lua validates status 200 and the complete `healthy` body. Maximum reported scheduled
latency must stay within one second. This timing excludes initial connection setup
and does not establish complete-call latency. The accepted native count is 99, 100 or 101,
allowing one response on either side of the run boundary. A final response can finish
after the configured deadline and before worker cancellation. All native errors must be zero and every
completed response must pass validation.

Exposure starts after a positive health interval. The health process must remain
alive through exposure and the 30-second recovery observation. Its complete native
interval series must cover both phases, with regular timestamps and positive
completion evidence. Launch/readiness bounds align native elapsed intervals with
monotonic phase times; interval endpoints are not individual response timestamps.
The final summary may include one response beyond the last interval snapshot; the
checker records this difference explicitly.

Recovery requires zero workload handlers/exchanges, balanced admitted/terminal
counts and the selected peak concurrency. Within 30 seconds, only the state-control
connection and at most one owned health connection may remain. After the health
process exits, exactly one control connection may remain. zrk can reconnect before
waiting for its next scheduled probe, which accounts for that temporary connection.
An observed health/OOM failure stops the campaign. Failed or partial evidence remains
failed; the suite does not reduce the selected load to obtain a passing result.

The artifact retains native JSON/NDJSON/HDR and logs, slowhttptest CSV/HTML, actual
arguments, versions/hashes, process CPU/RSS and FD samples, baseline/recovered heap
and thread dumps, cgroup limits/OOM evidence and bounded JFR recordings. State and
resource sampling runs during load; heavy heap/thread snapshots run before and after
it. Workload processes and profiler tools stay outside the server memory cgroup.
Owned process groups are stopped and the empty cgroup is removed after final OOM
checks. Results are never overwritten.

[The checker](slow-client-check.mjs) and [its tests](slow-client.test.mjs) validate
native evidence, sampled exposure, recovery and OOM invariants. Passing is not a leak
verdict: inspect FD, heap, RSS/cgroup and thread trends across repeats. Distinguish
retained request resources from native pools, JIT and cache warmup. Classify failures
before proposing changes to serving behavior; this fixture installs no quota,
admission or deadline policy.

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

## Local static-file selection diagnostic

The [HTTP fixture](src/main/java/io/github/suppierk/shoostr/bench/ServerMain.java) accepts
an optional static-root directory after the port and
route-group count. For example, `benchmarks 18080 1 /path/to/corpus` anchors one mount
at `/static`. Without that argument, the fixture retains its existing endpoints.
The application owns the anchored mount and closes it at shutdown.

Prepare 64 files named `file-0.bin` through `file-63.bin` in both the corpus root and
`nested/deeper/`. Use an existing directory on a filesystem provider that supports
secure directory operations. Each file must contain exactly 1,024 ASCII `x` bytes. Corpus creation
is outside the workload; the fixture does not generate files. Pass the positive file count as the third script argument. The script cycles through
the corpus and validates response bodies, content type and static metadata. Run long
enough to visit every file before interpreting a timing result.

Build the fixture distribution and start its generated launcher from the repository root:

```sh
cmdshape ./gradlew :benchmarks:installDist
cmdshape .github/benchmarks/build/install/benchmarks/bin/benchmarks 18080 1 /path/to/corpus
```

In a second terminal, run the [Lua workload](http.lua) with zrk 3.0.0:

```sh
cmdshape zrk -t2 -c128 -d10s -R100 --script .github/benchmarks/http.lua --format json -o shallow.json --hdr shallow.hgrm --timeseries shallow.ndjson --timeseries-histogram http://127.0.0.1:18080 static-shallow 1 64
```

The arguments after the URL select workload, route-group count and file count.
Use `static-nested` for the nested path family. Each response must contain exactly
1,024 ASCII `x` bytes, status 200, the octet-stream content type and expected cache,
range and modification metadata. The [fixture test](src/test/java/io/github/suppierk/shoostr/bench/ServerMainTest.java)
checks shallow and nested selection through real HTTP. These local diagnostics are
outside the Actions matrix.

Warm the corpus and compare fixed rates before trying a ramp. Retain the native
summary, histogram, intervals and logs, together with both processes' CPU/RSS,
JVM flags, affinity, filesystem/provider, corpus size, path depth and cache assumptions.
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
The companion candidate-only zrk echo workload measures the complete HTTP path.

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
| ------ | ----------------------------------------------------- |
| GET    | `/latest`                                             |
| GET    | `/{id}`                                               |
| POST   | `/{orderId}`                                          |
| GET    | `/fixed/details`                                      |
| GET    | `/{id}/events`                                        |
| GET    | `/{id}/items/{itemId}`                                |
| GET    | `/shadowed`                                           |
| POST   | `/latest`                                             |

`groups=1,100,1000` therefore means 8, 800, or 8,000 endpoints. Every measured workload uses this same table, including the overlapping registrations; the table is not tailored to contain only the queried route type. Inputs are a deterministic shuffled corpus of 16,384 requests spread approximately uniformly over groups.

| Workload                              | Operation                                                                       |
| ------------------------------------- | ------------------------------------------------------------------------------- |
| `literal`                             | Match `/latest`, without reading parameters                                     |
| `oneParameter`                        | Match a varying order identifier and read `id`                                  |
| `twoParameters`                       | Match an order/item path and read both values                                   |
| `encodedParameter`                    | Match a percent-encoded UTF-8 value and decode `id`                             |
| `precedence`                          | Match literal `/shadowed` despite an earlier parameter route                    |
| `fallback`                            | Match `/fixed/events` despite the nonmatching `/fixed/details` branch           |
| `notFound`                            | Fail path lookup, then collect the empty allowed-method set                     |
| `wrongMethod`                         | Request DELETE on `/latest`, then collect GET and POST from overlapping matches |
| `catchAllNoRead`, `catchAllRead`      | Match a terminal named tail without/with reading `tail`                         |
| `catchAllFallback`                    | Fall through a partial literal branch to the catch-all                          |
| `catchAllWrongMethod`, `catchAllMiss` | Collect methods after a catch-all method mismatch, or miss an empty tail        |
| `regexNoRead`, `regexRead`            | Match a digit-constrained segment without/with reading `numericId`              |
| `regexFallback`                       | Fall through a nonmatching constraint to a plain parameter route                |
| `regexWrongMethod`, `regexMiss`       | Collect methods after a constrained method mismatch, or miss an empty segment   |

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

| Parameter      | Default         | Other supported values                          |
| -------------- | --------------- | ----------------------------------------------- |
| `routeCount`   | `5,50,500,5000` | Positive counts supported by the fixture        |
| `shape`        | `shared`        | `divergent`, `prefix`                           |
| `outcome`      | `hit`           | `earlyMiss`, `lateMiss`, `wrongMethod`, `mixed` |
| `distribution` | `uniform`       | `hot`                                           |

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

References: [manual workflow execution](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow),
[zrk scripting and CLI](https://github.com/zoxy-io/zrk/blob/v3.0.0/README.md),
[zrk native output](https://github.com/zoxy-io/zrk/blob/v3.0.0/docs/output.md),
[JDK 21 diagnostic commands](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jcmd.html),
[JDK 21 JFR CLI](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jfr.html).
