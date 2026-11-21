package io.github.suppierk.shoostr;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpFields.Mutable;
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

/** Measures public finite-body staging without peer construction, completion or network output. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(
    value = 2,
    jvmArgsAppend = {"-Xms512m", "-Xmx512m", "-XX:+UseG1GC"})
@Threads(1)
public class FiniteResponseBenchmark {
  private static final String CONTENT_TYPE = "text/plain; charset=utf-8";

  /** Number of characters in the response body. */
  @Param({"0", "13", "1024", "16384"})
  public int characters;

  /** Character repertoire used to build the response. */
  @Param({"ascii", "unicode"})
  public String shape;

  /** Response API used to send the prepared body. */
  @Param({"bytes", "text"})
  public String representation;

  private String text;
  private byte[] payload;
  private Response response;
  private boolean textMode;
  private boolean written;
  private ByteBuffer submitted;

  /** Initializes the fixture's empty transport capture before trial setup. */
  public FiniteResponseBenchmark() {
    this.submitted = ByteBuffer.allocate(0);
  }

  /**
   * Replaces one open response's staged body using the real public byte or text operation.
   *
   * @return the response retaining the staged bytes
   */
  @Benchmark
  public Response stage() {
    return textMode ? response.text(text) : response.body(CONTENT_TYPE, payload);
  }

  /**
   * Prepares equivalent UTF-8 payloads and an open response outside the measured operation.
   *
   * @throws IllegalArgumentException if a fixture parameter is unknown
   */
  @Setup
  public void setup() {
    textMode =
        switch (representation) {
          case "text" -> true;
          case "bytes" -> false;
          default ->
              throw new IllegalArgumentException("Unknown representation: " + representation);
        };
    text =
        switch (shape) {
          case "ascii" -> "x".repeat(characters);
          case "unicode" -> "é".repeat(characters);
          default -> throw new IllegalArgumentException("Unknown shape: " + shape);
        };
    payload = text.getBytes(StandardCharsets.UTF_8);
    var inbound = nativeRequest();
    var headers = HttpFields.build();
    var sink = responseSink(headers);
    response =
        io.github.suppierk.shoostr.Request.create(inbound, sink, new Options(), Callback.NOOP)
            .response();
  }

  /**
   * Creates the native request fixture used by the benchmark.
   *
   * @return native request fixture
   */
  private Request nativeRequest() {
    return (Request)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {Request.class},
            (ignored, method, ignoredObjectValues) ->
                switch (method.getName()) {
                  case "getMethod" -> "GET";
                  case "getHeaders" -> HttpFields.EMPTY;
                  case "consumeAvailable" -> true;
                  case "addHttpStreamWrapper" -> null;
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  /**
   * Creates the native response sink used by the benchmark.
   *
   * @param headers native mutable response headers
   * @return native response fixture
   */
  private org.eclipse.jetty.server.Response responseSink(Mutable headers) {
    return (org.eclipse.jetty.server.Response)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {org.eclipse.jetty.server.Response.class},
            (ignoredObject, method, arguments) ->
                switch (method.getName()) {
                  case "getStatus" -> 200;
                  case "getHeaders" -> headers;
                  case "isCommitted" -> written;
                  case "write" -> {
                    written = true;
                    submitted = (ByteBuffer) arguments[1];

                    ((Callback) arguments[2]).succeeded();
                    yield null;
                  }
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  /**
   * Completes the staged response solely for fixture correctness checks, outside JMH measurement.
   *
   * @return the exact bytes accepted by the fixture transport
   * @throws IOException if completion fails
   */
  byte[] complete() throws IOException {
    response.complete();

    var bytes = new byte[submitted.remaining()];
    submitted.duplicate().get(bytes);

    return bytes;
  }
}
