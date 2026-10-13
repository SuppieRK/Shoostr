package io.github.suppierk.shoostr.example.metrics;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.micrometer.MicrometerMetrics;
import java.io.IOException;
import org.eclipse.jetty.server.ServerConnector;

/** Composes the metrics demonstration without a second HTTP server for metrics. */
public final class MetricsExample {
  /** Prevents construction of the launcher. */
  private MetricsExample() {}

  /**
   * Starts the app; backend addresses are described by the Compose recipe.
   *
   * @throws Exception if startup fails
   */
  static void main() throws Exception {
    var app = application(Options.defaults());

    try {
      app.modifyServer(
          server ->
              ((ServerConnector) server.getConnectors()[0])
                  .setHost(System.getProperty("shoostr.host", "127.0.0.1")));
      app.start();
    } catch (Exception | Error failure) {
      try {
        app.close();
      } catch (IOException cleanup) {
        failure.addSuppressed(cleanup);
      }

      throw failure;
    }

    System.out.println("Metrics: http://127.0.0.1:" + app.port() + "/metrics");
  }

  /**
   * Returns the app with explicit registry ownership and instrumentation installation.
   *
   * @param options listener settings
   * @return app owned by its eventual caller
   */
  public static Shoostr application(Options options) {
    var app = new Shoostr(options);

    try {
      var configuration = new MetricsConfiguration();
      app.extensions(configuration);
      app.extensions(new MicrometerMetrics(configuration.registry()).allRequests());
      app.routes(routes -> MetricsRoutes.register(routes, configuration.registry()));
      return app;
    } catch (RuntimeException | Error failure) {
      try {
        app.close();
      } catch (IOException cleanup) {
        failure.addSuppressed(cleanup);
      }

      throw failure;
    }
  }
}
