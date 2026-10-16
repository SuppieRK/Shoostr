package io.github.suppierk.shoostr;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
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

/**
 * Measures paired response-stream construction and bounded write lifetimes on a fixed transport.
 */
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
  private static final int FIXED_TOTAL_BYTES = 16384;

  private byte[] small;
  private byte[] kilobyte;
  private byte[] large;
  private byte[] singleByte;
  private Options options;
  private Options growthOptions;
  private HttpFields.Mutable headers;
  private org.eclipse.jetty.server.Response delegate;
  private Request nativeRequest;
  private ByteBuffer submitted;
  private int bytesWritten;
  private int writes;

  /** Initializes the view retained by the benchmark transport fixture. */
  public StreamBufferBenchmark() {
    submitted = ByteBuffer.allocate(0);
  }

  /** Prepares immutable payloads and a callback-completing transport outside measured work. */
  @Setup
  public void setupTrial() {
    small = new byte[128];
    kilobyte = new byte[1024];
    large = new byte[65536];
    singleByte = new byte[1];
    var defaults = Options.defaults();
    options = defaults;
    growthOptions =
        new Options(
            defaults.host(),
            defaults.port(),
            defaults.maxRequestBytes(),
            defaults.maxResponseBytes(),
            GROWTH_BYTES,
            defaults.idleTimeoutMillis());
    var inbound =
        (Request)
            Proxy.newProxyInstance(
                StreamBufferBenchmark.class.getClassLoader(),
                new Class<?>[] {Request.class},
                (_, method, _) ->
                    switch (method.getName()) {
                      case "getMethod" -> "GET";
                      case "getHeaders" -> HttpFields.EMPTY;
                      case "addHttpStreamWrapper" -> null;
                      default -> throw new UnsupportedOperationException(method.getName());
                    });
    nativeRequest = inbound;
    headers = HttpFields.build();
    delegate =
        (org.eclipse.jetty.server.Response)
            Proxy.newProxyInstance(
                StreamBufferBenchmark.class.getClassLoader(),
                new Class<?>[] {org.eclipse.jetty.server.Response.class},
                (_, method, arguments) ->
                    switch (method.getName()) {
                      case "getStatus" -> 200;
                      case "getHeaders" -> headers;
                      case "isCommitted" -> writes != 0;
                      case "write" -> {
                        writes++;
                        submitted = (ByteBuffer) arguments[1];
                        bytesWritten += submitted.remaining();
                        ((Callback) arguments[2]).succeeded();
                        yield null;
                      }
                      default -> throw new UnsupportedOperationException(method.getName());
                    });
  }

  /**
   * Starts a stream without body bytes.
   *
   * @return the constructed stream so JMH observes it
   * @throws IOException if the transport fails
   */
  @Benchmark
  public Response.Stream construct() throws IOException {
    return freshResponse(options).startStream(CONTENT_TYPE);
  }

  /**
   * Writes and explicitly flushes one small event-sized payload.
   *
   * @return total bytes accepted by the transport
   * @throws IOException if the transport fails
   */
  @Benchmark
  public int smallExplicitFlush() throws IOException {
    var response = freshResponse(options);
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
    var response = freshResponse(options);
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
    var response = freshResponse(options);
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
    var response = freshResponse(growthOptions);
    var stream = response.startStream(CONTENT_TYPE);
    for (int index = 0; index < GROWTH_BYTES; index++) {
      stream.write(singleByte);
    }

    response.complete();
    return bytesWritten;
  }

  /**
   * Writes one fixed 16 KiB response while varying chunking, buffer capacity and flush strategy.
   *
   * @param fixedTotal configured chunking, buffer capacity and flush strategy
   * @return total bytes accepted by the transport
   * @throws IOException if the transport fails
   */
  @Benchmark
  public int fixedTotalWriteMatrix(FixedTotalWriteState fixedTotal) throws IOException {
    var response = freshResponse(fixedTotal.options);
    var stream = response.startStream(CONTENT_TYPE);
    for (int index = 0; index < FIXED_TOTAL_BYTES / fixedTotal.chunkBytes; index++) {
      stream.write(fixedTotal.chunk);
      if (fixedTotal.explicitFlush) {
        stream.flush();
      }
    }

    response.complete();
    return bytesWritten;
  }

  /**
   * Resets the fixed transport and constructs exactly the peer pair used by this operation.
   *
   * @param selected configured application limits for this operation
   * @return fresh response whose construction is included in both timing and allocation
   */
  private Response freshResponse(Options selected) {
    bytesWritten = 0;
    writes = 0;
    headers.clear();
    return io.github.suppierk.shoostr.Request.create(
            nativeRequest, delegate, selected, Callback.NOOP)
        .response();
  }

  /**
   * Reports how many transport writes occurred in the last measured lifetime.
   *
   * @return initial, body and final transport write count
   */
  int writes() {
    return writes;
  }

  /**
   * Exposes the retained transport view to fixture tests.
   *
   * @return the most recently submitted view
   */
  ByteBuffer submitted() {
    return submitted;
  }

  /** Holds the parameters used only by {@link #fixedTotalWriteMatrix(FixedTotalWriteState)}. */
  @State(Scope.Thread)
  public static class FixedTotalWriteState {
    @Param({"128", "1024", "16384"})
    private int chunkBytes;

    @Param({"1024", "8192", "16384"})
    private int bufferBytes;

    @Param({"true", "false"})
    private boolean explicitFlush;

    private byte[] chunk;
    private Options options;

    /** Initializes the direct-fixture defaults before JMH injects a matrix case. */
    public FixedTotalWriteState() {
      chunkBytes = 1024;
      bufferBytes = 8192;
      explicitFlush = true;
    }

    /**
     * Initializes one matrix case outside measured work.
     *
     * @throws IllegalStateException if the configured chunk or buffer capacity is invalid
     */
    @Setup
    public void setupTrial() {
      if (chunkBytes <= 0 || bufferBytes <= 0 || FIXED_TOTAL_BYTES % chunkBytes != 0) {
        throw new IllegalStateException("Invalid fixed-total stream matrix parameters");
      }

      var defaults = Options.defaults();
      chunk = new byte[chunkBytes];
      options =
          new Options(
              defaults.host(),
              defaults.port(),
              defaults.maxRequestBytes(),
              defaults.maxResponseBytes(),
              bufferBytes,
              defaults.idleTimeoutMillis());
    }

    /**
     * Configures one matrix case for a direct fixture test.
     *
     * @param selectedChunkBytes bytes in each application write
     * @param selectedBufferBytes maximum buffered response bytes before automatic flush
     * @param selectedExplicitFlush whether every application write explicitly flushes
     */
    void configure(int selectedChunkBytes, int selectedBufferBytes, boolean selectedExplicitFlush) {
      chunkBytes = selectedChunkBytes;
      bufferBytes = selectedBufferBytes;
      explicitFlush = selectedExplicitFlush;
    }
  }
}
