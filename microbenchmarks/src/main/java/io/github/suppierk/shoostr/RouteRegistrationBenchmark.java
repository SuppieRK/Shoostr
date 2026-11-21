package io.github.suppierk.shoostr;

import io.github.suppierk.shoostr.http.HttpMethods;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.websocket.api.Session;
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

/** Measures endpoint creation and enrichment without tree freezing or request handling. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
    value = 3,
    jvmArgsAppend = {"-Xms512m", "-Xmx512m", "-XX:+UseG1GC"})
@Threads(1)
public class RouteRegistrationBenchmark {
  static final Handler HANDLER = (request, response) -> {};
  static final WebSocket.Factory FACTORY =
      (request, response, runtime) -> new Session.Listener() {};

  /** Selects the parser work performed by each endpoint factory. */
  @Param({"literal", "constrained"})
  public String pattern;

  String path;
  RadixRoutes.Endpoint endpoint;
  EndpointBehavior behavior;
  ExecutorService executor;

  /** Creates a fixture for JMH. */
  public RouteRegistrationBenchmark() {}

  /**
   * Creates one ordinary endpoint, including path parsing and metadata validation.
   *
   * @return newly created endpoint
   */
  @Benchmark
  public Object http() {
    return RadixRoutes.endpoint(HttpMethods.GET, path, HANDLER);
  }

  /**
   * Creates one upgrade endpoint, including path parsing and metadata validation.
   *
   * @return newly created WebSocket endpoint
   */
  @Benchmark
  public Object websocket() {
    return RadixRoutes.websocketEndpoint(path, HANDLER, FACTORY);
  }

  /**
   * Attaches prepared behavior to a prebuilt endpoint.
   *
   * @return enriched endpoint
   */
  @Benchmark
  public Object withBehavior() {
    return endpoint.withBehavior(behavior);
  }

  /**
   * Attaches a borrowed executor to a prebuilt endpoint without submitting work.
   *
   * @return enriched endpoint
   */
  @Benchmark
  public Object withExecutor() {
    return endpoint.withExecutor(executor);
  }

  /**
   * Prepares input and acquires the unused executor after all other fixture initialization.
   *
   * @throws IllegalArgumentException if the selected pattern is unsupported
   */
  @Setup
  public void setup() {
    path =
        switch (pattern) {
          case "literal" -> "/items/latest";
          case "constrained" -> "/items/{id:[0-9]+}/events/{*tail}";
          default -> throw new IllegalArgumentException("Unknown pattern: " + pattern);
        };
    endpoint = RadixRoutes.endpoint(HttpMethods.GET, path, HANDLER);
    behavior = behavior();

    executor = Executors.newVirtualThreadPerTaskExecutor();
  }

  /**
   * Creates reusable local behavior outside measurement.
   *
   * @return prepared authentication behavior
   */
  private static EndpointBehavior behavior() {
    return new EndpointBehavior(
        HANDLER, null, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(), Map.of(), Map.of(), List.of());
  }

  /** Closes the fixture-owned executor after measurement. */
  @TearDown
  public void tearDown() {
    executor.close();
  }
}
