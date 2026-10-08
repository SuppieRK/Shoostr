package io.github.suppierk.shoostr;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpVersion;
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

/** Measures native HTTP parsing with different connection-local header cache capacities. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
    value = 2,
    jvmArgsAppend = {"-Xms256m", "-Xmx256m", "-XX:+UseG1GC"})
@Threads(1)
public class HeaderCacheBenchmark implements HttpParser.RequestHandler {
  @Param({"0", "128", "256", "512", "1024"})
  public int capacity;

  @Param({
    "repeated",
    "varying",
    "large",
    "cookie64",
    "cookie192",
    "cookie384",
    "cookie768",
    "cookie1536"
  })
  public String headers;

  private final HttpParser parser;
  private final ByteBuffer[] requests;
  private int next;
  private int fields;
  private int messages;

  /** Initializes parser and wire storage before JMH configures the trial parameters. */
  public HeaderCacheBenchmark() {
    headers = "repeated";
    requests = new ByteBuffer[64];
    parser = new HttpParser(this, 8192);
  }

  /**
   * Prepares wire requests outside timing, retaining normal parser compliance checks.
   *
   * @throws IllegalArgumentException if the header fixture is unknown
   */
  @Setup
  public void setup() {
    int cookieBytes =
        switch (headers) {
          case "repeated", "varying", "large" -> 0;
          case "cookie64" -> 64;
          case "cookie192" -> 192;
          case "cookie384" -> 384;
          case "cookie768" -> 768;
          case "cookie1536" -> 1536;
          default -> throw new IllegalArgumentException("Unknown header fixture");
        };

    for (int index = 0; index < requests.length; index++) {
      var authorization = "varying".equals(headers) ? Integer.toString(index) : "fixed";
      var custom = "large".equals(headers) ? "x".repeat(2048) : "fixture";
      var cookie = cookieBytes == 0 ? "" : "Cookie: session=" + "x".repeat(cookieBytes) + "\r\n";
      requests[index] =
          ByteBuffer.wrap(
              ("GET /stream HTTP/1.1\r\n"
                      + "Host: localhost:18080\r\nUser-Agent: k6/2.2.0\r\n"
                      + "Accept: */*\r\nAccept-Encoding: gzip\r\nAuthorization: Bearer "
                      + authorization
                      + "\r\nX-Custom: "
                      + custom
                      + "\r\n"
                      + cookie
                      + "\r\n")
                  .getBytes(StandardCharsets.US_ASCII));
    }
    parser.setHeaderCacheSize(capacity);
  }

  /**
   * Parses a request on a persistent connection.
   *
   * @return number of delivered headers and completed messages
   */
  @Benchmark
  public int reusedConnection() {
    parser.reset();
    return parse(parser);
  }

  /**
   * Parses two requests on a fresh connection, including cache creation.
   *
   * @return number of delivered headers and completed messages for the second request
   */
  @Benchmark
  public int newConnection() {
    var fresh = newParser();
    parse(fresh);
    fresh.reset();
    return parse(fresh);
  }

  /**
   * Creates a connection-local parser with the trial's cache capacity.
   *
   * @return configured native parser
   */
  private HttpParser newParser() {
    var created = new HttpParser(this, 8192);
    created.setHeaderCacheSize(capacity);
    return created;
  }

  /**
   * Delivers one complete wire request to the selected native parser.
   *
   * @param selected parser for this connection
   * @return delivered headers plus completed messages
   * @throws IllegalStateException if parsing leaves incomplete or unconsumed input
   */
  private int parse(HttpParser selected) {
    fields = 0;
    messages = 0;
    var request = requests[next++ & 63];
    request.clear();
    selected.parseNext(request);
    if (request.hasRemaining() || !selected.isComplete()) {
      throw new IllegalStateException("Parser did not consume the complete fixture");
    }

    return fields + messages;
  }

  /** {@inheritDoc} */
  @Override
  public void startRequest(String method, String uri, HttpVersion version) {}

  /** {@inheritDoc} */
  @Override
  public boolean content(ByteBuffer content) {
    return false;
  }

  /** {@inheritDoc} */
  @Override
  public boolean headerComplete() {
    return false;
  }

  /** {@inheritDoc} */
  @Override
  public boolean contentComplete() {
    return false;
  }

  /** {@inheritDoc} */
  @Override
  public boolean messageComplete() {
    messages++;
    return true;
  }

  /** {@inheritDoc} */
  @Override
  public void parsedHeader(HttpField field) {
    fields++;
  }

  /**
   * Rejects truncated fixture input.
   *
   * @throws IllegalStateException whenever the parser encounters an early EOF
   */
  @Override
  public void earlyEOF() {
    throw new IllegalStateException("Unexpected EOF");
  }

  /**
   * Rejects malformed fixture input.
   *
   * @param failure native parser rejection
   * @throws IllegalStateException whenever the parser rejects the request
   */
  @Override
  public void badMessage(HttpException failure) {
    throw new IllegalStateException(failure.getReason());
  }
}
