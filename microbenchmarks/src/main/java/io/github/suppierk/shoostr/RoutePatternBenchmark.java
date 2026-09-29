package io.github.suppierk.shoostr;

import io.github.suppierk.shoostr.http.HttpMethods;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/** Exercises the compiled routing patterns produced by nested resource groups. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
    value = 3,
    jvmArgsAppend = {"-Xms512m", "-Xmx512m", "-XX:+UseG1GC"})
@Threads(1)
public class RoutePatternBenchmark {
  private static final int QUERY_COUNT = 16384;
  private static final Handler HANDLER = (_, _) -> {};

  @Param({"1", "100", "1000"})
  public int groups;

  @Param({
    "literal",
    "oneParameter",
    "twoParameters",
    "encodedParameter",
    "precedence",
    "fallback",
    "notFound",
    "wrongMethod",
    "catchAllNoRead",
    "catchAllRead",
    "catchAllFallback",
    "catchAllWrongMethod",
    "catchAllMiss",
    "regexNoRead",
    "regexRead",
    "regexFallback",
    "regexWrongMethod",
    "regexMiss"
  })
  public String workload;

  @Param({"reused"})
  public String input;

  RadixRoutes router;
  String[] paths;
  HttpMethods method;
  String[] parameterNames;
  private char[][] characters;
  private boolean fresh;
  private int cursor;

  /**
   * Matches one path and reads the parameters a handler would consume, or collects allowed methods.
   *
   * @param blackhole consumes decoded parameter strings to prevent elimination
   * @return the selected endpoint or the allowed-method set for an unmatched method/path
   */
  @Benchmark
  public Object route(Blackhole blackhole) {
    int index = cursor;
    cursor = (cursor + 1) & (QUERY_COUNT - 1);
    String path = fresh ? new String(characters[index]) : paths[index];
    var endpoint = router.match(path, method);
    if (endpoint == null) {
      return router.allowedMethods(path);
    }

    for (String name : parameterNames) {
      blackhole.consume(endpoint.parameter(path, name));
    }
    return endpoint;
  }

  /**
   * Freezes the complete table and constructs deterministic inputs outside measurement.
   *
   * @throws IllegalArgumentException if the input or workload is unsupported
   */
  @Setup
  public void setup() {
    fresh =
        switch (input) {
          case "fresh" -> true;
          case "reused" -> false;
          default -> throw new IllegalArgumentException("Unknown input: " + input);
        };
    router =
        RadixRoutes.from(
            registrations(groups, workload.startsWith("catchAll"), workload.startsWith("regex")));
    method =
        "wrongMethod".equals(workload)
                || "catchAllWrongMethod".equals(workload)
                || "regexWrongMethod".equals(workload)
            ? HttpMethods.DELETE
            : HttpMethods.GET;
    parameterNames =
        switch (workload) {
          case "oneParameter", "encodedParameter", "fallback" -> new String[] {"id"};
          case "twoParameters" -> new String[] {"id", "itemId"};
          case "catchAllRead" -> new String[] {"tail"};
          case "regexRead" -> new String[] {"numericId"};
          case "literal",
              "precedence",
              "notFound",
              "wrongMethod",
              "catchAllNoRead",
              "catchAllFallback",
              "catchAllWrongMethod",
              "catchAllMiss",
              "regexNoRead",
              "regexFallback",
              "regexWrongMethod",
              "regexMiss" ->
              new String[0];
          default -> throw new IllegalArgumentException("Unknown workload: " + workload);
        };
    paths = new String[QUERY_COUNT];
    characters = new char[QUERY_COUNT][];
    var order = new ArrayList<Integer>(QUERY_COUNT);
    for (int i = 0; i < QUERY_COUNT; i++) {
      order.add(i);
    }
    Collections.shuffle(order, new Random(1729));
    for (int i = 0; i < QUERY_COUNT; i++) {
      int query = order.get(i);
      String prefix = "/api/resources/" + (query % groups) + "/orders";
      String suffix =
          switch (workload) {
            case "literal", "wrongMethod" -> "/latest";
            case "oneParameter" -> "/order-" + query;
            case "twoParameters" -> "/order-" + query + "/items/item-" + query;
            case "encodedParameter" -> "/caf%C3%A9%20" + query;
            case "precedence" -> "/shadowed";
            case "fallback" -> "/fixed/events";
            case "notFound" -> "/order-" + query + "/missing";
            case "catchAllNoRead", "catchAllRead", "catchAllWrongMethod" ->
                "/archive/order-" + query + "/receipt";
            case "catchAllFallback" -> "/fixed/other/" + query;
            case "catchAllMiss" -> "/";
            case "regexNoRead", "regexRead", "regexWrongMethod" -> "/" + query;
            case "regexFallback" -> "/fixed/events";
            case "regexMiss" -> "/";
            default -> throw new IllegalArgumentException("Unknown workload: " + workload);
          };
      paths[i] = prefix + suffix;
      characters[i] = paths[i].toCharArray();
    }
    cursor = 0;
  }

  /**
   * Builds the shared route table plus branches required by the selected workload.
   *
   * @param groups number of resource groups
   * @param catchAll whether to register catch-all routes
   * @param regex whether to register constrained routes
   * @return ordered endpoint registrations
   */
  private static List<RadixRoutes.Endpoint> registrations(
      int groups, boolean catchAll, boolean regex) {
    var endpoints = new ArrayList<RadixRoutes.Endpoint>();
    for (int group = 0; group < groups; group++) {
      String prefix = "/api/resources/" + group + "/orders";
      add(endpoints, HttpMethods.GET, prefix + "/latest");
      add(endpoints, HttpMethods.GET, prefix + "/{id}");
      add(endpoints, HttpMethods.POST, prefix + "/{orderId}");
      add(endpoints, HttpMethods.GET, prefix + "/fixed/details");
      add(endpoints, HttpMethods.GET, prefix + "/{id}/events");
      add(endpoints, HttpMethods.GET, prefix + "/{id}/items/{itemId}");
      add(endpoints, HttpMethods.GET, prefix + "/shadowed");
      add(endpoints, HttpMethods.POST, prefix + "/latest");
      if (catchAll) {
        add(endpoints, HttpMethods.GET, prefix + "/{*tail}");
        add(endpoints, HttpMethods.POST, prefix + "/{*tail}");
      }

      if (regex) {
        add(endpoints, HttpMethods.GET, prefix + "/{numericId:[0-9]+}");
        add(endpoints, HttpMethods.POST, prefix + "/{numericId:[0-9]+}");
      }
    }
    return endpoints;
  }

  /**
   * Appends one endpoint to the fixture's registration list.
   *
   * @param endpoints ordered fixture registrations
   * @param method registered HTTP method
   * @param pattern absolute route pattern
   */
  private static void add(
      List<RadixRoutes.Endpoint> endpoints, HttpMethods method, String pattern) {
    endpoints.add(RadixRoutes.endpoint(method, pattern, HANDLER));
  }
}
