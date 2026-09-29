# Issue 53: Stream-local ByteBuffer view

This candidate-only comparison uses base revision
`a03da1d45f483b971f24aab84e30fef6e3c146f0` on branch
`issue-53-stream-bytebuffer-view`. Raw JMH JSON/logs, k6 summaries/logs, and server
JFR recordings are under ignored `benchmark-results/issue53-20260929/`; copy that
directory separately when sharing the evidence. The pre-existing local
`gradlew.bat` edit was not part of this change or the Linux runs. No Jooby or
Javalin workload was run.

The host was WSL2 Linux 6.6.87.2 on an AMD Ryzen 7 9800X3D (8 cores, 16
threads), using Temurin 25.0.2, JMH 1.37, k6 2.2.0, and k6-sse 1.7.1 with
xk6-sse 0.1.11. Server and load generator shared the host; these are one
before/after trial per workload, not capacity or statistical latency claims.

## JMH: allocation and time are separate outcomes

`StreamBufferBenchmark` starts a fresh response stream and completes it within
each benchmark operation. Its transport proxy retains the submitted ByteBuffer
through the invocation, so a real transport-style escape remains visible to
the GC profiler. The same fixture source was used for both measured phases.
Response-wrapper construction occurs in invocation setup; JMH's B/op includes
that allocation even though the timed method body starts afterward. JMH used
one thread, two forks, three one-second warmup and measurement iterations per
fork, G1 and a fixed 512 MiB heap. All rows below are from the corrected
`retained-jmh.json` files.

| Operation | Baseline B/op | After B/op | Delta B/op | Baseline µs/op | After µs/op |
| --- | ---: | ---: | ---: | ---: | ---: |
| Construct stream | 744 | 752 | +8 | 0.0453 | 0.0454 |
| One small explicit flush | 1,368 | 1,320 | −48 | 0.1018 | 0.1020 |
| Sixteen explicit 1 KiB flushes | 3,144 | 2,256 | −888 | 0.4653 | 0.4390 |
| One 64 KiB write, automatic flushes | 9,672 | 9,232 | −440 | 0.8762 | 0.8407 |
| Incremental writes to 300-byte boundary | 1,688 | 1,640 | −48 | 1.2987 | 1.5275 |

The +8 B/op construction cost is the extra per-stream reference. For sixteen
flushes, one initial empty view and one view after storage growth replace a
fresh view for every transport write; the net −888 B/op is consistent with
sixteen avoided heap wrappers after that field cost. This is an attribution
from the fixture and allocation deltas, not an end-to-end byte count. The
repeated-flush JMH time intervals overlap (baseline ±0.0296 µs/op; after
±0.0133 µs/op). The incremental case's after time is higher but highly
variable (±0.2564 versus baseline ±0.0165 µs/op); neither supports a firm
timing claim. Allocation fell in every writing case, while construction
allocated slightly more.

The first `jmh.json` pair is preserved but **not** used for allocation
attribution. That fixture merely read `remaining()` from the submitted view,
allowing the JIT to scalar-replace short-lived baseline wrappers. It showed
2,136 → 2,256 B/op for repeated flushes, contradicting the live JFR stacks.
Retaining the submitted view in the proxy restored the relevant escape;
the baseline and after-state were both rebuilt and rerun with this identical
fixture. Its focused fixture test verifies that the final submitted view is
retained. A later Checkstyle-only edit moved the fixture's initial empty-view
assignment from the field declaration to its constructor; the measured
operations and transport callback stayed unchanged.

Command for each phase (using that phase's installed candidate build):

```sh
JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-amd64 cmdshape microbenchmarks/build/install/microbenchmarks/bin/microbenchmarks StreamBufferBenchmark -f 2 -wi 3 -i 3 -w 1s -r 1s -prof gc -foe true -rf json -rff benchmark-results/issue53-20260929/PHASE/retained-jmh.json
```

## Sustained chunked HTTP and SSE

Each phase used a fresh candidate server JVM with the generated launcher's
256 MiB G1 heap, a 15-second warmup, then 180 seconds of k6 load with a
server-side JFR `profile` recording. HTTP `stream` requests send sixteen
explicitly flushed 1 KiB chunks at an offered 1,000 requests/s using k6;
SSE `burst` sends sixteen 128-byte events at an offered 100 streams/s using
k6-sse. Both used 64 preallocated VUs. The scripts verified complete response
bytes or ordered SSE payloads. Every k6 process exited successfully, with
zero failed checks, HTTP request failures, or dropped iterations.

| Workload | Phase | Completed | Count/180 s | Native k6 rate/s | p99, ms | Young GCs | ByteBuffer.wrap sampled share |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Chunked HTTP | Baseline | 180,001 | 1,000.0 | 1,035.1 | 0.571 | 10 | 16.23% |
| Chunked HTTP | After | 180,001 | 1,000.0 | 1,042.6 | 0.613 | 9 | 1.05% |
| SSE burst | Baseline | 18,000 | 100.0 | 104.2 | 1 | 2 | 5.72% |
| SSE burst | After | 18,000 | 100.0 | 103.6 | 1 | 2 | 1.15% |

HTTP uses the scenario-tagged `http_req_duration` and `http_reqs`; SSE uses
`sse_stream_duration` and `iterations`. The native k6 rates use its metric
sample time span and exceed the configured arrival rates here; completed
count divided by the 180-second measurement is the comparable achieved
rate. SSE duration uses `Date.now()` and therefore has whole-millisecond
resolution. Its equal p99 values cannot establish a sub-millisecond change.

JFR's `allocation-by-site` percentages are sampled pressure shares, **not**
exact bytes or allocations per request. Full baseline stacks link
`ByteBuffer.wrap(byte[], int, int)` to `Response.write`, while after-state
stacks link the remaining `wrap` samples to `Response.Stream.view` when a
stream first obtains or replaces its view. The observed shares fell sharply
in both workloads. HTTP young-GC count was 10 → 9 and SSE was 2 → 2; neither
single-run count proves a GC-rate improvement. HTTP p99 increased by
0.042 ms in this trial despite the lower wrapper pressure. No end-to-end
latency gain is claimed, and this shared-host result should be repeated
before treating that p99 difference as a stable regression.

## Contract and implementation

`Stream` owns one heap ByteBuffer view. Each streaming transport write
clears it and sets the pending-byte limit only after the previous synchronous
transport wait completed successfully. Growth invalidates the old view;
transport failure or interruption makes the response terminal and discards
its view. Finite-response asynchronous writes are unchanged. No pool,
off-heap buffer, or transport abstraction was introduced.

`StreamBufferTest` checks exact public output across differing lengths,
backing-array growth, two interleaved responses, empty and final flushes,
caller-owned input mutation, fluent chaining, flush-hook order, delayed
completion, synchronous and delayed asynchronous failures, interruption,
and rejected writes after failure. Its transport-boundary proxy consumes
the submitted view, exercising position reset without asserting private
buffer identity. For interruption, the boundary instead holds the submitted
view unconsumed until the writer exits, verifies its position, limit, and
readable bytes are unchanged, then consumes it and completes the callback.
A deliberate failure-path `clear()` made that test fail with limit 128 instead
of 3; the mutation was removed, and the test passed again. The targeted core
and benchmark tests passed before the full repository gate. IDEA built the
edited Java files and reported no new issues; its existing `Response`
try-with-resources suggestions are
non-actionable because the framework, not callers inside these methods or
tests, owns `Response.close()`.

The local full-build and delegated-review results are recorded in the local
issue review record. Delivery was deferred for that session; the user has now
authorized a PR. CI and the full-project SonarCloud audit must pass for its
latest commit before issue closure.
