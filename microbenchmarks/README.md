# JVM microbenchmarks

Local results: [latest lazy-allocation and child-search experiments with JFR](../benchmark-results/radix-fastpaths-20260918-01/REPORT.md), [constants-cleanup validation](../benchmark-results/radix-constants-20260918-01/REPORT.md), and [earlier routing optimizations with JFR and retained memory](../benchmark-results/radix-patterns-20260918-01/REPORT.md). The results directories are ignored by Git; retain or copy them when sharing evidence.

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
issue 50 candidate-only comparison is saved locally under
`benchmark-results/issue50-20260925/`.

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

Each operation consumes the selected endpoint and requested values, or the allowed-method set, through JMH. This measures routing and parameter access. It excludes HTTP parsing, wire-token-to-enum lookup, request/response wrappers, handler execution, and formatting the `Allow` header. The failure benchmarks retain the returned sets; escape analysis in a complete HTTP handler may differ. `input=reused` uses existing path strings; `input=fresh` additionally constructs a string inside the timed operation, and its cost/allocations must be reported explicitly.

```sh
cmdshape ./gradlew :microbenchmarks:check :microbenchmarks:installDist
cmdshape microbenchmarks/build/install/microbenchmarks/bin/microbenchmarks RoutePatternBenchmark -prof gc -foe true -rf json -rff benchmark-results/patterns.json
```

Defaults are three forks, five one-second warmups, five one-second measurements, one thread, and a fixed 512 MiB G1 heap. The 24-case default matrix takes about 12–13 minutes. The 48 JUnit fixture cases cover all workloads, table sizes, and both input modes before measurement.

Capture JFR **separately** from the timing run. Use one fork per profile directory: JMH 1.37 writes a fixed `profile.jfr` filename per parameter combination, so multiple forks would overwrite that recording. For example:

```sh
cmdshape microbenchmarks/build/install/microbenchmarks/bin/microbenchmarks RoutePatternBenchmark -p groups=1000 -p workload=oneParameter,twoParameters,encodedParameter,notFound,wrongMethod -f 1 -wi 3 -i 5 -w 1s -r 1s -prof jfr:dir=benchmark-results/pattern-jfr -foe true
cmdshape jfr view --width 160 hot-methods path/to/profile.jfr
cmdshape jfr view --width 160 allocation-by-site path/to/profile.jfr
```

The JMH JFR profiler records measurement iterations using the JDK `profile` configuration and enables non-safepoint samples. Use native `jfr view`/`summary` output and keep the recordings. JFR is sampled evidence of hotspots, not an exact count of allocations or a substitute for the unprofiled JMH measurements.

## Literal dictionary baseline

This module uses JMH 1.37 on Java 25 to compare the historical literal-route `HashMap<path, HashMap<HttpMethods, Handler>>` baseline with the package-private `RadixRoutes` implementation in `core`. It does not start an HTTP server. JMH and JOL are benchmark dependencies only; JMH annotation processing is enabled only for this module. Existing HTTP fixtures remain in `benchmarks`.

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
