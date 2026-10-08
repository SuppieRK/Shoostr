package io.github.suppierk.shoostr;

import io.github.suppierk.shoostr.micrometer.MicrometerMetrics;
import io.github.suppierk.shoostr.opentelemetry.OpenTelemetryTracing;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.eclipse.jetty.http.HttpFields;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/** Measures current optional observers, excluding HTTP dispatch, exporters and fixture creation. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(
    value = 2,
    jvmArgsAppend = {"-Xms512m", "-Xmx512m", "-XX:+UseG1GC"})
@Threads(1)
public class TelemetryObservationBenchmark {
  @Param({"minimal", "micrometer", "otelNoop", "otelDropped", "otelRecording"})
  public String configuration;

  @Param({"absent", "remote"})
  public String parent;

  @Param({"matched", "missed", "serverError"})
  public String outcome;

  private final List<AutoCloseable> resources;
  private Function<Request, RequestObservation> observer;
  private Request request;
  private RequestOutcome terminal;

  /** Initializes ownership tracking for the dependencies created during trial setup. */
  public TelemetryObservationBenchmark() {
    resources = new ArrayList<>();
  }

  /**
   * Invokes the real adapter, restores its invocation scope and delivers one terminal snapshot.
   *
   * @return observation retained by the JMH consumer
   */
  @Benchmark
  public RequestObservation observe() {
    var observation = observer.apply(request);
    observation.close();
    observation.complete(terminal);
    return observation;
  }

  /**
   * Builds a reusable metadata snapshot and warms the fixed meter IDs outside measurement.
   *
   * @throws IllegalArgumentException if the outcome or observer configuration is unknown
   */
  @Setup
  public void setup() {
    var headers = HttpFields.build().put("Host", "localhost");
    if ("remote".equals(parent)) {
      headers.put("traceparent", "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01");
    }

    var fields = headers.asImmutable();
    var delegate =
        (org.eclipse.jetty.server.Request)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {org.eclipse.jetty.server.Request.class},
                (_, method, _) ->
                    switch (method.getName()) {
                      case "getHeaders" -> fields;
                      case "getMethod" -> "GET";
                      case "addHttpStreamWrapper" -> null;
                      default -> throw new UnsupportedOperationException(method.getName());
                    });
    var sink =
        (Response)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {Response.class},
                (_, method, _) -> {
                  throw new UnsupportedOperationException(method.getName());
                });
    request = Request.create(delegate, sink, Options.defaults(), Callback.NOOP);
    terminal =
        switch (outcome) {
          case "matched" -> new RequestOutcome("GET", "/users/{id}", 200, 1_000_000, null, null);
          case "missed" -> new RequestOutcome("GET", null, 404, 1_000_000, null, null);
          case "serverError" ->
              new RequestOutcome("GET", "/users/{id}", 500, 1_000_000, null, null);
          default -> throw new IllegalArgumentException("Unknown outcome: " + outcome);
        };
    observer = configureObserver();
    observe();
  }

  /**
   * Closes only fixture-owned registries/providers after measured observations finish.
   *
   * @throws Exception if a fixture-owned dependency cannot close
   */
  @TearDown
  public void close() throws Exception {
    for (var resource : resources) {
      resource.close();
    }
  }

  /**
   * Uses the public observers with identical propagation and no exporter or global registration.
   *
   * @return current production observer, or the deliberately minimal reference
   * @throws IllegalArgumentException if the observer configuration is unknown
   */
  private Function<Request, RequestObservation> configureObserver() {
    var propagators = ContextPropagators.create(W3CTraceContextPropagator.getInstance());
    switch (configuration) {
      case "minimal":
        RequestObservation minimal = _ -> {};
        return _ -> minimal;
      case "micrometer":
        var registry = new SimpleMeterRegistry();
        resources.add(registry::close);
        return new MicrometerMetrics(registry);
      case "otelNoop":
        return new OpenTelemetryTracing(OpenTelemetry.propagating(propagators));
      case "otelDropped", "otelRecording":
        var provider =
            SdkTracerProvider.builder()
                .setSampler(
                    "otelDropped".equals(configuration) ? Sampler.alwaysOff() : Sampler.alwaysOn())
                .build();
        resources.add(provider);
        return new OpenTelemetryTracing(
            OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .setPropagators(propagators)
                .build());
      default:
        throw new IllegalArgumentException("Unknown configuration: " + configuration);
    }
  }
}
