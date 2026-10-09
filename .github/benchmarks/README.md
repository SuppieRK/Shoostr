# Shoostr diagnostics in Actions

Run **Shoostr benchmarks** manually from the Actions tab. Select the branch,
`steady`, `overload` or `threading`, and artifact retention (1–14 days, default 7).
The workflow requires its support files on the selected branch. It does not run
benchmarks on pushes or pull requests.

Steady mode runs eight HTTP workloads (plaintext, pre-encoded JSON, 1 KiB echo,
16 KiB flushed stream, literal routing, parameter routing, 404 and 405), three SSE
workloads (burst, paced and callback-delayed large events), and three WebSocket
workloads (text, binary and callback-delayed large frames). Each workload has its
own Linux runner. Its three trials use fresh JVMs, a 30-second warmup, and a
180-second offered-load phase. Up to fourteen workload jobs run concurrently;
GitHub may queue jobs when account capacity is unavailable.

Overload mode runs only the eight HTTP workloads. Each trial offers 1,000, 2,000,
5,000, 10,000, 20,000, 50,000 and 100,000 requests/second in 15-second plateaus with
five-second drains. Threshold breaches appear as **OVERLOAD**, while startup,
tooling, profiling and publication failures fail the workflow. Steady-mode
correctness failures also fail the workflow. The load generator can saturate
before the service; inspect both processes before interpreting a boundary.

Slow-WebSocket steady traffic uses one session/second: the initial hosted trial
at ten sessions/second saturated the two-CPU k6 client and failed during warmup.
Payload validation, callback delay, timeouts and correctness checks are unchanged.

The build job prepares one fixture distribution and one pinned k6 binary with
the SSE extension. Workload jobs reuse those binaries and a 256 MiB fixed G1
heap. Available CPUs are divided evenly between the JVM and k6; actual affinity,
CPU model, runner image, JVM/client versions, source revision and hashes accompany
the recordings. This separates the campaign from the developer's machine, but
hosted runner hardware and images can still vary.

Each fresh JVM prints and retains native `jcmd` version, command line, effective
non-default flags and `VM.info` diagnostics before warmup. The complete effective
flag list (`VM.flags -all`) and OS process limits are also retained. Summaries show
actual JVM/client CPU affinity and JVM-visible CPU/RAM/container limits. Affinity
does not reserve exclusive cores; the 256 MiB Java heap cap does not cap native
memory or process RSS. Older artifacts lacking these diagnostics are visibly
incomplete under the expanded evidence requirements, not assigned invented limits.

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
JFR starts after warmup and spans the full measured k6 invocation, including
client initialization/setup, drain and output finalization. Recordings can therefore
last longer than the offered-load phase. JFR is explicitly stopped before JVM shutdown. Cancellation
recovery is best-effort; incomplete evidence stays visibly marked. Artifact sizes
and expiry are shown in summaries. Upload or storage-quota failures block
successful publication; the workflow does not change billing settings.

Steady and overload measured runs include profiling and raw-output overhead. Thirty seconds is the
chosen warmup duration, not a guarantee of steady JIT state. These measurements
provide diagnostics rather than an automatic latency-regression gate. Inspect
the native data before attributing a change to Shoostr.

## Threading experiment

Threading mode compares internal A/B/C/D candidates; it does not publish execution options or
change the public default. A retains the current virtual producer pool; B uses native platform
transport with virtual blocking consumers; C is a platform-only control; D uses B plus one shared
platform pool for selected matched lifecycles. B/C/D use a `QueuedThreadPool(16, 8)` with zero
reserved workers. Actual leased/available counts are diagnostic evidence, not inferred handler
capacity. D has one worker per server-affinity logical CPU. All candidates receive the same
CPU affinity, JVM-visible processor budget, fixed 256 MiB G1 heap, payloads and offered rates.

Four workload jobs run in parallel; candidates run sequentially on the same workload runner.
Each job rotates A/B/C/D, B/C/D/A and C/D/A/B across three fresh-JVM timing repetitions, then
performs one separate profile per candidate. Each trial warms for 30 seconds and offers load for
180 seconds. Timing trials omit JFR, raw JSON output and dashboards; profiles retain those
diagnostics and must not be mixed into the timing comparison. OS process monitoring is retained
in both. Profiles sample native scheduler/worker/pool/adaptive counters once per second; unavailable
adaptive beans are identified as unavailable rather than assigned zero activity.

The tiny workload offers 5,000 constant-response requests/s. I/O offers 80 requests/s against an
independently monitored loopback downstream with a fixed 20 ms wait, plus a direct 2/s downstream
probe. The downstream shares the client CPU partition, never the server partition. CPU offers
200 requests/s, hashing exactly 4 MiB of ASCII x with SHA-256 and validating the digest. Mixed
traffic keeps tiny at 1,000/s while CPU traffic holds 200, 500 and 1,000/s, with two 1-second
transitions inside the three 60-second windows. Both endpoints have independent step-tagged
native metrics. k6 allocates 256 VUs per scenario and can grow to 1,024; drops and client saturation
remain evidence, not an invented service capacity boundary. In D, pure workloads select that
endpoint; mixed selects only CPU. HTTP setup checks actual thread kind and response parity.

Summaries keep every repetition's p50/p95/p99/max, failures and drops/scheduled total/percentage.
Mean throughput is supplemental, never a pooled percentile. A threshold breach is reported as
an overload diagnostic; startup, fixture-setup, tooling or missing evidence still fails publication.
CPU/RAM, effective flags, process limits, affinity, resource reports and native outputs are retained.
The timing/profiling distinction and hosted-runner limitations remain mandatory in conclusions.

The companion JMH job measures complete `LocalConnector` exchanges (tiny, small parameter route,
and CPU) with three forks and allocation reporting, including an installed-but-unused worker pool.
It rebuilds unchanged core `f34092d149badbd8b371a1527ee9c3d1c918a8b9` with the same pinned JDK for
an additional original-default control; this is not a replacement for A/B/C/D. JFR forks are
separate and each recording has a unique path. HTTP verdicts wait for the entire matrix.

```sh
cmdshape gh workflow run benchmarks.yml --ref issue80-threading-benchmarks -f mode=threading -f retention_days=7
```

The fixture remains the `:benchmarks` Gradle module, now located in this directory.
Normal builds compile and check it; they do not execute timed campaigns. Targeted
JMH diagnostics remain in the separate `microbenchmarks` module.

References: [manual workflow execution](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow),
[k6 native dashboard](https://grafana.com/docs/k6/latest/results-output/web-dashboard/),
[k6 metric definitions](https://grafana.com/docs/k6/latest/using-k6/metrics/reference/),
[JDK 25 diagnostic commands](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jcmd.html),
[JDK 25 JFR CLI](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jfr.html).
