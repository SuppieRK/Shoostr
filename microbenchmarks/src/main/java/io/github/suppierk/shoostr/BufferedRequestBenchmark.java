package io.github.suppierk.shoostr;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.content.ByteBufferContentSource;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
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

  @Param({"0", "4", "12"})
  public int headers;

  @Param({"UTF-8"})
  public String charset;

  @Param({"ascii"})
  public String content;

  private byte[] payload;
  private byte[] textPayload;
  private Charset textCharset;
  private int contentLength;
  private Request delegate;
  private Response sink;
  private Options options;
  private ByteBufferContentSource source;

  /** Initializes the parameter defaults also used by direct fixture callers. */
  public BufferedRequestBenchmark() {
    charset = "UTF-8";
    content = "ascii";
  }

  /**
   * Reads a freshly paired request body, including peer construction, caching and its public copy.
   *
   * @return owned copy of the bytes read through Jetty's Content.Source adapter
   * @throws IOException if the source fails
   */
  @Benchmark
  public byte[] bodyBytes() throws IOException {
    return request(payload).bodyBytes();
  }

  /**
   * Reads and decodes a fresh bounded request using an encoded payload prepared during setup.
   *
   * @return decoded text through the public charset body operation
   * @throws IOException if the source fails
   */
  @Benchmark
  public String bodyText() throws IOException {
    return request(textPayload).bodyText(textCharset);
  }

  /**
   * Builds reusable payload and transport proxies outside the measured operation.
   *
   * @throws IllegalStateException if the fixture does not expose the configured header count
   * @throws IllegalArgumentException if the charset, text shape or encoded size is unsupported
   */
  @Setup
  public void setup() {
    payload = new byte[bytes];
    Arrays.fill(payload, (byte) 0x5A);
    textCharset =
        switch (charset) {
          case "UTF-8" -> StandardCharsets.UTF_8;
          case "ISO-8859-1" -> StandardCharsets.ISO_8859_1;
          case "UTF-16BE" -> StandardCharsets.UTF_16BE;
          default -> throw new IllegalArgumentException("Unknown benchmark charset: " + charset);
        };
    var unit =
        switch (content) {
          case "ascii" -> "Z";
          case "unicode" -> "é";
          default -> throw new IllegalArgumentException("Unknown benchmark text: " + content);
        };
    int unitBytes = unit.getBytes(textCharset).length;
    if (bytes % unitBytes != 0) {
      throw new IllegalArgumentException("Body size must contain complete encoded text units");
    }

    textPayload = unit.repeat(bytes / unitBytes).getBytes(textCharset);
    contentLength = payload.length;
    var defaults = Options.defaults();
    options =
        new Options(
            LIMIT,
            defaults.maxResponseBytes(),
            defaults.streamBufferBytes(),
            1000,
            MultipartOptions.defaults());
    var fields = HttpFields.build();
    for (int index = 0; index < headers; index++) {
      fields.add("X-Benchmark-" + index, "value-" + index);
    }

    var inboundHeaders = fields.asImmutable();
    delegate =
        (Request)
            Proxy.newProxyInstance(
                BufferedRequestBenchmark.class.getClassLoader(),
                new Class<?>[] {Request.class},
                (ignored, method, arguments) ->
                    switch (method.getName()) {
                      case "getHeaders" -> inboundHeaders;
                      case "getMethod" -> "GET";
                      case "getLength" -> "known".equals(length) ? (long) contentLength : -1L;
                      case "read" -> source.read();
                      case "demand" -> {
                        source.demand((Runnable) arguments[0]);
                        yield null;
                      }
                      case "addHttpStreamWrapper" -> null;
                      default -> throw new UnsupportedOperationException(method.getName());
                    });
    sink =
        (Response)
            Proxy.newProxyInstance(
                BufferedRequestBenchmark.class.getClassLoader(),
                new Class<?>[] {Response.class},
                (ignored2, method, ignored3) -> {
                  throw new UnsupportedOperationException(method.getName());
                });

    if (io.github.suppierk.shoostr.Request.create(delegate, sink, options, Callback.NOOP)
            .headerMap()
            .size()
        != headers) {
      throw new IllegalStateException("Unexpected header fixture size");
    }
  }

  /**
   * Pairs one fresh content source with its exact declared length and framework wrapper.
   *
   * @param body prepared encoded bytes
   * @return fresh bounded request
   */
  private io.github.suppierk.shoostr.Request request(byte[] body) {
    contentLength = body.length;
    source = new ByteBufferContentSource(ByteBuffer.wrap(body));
    return io.github.suppierk.shoostr.Request.create(delegate, sink, options, Callback.NOOP);
  }
}
