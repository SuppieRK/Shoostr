# HTTP measurements with k6

Local evidence: [RadixRoutes before/after validation and fresh Jooby comparison](../benchmark-results/radix-http-20260918-01/REPORT.md) and [three-minute HTTP, SSE, and WebSocket comparison](../benchmark-results/issue43-20260924/REPORT.md). Results directories are ignored by Git; preserve them when sharing measurements.

Use Java 25, the repository's Gradle wrapper, and [k6](https://grafana.com/docs/k6/latest/set-up/install-k6/). This setup was checked with k6 2.2.0. There is no project-specific load generator or statistics/reporting engine. `http.js` defines payloads, correctness checks, and native k6 options.

## Build and start a server

From the repository root:

```sh
cmdshape ./gradlew clean spotlessApply build :benchmarks:installDist
cmdshape benchmarks/build/install/benchmarks/bin/benchmarks candidate 8080
```

For Jooby or Javalin, stop that process and use `jooby` or `javalin` instead of `candidate`. The generated launcher uses a 256 MiB fixed heap and G1 for all three servers. On Windows use `gradlew.bat` and `benchmarks.bat`. Benchmark-only BOMs now align Jetty core and EE10 artifacts to 12.1.13 for all three frameworks. Historical saved candidate/Jooby runs used 12.1.11 and the later Javalin runs used 12.1.12; they must not be mixed with a fresh campaign. All use HTTP/1.1 and virtual-thread handlers. Jooby 4.5.4 uses explicit WORKER execution. The k6 setup phase verifies virtual-thread execution and response status, content type, and bytes for every fixture before load begins. This compares framework behavior on the same resolved Jetty patch; it does not establish which engine or configuration is fastest.

All three servers send `Date` and omit `Server`. Setup also verifies the complete response-header name set exposed by k6, a parseable date, and the expected content length for finite responses. k6 removes `Transfer-Encoding` after decoding streamed bodies, so its header map cannot verify wire-level chunked framing. The Jooby fixture explicitly adopts Jetty's selector-count, URI-compliance, request-header-size and output-buffer/aggregation defaults to match core, and uses the same request-body limit. Body bytes and header semantics are preserved by these checks; the live date naturally changes between requests.

Older result directories preceding this configuration change used different date-header and engine settings. Retain them as historical evidence, but do not combine them with corrected runs to attribute a performance change to a single optimization.

## Warm up and measure

Run from another terminal. Install k6 on PATH, or replace `k6` with `.scratch/tools/k6` when using the locally downloaded binary in this workspace.

```sh
cmdshape mkdir -p benchmark-results/candidate-plaintext-1
cmdshape k6 run --no-usage-report -e WORKLOAD=plaintext -e RATE=1000 -e DURATION=15s benchmarks/http.js
K6_WEB_DASHBOARD=true K6_WEB_DASHBOARD_PORT=-1 \
K6_WEB_DASHBOARD_PERIOD=1s \
K6_WEB_DASHBOARD_EXPORT=benchmark-results/candidate-plaintext-1/report.html \
cmdshape k6 run --no-usage-report \
  -e WORKLOAD=plaintext -e RATE=1000 -e VUS=64 -e DURATION=180s \
  --out json=benchmark-results/candidate-plaintext-1/metrics.json.gz \
  benchmarks/http.js
```

The [native web dashboard](https://grafana.com/docs/k6/latest/results-output/web-dashboard/) exports a standalone HTML report; [native JSON output](https://grafana.com/docs/k6/latest/results-output/real-time/json/) retains time series. The console reports latency percentiles, request failures, and dropped iterations. Warmup is a separate invocation and is not included in the measurement report. Setup requests are present in aggregate metrics; filter by `scenario=requests` when analyzing the exported time series.

Available workloads:

| `WORKLOAD` | Operation |
|---|---|
| `plaintext` | GET a 13-byte response |
| `json-bytes` | GET a pre-encoded JSON response; no serialization |
| `echo` | POST and echo 1 KiB |
| `stream` | GET 16 × 1 KiB chunks, flushing each |

Pass an optional third server argument to register composed route groups before startup for any of the three frameworks. Each group adds eight method/path endpoints with the same registration order and overlapping patterns as `RoutePatternBenchmark`; the original fixture routes remain available. This extends the HTTP fixture only; Jooby and Javalin are not part of the JMH suite.

```sh
cmdshape benchmarks/build/install/benchmarks/bin/benchmarks candidate 8080 1000
cmdshape k6 run --no-usage-report -e ROUTE_GROUPS=1000 -e WORKLOAD=not-found -e RATE=2000 -e VUS=256 -e DURATION=180s benchmarks/http.js
```

With `ROUTE_GROUPS` set to the same positive count, these additional workloads become available:

| `WORKLOAD` | Operation |
|---|---|
| `route-literal` | GET `/latest` beneath a resource group; return 13 bytes |
| `route-parameter` | GET `/order-42`; extract and return the path parameter |
| `not-found` | GET `/missing/extra`; verify 404 and `Not found` |
| `wrong-method` | DELETE `/latest`; verify 405, body, and `Allow: GET, POST` |

Groups are selected using `(iterationInTest * 977) % ROUTE_GROUPS`, cycling through all 1,000 groups in the documented configuration. The fixtures use native k6 [expected status callbacks](https://grafana.com/docs/k6/latest/javascript-api/k6-http/expected-statuses/) so expected 404/405 responses pass while unexpected statuses and transport failures remain errors. Body and header checks stay enabled, accepting equivalent charset whitespace and Allow method order. Setup verifies all enabled fixtures, including the original four payload workloads. `http_req_duration{scenario:requests}` and `http_reqs{scenario:requests}` in native summaries exclude setup traffic.

For candidate-only catch-all and constrained-segment HTTP measurements, pass `CANDIDATE_ROUTING=1` to k6 and run the candidate server. This adds three setup-checked workloads without changing the Jooby/Javalin parity fixture: `route-catch-all` GETs `/candidate/catch/archive/receipt`, `route-regex` GETs `/candidate/regex/42`, and `route-regex-fallback` GETs `/candidate/regex/alpha` through a plain-parameter fallback. Each returns its selected capture or fallback label. Do not use these workloads to claim competitor parity.

```sh
cmdshape ./gradlew :benchmarks:installDist
JAVA_OPTS='-XX:StartFlightRecording=filename=benchmark-results/issue77-server.jfr,settings=profile,dumponexit=true' \
cmdshape benchmarks/build/install/benchmarks/bin/benchmarks candidate 8080 1000
cmdshape .scratch/tools/k6 run --no-usage-report -e CANDIDATE_ROUTING=1 -e ROUTE_GROUPS=1000 -e WORKLOAD=route-regex -e RATE=1000 -e VUS=64 -e DURATION=60s benchmarks/http.js
```

Run a separate unprofiled trial for latency, then inspect the JFR recording for allocation sites and CPU samples. These are end-to-end loopback checks, not replacements for the before/after JMH allocation and timing matrix.

With route groups enabled, Jooby uses explicit 404/405 error handlers to return the same small plaintext bodies as the candidate. Its native router still detects missing routes and methods and generates `Allow`; there is no catch-all replacement router. These workloads do not test the frameworks' different ambiguous-route precedence policies. Candidate response-byte copying, parameter handling, and each framework's internal error dispatch remain part of the measured behavior.

Javalin natively returns 404 for this fixture's DELETE on a GET/POST path. Its 404 error handler maps the benchmark path shape to the same 405 body and `Allow` header, so Javalin's `wrong-method` latency is **not native 405 dispatch** and should be evaluated separately. The Javalin SSE fixture uses `InputStream` data to send raw UTF-8 rather than JSON-quoted strings; this also changes its allocation path. The [sustained report](../benchmark-results/issue43-20260924/REPORT.md) records those limits and the full JFR findings.

Use `benchmarks/ramp.js` instead of `http.js` to ramp from 1,000 req/s over ten seconds before holding `RATE` for `DURATION`. It reuses the same fixtures and checks. The native summary includes `phase:steady` latency metrics, excluding the ramp. Drops remain a threshold failure across the entire run. Counts divided by the hold duration approximate achieved hold throughput; k6's tagged rate uses the whole run duration and must not be reported as hold throughput.

`BASE_URL` defaults to `http://127.0.0.1:8080`. The [constant-arrival-rate executor](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/) drives `RATE` requests/second independently of response time using a fixed `VUS` pool. Defaults are starting settings, not a performance target. Increase the rate in separate runs to locate saturation; compare latency at equal offered load. Failed checks, failed HTTP requests, or dropped iterations fail the run. A failed threshold is useful evidence of overload or misconfiguration, not a valid speedup result. Streaming measurements cover completion of the full body, not per-chunk latency.

All k6 workload scripts now default to a 180-second measured phase. The dedicated SSE scripts require the pinned xk6-sse extension (`.scratch/tools/k6-sse` in this workspace); HTTP and WebSocket use k6 2.2.0. The scripts are `sse.js` (`WORKLOAD=burst|paced`), `websocket.js` (`WORKLOAD=text|binary`), `sse-slow.js`, and `websocket-slow.js`. Slow-client scripts pause in the application callback; they do not throttle the network socket. The [issue 43 report](../benchmark-results/issue43-20260924/REPORT.md) records 15-second warmups, three-minute candidate/Jooby runs and a later Javalin-only run for all 14 HTTP/SSE/WebSocket workloads, JFR timelines, and post-load heap recovery. Each result is one long trial per framework/workload, so the values show sustained behavior but no confidence interval; repeat them before making small latency-rank claims. The candidate GC recordings and reports are preserved locally in the result directory and in its `candidate-gc-artifacts.tar.gz` archive. The result directory is gitignored.

**Historical binary WebSocket limitation:** saved Javalin runs passed a borrowed inbound buffer to an asynchronous send and checked only message length and endpoint bytes. Those measurements remain exploratory. The current fixture copies the borrowed bytes before sending; the scripts now use distinct messages and verify every byte and message order. Fresh validation and measurements are required; the fix does not retrospectively validate old results.

## Fresh comparison campaign

`compare.sh` invokes the native k6 tools sequentially, with fresh JVMs, equal offered
load, separate warmup, three repeats and rotated framework order. It retains native
compressed time series, summaries, logs, process snapshots and `/usr/bin/time`
resource reports. It also retains resolved campaign/per-cell configuration, the
HEAD-to-worktree binary diff (including staged changes), an archive of nonignored
untracked sources outside the result directory, the
effective server launch affinity and the pinned client launch affinity inherited
by k6 at exec; these are launch snapshots, not continuous affinity monitoring.
It does not analyze results; ordinary comparisons do not profile
the timed JVMs. Resource
reports for the server cover its whole lifetime, including startup and warmup;
the process-stat snapshots delimit the measured phase for CPU-time comparisons.

```sh
JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-amd64 \
cmdshape bash benchmarks/compare.sh benchmark-results/fresh-comparison
```

Use a new destination for each campaign. Optional `DURATION`, `WARMUP`, `REPEATS`,
`WORKLOADS` and `HTTP_RATE` environment variables support smoke checks and
calibration. Defaults are 180 seconds, 15 seconds, three repeats, all 14 workloads
and 1,000 HTTP requests/sec. SSE/WebSocket use 50 sessions/sec except large,
callback-delayed sessions at 10/sec. These are equal-load comparisons, not maximum
capacity claims. `SERVER_CPUS` and `CLIENT_CPUS` default to `0-7` and `8-15` for
this host's physical-core layout; adjust them for another host. `TARGETS=candidate`
selects candidate-only diagnostics. For its three additional routing workloads,
also pass `CANDIDATE_ROUTING=1`. Use `PROFILE=1 REPEATS=1` in a separate campaign
for allocation attribution: JFR starts after warmup, stops after load, and native
JDK reports and full allocation stacks are retained. Profile timing is not a
ranking result. Benchmark-only Jetty and EE10 BOMs
align all installed Jetty artifacts with core's current 12.1.13 patch.

`BENCHMARK_HOME` selects a frozen benchmark installation instead of the current
`benchmarks/build/install/benchmarks`; its JAR hashes are recorded in the campaign.
`HTTP_SCRIPT=benchmarks/ramp.js` uses the existing ten-second arrival-rate ramp before
the ordinary HTTP steady phase. Smoke mode keeps its own stepped script, and neither
setting changes SSE/WebSocket workloads. `WARMUP_RATE` optionally sets a separate
warmup arrival rate; HTTP ramp workloads warm up at that constant rate. Measured
arrival rates and failure/drop thresholds are unchanged. Profile runs also enable JDK 25 CPU-time
sampling; keep them separate from unprofiled ranking runs.

`SchedulingBenchmark` screens the current queued-pool and proposed virtual-pool
integration using Jetty's real adaptive strategy and Shoostr finite-response work.
A persistent producer waits on a readiness queue, not a socket selector. Results
are microseconds per complete batch (1 or 32 responses), not HTTP latency or server
capacity. Its fixture tests verify producer ownership, virtual handler execution
and exactly-once completion. Use `-prof gc` for allocation and separate `-prof jfr`
runs for diagnosis; a win here still requires confirmation with live HTTP requests.

`TelemetryObservationBenchmark` in `microbenchmarks` isolates the current optional
observer lifecycle for allocation diagnostics. It compares a minimal reference,
Micrometer, and OpenTelemetry propagation-only, SDK-drop and SDK-recording modes,
without exporters. Fixture/request/outcome construction and the first fixed-ID
meter registration happen before timing. It is not an HTTP throughput benchmark,
and its total allocation includes necessary dependency work. Run it separately
from k6 so compilation and JMH do not compete with the measured HTTP processes:

```sh
cmdshape ./gradlew clean spotlessApply build :microbenchmarks:installDist
cmdshape microbenchmarks/build/install/microbenchmarks/bin/microbenchmarks \
  TelemetryObservationBenchmark -prof gc -rf json \
  -rff benchmark-results/telemetry-observation.json
```

`FiniteResponseBenchmark` measures public byte/text staging on a reusable open
response, excluding peer construction and completion. Its input size is characters:
ASCII encodes to one byte per character and the Unicode fixture (`é`) to two.
Text-versus-bytes results include necessary UTF-8 encoding as well as any redundant
copy; the entire difference is not an optimization saving.

`StreamBufferBenchmark` and `SseFramingBenchmark` include one fresh request/response
pair, fixed-transport reset and the stream/event operation in their measured
methods. Payloads, options and transport are prepared once outside measurement.
This replaces invocation-level setup, whose allocation JMH included in GC bytes/op
while excluding its direct time. Do not compare the corrected results with old
invocation-fixture scores, or call all fixture-inclusive bytes/op removable
stream/SSE allocation. Run all these diagnostics separately from k6:

```sh
cmdshape microbenchmarks/build/install/microbenchmarks/bin/microbenchmarks \
  FiniteResponseBenchmark -prof gc -rf json \
  -rff benchmark-results/finite-response.json
```

## Compare changes and inspect causes

### Bounded HTTP overload smoke

`MODE=smoke` uses the same fixtures and response checks, but selects native k6
constant-arrival-rate plateaus from `ramp.js`. It works with any supported
framework, not just Shoostr. The runner defaults to Shoostr (`candidate`) and
Jooby, one repeat and the plaintext workload; repeated runs alternate their order.

```sh
JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-amd64 MODE=smoke \
SMOKE_RATES=1000,2000,5000,10000,20000,50000,100000 \
SMOKE_STEP_SECONDS=15 VUS=256 \
cmdshape bash benchmarks/compare.sh benchmark-results/http-overload
```

Each plateau has its own native scenario and summary metrics. A five-second
drain separates plateaus, matching the request timeout; this avoids overlapping
VU pools. `VUS` is both the preallocated and maximum pool, so no additional VUs
are allocated during overload. `DURATION` does not control smoke plateaus;
`SMOKE_STEP_SECONDS` does. The default seven steps bound each measured run to
140 seconds including drains, plus the separate 15-second warmup. Rates must
be increasing positive integers; step seconds and VUs must be positive integers.
The runner validates the native execution plan before launching the JVM.

Use `WORKLOADS='plaintext stream route-parameter wrong-method'` for multiple
HTTP workloads, `TARGETS='jooby candidate'` for explicit order, or any supported
single target. SSE/WebSocket smoke is deliberately excluded. Candidate-only
route fixtures still require `TARGETS=candidate CANDIDATE_ROUTING=1`.
For an independently started server, the same mode works directly:

```sh
cmdshape .scratch/tools/k6 run --no-usage-report \
  -e MODE=smoke -e BASE_URL=http://127.0.0.1:8080 \
  -e SMOKE_RATES=1000,2000,5000 -e SMOKE_STEP_SECONDS=15 -e VUS=256 \
  benchmarks/ramp.js
```

Checks, HTTP errors and drops are strict thresholds, but do not abort the ramp
at the first breach. A completed overloaded k6 run normally exits 99; the runner
records the exact client exit code, failed outcome and whether the server remained
alive, then continues with the next cell. Other nonzero codes may mean invalid
configuration, a client exception or external interruption, not server overload.
Native summaries and logs remain available for failed cells. `top` records the
owned server and client CPU/RSS each second, alongside native resource reports.
A missing monitor is explicitly recorded and weakens capacity attribution.

Full raw metrics default off in smoke mode (`RAW_METRICS=0`) to avoid per-sample
JSON/gzip overhead; set `RAW_METRICS=1` when those time series are needed. Native
summary trends still retain samples, so duration, rates and client memory must
remain bounded. Scenario request counts divided by the plateau duration give
achieved throughput; native tagged rates use the entire run duration and are not
plateau throughput. Iteration percentiles include checks and the complete response;
HTTP duration excludes connection setup and can be affected by host clock behavior.

Set `FAILURE_DETAILS=1` to log the first transport failure per virtual user with
its native k6 error code, scenario and phase timings. This does not change request
timeouts or response checks; ordinary k6 warnings still report subsequent failures.

A failed plateau means its offered load was not sustained in that trial, not
necessarily that it is the server's capacity boundary. Higher passing steps can
expose lower-step startup or connection transients. Drops also arise when the
generator is CPU/VU limited. Inspect the complete throughput/latency curve and
both processes, then repeat the boundary range with reversed order before drawing
capacity conclusions. Tiny isolated zero-loss breaches are not a capacity ranking.
Use a separate generator host for a production-capacity claim. Profile Shoostr
only in a separate diagnostic run, never in the timed competitor comparison.

Keep workload, Java build, heap/GC settings, engine, rate, VUs, durations, and host conditions the same. Warm each server, run at least three trials, alternate candidate/Jooby order, and retain every result. Use descriptive run directories, including revision and workload. Record `git rev-parse HEAD`, `git status --short`, the working diff when uncommitted, `java -version`, `k6 version`, and `uname -a` alongside results; preserve untracked source files when testing an uncommitted implementation.

Start with loopback smoke checks; meaningful capacity comparisons should use a separate load host and monitor both machines so k6 capacity is not mistaken for server capacity. This small candidate has fewer features than Jooby. No claim of outperforming Jooby follows from the setup or a short local run.

For CPU, allocation, GC, and contention analysis, use JDK Flight Recorder in a separate profiling run. Supply the option through the generated launcher's `JAVA_OPTS`:

```sh
JAVA_OPTS='-XX:StartFlightRecording=filename=benchmark-results/server.jfr,settings=profile,dumponexit=true' \
cmdshape benchmarks/build/install/benchmarks/bin/benchmarks candidate 8080
```

Stop the server normally to save the recording, then inspect it with JDK Mission Control or `cmdshape jfr summary benchmark-results/server.jfr`. Compare profiling runs separately from runs without recording. No automatic performance-regression threshold is set until repeatability and noise are measured.
