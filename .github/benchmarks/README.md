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

### Completed threading campaign

[Campaign 37884898672](https://github.com/SuppieRK/Shoostr/actions/runs/37884898672)
completed all 48 HTTP timing trials, 16 separate HTTP profiles, 15 candidate JMH cases,
three original-core controls and 12 separate JMH profiles. Measured source is
`a28e9a8a665bf80fc3a5ca03a927ecbb0c775dcf`; later documentation checkpoints do not change it.
All HTTP trials used actual Temurin `25.0.4.1+1-LTS`, Jetty 12.1.14, the same fixture/client
binaries, fixed 256 MiB G1 heap and two JVM-visible processors. JVM affinity was `0,1`,
client affinity `2,3`. Logical affinity is not exclusive physical-core reservation.

The following are native per-repetition `http_req_duration` p99 values in milliseconds,
not pooled percentiles. This covers sending/waiting/receiving, not connection acquisition
or establishment; native blocked/connect metrics remain separate (A's highest mixed tiny
window has blocked p99 around 2–3 seconds and connecting p99 around 2 seconds).
Mixed tiny describes the highest CPU-load
window; other cells cover their whole measured workload. Every ordinary timing trial had
zero failures and drops.

| Model | Tiny 5,000/s | I/O 80/s | CPU 200/s | Mixed tiny 1,000/s |
| --- | --- | --- | --- | --- |
| A | 2.400 / 2.335 / 2.237 | 21.048 / 21.126 / 21.035 | 3.292 / 3.451 / 3.297 | 2,958.300 / 3,827.534 / 2,931.803 |
| B | 2.353 / 2.399 / 2.299 | 21.034 / 21.208 / 21.089 | 3.500 / 3.447 / 3.358 | 1,575.453 / 1,595.735 / 1,595.563 |
| C | 2.356 / 2.197 / 2.266 | 21.227 / 21.269 / 21.303 | 3.341 / 3.584 / 3.630 | 1,619.274 / 1,623.468 / 1,632.753 |
| D | 2.182 / 2.411 / 2.206 | 21.224 / 21.359 / 21.255 | 3.447 / 3.368 / 3.683 | 2.276 / 2.221 / 2.795 |

At the highest mixed window, CPU offered load is 1,000/s alongside tiny 1,000/s.
All twelve mixed trials reported **OVERLOAD**, even D. Completed successful requests/s
below exclude HTTP failures; drop counts cover the whole mixed trial, not only this window.
Each trial schedules 281,600 iterations (180,000 tiny + 101,600 CPU).

| Model | Successful tiny/s, repetitions 1/2/3 | Successful CPU/s, repetitions 1/2/3 | Whole-trial drops / 281,600 (%), repetitions 1/2/3 |
| --- | --- | --- | --- |
| A | 693.933 / 693.883 / 695.683 | 698.433 / 695.433 / 700.017 | 33,321 (11.8327%) / 29,625 (10.5202%) / 33,269 (11.8143%) |
| B | 691.617 / 695.417 / 691.133 | 687.633 / 691.200 / 687.200 | 36,994 (13.1371%) / 36,553 (12.9805%) / 37,049 (13.1566%) |
| C | 716.283 / 713.283 / 716.150 | 705.017 / 702.100 / 705.733 | 34,469 (12.2404%) / 34,826 (12.3672%) / 34,435 (12.2283%) |
| D | 1,000.033 / 1,000.017 / 1,000.033 | 676.083 / 677.583 / 676.117 | 19,184 (6.8125%) / 19,086 (6.7777%) / 19,179 (6.8107%) |

D had zero tiny drops and zero HTTP/check failures; all its drops were CPU iterations.
Its highest-window tiny max was 14.086 / 13.706 / 51.761 ms, versus A's approximately
five-second timeout. B/C had no HTTP/check failures either; A had timeouts. All native
p50/p95/p99/max, counts, checks and per-step rows remain in the Actions summaries/artifacts.

**Verdict after reexamination:** no consistent ordinary-workload tail-latency or CPU
throughput winner. D adds roughly 10–13 microseconds versus B to ordinary tiny median latency,
but convincingly isolates tiny requests from CPU overload in this two-processor setup.
Its highest-window CPU throughput is about 1.6–2.2% below B: isolation, not faster hashing.
The controlled I/O workload is healthy but too lightly concurrent to establish a general
I/O execution winner. Preserve the virtual default pending user review; no public option
or production transport change follows automatically from these results.

Separate profiles corroborate CPU contention and queue relocation: during the highest
mixed plateau the server consumes approximately its full two-CPU budget; k6 does not.
Sampled queue peaks are A virtual-scheduler 160, B virtual-scheduler 2,046, C transport
2,034; D virtual-scheduler 2 and selected-worker queue 1,022 with both workers active.
These are one-second samples, not exact queue maxima. SHA/digest stacks dominate native
samples, but high CPU-sampling bias prevents assigning precise method CPU shares.
Adaptive execution remains active in every candidate; it does not classify handler work.

Mixed profile GC pauses total 170 / 191 / 186 / 128 ms for A/B/C/D over approximately
183 seconds, with maximum pauses 45.1 / 47.0 / 45.9 / 43.5 ms. That is not the primary
explanation for seconds-long request queueing. One profile per model is not repeatable
GC-optimization proof; RSS ranges overlap, and post-run heap snapshots are GC-phase
dependent, not retained-object measurements. Allocation sampling is not exact bytes/request.
Profile runs with JFR/raw/dashboard diagnostics show ordinary tiny p99 around 19–21 ms:
do not substitute profile timings for the separate timing trials. JFR compiler counters
continue increasing after warmup; thirty seconds did not establish complete JIT stability.

The [contained JMH results](../../microbenchmarks/README.md) measure LocalConnector
completion/allocation, not network adaptive dispatch: local B/C both run on platform
threads. Live HTTP setup separately verifies A/B virtual and C/D-selected platform handler
kinds; D's unselected mixed tiny endpoint stays virtual.
Other CPU/worker budgets, high-concurrency I/O, active integration performance, exact
unprofiled allocation and retained heap graphs remain unmeasured. Before choosing a
production transport, a fresh paired A versus selective-on-A experiment is needed:
the useful isolation measured here uses B's transport, not an untested combination of winners.

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
