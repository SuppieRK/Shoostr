package io.github.suppierk.shoostr;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.jetty.util.thread.VirtualThreadPool;
import org.eclipse.jetty.util.thread.strategy.AdaptiveExecutionStrategy;

/** Runs transport work on virtual threads without limiting application concurrency. */
final class ProducerThreadPool extends VirtualThreadPool {
  private final Set<AdaptiveExecutionStrategy> pending;

  /** Creates the application's unlimited virtual-thread pool. */
  ProducerThreadPool() {
    super(0);
    pending = ConcurrentHashMap.newKeySet();
  }

  /**
   * Shares an already scheduled producer wake-up, without coalescing application work. Jetty's
   * current producer can continue consuming immediately ready work while its replacement is still
   * pending. Only one replacement is needed until it actually starts producing. Ordinary immediate
   * cleanup may still run on a one-off virtual thread after shutdown; normal execution and producer
   * submissions retain their shutdown rejection.
   *
   * @param task producer wake-up or ordinary transport task
   * @return whether this task has an available execution
   */
  @Override
  public boolean tryExecute(Runnable task) {
    if (!(task instanceof AdaptiveExecutionStrategy producer)) {
      if (super.tryExecute(task)) {
        return true;
      }

      if (isStopping() || isStopped()) {
        // Jetty's immediate HTTP/2 cleanup otherwise falls back to our closed owned executor.
        Thread.startVirtualThread(task);
        return true;
      }

      return false;
    }

    if (!pending.add(producer)) {
      return isRunning();
    }

    boolean submitted = false;

    try {
      submitted =
          super.tryExecute(
              () -> {
                // Release before running: this producer may immediately consume blocking work.
                pending.remove(producer);
                producer.run();
              });
      return submitted;
    } finally {
      if (!submitted) {
        pending.remove(producer);
      }
    }
  }
}
