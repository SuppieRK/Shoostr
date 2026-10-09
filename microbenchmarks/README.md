# JVM microbenchmarks

Targeted JMH experiments remain available locally. Historical recordings are
retired during the migration of end-to-end diagnostics to
[GitHub Actions](../.github/benchmarks/README.md). New JMH results are ignored by
Git; retain or copy them when sharing evidence.

## Endpoint execution

`EndpointExecutionBenchmark` uses identical fixtures from `:benchmarks` through the production
dispatcher and Jetty `LocalConnector`. Tiny bytes, a small parameter route, and deterministic
4 MiB SHA-256 work each consume complete response output. The measurements include native local
HTTP parsing, scheduling, matched lifecycle, response serialization and request cleanup, but exclude
sockets and k6. They are microseconds/exchange, not nanoseconds of isolated router lookup.

Models A/B/C/D reuse the internal campaign configurations; `inactive` is A with an installed but unused
worker pool. LocalConnector does not reproduce network adaptive dispatch: native recordings show
A on virtual threads, B/C on transport platform threads, and D on selected platform workers.
Inactive uses A's virtual fixture wiring; it has no separate retained profile.
B's configured virtual consumer is not used on this local path. This cannot establish a B/C
virtual-versus-platform consumer difference or the HTTP virtual-to-platform handoff cost.
Inactive must not be described as selected-endpoint handoff cost. Three forks, native
`-prof gc` bytes/op and timing are measured together; JFR runs are separate with unique fork files.
The Actions threading campaign additionally rebuilds the pinned original core with the same JDK
to measure default-path changes independently of the threading-model choice.

```sh
cmdshape ./gradlew :microbenchmarks:installDist
cmdshape microbenchmarks/build/install/microbenchmarks/bin/microbenchmarks EndpointExecutionBenchmark -f 3 -wi 5 -i 5 -w 2s -r 2s -prof gc -foe true -rf json -rff benchmark-results/endpoint-execution.json
```

These fixtures expose no supported public execution configuration. Blocking I/O and mixed traffic
are measured by k6 against complete HTTP responses, not substituted with task-submission timing.

### Contained result checkpoint

[Campaign 37884898672](https://github.com/SuppieRK/Shoostr/actions/runs/37884898672),
source `a28e9a8`, completed all 15 candidate cases, three original-core controls and 12
separate JFR forks. Native JSON retains three forks with five measured iterations each.
Temurin 25.0.4.1+1, Jetty 12.1.14, G1, 256 MiB fixed heap and two JVM-visible logical CPUs
on one AMD EPYC 9V45 hosted runner; affinity is not exclusive CPU reservation.

| Model | Tiny µs/exchange | Tiny bytes/exchange | Parameter route µs/exchange | CPU µs/exchange |
|---|---:|---:|---:|---:|
| A | 20.670 ± 0.440 | 15,737 | 21.167 ± 0.893 | 2,078 ± 40 |
| B | 33.402 ± 0.471 | 15,316 | 32.982 ± 0.353 | 2,121 ± 45 |
| C | 33.131 ± 0.298 | 15,281 | 33.296 ± 0.305 | 2,086 ± 41 |
| D | 47.302 ± 0.495 | 16,995 | 46.849 ± 0.872 | 2,117 ± 38 |
| Inactive | 21.603 ± 0.395 | 15,756 | 21.902 ± 0.546 | 2,097 ± 41 |
| Original core A | 21.455 ± 0.268 | 15,767 | 22.007 ± 0.615 | 2,033 ± 64 |

Errors are native JMH 99.9% confidence intervals, not HTTP percentiles. On the local path B adds about
12.7 µs to the contained tiny exchange versus A; D adds another 13.9 µs versus B and
about 1.7 KiB/exchange. D's local transfer is platform-to-platform, not the virtual-to-platform
transfer tested by live HTTP. CPU-work intervals overlap: no demonstrated CPU speedup.
Inactive is about 0.9 µs above A, but near the original-core control; neither this
sequential comparison nor allocation profiler noise establishes exactly zero inactive cost.

Profiles include startup and warmup, unlike HTTP profiles. Their sampled allocation
shares include LocalConnector buffer/response accumulation, not just Shoostr objects.
CPU-time samples have substantial bias/loss (A CPU: 1,896 biased of 2,014 successful;
D tiny: 1,777 lost versus 567 successful). Preserve these native statistics; do not
claim precise CPU percentages or compare profile-run timing with unprofiled timing.
GC bytes/op is allocation, not retained heap. These contained results alone do not
select a production transport, endpoint option or default; complete HTTP evidence is required.

## Extension dispatch

`ExtensionRequestBenchmark` runs complete HTTP/1.1 exchanges through Jetty's `LocalConnector` and
the production Shoostr dispatcher. It includes native parsing, virtual-thread dispatch, request
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

## Buffered request bodies

`BufferedRequestBenchmark` measures `Request.bodyBytes()` with a fresh Jetty
`ByteBufferContentSource` behind its `Content.Source.asInputStream` adapter. It
includes the framework request wrapper, body cache and public defensive copy;
the payload and response sink are prepared outside the timed operation. The
declared length is either known or unknown, and body sizes are 0, 1 KiB, 16 KiB
and the default 1 MiB request limit. It does not include HTTP parsing or a client.
The companion candidate-only k6 echo workload measures the complete HTTP path.

```sh
cmdshape ./gradlew :microbenchmarks:test :microbenchmarks:installDist
cmdshape microbenchmarks/build/install/microbenchmarks/bin/microbenchmarks BufferedRequestBenchmark -prof gc -foe true -rf json -rff benchmark-results/buffered-request.json
```

Compare `gc.alloc.rate.norm` in bytes per operation and average time separately.
Run before and after measurements with the same JVM and JMH settings; a sampled
JFR allocation share is not a substitute for the JMH allocation result. The
historical issue 50 comparison used the same complete-path distinction; its
local recordings are retired during the Actions migration.

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

This module uses JMH 1.37 on Java 25 to compare the historical literal-route `HashMap<path, HashMap<HttpMethods, Handler>>` baseline with the package-private `RadixRoutes` implementation in `core`. It does not start an HTTP server. JMH and JOL are benchmark dependencies only; JMH annotation processing is enabled only for this module. End-to-end fixtures are maintained under `.github/benchmarks`.

The current router is a compressed character-edge radix tree built from sorted, precompiled registrations. It supports named segments and literal-first precedence; these existing fixtures still exercise literal paths only. It has sorted child arrays, direct single-child selection with binary search for larger arrays, immutable endpoint maps, and offset-based exact lookup. Allowed-method sets are allocated only after matching a registered endpoint; path misses return the JDK empty set. Construction finishes before any measured read. Neither implementation is mutated during lookup; no concurrent map, update protocol, locks, or runtime resizing are needed. The benchmark uses the same package as `core` to access the internal implementation without adding a public routing API.

## Run

From the repository root:

```sh
cmdshape ./gradlew build :microbenchmarks:installDist
cmdshape mkdir -p benchmark-results/radix-local
cmdshape microbenchmarks/build/install/microbenchmarks/bin/microbenchmarks RouteLookupBenchmark -prof gc -foe true -rf json -rff benchmark-results/radix-local/lookup.json
```

Use a new results directory for each comparison. On Windows, use the generated `.bat` launcher. The launcher uses `JAVA_HOME` or `java` on `PATH`; ensure it selects JDK 25. Alternatively, `cmdshape ./gradlew :microbenchmarks:run --args='RouteLookupBenchmark -prof gc'` uses the configured Java toolchain.

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
