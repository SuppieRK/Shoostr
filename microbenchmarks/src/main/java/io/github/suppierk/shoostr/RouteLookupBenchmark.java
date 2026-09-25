package io.github.suppierk.shoostr;

import io.github.suppierk.shoostr.http.HttpMethods;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

/** Measures exact path and method resolution against a table constructed once per trial. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
    value = 3,
    jvmArgsAppend = {"-Xms512m", "-Xmx512m", "-XX:+UseG1GC"})
@Threads(1)
public class RouteLookupBenchmark {
  static final Object NOT_FOUND = new Object();
  static final Object METHOD_NOT_ALLOWED = new Object();
  private static final int QUERY_COUNT = 16384;

  @Param({"5", "50", "500", "5000"})
  public int routeCount;

  @Param({"shared"})
  public String shape;

  @Param({"hit"})
  public String outcome;

  @Param({"uniform"})
  public String distribution;

  private Map<String, Map<HttpMethods, Handler>> map;
  private RadixRoutes radix;
  private String[] paths;
  private char[][] characters;
  private HttpMethods[] methods;
  private int cursor;

  /**
   * Resolves a reused string with a cached hash through the current map layout.
   *
   * @return handler identity or a distinct 404/405 sentinel
   */
  @Benchmark
  public Object mapReused() {
    int index = next();
    return resolve(map.get(paths[index]), methods[index]);
  }

  /**
   * Resolves a reused string through the production radix implementation.
   *
   * @return handler identity or a distinct 404/405 sentinel
   */
  @Benchmark
  public Object radixReused() {
    int index = next();
    return resolveRadix(paths[index], methods[index]);
  }

  /**
   * Constructs a fresh path string, then resolves it through the current map layout.
   *
   * @return handler identity or a distinct 404/405 sentinel
   */
  @Benchmark
  public Object mapFresh() {
    int index = next();
    return resolve(map.get(new String(characters[index])), methods[index]);
  }

  /**
   * Constructs a fresh path string, then resolves it through the radix implementation.
   *
   * @return handler identity or a distinct 404/405 sentinel
   */
  @Benchmark
  public Object radixFresh() {
    int index = next();
    return resolveRadix(new String(characters[index]), methods[index]);
  }

  /**
   * Builds all indexes and deterministic queries outside the measured operation.
   *
   * @throws IllegalArgumentException if a workload parameter is unknown
   */
  @Setup
  public void setup() {
    map = registrations(routeCount, shape);
    radix = RadixRoutes.from(endpoints(map));
    paths = new String[QUERY_COUNT];
    characters = new char[QUERY_COUNT][];
    methods = new HttpMethods[QUERY_COUNT];
    var order = new ArrayList<Integer>(QUERY_COUNT);
    for (int i = 0; i < QUERY_COUNT; i++) {
      order.add(i);
    }
    Collections.shuffle(order, new Random(1729));
    var traffic = new Random(2718);
    for (int i = 0; i < QUERY_COUNT; i++) {
      int query = order.get(i);
      int route =
          switch (distribution) {
            case "uniform" -> query % routeCount;
            case "hot" ->
                traffic.nextInt(10) == 0
                    ? traffic.nextInt(routeCount)
                    : traffic.nextInt(Math.min(4, routeCount));
            default -> throw new IllegalArgumentException("Unknown distribution: " + distribution);
          };
      String path = path(route, shape);
      String selectedOutcome = outcome;
      if ("mixed".equals(outcome)) {
        selectedOutcome =
            switch (query % 10) {
              case 7 -> "earlyMiss";
              case 8 -> "lateMiss";
              case 9 -> "wrongMethod";
              default -> "hit";
            };
      }

      HttpMethods method = query % 2 == 0 ? HttpMethods.GET : HttpMethods.POST;
      switch (selectedOutcome) {
        case "hit" -> {}
        case "earlyMiss" -> path = "/!" + path.substring(2);
        case "lateMiss" -> path += "!";
        case "wrongMethod" -> method = HttpMethods.DELETE;
        default -> throw new IllegalArgumentException("Unknown outcome: " + selectedOutcome);
      }
      characters[i] = path.toCharArray();
      // Independent from registered keys: no String identity shortcut in the baseline.
      paths[i] = new String(characters[i]);
      paths[i].hashCode();
      methods[i] = method;
    }
    cursor = 0;
  }

  /**
   * Creates a literal-route table with distinct GET and POST handler identities per path.
   *
   * @param count number of paths
   * @param shape path-prefix distribution
   * @return mutable fixture registrations shared by both index builders
   */
  static Map<String, Map<HttpMethods, Handler>> registrations(int count, String shape) {
    var routes = new HashMap<String, Map<HttpMethods, Handler>>();
    for (int i = 0; i < count; i++) {
      String path = path(i, shape);
      var endpoints = new HashMap<HttpMethods, Handler>();
      endpoints.put(HttpMethods.GET, handler());
      endpoints.put(HttpMethods.POST, handler());
      routes.put(path, endpoints);
    }
    return routes;
  }

  /**
   * Converts the map fixture to radix endpoint metadata while retaining handler identities.
   *
   * @param registrations literal paths and method-specific handlers
   * @return endpoint list ready for one-time tree construction
   */
  static List<RadixRoutes.Endpoint> endpoints(
      Map<String, Map<HttpMethods, Handler>> registrations) {
    var endpoints = new ArrayList<RadixRoutes.Endpoint>();
    registrations.forEach(
        (path, methods) ->
            methods.forEach(
                (method, handler) -> endpoints.add(RadixRoutes.endpoint(method, path, handler))));
    return endpoints;
  }

  /**
   * Includes the allowed-method lookup on misses so radix and map results have equivalent
   * semantics.
   *
   * @param path query path
   * @param method query method
   * @return handler identity or the corresponding 404/405 sentinel
   */
  private Object resolveRadix(String path, HttpMethods method) {
    var endpoint = radix.match(path, method);
    if (endpoint != null) {
      return endpoint.handler();
    }

    return radix.allowedMethods(path).isEmpty() ? NOT_FOUND : METHOD_NOT_ALLOWED;
  }

  /**
   * Allocates a distinct handler without capturing route keys in the retained object graph.
   *
   * @return no-op handler used only as a lookup identity
   */
  private static Handler handler() {
    // Distinct handler identities without retaining route keys in captured lambda fields.
    return new Handler() {
      /**
       * Leaves the benchmark response untouched because only handler identity is measured.
       *
       * @param request unused request
       * @param response unused response
       */
      @Override
      public void handle(Request request, Response response) {}
    };
  }

  /**
   * Advances through the power-of-two query array with a wrapping bit mask.
   *
   * @return current query index
   */
  private int next() {
    int index = cursor;
    cursor = (cursor + 1) & (QUERY_COUNT - 1);
    return index;
  }

  /**
   * Resolves a map endpoint using the same success and failure identities as radix lookup.
   *
   * @param endpoints methods at the requested path, or null on a path miss
   * @param method query method
   * @return handler identity or the corresponding 404/405 sentinel
   */
  private static Object resolve(Map<HttpMethods, Handler> endpoints, HttpMethods method) {
    if (endpoints == null) {
      return NOT_FOUND;
    }

    var handler = endpoints.get(method);
    return handler == null ? METHOD_NOT_ALLOWED : handler;
  }

  /**
   * Constructs deterministic paths with shared, divergent, or nested prefixes.
   *
   * @param index route number
   * @param shape selected prefix distribution
   * @return absolute fixture path
   * @throws IllegalArgumentException if the shape is unsupported
   */
  private static String path(int index, String shape) {
    return switch (shape) {
      case "shared" -> "/api/v1/accounts/" + (index % 17) + "/orders/" + index;
      case "divergent" -> "/" + Integer.toUnsignedString(index * 0x9E3779B9, 16) + "/resource";
      case "prefix" -> "/api/" + (index / 3) + "/orders" + "/recent".repeat(index % 3);
      default -> throw new IllegalArgumentException("Unknown shape: " + shape);
    };
  }
}
