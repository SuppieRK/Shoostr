package io.github.suppierk.shoostr;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.VirtualThreads;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.jspecify.annotations.Nullable;

/** Internal experiment wiring; public endpoint execution configuration remains evidence-gated. */
record ExecutionSettings(
    ThreadPool transport,
    boolean virtualConsumers,
    @Nullable ExecutorService platformWorkers,
    Set<String> platformPaths) {
  /**
   * Validates available inputs before the application accepts ownership of worker resources.
   *
   * @param transport unstarted native pool
   * @param virtualConsumers whether native blocking tasks use the owned virtual executor
   * @param platformWorkers owned selected-endpoint pool, or null
   * @param platformPaths registered absolute templates selected by the benchmark fixture
   */
  ExecutionSettings {
    Objects.requireNonNull(transport);
    if (virtualConsumers
        && !(transport instanceof org.eclipse.jetty.util.VirtualThreads.Configurable)) {
      throw new IllegalArgumentException("Transport pool must support virtual consumers");
    }

    if (transport instanceof LifeCycle lifecycle && !lifecycle.isStopped()) {
      throw new IllegalArgumentException("Transport pool must be unstarted");
    }

    platformPaths = Set.copyOf(platformPaths);
    for (var path : platformPaths) {
      if (!path.startsWith("/")) {
        throw new IllegalArgumentException("Selected route templates must be absolute");
      }
    }
    if (platformWorkers == null && !platformPaths.isEmpty()) {
      throw new IllegalArgumentException("Selected endpoints require a platform executor");
    }

    if (platformWorkers != null && platformWorkers.isShutdown()) {
      throw new IllegalArgumentException("Platform executor must be usable");
    }

    if (platformWorkers instanceof ThreadPoolExecutor pool
        && !(pool.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.AbortPolicy)) {
      throw new IllegalArgumentException(
          "Platform executor must reject without fallback or discard");
    }
  }

  /**
   * Creates the native transport with the experiment's consumer wiring or the existing defaults.
   *
   * @param settings experiment settings, or null for the default producer pool
   * @param virtualThreads application-owned virtual executor
   * @return unstarted native server
   */
  static Server createServer(@Nullable ExecutionSettings settings, ExecutorService virtualThreads) {
    var pool = settings == null ? new ProducerThreadPool() : settings.transport();
    if (settings == null || settings.virtualConsumers()) {
      ((VirtualThreads.Configurable) pool).setVirtualThreadsExecutor(virtualThreads);
    }

    return new Server(pool);
  }
}
