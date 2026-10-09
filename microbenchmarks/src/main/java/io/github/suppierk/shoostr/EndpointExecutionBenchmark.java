package io.github.suppierk.shoostr;

import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.server.LocalConnector;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Complete local HTTP exchanges: dispatch, useful work, response and cleanup, not submission only.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(
    value = 3,
    jvmArgsAppend = {"-Xms256m", "-Xmx256m", "-XX:+UseG1GC"})
@Threads(1)
public class EndpointExecutionBenchmark {
  @Param({"A", "B", "C", "D", "inactive"})
  public String model;

  @Param({"tiny", "routed", "cpu"})
  public String workload;

  private Shoostr app;
  private LocalConnector connector;
  private String request;

  /**
   * Completes one exchange without socket/client latency.
   *
   * @return serialized response consumed by JMH
   * @throws Exception if processing fails
   */
  @Benchmark
  public String completeExchange() throws Exception {
    return connector.getResponse(request);
  }

  /**
   * Prepares and validates the fixture outside timing.
   *
   * @throws Exception if startup or parity validation fails
   * @throws IllegalStateException if the fixture response does not match the expected output
   */
  @Setup
  public void setup() throws Exception {
    app =
        ThreadingFixture.create(
            model,
            "routed".equals(workload) ? "tiny" : workload,
            0,
            Runtime.getRuntime().availableProcessors());
    app.modifyServer(
        server -> {
          connector = new LocalConnector(server);
          server.addConnector(connector);
        });
    app.start();
    var path = "routed".equals(workload) ? "/api/users/42" : "/" + workload;
    request = "GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
    var expected =
        switch (workload) {
          case "tiny" -> "Hello, World!";
          case "routed" -> "42";
          default -> "baa7a6d36ffa957552df230235c2d51d735f28d49c58a5f3438a3a973a25a37d";
        };
    var response = connector.getResponse(request);
    if (!response.startsWith("HTTP/1.1 200") || !response.endsWith(expected)) {
      throw new IllegalStateException("Fixture parity failed: " + response);
    }
  }

  /**
   * Closes all owned resources after the trial.
   *
   * @throws Exception if cleanup fails
   */
  @TearDown
  public void close() throws Exception {
    app.close();
  }
}
