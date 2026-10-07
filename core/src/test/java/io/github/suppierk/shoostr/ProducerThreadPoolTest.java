package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.util.thread.strategy.AdaptiveExecutionStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ProducerThreadPoolTest {
  @Test
  void sustainedReadyWorkDoesNotAccumulateRedundantProducerWakeups() throws Exception {
    var pending = new ArrayDeque<Runnable>();
    var completed = new AtomicInteger();
    var remaining = new AtomicInteger(50_000);
    var pool = new ProducerThreadPool();
    pool.setVirtualThreadsExecutor(pending::add);
    Runnable work = completed::incrementAndGet;
    var strategy =
        new AdaptiveExecutionStrategy(() -> remaining.getAndDecrement() > 0 ? work : null, pool);
    pool.start();
    strategy.start();

    try {
      strategy.produce();

      assertEquals(50_000, completed.get());
      assertEquals(1, pending.size());
      pending.remove().run();
      assertEquals(50_000, completed.get());
      assertEquals(0, pending.size());
    } finally {
      strategy.stop();
      pool.stop();
    }
  }

  @Test
  void blockedConsumersAllowOtherConsumersToStartOnVirtualThreads() throws Exception {
    var entered = new CountDownLatch(64);
    var release = new CountDownLatch(1);
    var completed = new CountDownLatch(64);
    var platformExecutions = new AtomicInteger();
    var remaining = new AtomicInteger(64);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var pool = new ProducerThreadPool();
      pool.setVirtualThreadsExecutor(executor);
      Runnable work =
          () -> {
            if (!Thread.currentThread().isVirtual()) {
              platformExecutions.incrementAndGet();
            }

            entered.countDown();

            try {
              release.await();
            } catch (InterruptedException _) {
              Thread.currentThread().interrupt();
            } finally {
              completed.countDown();
            }
          };
      var strategy =
          new AdaptiveExecutionStrategy(() -> remaining.getAndDecrement() > 0 ? work : null, pool);
      pool.start();
      strategy.start();

      try {
        pool.execute(strategy::produce);
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        release.countDown();
        assertTrue(completed.await(3, TimeUnit.SECONDS));
        assertEquals(0, platformExecutions.get());
      } finally {
        release.countDown();
        strategy.stop();
        pool.stop();
        executor.shutdownNow();
      }
    }
  }

  @Test
  void concurrentWakeupsShareOnlyTheSamePendingProducer() throws Exception {
    var pending = new ConcurrentLinkedQueue<Runnable>();
    var productions = new AtomicInteger();
    var pool = new ProducerThreadPool();
    pool.setVirtualThreadsExecutor(pending::add);
    var strategy =
        new AdaptiveExecutionStrategy(
            () -> {
              productions.incrementAndGet();
              return null;
            },
            pool);
    pool.start();
    strategy.start();

    try {
      var ready = new CountDownLatch(64);

      try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
        var submissions = new ArrayList<Future<?>>();
        for (int index = 0; index < 64; index++) {
          submissions.add(
              callers.submit(
                  () -> {
                    ready.countDown();
                    assertTrue(ready.await(3, TimeUnit.SECONDS));
                    strategy.dispatch();
                    return null;
                  }));
        }
        for (var submission : submissions) {
          submission.get(5, TimeUnit.SECONDS);
        }
      }

      assertEquals(1, pending.size());
      pending.remove().run();
      assertEquals(1, productions.get());

      strategy.dispatch();
      assertEquals(1, pending.size());
      pending.remove().run();
      assertEquals(2, productions.get());
    } finally {
      strategy.stop();
      pool.stop();
    }
  }

  @Test
  void independentStrategiesEachReceiveTheirOwnWakeup() throws Exception {
    var pending = new ArrayDeque<Runnable>();
    var completed = new AtomicInteger();
    var pool = new ProducerThreadPool();
    pool.setVirtualThreadsExecutor(pending::add);
    var first =
        new AdaptiveExecutionStrategy(
            () -> {
              completed.incrementAndGet();
              return null;
            },
            pool);
    var second =
        new AdaptiveExecutionStrategy(
            () -> {
              completed.incrementAndGet();
              return null;
            },
            pool);
    pool.start();
    first.start();
    second.start();

    try {
      first.dispatch();
      second.dispatch();
      assertEquals(2, pending.size());
      pending.remove().run();
      pending.remove().run();
      assertEquals(2, completed.get());
    } finally {
      first.stop();
      second.stop();
      pool.stop();
    }
  }

  @Test
  void repeatedApplicationTaskSubmissionsAreNeverCoalesced() throws Exception {
    var pending = new ArrayDeque<Runnable>();
    var completed = new AtomicInteger();
    var pool = new ProducerThreadPool();
    pool.setVirtualThreadsExecutor(pending::add);
    pool.start();

    try {
      Runnable work = completed::incrementAndGet;
      for (int index = 0; index < 1_000; index++) {
        pool.execute(work);
        assertTrue(pool.tryExecute(work));
      }
      assertEquals(2_000, pending.size());
      while (!pending.isEmpty()) {
        pending.remove().run();
      }
      assertEquals(2_000, completed.get());
    } finally {
      pool.stop();
    }
  }

  @Test
  void rejectedProducerSubmissionCanBeRetried() throws Exception {
    var pending = new ArrayDeque<Runnable>();
    var reject = new AtomicBoolean(true);
    var pool = new ProducerThreadPool();
    pool.setVirtualThreadsExecutor(
        task -> {
          if (reject.get()) {
            throw new RejectedExecutionException("rejected test submission");
          }

          pending.add(task);
        });
    var strategy = new AdaptiveExecutionStrategy(() -> null, pool);
    pool.start();
    strategy.start();

    try {
      assertFalse(pool.tryExecute(strategy));
      reject.set(false);
      assertTrue(pool.tryExecute(strategy));
      assertEquals(1, pending.size());
      pending.remove().run();
    } finally {
      strategy.stop();
      pool.stop();
    }
  }

  @Test
  void unexpectedSubmissionFailureDoesNotLeaveAStaleWakeup() throws Exception {
    var pending = new ArrayDeque<Runnable>();
    var fail = new AtomicBoolean(true);
    var pool = new ProducerThreadPool();
    pool.setVirtualThreadsExecutor(
        task -> {
          if (fail.getAndSet(false)) {
            throw new IllegalStateException("failed test submission");
          }

          pending.add(task);
        });
    var strategy = new AdaptiveExecutionStrategy(() -> null, pool);
    pool.start();
    strategy.start();

    try {
      assertThrows(IllegalStateException.class, () -> pool.tryExecute(strategy));
      assertTrue(pool.tryExecute(strategy));
      assertEquals(1, pending.size());
      pending.remove().run();
    } finally {
      strategy.stop();
      pool.stop();
    }
  }
}
