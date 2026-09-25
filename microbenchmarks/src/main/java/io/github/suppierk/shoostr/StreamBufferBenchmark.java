package io.github.suppierk.shoostr;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.util.Callback;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/** Measures one candidate response stream's construction and bounded write lifetimes. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(
    value = 2,
    jvmArgsAppend = {"-Xms512m", "-Xmx512m", "-XX:+UseG1GC"})
@Threads(1)
public class StreamBufferBenchmark {
  private static final String CONTENT_TYPE = "application/octet-stream";
  private static final int CHUNKS = 16;
  private static final int GROWTH_BYTES = 300;
  private byte[] small;
  private byte[] kilobyte;
  private byte[] large;
  private byte[] singleByte;
  private Options growthOptions;
  private org.eclipse.jetty.server.Response delegate;
  private Response response;
  private Response growthResponse;
  private int bytesWritten;
  private int writes;

  /** Prepares immutable payloads and a callback-completing transport outside measured work. */
  @Setup(Level.Trial)
  public void setupTrial() {
    small = new byte[128];
    kilobyte = new byte[1024];
    large = new byte[65536];
    singleByte = new byte[1];
    var defaults = Options.defaults();
    growthOptions =
        new Options(
            defaults.host(),
            defaults.port(),
            defaults.maxRequestBytes(),
            defaults.maxResponseBytes(),
            GROWTH_BYTES,
            defaults.idleTimeoutMillis());
    var headers = HttpFields.build();
    delegate =
        (org.eclipse.jetty.server.Response)
            Proxy.newProxyInstance(
                StreamBufferBenchmark.class.getClassLoader(),
                new Class<?>[] {org.eclipse.jetty.server.Response.class},
                (proxy, method, arguments) ->
                    switch (method.getName()) {
                      case "getStatus" -> 200;
                      case "getHeaders" -> headers;
                      case "isCommitted" -> writes != 0;
                      case "write" -> {
                        writes++;
                        bytesWritten += ((ByteBuffer) arguments[1]).remaining();
                        ((Callback) arguments[2]).succeeded();
                        yield null;
                      }
                      default -> throw new UnsupportedOperationException(method.getName());
                    });
  }

  /** Creates a fresh response before each measured stream lifetime. */
  @Setup(Level.Invocation)
  public void setupInvocation() {
    bytesWritten = 0;
    writes = 0;
    response = new Response(delegate, Options.defaults(), Callback.NOOP);
    growthResponse = new Response(delegate, growthOptions, Callback.NOOP);
  }

  /**
   * Starts a stream without body bytes.
   *
   * @return the constructed stream so JMH observes it
   * @throws IOException if the transport fails
   */
  @Benchmark
  public Response.Stream construct() throws IOException {
    return response.startStream(CONTENT_TYPE);
  }

  /**
   * Writes and explicitly flushes one small event-sized payload.
   *
   * @return total bytes accepted by the transport
   * @throws IOException if the transport fails
   */
  @Benchmark
  public int smallExplicitFlush() throws IOException {
    var stream = response.startStream(CONTENT_TYPE);
    stream.write(small);
    stream.flush();
    response.complete();
    return bytesWritten;
  }

  /**
   * Writes sixteen one-kilobyte chunks with an explicit flush after each.
   *
   * @return total bytes accepted by the transport
   * @throws IOException if the transport fails
   */
  @Benchmark
  public int repeatedExplicitFlush() throws IOException {
    var stream = response.startStream(CONTENT_TYPE);
    for (int index = 0; index < CHUNKS; index++) {
      stream.write(kilobyte);
      stream.flush();
    }

    response.complete();
    return bytesWritten;
  }

  /**
   * Writes one 64 KiB payload through automatic flush boundaries.
   *
   * @return total bytes accepted by the transport
   * @throws IOException if the transport fails
   */
  @Benchmark
  public int largeWrite() throws IOException {
    var stream = response.startStream(CONTENT_TYPE);
    stream.write(large);
    response.complete();
    return bytesWritten;
  }

  /**
   * Reaches a 300-byte logical flush boundary with one-byte writes, forcing physical growth.
   *
   * @return total bytes accepted by the transport
   * @throws IOException if the transport fails
   */
  @Benchmark
  public int incrementalGrowth() throws IOException {
    var stream = growthResponse.startStream(CONTENT_TYPE);
    for (int index = 0; index < GROWTH_BYTES; index++) {
      stream.write(singleByte);
    }

    growthResponse.complete();
    return bytesWritten;
  }

  /**
   * Reports how many transport writes occurred in the last measured lifetime.
   *
   * @return initial, body and final transport write count
   */
  int writes() {
    return writes;
  }
}
