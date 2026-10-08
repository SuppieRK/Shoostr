package io.github.suppierk.shoostr;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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

  @Param({"0", "13", "1024", "16384"})
  public int characters;

  @Param({"ascii", "unicode"})
  public String shape;

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
    submitted = ByteBuffer.allocate(0);
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
    var inbound =
        (Request)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {Request.class},
                (_, method, _) ->
                    switch (method.getName()) {
                      case "getMethod" -> "GET";
                      case "getHeaders" -> HttpFields.EMPTY;
                      case "addHttpStreamWrapper" -> null;
                      default -> throw new UnsupportedOperationException(method.getName());
                    });
    var headers = HttpFields.build();
    var sink =
        (org.eclipse.jetty.server.Response)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {org.eclipse.jetty.server.Response.class},
                (_, method, arguments) ->
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
    response =
        io.github.suppierk.shoostr.Request.create(inbound, sink, Options.defaults(), Callback.NOOP)
            .response();
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
