package io.github.suppierk.shoostr.example.application;

import io.github.suppierk.shoostr.Shoostr;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

/** Registers native Jetty settings before application startup. */
public final class NativeConfigurationExample {
  /** Prevents construction of this example factory. */
  private NativeConfigurationExample() {}

  /**
   * Creates an unstarted loopback application with explicit native transport settings.
   *
   * @param port listening port, or zero to select an available port
   * @return configured application owned by its caller
   */
  public static Shoostr application(int port) {
    return new Shoostr()
        // Jetty rejects oversized headers before the health handler runs.
        .modifyHttpConfiguration(http -> http.setRequestHeaderSize(1024))
        .modifyServer(
            server -> {
              // Native pool and listener settings stay with Jetty's own configuration types.
              var pool = (QueuedThreadPool) server.getThreadPool();
              pool.setMaxThreads(32);
              var connector = (ServerConnector) server.getConnectors()[0];
              connector.setHost("127.0.0.1");
              connector.setPort(port);
              connector.setIdleTimeout(10_000);
              server.setStopTimeout(1000);
            })
        .routes(routes -> routes.get("/health", (ignored, response) -> response.text("ready")));
  }
}
