# Shoostr diagnostics in Actions

Run **Shoostr benchmarks** manually from the Actions tab. Select the branch,
`steady`, `overload`, `slow-client` or `tracing`, and artifact retention (1–14 days, default 7).
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
cancellation of arbitrary application code. Ending load disconnects those clients,
and the control route releases the gate before a full 30-second recovery window.

Blocked raw metrics must also show the selected active VUs throughout the nominal
load (after five seconds of startup), with no missing one-second gauge series
interval beyond 1.5 seconds. Each selected VU's successful native request-duration
intervals must span seconds 5–55, with at most one second between calls; the final
five-second batch can be interrupted at termination. A historical peak or one
successful batch is insufficient. These are exposure-validity bounds, not requests/
second targets. The checker uses k6's optional native `vu` tag and
[end-of-response duration timestamps](https://grafana.com/docs/k6/latest/using-k6/metrics/reference/),
not synthetic server throughput estimates.

A separate k6 process schedules 100 complete healthy requests at one per second,
covering load and recovery plus bounded tool-start/output margin. Every status,
body and complete-call duration must pass; no response may exceed one second and
no probe may be missing or dropped. Native raw timestamps must span both phases.
The campaign stops on an observed probe/OOM failure; failed or partial runs are
retained as FAILED, never converted into successful reduced-load measurements.

Public matched/terminal callbacks distinguish active handlers from unfinished
exchanges. Recovery requires zero workload handlers/exchanges, balanced admitted
and terminal counts, the exact selected peak concurrency, and only the current
state-control connection. A qualifying recovery sample must fall within 30 seconds,
not merely after the 100-probe process exits. Native connected endpoints include
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
