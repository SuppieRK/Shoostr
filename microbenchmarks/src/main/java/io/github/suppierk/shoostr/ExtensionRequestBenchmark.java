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
  @Param({"plain", "inactive", "callbacks", "authentication", "localObserver", "appObserver"})
  public String configuration;

  @Param({"1", "1000"})
  public int groups;

  @Param({"matched", "missed", "wrongMethod"})
  public String outcome;

  private Shoostr app;
  private LocalConnector connector;
  private String request;

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
   * @throws IllegalStateException if the prepared exchange has the wrong status
   */
  @Setup
  public void setup() throws Exception {
    app = new Shoostr(Options.defaults().withPort(0));
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
  }

  /**
   * Registers the same table using the pre-extension public API for a pinned baseline comparison.
   */
  private void registerPlain() {
    for (int group = 0; group < groups; group++) {
      var path = "/api/" + group + "/users/{id}";
      app.routes().get(path, (_, response) -> response.text("ok"));
      app.routes().post(path, (_, response) -> response.text("ok"));
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
      if ("inactive".equals(benchmark.configuration)) {
        benchmark.app.extensions(new Inactive());
        benchmark.registerPlain();
        return;
      }

      if ("appObserver".equals(benchmark.configuration)) {
        benchmark.app.afterRequest(_ -> {});
        benchmark.registerPlain();
        return;
      }

      var authentication = new Identity();
      if ("authentication".equals(benchmark.configuration)) {
        benchmark.app.authentication(authentication);
      }

      for (int group = 0; group < benchmark.groups; group++) {
        var path = "/api/" + group + "/users/{id}";
        Handler handler = (_, response) -> response.text("ok");
        benchmark
            .app
            .routes()
            .get(path, handler, e -> configure(e, authentication, benchmark.configuration));
        benchmark
            .app
            .routes()
            .post(path, handler, e -> configure(e, authentication, benchmark.configuration));
      }
    }

    /**
     * Adds real callbacks or a managed identity requirement to one binding.
     *
     * @param endpoint local registration
     * @param authentication identity source
     * @param configuration selected mode
     */
    private static void configure(
        Extensions endpoint, Identity authentication, String configuration) {
      if ("authentication".equals(configuration)) {
        endpoint.get(authentication).required();
      } else if ("localObserver".equals(configuration)) {
        endpoint.afterRequest(_ -> {});
      } else {
        endpoint.beforeRouteHandler((request, _) -> request.attribute("admitted", Boolean.TRUE));
        endpoint.afterRouteHandler((request, _) -> request.attribute("admitted", null));
      }
    }
  }

  /** Available capability with deliberately no request behavior. */
  private static final class Inactive implements Extension<Void> {}

  /** Identity fixture independent of any external security provider. */
  private static final class Identity extends AuthenticationExtension {
    /** {@inheritDoc} */
    @Override
    public void handle(Request request, Response response) {
      request.principal(() -> "benchmark");
    }
  }
}
