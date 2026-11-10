package io.github.suppierk.shoostr;

import io.github.suppierk.shoostr.opentelemetry.OpenTelemetryTracing;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.OpenTelemetrySdkBuilder;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.ServerConnector;
import org.jspecify.annotations.Nullable;
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

/** Measures complete Jetty local exchanges, including actual framework dispatch and completion. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(
    value = 2,
    jvmArgsAppend = {"-Xms512m", "-Xmx512m", "-XX:+UseG1GC"})
@Threads(1)
public class ExtensionRequestBenchmark {
  @Param({
    "plain",
    "inactive",
    "callbacks",
    "authentication",
    "localObserver",
    "appObserver",
    "localInstrumentation",
    "unselectedInstrumentation",
    "appInstrumentation",
    "otelSelected",
    "otelUnselected",
    "otelAppWide"
  })
  public String configuration;

  @Param({"1", "1000"})
  public int groups;

  @Param({"matched", "missed", "wrongMethod"})
  public String outcome;

  @Param({"default", "platform", "forkJoin"})
  public String execution;

  private final List<AutoCloseable> resources;
  private Shoostr app;
  private LocalConnector connector;
  private String request;
  private @Nullable ExecutorService executor;

  /**
   * Initializes fixture-owned resources and the execution default used by existing fixture tests.
   */
  public ExtensionRequestBenchmark() {
    this.resources = new ArrayList<>();
    this.execution = "default";
  }

  /**
   * Runs one HTTP exchange through the production dispatcher; no network/client is involved.
   *
   * @return serialized response, consumed by JMH
   * @throws Exception if transport or application handling fails
   */
  @Benchmark
  public String request() throws Exception {
    return connector.getResponse(request);
  }

  /**
   * Starts one app and prepares an independent request corpus outside the timed operation.
   *
   * @throws Exception if the application cannot start
   * @throws IllegalArgumentException if the execution mode is unknown
   * @throws IllegalStateException if the prepared exchange has the wrong status
   */
  @Setup
  public void setup() throws Exception {
    app =
        new Shoostr()
            .modifyServer(
                nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

    executor =
        switch (execution) {
          case "default" -> null;
          case "platform" -> Executors.newFixedThreadPool(1);
          case "forkJoin" -> new ForkJoinPool(1);
          default -> throw new IllegalArgumentException("Unknown execution: " + execution);
        };
    if (executor != null) {
      resources.add(executor);
    }

    app.modifyServer(
        server -> {
          connector = new LocalConnector(server);
          server.addConnector(connector);
        });

    if ("plain".equals(configuration)) {
      registerPlain();
    } else {
      registerExtensions();
    }

    app.start();

    String path = "missed".equals(outcome) ? "/missing" : "/api/" + (groups - 1) + "/users/42";
    String method = "wrongMethod".equals(outcome) ? "DELETE" : "GET";
    request = method + " " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
    String expected =
        "missed".equals(outcome) ? "404" : "wrongMethod".equals(outcome) ? "405" : "200";
    if (!connector.getResponse(request).startsWith("HTTP/1.1 " + expected)) {
      throw new IllegalStateException("Unexpected fixture outcome");
    }
  }

  /**
   * Stops the fixture app and its owned transport resources.
   *
   * @throws Exception if cleanup fails
   */
  @TearDown
  public void close() throws Exception {
    app.close();

    for (var resource : resources) {
      resource.close();
    }
  }

  /**
   * Registers the same table using the pre-extension public API for a pinned baseline comparison.
   */
  private void registerPlain() {
    for (int group = 0; group < groups; group++) {
      var path = "/api/" + group + "/users/{id}";
      if (executor == null) {
        app.routes().get(path, (ignored, response) -> response.text("ok"));
        app.routes().post(path, (ignoredRequest, response) -> response.text("ok"));
      } else {
        app.routes().get(path, executor, (ignoredRequest, response) -> response.text("ok"));
        app.routes().post(path, executor, (ignoredRequest, response) -> response.text("ok"));
      }
    }
  }

  /**
   * Loads extension-only fixture code outside the measured operation. Keeping its signatures out of
   * the outer benchmark lets the same plain fixture link against the pinned pre-extension core.
   *
   * @throws ReflectiveOperationException if fixture setup cannot be invoked
   */
  private void registerExtensions() throws ReflectiveOperationException {
    Class.forName(ExtensionRequestBenchmark.class.getName() + "$ConfiguredRoutes")
        .getDeclaredMethod("register", ExtensionRequestBenchmark.class)
        .invoke(null, this);
  }

  /** Extension-only setup, loaded only for opted-in configurations. */
  private static final class ConfiguredRoutes {
    /** Prevents instances of the fixture utility. */
    private ConfiguredRoutes() {}

    /**
     * Configures the actual public extension API outside measured exchanges.
     *
     * @param benchmark owning trial
     */
    public static void register(ExtensionRequestBenchmark benchmark) {
      switch (benchmark.configuration) {
        case "otelSelected", "otelUnselected", "otelAppWide" -> {
          registerTracing(benchmark);
          return;
        }
        case "inactive" -> {
          benchmark.app.withExtension(new Inactive());
          benchmark.registerPlain();
          return;
        }
        case "appObserver" -> {
          benchmark.app.afterRequest(ignoredRequestOutcome -> {});
          benchmark.registerPlain();
          return;
        }
        case "appInstrumentation" -> {
          benchmark.app.observe(ignoredRequest -> ignoredRequestOutcome -> {});
          benchmark.registerPlain();
          return;
        }
        default -> {
          /* Remaining modes configure individual endpoints below. */
        }
      }

      var authentication = new Identity();

      for (int group = 0; group < benchmark.groups; group++) {
        var path = "/api/" + group + "/users/{id}";
        Handler handler = (ignoredRequest, response) -> response.text("ok");
        Consumer<Lifecycle> get = endpointRegistration(benchmark, authentication);
        Consumer<Lifecycle> post =
            lifecycle -> configure(lifecycle, authentication, benchmark.configuration);
        if (benchmark.executor == null) {
          benchmark.app.routes().get(path, handler, get);
          benchmark.app.routes().post(path, handler, post);
        } else {
          benchmark.app.routes().get(path, benchmark.executor, handler, get);
          benchmark.app.routes().post(path, benchmark.executor, handler, post);
        }
      }
    }

    /**
     * Creates endpoint lifecycle configuration for the selected benchmark scenario.
     *
     * @param benchmark configured benchmark state
     * @param authentication benchmark identity provider
     * @return endpoint registration callback
     */
    private static Consumer<Lifecycle> endpointRegistration(
        ExtensionRequestBenchmark benchmark, Identity authentication) {
      return lifecycle -> {
        if (!"unselectedInstrumentation".equals(benchmark.configuration)) {
          configure(lifecycle, authentication, benchmark.configuration);
        }
      };
    }

    /**
     * Installs a real recording SDK without an exporter; all allocations are SDK/dispatch work.
     *
     * @param benchmark owning trial
     */
    private static void registerTracing(ExtensionRequestBenchmark benchmark) {
      var provider = createTracerProvider();

      benchmark.resources.add(provider);

      var tracing = new OpenTelemetryTracing(createTelemetry(provider));
      if ("otelAppWide".equals(benchmark.configuration)) {
        benchmark.app.withExtension(tracing);
      }

      for (int group = 0; group < benchmark.groups; group++) {
        var path = "/api/" + group + "/users/{id}";
        for (var method : List.of("GET", "POST")) {
          Consumer<Lifecycle> configure = tracingSelection(benchmark, method, tracing);
          Handler handler = (ignoredRequest, response) -> response.text("ok");
          if (benchmark.executor == null) {
            benchmark.app.routes().route(method, path, handler, configure);
          } else {
            benchmark.app.routes().route(method, path, benchmark.executor, handler, configure);
          }
        }
      }
    }

    /**
     * Acquires the recording provider before the trial registers its cleanup ownership.
     *
     * @return fresh recording provider
     */
    private static SdkTracerProvider createTracerProvider() {
      return tracerProviderProperties().build();
    }

    /**
     * Configures the benchmark's exporter-free recording provider.
     *
     * @return native provider builder
     */
    private static SdkTracerProviderBuilder tracerProviderProperties() {
      return SdkTracerProvider.builder();
    }

    /**
     * Constructs the SDK after its provider has a cleanup owner.
     *
     * @param provider trial-owned recording provider
     * @return configured SDK
     */
    private static OpenTelemetrySdk createTelemetry(SdkTracerProvider provider) {
      return telemetryProperties(provider).build();
    }

    /**
     * Configures the SDK with the trial-owned provider.
     *
     * @param provider trial-owned recording provider
     * @return native SDK builder
     */
    private static OpenTelemetrySdkBuilder telemetryProperties(SdkTracerProvider provider) {
      return OpenTelemetrySdk.builder().setTracerProvider(provider);
    }

    /**
     * Creates the tracing selection used by the benchmark endpoint.
     *
     * @param benchmark configured benchmark state
     * @param method HTTP method token
     * @param tracing borrowed tracing extension
     * @return tracing registration callback
     */
    private static Consumer<Lifecycle> tracingSelection(
        ExtensionRequestBenchmark benchmark, String method, OpenTelemetryTracing tracing) {
      return lifecycle -> {
        if ("otelSelected".equals(benchmark.configuration)
            || ("otelUnselected".equals(benchmark.configuration) && "POST".equals(method))) {
          lifecycle.withExtension(tracing);
        }
      };
    }

    /**
     * Adds real callbacks or a managed identity requirement to one binding.
     *
     * @param endpoint local registration
     * @param authentication identity source
     * @param configuration selected mode
     */
    private static void configure(
        Lifecycle endpoint, Identity authentication, String configuration) {
      switch (configuration) {
        case "authentication" -> endpoint.withExtension(authentication);
        case "localObserver" -> endpoint.afterRequest(ignoredRequestOutcome -> {});
        case "localInstrumentation", "unselectedInstrumentation" ->
            endpoint.observe(ignoredRequest -> ignoredRequestOutcome -> {});
        default -> {
          endpoint.beforeRouteHandler(
              (request, ignoredResponse) -> request.attribute("admitted", Boolean.TRUE));
          endpoint.afterRouteHandler(
              (request, ignoredResponse) -> request.attribute("admitted", null));
        }
      }
    }
  }

  /** Available capability with deliberately no request behavior. */
  private static final class Inactive implements ApplicationExtension<Inactive> {
    /** {@inheritDoc} */
    @Override
    public Inactive configure(Lifecycle lifecycle, @Nullable Inactive inherited) {
      return this;
    }
  }

  /** Identity fixture independent of any external security provider. */
  private static final class Identity extends AuthenticationExtension {
    /** {@inheritDoc} */
    @Override
    public void handle(Request request, Response response) {
      request.principal(() -> "benchmark");
    }
  }
}
