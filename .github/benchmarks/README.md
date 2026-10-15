# Shoostr diagnostics in Actions

Run **Shoostr benchmarks** manually from the Actions tab. Select the branch,
`steady`, `overload` or `tracing`, and artifact retention (1–14 days, default 7).
For HTTP modes, set the JVM's logical CPU count (default 1) and total memory cap in MiB (default
1024). The heap remains fixed at 256 MiB; raising the total cap does not raise it.
CPU counts must leave at least one permitted CPU for k6. Memory must exceed the
256 MiB heap and cannot exceed host RAM. Invalid settings fail, never clamp.
The workflow requires its support files on the selected branch. It does not run
benchmarks on pushes or pull requests.

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

## Paired tracing JMH

Select `tracing` and supply `baseline_sha` as an immutable full 40-character commit SHA.
The candidate is the selected workflow branch's exact commit. One Ubuntu runner builds the
candidate fixture and baseline adapters serially, then uses the same fixture and dependency jars
for both revisions, substituting only the project's core and OpenTelemetry adapter jars. Classpaths,
checksums, source revisions, hardware, JVM flags, CPU/memory visibility and OS limits are retained.
An incompatible baseline fails smoke validation rather than producing a comparison.

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
[JDK 25 diagnostic commands](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jcmd.html),
[JDK 25 JFR CLI](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jfr.html).
