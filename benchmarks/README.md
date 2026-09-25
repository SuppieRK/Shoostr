# HTTP measurements with k6

Local evidence: [RadixRoutes before/after validation and fresh Jooby comparison](../benchmark-results/radix-http-20260918-01/REPORT.md) and [three-minute HTTP, SSE, and WebSocket comparison](../benchmark-results/issue43-20260924/REPORT.md). Results directories are ignored by Git; preserve them when sharing measurements.

Use Java 25, the repository's Gradle wrapper, and [k6](https://grafana.com/docs/k6/latest/set-up/install-k6/). This setup was checked with k6 2.2.0. There is no project-specific load generator or statistics/reporting engine. `http.js` defines payloads, correctness checks, and native k6 options.

## Build and start a server

From the repository root:

```sh
cmdshape ./gradlew clean spotlessApply build :benchmarks:installDist
cmdshape benchmarks/build/install/benchmarks/bin/benchmarks candidate 8080
```

For Jooby or Javalin, stop that process and use `jooby` or `javalin` instead of `candidate`. The generated launcher uses a 256 MiB fixed heap and G1 for all three servers. On Windows use `gradlew.bat` and `benchmarks.bat`. The current benchmark dependency graph resolves Jetty 12.1.12 for all three branches because Javalin 7.2.3 requires that patch. The **saved** candidate/Jooby runs used Jetty 12.1.11 and were not rerun after Javalin was added. All use HTTP/1.1 and virtual-thread handlers. Jooby 4.5.4 uses explicit WORKER execution. The k6 setup phase verifies virtual-thread execution and response status, content type, and bytes for every fixture before load begins. This compares framework behavior on closely related Jetty versions; it does not establish which engine or configuration is fastest.

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

With route groups enabled, Jooby uses explicit 404/405 error handlers to return the same small plaintext bodies as the candidate. Its native router still detects missing routes and methods and generates `Allow`; there is no catch-all replacement router. These workloads do not test the frameworks' different ambiguous-route precedence policies. Candidate response-byte copying, parameter handling, and each framework's internal error dispatch remain part of the measured behavior.

Javalin natively returns 404 for this fixture's DELETE on a GET/POST path. Its 404 error handler maps the benchmark path shape to the same 405 body and `Allow` header, so Javalin's `wrong-method` latency is **not native 405 dispatch** and should be evaluated separately. The Javalin SSE fixture uses `InputStream` data to send raw UTF-8 rather than JSON-quoted strings; this also changes its allocation path. The [sustained report](../benchmark-results/issue43-20260924/REPORT.md) records those limits and the full JFR findings.

Use `benchmarks/ramp.js` instead of `http.js` to ramp from 1,000 req/s over ten seconds before holding `RATE` for `DURATION`. It reuses the same fixtures and checks. The native summary includes `phase:steady` latency metrics, excluding the ramp. Drops remain a threshold failure across the entire run. Counts divided by the hold duration approximate achieved hold throughput; k6's tagged rate uses the whole run duration and must not be reported as hold throughput.

`BASE_URL` defaults to `http://127.0.0.1:8080`. The [constant-arrival-rate executor](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/) drives `RATE` requests/second independently of response time using a fixed `VUS` pool. Defaults are starting settings, not a performance target. Increase the rate in separate runs to locate saturation; compare latency at equal offered load. Failed checks, failed HTTP requests, or dropped iterations fail the run. A failed threshold is useful evidence of overload or misconfiguration, not a valid speedup result. Streaming measurements cover completion of the full body, not per-chunk latency.

All k6 workload scripts now default to a 180-second measured phase. The dedicated SSE scripts require the pinned xk6-sse extension (`.scratch/tools/k6-sse` in this workspace); HTTP and WebSocket use k6 2.2.0. The scripts are `sse.js` (`WORKLOAD=burst|paced`), `websocket.js` (`WORKLOAD=text|binary`), `sse-slow.js`, and `websocket-slow.js`. Slow-client scripts pause in the application callback; they do not throttle the network socket. The [issue 43 report](../benchmark-results/issue43-20260924/REPORT.md) records 15-second warmups, three-minute candidate/Jooby runs and a later Javalin-only run for all 14 HTTP/SSE/WebSocket workloads, JFR timelines, and post-load heap recovery. Each result is one long trial per framework/workload, so the values show sustained behavior but no confidence interval; repeat them before making small latency-rank claims. The candidate GC recordings and reports are preserved locally in the result directory and in its `candidate-gc-artifacts.tar.gz` archive. The result directory is gitignored.

**Known binary WebSocket limitation:** the Javalin fixture passes a borrowed inbound `ByteBuffer` to an asynchronous send that can outlive that buffer's valid lifetime. Both binary scripts (`websocket.js` in binary mode and `websocket-slow.js`) send identical messages and check only length and the first/last bytes, so passing checks cannot exclude interior corruption or reordered echoes. Javalin's binary measurements remain exploratory and must not be used to rank correct binary-echo implementations. This limitation is retained explicitly; no fixture fix or rerun was performed.

## Compare changes and inspect causes

Keep workload, Java build, heap/GC settings, engine, rate, VUs, durations, and host conditions the same. Warm each server, run at least three trials, alternate candidate/Jooby order, and retain every result. Use descriptive run directories, including revision and workload. Record `git rev-parse HEAD`, `git status --short`, the working diff when uncommitted, `java -version`, `k6 version`, and `uname -a` alongside results; preserve untracked source files when testing an uncommitted implementation.

Start with loopback smoke checks; meaningful capacity comparisons should use a separate load host and monitor both machines so k6 capacity is not mistaken for server capacity. This small candidate has fewer features than Jooby. No claim of outperforming Jooby follows from the setup or a short local run.

For CPU, allocation, GC, and contention analysis, use JDK Flight Recorder in a separate profiling run. Supply the option through the generated launcher's `JAVA_OPTS`:

```sh
JAVA_OPTS='-XX:StartFlightRecording=filename=benchmark-results/server.jfr,settings=profile,dumponexit=true' \
cmdshape benchmarks/build/install/benchmarks/bin/benchmarks candidate 8080
```

Stop the server normally to save the recording, then inspect it with JDK Mission Control or `cmdshape jfr summary benchmark-results/server.jfr`. Compare profiling runs separately from runs without recording. No automatic performance-regression threshold is set until repeatability and noise are measured.
