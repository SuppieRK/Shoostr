# Issue 52: SSE framing allocation

## Fresh candidate baseline, before implementation

This baseline measures Shoostr only, at production revision
`080b042ce7caecaf24baf484682e044a9aa7ad3d` (the then-current `main`). The
`issue-52-sse-framing` branch had only the new JMH fixture when measurements began;
no SSE production source had changed. A pre-existing local `gradlew.bat` edit was not
part of the Linux wrapper build or this benchmark. Raw JMH JSON/logs, k6 summaries/logs,
and JFR recordings/views are preserved locally under
`benchmark-results/issue52-20260929/baseline/`. That directory is gitignored; copy it
separately if native artifacts are needed outside this workspace.

The host was WSL2 Linux 6.6.87.2 on an AMD Ryzen 7 9800X3D (8 cores, 16 threads).
Java was Temurin 25.0.2; JMH was 1.37; SSE load used `k6-sse` v1.7.1 with
`xk6-sse` v0.1.11. The same host ran server and load generator, so the transport
timings include shared-host contention and do not establish a capacity ceiling.

### Isolated JMH baseline

`SseFramingBenchmark.send` calls the public `Response.startEventStream()` and
`EventStream.send(String)` for data-only cases, or `send(SseEvent)` for a prebuilt
event with name, ID, and retry. It completes the response and consumes the proxy
transport's byte count and edge-byte checksum. Payload and metadata event creation
occur in trial setup; a fresh response is created in invocation setup. Stream
construction, framing, explicit event flush, and response completion are inside the
timed method. The GC profiler's B/op **also includes invocation setup allocations**,
including the response wrapper and proxy transport activity, even though setup is
outside the timed method body. The fixture SHA-256 was
`ccc65c2f7c3463b77f35a0619f241b713e342de0dd486094f1b6de17fd651e2b`
for all benchmark runs. Final Spotless formatting added only blank lines to the
fixture, changing its source hash without changing the benchmark operations.

JMH used one thread, G1, a fixed 512 MiB heap, two forks, and three one-second
warmup and measurement iterations per fork. Each row has six measurement values;
time errors are JMH's reported intervals. `payloadBytes` counts UTF-8 ASCII input
bytes, including the newline in multiline cases. The single-line data-only path is
the primary optimization target; metadata and multiline rows check spillover and
regressions.

| Event | Payload | Lines | Time, µs/op | JMH error, µs/op | GC allocation, B/op |
| --- | ---: | --- | ---: | ---: | ---: |
| Data-only | 128 B | Single | 0.152 | ±0.013 | 1,488 |
| Data-only | 128 B | Multiline | 0.186 | ±0.016 | 1,744 |
| Data-only | 64 KiB | Single | 24.839 | ±1.315 | 141,264 |
| Data-only | 64 KiB | Multiline | 28.211 | ±1.602 | 206,928 |
| Metadata | 128 B | Single | 0.183 | ±0.012 | 1,648 |
| Metadata | 128 B | Multiline | 0.230 | ±0.003 | 1,904 |
| Metadata | 64 KiB | Single | 25.887 | ±0.340 | 141,696 |
| Metadata | 64 KiB | Multiline | 28.902 | ±1.474 | 207,360 |

Command: `JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-amd64 cmdshape
microbenchmarks/build/install/microbenchmarks/bin/microbenchmarks
SseFramingBenchmark -f 2 -wi 3 -i 3 -w 1s -r 1s -prof gc -foe true -rf json
-rff benchmark-results/issue52-20260929/baseline/jmh.json`.

### Sustained k6 and JFR baseline

Each workload used a fresh candidate server JVM with the generated launcher's
256 MiB G1 heap, a 15-second warmup, then a 180-second k6 measured phase with a
JFR `profile` recording on the server. There were 64 preallocated VUs. Burst sent
16 × 128-byte events at an offered 100 streams/s; paced sent the same events with
server-side pauses at 50 streams/s; slow sent 16 × 64 KiB events with a 50 ms
client callback pause at 10 streams/s. k6 verified event order and complete payloads.
Latency is k6 `http_req_duration` for the whole SSE connection, not per-event
delivery. Achieved rate is the native `iterations.rate`, which can include the
graceful-stop interval.

| Workload | Streams | Achieved streams/s | Achieved events/s | p99 stream duration, ms | Failed checks | Dropped iterations | Young GCs | JFR duration |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Burst | 18,001 | 100.005 | 1,600.082 | 1.129 | 0 | 0 | 3 | 188 s |
| Paced | 9,001 | 49.921 | 798.731 | 303.836 | 0 | 0 | 2 | 188 s |
| Large/slow | 1,801 | 9.961 | 159.370 | 814.470 | 0 | 0 | 39 | 189 s |

JFR's `allocation-by-site` view reports **sampled allocation-pressure shares**,
not exact bytes per event or total allocation rate. `SseEvent.of(String)` appears
at 2.42% in burst and 2.39% in paced. Full sampled stacks tie these objects to
`EventStream.send(String)` and the candidate handler. In the large/slow recording,
`String.encodeUTF8` is 29.19% and `Unsafe.allocateUninitializedArray` is 69.95%,
but the latter includes both the benchmark handler's `index + ":" + payload`
construction and framework `EventStream.writeLines` prefix construction. The
encoding stack flows through `Stream.write(String)` and SSE data framing. These
shares cannot be added or converted into predicted savings; the JMH B/op comparison
isolates the change more directly. JFR includes startup/profiler activity and
has sampling error. The large/slow fixture also performs client-side waiting, so
its p99 is not a server framing latency.

The baseline was complete before production changes began. The after-state JMH,
k6, and JFR comparisons follow.

## After-state result

`EventStream.send(String)` now writes data directly and performs the existing
explicit event flush without constructing a data-only `SseEvent`. For single-line
data, `writeData` emits constant UTF-8 framing bytes, writes the original payload,
and emits the event terminator. The existing multiline writer is unchanged. The
metadata event still uses its validated `SseEvent` and follows the same data writer.

### Isolated JMH after-state

The same JMH fixture, host, JVM, heap, forks and iteration settings were used in
all runs. The intermediate `direct` run measured only the direct-`send(String)`
change; `after-initial` measured the first fast path, and `after` measured the final
line-check order. All raw results are preserved locally. Times are not adjusted
for fixture work and should not be read as HTTP request latency.

| Event | Payload | Lines | Baseline B/op | Direct B/op | Final B/op | Baseline µs/op | Direct µs/op | Final µs/op |
| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Data-only | 128 B | Single | 1,488 | 1,456 | 1,272 | 0.152 | 0.152 | 0.111 |
| Data-only | 128 B | Multiline | 1,744 | 1,712 | 1,712 | 0.186 | 0.183 | 0.186 |
| Data-only | 64 KiB | Single | 141,264 | 141,232 | 75,896 | 24.839 | 25.451 | 4.998 |
| Data-only | 64 KiB | Multiline | 206,928 | 206,896 | 206,896 | 28.211 | 28.380 | 28.636 |
| Metadata | 128 B | Single | 1,648 | 1,648 | 1,464 | 0.183 | 0.190 | 0.152 |
| Metadata | 128 B | Multiline | 1,904 | 1,904 | 1,904 | 0.230 | 0.232 | 0.237 |
| Metadata | 64 KiB | Single | 141,696 | 141,696 | 76,104 | 25.887 | 25.119 | 5.047 |
| Metadata | 64 KiB | Multiline | 207,360 | 207,360 | 207,360 | 28.902 | 28.793 | 29.271 |

The direct slice removed 32 B/op from data-only cases and none from prebuilt
metadata cases. The single-line framing path removed another 184 B/op at 128 B,
and about 64 KiB/op at 64 KiB. It does not remove UTF-8 encoding, stream buffering,
transport fixture allocations, or payload construction outside the measured method.
The first fast-path revision checked for absent `\r` before finding `\n` in
multiline input. Its 64 KiB data-only multiline case regressed to 35.741 µs/op
(±0.988), versus 28.211 µs/op (±1.602) at baseline. Checking `\n` first brought
the final result to 28.636 µs/op (±0.245), with the same 206,896 B/op. Thus the
initial regression was removed without changing the multiline writer; the final
multiline timing shows no clear improvement. The final run used:

`JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-amd64 cmdshape
microbenchmarks/build/install/microbenchmarks/bin/microbenchmarks
SseFramingBenchmark -f 2 -wi 3 -i 3 -w 1s -r 1s -prof gc -foe true -rf json
-rff benchmark-results/issue52-20260929/after/jmh.json`.

### Sustained k6 and JFR after-state

The first fast-path revision completed equivalent candidate-only burst, paced
and large/slow measurements with zero failed checks and dropped iterations. Its
raw recordings are preserved locally under `after-initial`. The final revision
was measured with the same scripts, offered rates, warmup, VUs, server heap and
180-second k6 measured phases, each on a fresh candidate server JVM. Native
JFR recording durations vary by a few seconds because recording starts and stops
around k6. Every final k6 process exited successfully; all checks passed and
there were zero dropped iterations.

| Workload | Phase | Streams | k6 streams/s | k6 p99 stream duration, ms | Young GCs | JFR duration |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Burst | Baseline | 18,001 | 100.005 | 1.129 | 3 | 188 s |
| Burst | Final | 18,001 | 101.540 | 1.158 | 2 | 185 s |
| Paced | Baseline | 9,001 | 49.921 | 303.836 | 2 | 188 s |
| Paced | Final | 9,000 | 50.808 | 304.147 | 1 | 185 s |
| Large/slow | Baseline | 1,801 | 9.961 | 814.470 | 39 | 189 s |
| Large/slow | Final | 1,800 | 10.134 | 814.666 | 26 | 186 s |

The k6-reported stream rates stayed near the offered 100, 50 and 10 streams/s.
Their minor before/after differences are not a capacity measurement. All three
final p99 values were slightly higher than baseline, not lower. Paced and
large/slow durations are dominated by deliberate server or client waiting;
the burst difference is small relative to the shared-host and run-to-run
variation. The first fast-path revision's burst p99 was 1.078 ms and its
large/slow p99 was 814.377 ms, illustrating that variation. No HTTP latency
improvement is established at these rates.

The JFR GC view shows fewer young collections: 3→2 for burst, 2→1 for paced,
and 39→26 for large/slow. The first fast-path revision had the same collection
counts. JFR `allocation-by-site` no longer samples `SseEvent.of(String)` in
burst or paced after-state recordings, where it had occupied 2.42% and 2.39%
of baseline sampled pressure. The large/slow after-state still attributes
28.06% to `String.encodeUTF8` and 71.04% to the generic
`Unsafe.allocateUninitializedArray` site; full sampled stacks show that payload
encoding remains in `Stream.write(String)` and that the generic site includes
benchmark-handler payload concatenation. These percentages are shares of
sampled allocation pressure, not exact per-event bytes or proof that all of a
generic site's allocations belong to the framework. The JMH B/op reduction
is the stronger isolated framing-allocation evidence.

The final result removes the data-only event wrapper and one large prefixed
payload intermediate for common single-line events, while retaining the UTF-8
encoding byte array and bounded stream buffer. It preserves multiline framing
and flush/backpressure behavior. No custom encoder or batching was introduced.

### Contract validation

`ServerSentEventsTest` checks exact UTF-8 response bytes for empty, ASCII,
non-ASCII, supplementary and malformed-surrogate data; multiline CR, LF and
CRLF normalization with trailing empty lines (including CR-only payloads through
both `send(String)` and `send(SseEvent)`); metadata, retry, comments and
heartbeats. Its existing tests also cover fluent writer chaining, delivery
before handler return, disconnect/failure behavior and slow-client backpressure.
The new small-buffer test observes initial, automatic, explicit-event and
terminal flush hooks. It detected a deliberately added extra flush (five hooks
instead of four) before the mutation was removed. A 64 KiB single-line event
is compared byte-for-byte, and a rejected null input is verified not to write
a partial frame. Removing the CR guard deliberately made the CR-only test fail
(88 expected bytes versus 64 observed) before the guard was restored.

The focused SSE suite passed after the final line-check change. The required
`./gradlew clean spotlessApply build` passed all modules (155 tasks; 5m44s).
The one NullAway compile warning in the new null-input test is intentional:
that test deliberately violates the non-null contract to verify the rejection
behavior, so the warning remains visible.
