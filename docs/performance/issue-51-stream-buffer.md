# Issue 51: adaptive stream storage

`Response.Stream` used to allocate the configured 8 KiB buffer as soon as a stream
started, including streams that emitted no body or explicitly flushed much smaller
chunks. It now starts with shared empty storage and allocates on the first nonempty
write. Physical storage grows as needed, capped by `Options.streamBufferBytes()`;
that configured value remains the automatic-flush threshold. This is an internal
change with no new public option or change to the 8 KiB default.

## Isolated JVM results

The candidate-only JMH fixture excludes payload creation and response setup from
the **timed** method body, but the GC profiler's B/op includes the fresh responses
created during invocation setup and the fixture's proxy transport. It uses Java
25.0.2, G1, a 512 MiB heap, two forks, and three one-second warmup and measurement
iterations per fork. Values are averages of six measurements. The original
candidate is commit `e506aa1`; the changed candidate is the implementation on
`perf/adaptive-stream-buffer`. Both use the same fixture.

| Operation | Before, µs/op | After, µs/op | Before, B/op | After, B/op |
| --- | ---: | ---: | ---: | ---: |
| Start empty stream | 0.334 | 0.037 | 8,504 | 296 |
| Write and explicitly flush 128 bytes | 0.364 | 0.087 | 8,912 | 976 |
| Explicitly flush sixteen 1 KiB chunks | 0.566 | 0.332 | 8,912 | 1,744 |
| Write 64 KiB through automatic flushes | 0.786 | 0.788 | 8,720 | 8,720 |
| Write 300 one-byte chunks, 300-byte limit | 0.958 | 1.312 | 1,144 | 1,416 |

For the measured 128-byte write, the changed code allocates one 256-byte storage
array; for repeated 1 KiB explicit flushes, it allocates one 1 KiB array; for the
64 KiB write, it allocates one 8 KiB array, just as before. Payload bytes are
copied into that storage in both versions. The incremental case is deliberately
unfavorable to growth: it allocates 256 bytes and then 300 bytes, copying 256
pending bytes into the second array before the configured automatic flush. The
extra array accounts for the measured 272 B/op increase, including its object
header; repeated capacity checks and the copy coincide with a 0.354 µs/op cost.
The benchmark does not establish that this pattern is common in real traffic.

## Sustained HTTP and SSE results

Each candidate run used a fresh Jetty 12.1.13 server JVM with a 256 MiB G1 heap,
15 seconds of warmup, 180 seconds of k6 load, and JFR `profile` during the
measured window. Server and load generator shared one WSL2 host. The same scripts,
offered rates and 64 preallocated VUs were used before and after. HTTP used k6
v2.2.0; SSE used k6 v1.7.1 with xk6-sse. Checks verified complete payloads and
event order. Durations are k6 `http_req_duration` for HTTP and
`iteration_duration` for SSE; paced and large SSE include deliberate client
waiting, so they are not per-event latency. This was one run per case, except for
the repeated post-change HTTP run.

| Workload | Offered rate | Achieved rate before → after | p99 before → after, ms | Failed checks before → after | Dropped iterations before → after |
| --- | ---: | ---: | ---: | ---: | ---: |
| HTTP stream, 16 × 1 KiB flushes | 2,000/s | 1,999.90 → 1,999.93/s | 0.460 → 0.440 | 0 → 0 | 0 → 0 |
| SSE burst, 16 small events | 100/s | 100.00 → 100.00/s | 1.214 → 1.208 | 0 → 0 | 0 → 0 |
| SSE paced, 16 small events | 50/s | 49.92 → 49.92/s | 303.868 → 303.710 | 0 → 0 | 0 → 0 |
| SSE large, 16 × 64 KiB events with delayed client | 10/s | 9.96 → 9.96/s | 813.884 → 813.015 | 0 → 0 | 0 → 0 |

The first post-change HTTP run also completed 359,985 requests at 1,999.79/s,
0.450 ms p99, with zero failed responses or checks, but k6 dropped 21 of 360,000
scheduled iterations and exited with a threshold failure. Its maximum VU use was
three of 64, and the unchanged-binary repeat dropped none. This isolated scheduling
failure remains part of the evidence; one repeat does not prove its cause. The
small p99 differences in the table are not a demonstrated end-to-end latency gain
because these runs were not interleaved or repeated enough to estimate host noise.

JFR's allocation-site samples and young-GC events show where memory changed.
Site percentages are shares of **sampled allocation pressure**, not bytes per
request. JMH B/op measures the synthetic fixture, including invocation setup; it
does not measure total HTTP or SSE allocation per request. Its before/after deltas
are useful because the fixture and setup are the same on both sides.

| Workload | Stream storage site before → after | Young GCs before → after | JFR duration before → after |
| --- | ---: | ---: | ---: |
| HTTP stream | constructor 47.08% → growth 15.72% | 35 → 18 | 199 → 187 s |
| SSE burst | constructor 23.59% → growth 0.96% | 4 → 3 | 182 → 187 s |
| SSE paced | constructor 17.17% → below top sampled sites | 2 → 2 | 186 → 187 s |
| SSE large | constructor 10.25% → below top sampled sites | 39 → 39 | 186 → 184 s |

The large-event recording is dominated by payload string construction and UTF-8
encoding. The unchanged large-write JMH allocation and unchanged GC count are
consistent with the same 8 KiB storage allocation in that path. JFR site shares
cannot by themselves show an absolute allocation reduction there. No throughput
ceiling or superiority over another framework was tested.

Focused JUnit 5 tests cover empty and final writes, growth boundaries, configured
capacities below 256 bytes, 64 KiB writes, caller-array mutation, hook order,
blocking delayed completion, and transport failure. Existing lifecycle tests cover
disconnect finalization. The benchmark fixture has JUnit tests for write counts
and bytes. The raw JSON summaries, logs, JFR recordings, and JMH results are
retained locally under `benchmark-results/issue51-20260925/`; that directory is
gitignored and must be preserved separately from this tracked report.
