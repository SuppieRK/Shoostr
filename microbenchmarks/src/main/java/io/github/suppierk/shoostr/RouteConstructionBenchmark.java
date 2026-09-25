package io.github.suppierk.shoostr;

import java.util.List;
import java.util.concurrent.TimeUnit;
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
import org.openjdk.jmh.annotations.Warmup;

/** Measures the additional one-time freeze cost after route registration. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
    value = 3,
    jvmArgsAppend = {"-Xms512m", "-Xmx512m", "-XX:+UseG1GC"})
public class RouteConstructionBenchmark {
  @Param({"5", "50", "500", "5000"})
  public int routeCount;

  @Param({"shared"})
  public String shape;

  private List<RadixRoutes.Endpoint> registrations;

  /**
   * Sorts registrations and constructs a complete immutable radix tree.
   *
   * @return the constructed tree, consumed by JMH
   */
  @Benchmark
  public Object freezeRadix() {
    return RadixRoutes.from(registrations);
  }

  /** Creates the already-registered input table outside freeze timing. */
  @Setup
  public void setup() {
    registrations =
        RouteLookupBenchmark.endpoints(RouteLookupBenchmark.registrations(routeCount, shape));
  }
}
