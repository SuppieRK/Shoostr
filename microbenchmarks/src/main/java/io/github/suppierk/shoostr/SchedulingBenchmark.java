package io.github.suppierk.shoostr;

import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.VirtualThreadPool;
import org.eclipse.jetty.util.thread.strategy.AdaptiveExecutionStrategy;
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

/**
 * Screens Jetty scheduling configurations with actual Shoostr finite-response work. Times include
 * submitting and awaiting one whole batch, not socket I/O or the full application dispatcher. A
 * blocking readiness queue stands in for selector waiting; the selected pool starts its producer.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
    value = 3,
    jvmArgsAppend = {"-Xms256m", "-Xmx256m", "-XX:+UseG1GC"})
@Threads(1)
public class SchedulingBenchmark {
  private static final byte[] PAYLOAD = "Hello, World!".getBytes(StandardCharsets.UTF_8);

  @Param({"queued", "virtual", "coalesced"})
  public String pool;

  @Param({"1", "32", "4096"})
  public int batchSize;

  private final LinkedBlockingQueue<Exchange> pending;
  private final Options options;
  private ExecutorService virtualThreads;
  private ContainerLifeCycle lifecycle;
  private CountDownLatch finished;
  private volatile Throwable failure;
  AdaptiveExecutionStrategy strategy;
  Exchange[] exchanges;

  /** Creates the common producer queue and framework options outside timing. */
  public SchedulingBenchmark() {
    pending = new LinkedBlockingQueue<>();
    options = Options.defaults();
  }

  /**
   * Submits an isolated exchange or bounded burst and waits for all response and cleanup work.
   *
   * @return size of the first completed response, consumed by JMH
   * @throws InterruptedException if interrupted while awaiting completion
   * @throws TimeoutException if a scheduling failure strands any exchange
   * @throws IllegalStateException if a task fails
   */
  @Benchmark
  public int responses() throws InterruptedException, TimeoutException {
    finished = new CountDownLatch(batchSize);
    for (var exchange : exchanges) {
      pending.add(exchange);
    }

    if (!finished.await(10, TimeUnit.SECONDS)) {
      throw new TimeoutException("Scheduling fixture did not complete its batch");
    }

    if (failure != null) {
      throw new IllegalStateException("Scheduling fixture failed", failure);
    }

    return exchanges[0].submitted.remaining();
  }

  /**
   * Creates the current or proposed executor wiring outside the timed operation.
   *
   * @throws Exception if Jetty lifecycle startup fails
   * @throws IllegalArgumentException if a fixture parameter is invalid
   */
  @Setup
  public void setup() throws Exception {
    if ((!"queued".equals(pool) && !"virtual".equals(pool) && !"coalesced".equals(pool))
        || (batchSize != 1 && batchSize != 32 && batchSize != 4096)) {
      throw new IllegalArgumentException(
          "Expected queued/virtual/coalesced pool and batch size 1/32/4096");
    }

    virtualThreads = Executors.newVirtualThreadPerTaskExecutor();
    if ("queued".equals(pool)) {
      var queued = new QueuedThreadPool();
      queued.setReservedThreads(0);
      queued.setVirtualThreadsExecutor(virtualThreads);
      lifecycle = queued;
      strategy = new AdaptiveExecutionStrategy(this::produce, queued);
    } else {
      var virtual = "coalesced".equals(pool) ? new ProducerThreadPool() : new VirtualThreadPool(0);
      virtual.setVirtualThreadsExecutor(virtualThreads);
      lifecycle = virtual;
      strategy = new AdaptiveExecutionStrategy(this::produce, virtual);
    }

    try {
      exchanges = new Exchange[batchSize];
      for (int index = 0; index < exchanges.length; index++) {
        exchanges[index] = new Exchange();
      }

      lifecycle.start();
      strategy.start();
      ((Executor) lifecycle).execute(strategy::produce);
    } catch (Exception | Error startupFailure) {
      try {
        close();
      } catch (Exception cleanupFailure) {
        startupFailure.addSuppressed(cleanupFailure);
      }

      throw startupFailure;
    }
  }

  /**
   * Waits for ready work on the pool's producer thread, without adding per-batch dispatch tasks.
   *
   * @return the next exchange, or null when shutdown interrupts the readiness wait
   */
  private Runnable produce() {
    try {
      var exchange = pending.take();
      exchange.producedVirtually = Thread.currentThread().isVirtual();
      return exchange;
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  /**
   * Stops Jetty and the owned virtual executor, including after a failed measurement.
   *
   * @throws Exception if lifecycle cleanup fails
   * @throws IllegalStateException if the owned executor cannot terminate
   */
  @TearDown
  public void close() throws Exception {
    try {
      if (strategy != null) {
        strategy.stop();
      }
    } finally {
      try {
        if (lifecycle != null) {
          lifecycle.stop();
        }
      } finally {
        if (virtualThreads != null) {
          virtualThreads.shutdownNow();
          if (!virtualThreads.awaitTermination(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Scheduling fixture executor did not terminate");
          }
        }
      }
    }
  }

  /** One reusable native transport fixture; every task constructs a fresh Shoostr request pair. */
  final class Exchange implements Runnable {
    private final HttpFields.Mutable headers;
    private final Request inbound;
    private final Response sink;
    private final Callback completion;
    ByteBuffer submitted;
    int invocations;
    int writes;
    int completions;
    boolean virtual;
    boolean producedVirtually;

    /** Creates transport proxies outside timing; neither proxy performs socket or parser work. */
    private Exchange() {
      headers = HttpFields.build();
      submitted = ByteBuffer.allocate(0);
      inbound =
          (Request)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {Request.class},
                  (_, method, _) ->
                      switch (method.getName()) {
                        case "getMethod" -> "GET";
                        case "getHeaders" -> HttpFields.EMPTY;
                        default -> throw new UnsupportedOperationException(method.getName());
                      });
      sink =
          (Response)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {Response.class},
                  (_, method, arguments) ->
                      switch (method.getName()) {
                        case "getStatus" -> 200;
                        case "getHeaders" -> headers;
                        case "isCommitted" -> writes != 0;
                        case "write" -> {
                          if (!Boolean.TRUE.equals(arguments[0])) {
                            throw new IllegalStateException("Expected a terminal finite write");
                          }

                          writes++;
                          submitted = (ByteBuffer) arguments[1];
                          ((Callback) arguments[2]).succeeded();
                          yield null;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                      });
      completion = Callback.from(() -> completions++, thrown -> failure = thrown);
    }

    /**
     * Constructs, stages, completes and releases one real Shoostr exchange on the selected thread.
     */
    @Override
    public void run() {
      var done = finished;

      try {
        headers.clear();
        writes = 0;
        completions = 0;
        invocations++;
        virtual = Thread.currentThread().isVirtual();
        var request = io.github.suppierk.shoostr.Request.create(inbound, sink, options, completion);

        try {
          request.response().body("text/plain;charset=utf-8", PAYLOAD);
          request.response().complete();
        } finally {
          request.finish();
        }
      } catch (Exception | Error thrown) {
        failure = thrown;
      } finally {
        done.countDown();
      }
    }
  }
}
