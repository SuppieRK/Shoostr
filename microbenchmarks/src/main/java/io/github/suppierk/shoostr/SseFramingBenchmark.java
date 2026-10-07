package io.github.suppierk.shoostr;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Callback;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/** Measures peer construction and SSE framing through the public event-stream API. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(
    value = 2,
    jvmArgsAppend = {"-Xms512m", "-Xmx512m", "-XX:+UseG1GC"})
@Threads(1)
public class SseFramingBenchmark {
  @Param({"128", "65536"})
  public int payloadBytes;

  @Param({"single", "multiline"})
  public String lineShape;

  @Param({"data", "metadata"})
  public String eventShape;

  private String payload;
  private SseEvent metadataEvent;
  private Options options;
  private HttpFields.Mutable headers;
  private Request nativeRequest;
  private org.eclipse.jetty.server.Response delegate;
  private int bytesWritten;
  private int checksum;
  private int writes;

  /** Prepares payloads and a callback-completing transport outside timed work. */
  @Setup
  public void setupTrial() {
    options = Options.defaults();
    payload =
        "multiline".equals(lineShape)
            ? "x".repeat(payloadBytes / 2) + "\n" + "x".repeat(payloadBytes - payloadBytes / 2 - 1)
            : "x".repeat(payloadBytes);
    metadataEvent =
        SseEvent.of(payload)
            .withEvent("update")
            .withId("cursor-7")
            .withRetry(Duration.ofSeconds(2));

    nativeRequest =
        (Request)
            Proxy.newProxyInstance(
                SseFramingBenchmark.class.getClassLoader(),
                new Class<?>[] {Request.class},
                (_, method, _) ->
                    switch (method.getName()) {
                      case "getMethod" -> "GET";
                      case "getHeaders" -> HttpFields.EMPTY;
                      case "addHttpStreamWrapper" -> null;
                      default -> throw new UnsupportedOperationException(method.getName());
                    });
    headers = HttpFields.build();
    delegate =
        (org.eclipse.jetty.server.Response)
            Proxy.newProxyInstance(
                SseFramingBenchmark.class.getClassLoader(),
                new Class<?>[] {org.eclipse.jetty.server.Response.class},
                (_, method, arguments) ->
                    switch (method.getName()) {
                      case "getStatus" -> 200;
                      case "getHeaders" -> headers;
                      case "isCommitted" -> writes != 0;
                      case "write" -> {
                        writes++;
                        var content = (ByteBuffer) arguments[1];
                        bytesWritten += content.remaining();
                        if (content.hasRemaining()) {
                          checksum += content.get(content.position()) & 0xff;
                          checksum += content.get(content.limit() - 1) & 0xff;
                        }

                        ((Callback) arguments[2]).succeeded();
                        yield null;
                      }
                      default -> throw new UnsupportedOperationException(method.getName());
                    });
  }

  /**
   * Frames, flushes and completes one data-only or metadata SSE event.
   *
   * @return accepted byte count combined with observed byte values
   * @throws IOException if the transport write fails
   */
  @Benchmark
  public int send() throws IOException {
    var response = freshResponse();
    var stream = response.startEventStream();
    if ("data".equals(eventShape)) {
      stream.send(payload);
    } else {
      stream.send(metadataEvent);
    }

    response.complete();
    return bytesWritten + checksum;
  }

  /**
   * Resets the fixed transport and constructs the pair included in this measured event lifetime.
   *
   * @return fresh response, with no per-invocation JMH setup outside the measured method
   */
  private Response freshResponse() {
    bytesWritten = 0;
    checksum = 0;
    writes = 0;
    headers.clear();
    return io.github.suppierk.shoostr.Request.create(
            nativeRequest, delegate, options, Callback.NOOP)
        .response();
  }

  /**
   * Reports the exact encoded byte count accepted during the last fixture operation.
   *
   * @return data and framing bytes, excluding the fixture's checksum
   */
  int bytesWritten() {
    return bytesWritten;
  }
}
