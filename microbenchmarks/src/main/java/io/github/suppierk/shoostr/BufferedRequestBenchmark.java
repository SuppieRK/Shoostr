package io.github.suppierk.shoostr;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.io.content.ByteBufferContentSource;
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

/** Measures the framework's bounded body read through Jetty's real content-to-input adapter. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(
    value = 2,
    jvmArgsAppend = {"-Xms512m", "-Xmx512m", "-XX:+UseG1GC"})
@Threads(1)
public class BufferedRequestBenchmark {
  private static final int LIMIT = 1_048_576;

  @Param({"0", "1024", "16384", "1048576"})
  public int bytes;

  @Param({"known", "unknown"})
  public String length;

  private byte[] payload;
  private org.eclipse.jetty.server.Request delegate;
  private Response response;
  private MultipartOptions multipartOptions;
  private ByteBufferContentSource source;

  /**
   * Reads a fresh request body, including framework caching and its defensive public copy.
   *
   * @return owned copy of the bytes read through Jetty's Content.Source adapter
   * @throws IOException if the source fails
   */
  @Benchmark
  public byte[] bodyBytes() throws IOException {
    source = new ByteBufferContentSource(ByteBuffer.wrap(payload));
    return new Request(delegate, response, LIMIT, 1000, multipartOptions).bodyBytes();
  }

  /** Builds reusable payload and transport proxies outside the measured operation. */
  @Setup
  public void setup() {
    payload = new byte[bytes];
    Arrays.fill(payload, (byte) 0x5A);
    multipartOptions = MultipartOptions.defaults();
    delegate =
        (org.eclipse.jetty.server.Request)
            Proxy.newProxyInstance(
                BufferedRequestBenchmark.class.getClassLoader(),
                new Class<?>[] {org.eclipse.jetty.server.Request.class},
                (proxy, method, arguments) ->
                    switch (method.getName()) {
                      case "getLength" -> "known".equals(length) ? (long) bytes : -1L;
                      case "read" -> source.read();
                      case "demand" -> {
                        source.demand((Runnable) arguments[0]);
                        yield null;
                      }
                      case "addHttpStreamWrapper" -> null;
                      default -> throw new UnsupportedOperationException(method.getName());
                    });
    var sink =
        (org.eclipse.jetty.server.Response)
            Proxy.newProxyInstance(
                BufferedRequestBenchmark.class.getClassLoader(),
                new Class<?>[] {org.eclipse.jetty.server.Response.class},
                (proxy, method, arguments) -> {
                  throw new UnsupportedOperationException(method.getName());
                });
    response = new Response(sink, Options.defaults(), Callback.NOOP);
  }
}
