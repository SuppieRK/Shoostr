package io.github.suppierk.shoostr;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Callback;
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

/** Compares per-write blocking callbacks with one reusable blocker per eighteen-write stream. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
    value = 3,
    jvmArgsAppend = {"-Xms256m", "-Xmx256m", "-XX:+UseG1GC"})
@Threads(1)
public class StreamCompletionBenchmark {
  @Param({"fresh", "shared"})
  public String blocker;

  @Param({"inline", "deferred"})
  public String completion;

  private final ByteBuffer bytes;
  private final Content.Sink sink;
  private final ArrayBlockingQueue<Callback> pending;
  private @Nullable Thread worker;
  private int writes;

  /** Initializes transport storage before JMH configures the trial parameters. */
  public StreamCompletionBenchmark() {
    blocker = "fresh";
    completion = "inline";
    bytes = ByteBuffer.allocate(1024);
    sink = this::complete;
    pending = new ArrayBlockingQueue<>(1);
  }

  /**
   * Creates the completion transport; deferred callbacks use one reusable virtual worker.
   *
   * @throws IllegalArgumentException if a fixture parameter is unknown
   */
  @Setup
  public void setup() {
    if ((!"fresh".equals(blocker) && !"shared".equals(blocker))
        || (!"inline".equals(completion) && !"deferred".equals(completion))) {
      throw new IllegalArgumentException(
          "Expected fresh/shared blocker and inline/deferred completion");
    }

    if ("deferred".equals(completion)) {
      worker =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      while (!Thread.currentThread().isInterrupted()) {
                        var callback = pending.take();
                        writes++;
                        callback.succeeded();
                      }
                    } catch (InterruptedException _) {
                      Thread.currentThread().interrupt();
                    }
                  });
    }
  }

  /**
   * Includes blocker construction and all initial, explicit and final flush completions.
   *
   * @return completed writes
   * @throws IOException if the completion worker cannot complete a write
   */
  @Benchmark
  public int flushes() throws IOException {
    writes = 0;
    if ("shared".equals(blocker)) {
      var shared = new Blocker.Shared();
      for (int index = 0; index < 18; index++) {
        try (var callback = shared.callback()) {
          sink.write(index == 17, bytes, callback);
          callback.block();
        }
      }
    } else {
      for (int index = 0; index < 18; index++) {
        Content.Sink.write(sink, index == 17, bytes);
      }
    }

    return writes;
  }

  /**
   * Stops the deferred-completion worker outside timing.
   *
   * @throws InterruptedException if fixture teardown is interrupted
   * @throws IllegalStateException if the worker does not stop
   */
  @TearDown
  public void close() throws InterruptedException {
    if (worker != null) {
      worker.interrupt();
      worker.join(5000);
      if (worker.isAlive()) {
        throw new IllegalStateException("Completion worker did not stop");
      }
    }
  }

  /**
   * Completes a simulated write inline or on the trial's virtual completion worker.
   *
   * @param last whether this write terminates the stream
   * @param content simulated transport bytes
   * @param callback completion to acknowledge after transport consumption
   */
  private void complete(boolean last, ByteBuffer content, Callback callback) {
    if (worker == null) {
      writes++;
      callback.succeeded();
    } else {
      try {
        pending.put(callback);
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        callback.failed(failure);
      }
    }
  }
}
