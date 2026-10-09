package io.github.suppierk.shoostr;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BorrowedExecutorShutdownTest {
  @Test
  void closesOnlyItsOwnQueuedExchangesOnAnActivelySharedExecutor() throws Exception {
    var entered = new CountDownLatch(1);
    var releaseActive = new CountDownLatch(1);
    var blockerEntered = new CountDownLatch(1);
    var releaseBlocker = new CountDownLatch(1);
    var activeInterrupted = new AtomicBoolean();
    var queuedInvoked = new AtomicBoolean();
    var queuedOutcome = new CompletableFuture<RequestOutcome>();
    var activeOutcome = new CompletableFuture<RequestOutcome>();

    try (var workers =
            new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        var drivers = Executors.newVirtualThreadPerTaskExecutor();
        var first = new Shoostr(Options.defaults().withPort(0));
        var second = new Shoostr(Options.defaults().withPort(0))) {
      first.modifyServer(server -> server.setStopTimeout(0));
      first.afterRequest(
          outcome -> {
            if ("/queued".equals(outcome.routePattern())) {
              queuedOutcome.complete(outcome);
            } else {
              activeOutcome.complete(outcome);
            }
          });
      first
          .routes()
          .get(
              "/active",
              workers,
              (_, _) -> {
                entered.countDown();

                try {
                  if (!releaseActive.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Active handler was not released");
                  }
                } catch (InterruptedException failure) {
                  activeInterrupted.set(true);
                  throw failure;
                }
              })
          .get("/queued", workers, (_, _) -> queuedInvoked.set(true));
      second.routes().get("/second", workers, (_, response) -> response.text("second"));

      try (var firstTest = TestServer.start(first);
          var secondTest = TestServer.start(second)) {
        var active = drivers.submit(() -> firstTest.send(request -> request.path("/active")));

        try {
          assertTrue(entered.await(5, TimeUnit.SECONDS));
          var unrelated =
              workers.submit(
                  () -> {
                    blockerEntered.countDown();
                    return releaseBlocker.await(10, TimeUnit.SECONDS);
                  });
          assertTrue(blockerEntered.await(5, TimeUnit.SECONDS));
          var queued = drivers.submit(() -> firstTest.send(request -> request.path("/queued")));
          await().atMost(Duration.ofSeconds(5)).until(() -> workers.getQueue().size() == 1);
          var otherApp =
              drivers.submit(
                  () ->
                      secondTest.send(
                          request -> request.path("/second"),
                          HttpResponse.BodyHandlers.ofString()));
          await().atMost(Duration.ofSeconds(5)).until(() -> workers.getQueue().size() == 2);
          first.close();
          assertInstanceOf(
              RejectedExecutionException.class,
              queuedOutcome.get(5, TimeUnit.SECONDS).applicationFailure());
          assertFalse(queuedInvoked.get());
          assertFalse(activeInterrupted.get());
          assertFalse(activeOutcome.isDone());
          assertFalse(workers.isShutdown());
          assertFalse(unrelated.isDone());
          releaseBlocker.countDown();
          assertTrue(unrelated.get(5, TimeUnit.SECONDS));
          assertEquals("second", otherApp.get(5, TimeUnit.SECONDS).body());
          assertEquals("usable", workers.submit(() -> "usable").get(5, TimeUnit.SECONDS));
          assertFalse(queuedInvoked.get());
          releaseActive.countDown();
          assertEquals("/active", activeOutcome.get(5, TimeUnit.SECONDS).routePattern());
          for (var sent : List.of(active, queued)) {
            try {
              sent.get(5, TimeUnit.SECONDS);
            } catch (ExecutionException failure) {
              assertInstanceOf(IOException.class, failure.getCause());
            }
          }
        } finally {
          releaseActive.countDown();
          releaseBlocker.countDown();
        }
      }
    }
  }

  @Test
  void cancelsARegisteredExchangeWhenCloseWinsBeforeExecutorSubmissionReturns() throws Exception {
    var submitting = new CountDownLatch(1);
    var releaseSubmission = new Semaphore(0);
    var firstSubmission = new AtomicBoolean(true);
    var invoked = new AtomicBoolean();
    var completed = new CompletableFuture<RequestOutcome>();
    var completions = new AtomicInteger();

    try (var workers =
            new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>()) {
              @Override
              public void execute(Runnable command) {
                if (firstSubmission.compareAndSet(true, false)) {
                  submitting.countDown();

                  releaseSubmission.acquireUninterruptibly();
                }

                super.execute(command);
              }
            };
        var driver = Executors.newVirtualThreadPerTaskExecutor();
        var app = new Shoostr(Options.defaults().withPort(0))) {
      app.modifyServer(server -> server.setStopTimeout(0));
      app.afterRequest(
          outcome -> {
            completions.incrementAndGet();
            completed.complete(outcome);
          });
      app.routes().get("/racing", workers, (_, _) -> invoked.set(true));

      try (var test = TestServer.start(app)) {
        var sent = driver.submit(() -> test.send(request -> request.path("/racing")));

        try {
          assertTrue(submitting.await(5, TimeUnit.SECONDS));
          app.close();
          assertFalse(invoked.get());
        } finally {
          releaseSubmission.release();
        }

        assertEquals("usable", workers.submit(() -> "usable").get(5, TimeUnit.SECONDS));
        assertFalse(invoked.get());
        assertInstanceOf(
            RejectedExecutionException.class,
            completed.get(5, TimeUnit.SECONDS).applicationFailure());
        assertEquals(1, completions.get());

        try {
          sent.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
          assertInstanceOf(IOException.class, failure.getCause());
        }
      } finally {
        releaseSubmission.release();
      }
    }
  }

  @Test
  void leavesRunningHandlerUninterruptedAndRequestLiveUntilItExits() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var interrupted = new AtomicBoolean();
    var liveAfterClose = new CompletableFuture<String>();
    var finished = new CompletableFuture<RequestOutcome>();
    var completions = new AtomicInteger();

    try (var workers = Executors.newSingleThreadExecutor();
        var driver = Executors.newVirtualThreadPerTaskExecutor();
        var app = new Shoostr(Options.defaults().withPort(0))) {
      app.modifyServer(server -> server.setStopTimeout(0));
      app.afterRequest(
          outcome -> {
            completions.incrementAndGet();
            finished.complete(outcome);
          });
      app.routes()
          .get(
              "/active",
              workers,
              (request, _) -> {
                entered.countDown();

                try {
                  if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Handler was not released");
                  }

                  liveAfterClose.complete(request.routePattern().orElseThrow());
                } catch (InterruptedException failure) {
                  interrupted.set(true);
                  throw failure;
                }
              });

      try (var test = TestServer.start(app)) {
        var sent = driver.submit(() -> test.send(request -> request.path("/active")));

        try {
          assertTrue(entered.await(5, TimeUnit.SECONDS));
          app.close();
          assertFalse(interrupted.get());
          assertFalse(workers.isShutdown());
          assertFalse(finished.isDone());
        } finally {
          release.countDown();
        }

        assertEquals("/active", liveAfterClose.get(5, TimeUnit.SECONDS));
        assertEquals("/active", finished.get(5, TimeUnit.SECONDS).routePattern());
        assertEquals(1, completions.get());

        try {
          sent.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
          assertInstanceOf(IOException.class, failure.getCause());
        }
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void doesNotInterruptUnrelatedWorkHelpedByARunningForkJoinHandler() throws Exception {
    var entered = new CountDownLatch(1);
    var allowHelping = new CountDownLatch(1);
    var helped = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var handlerThread = new CompletableFuture<Thread>();
    var helpedThread = new CompletableFuture<Thread>();
    var interrupted = new AtomicBoolean();
    var completed = new CompletableFuture<RequestOutcome>();

    try (var workers = new ForkJoinPool(1);
        var driver = Executors.newVirtualThreadPerTaskExecutor();
        var app = new Shoostr(Options.defaults().withPort(0))) {
      app.modifyServer(server -> server.setStopTimeout(0));
      app.afterRequest(completed::complete);
      app.routes()
          .get(
              "/help",
              workers,
              (_, _) -> {
                handlerThread.complete(Thread.currentThread());
                entered.countDown();
                if (!allowHelping.await(5, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("Helping was not enabled");
                }

                assertTrue(workers.awaitQuiescence(10, TimeUnit.SECONDS));
              });

      try (var test = TestServer.start(app)) {
        var sent = driver.submit(() -> test.send(request -> request.path("/help")));

        try {
          assertTrue(entered.await(5, TimeUnit.SECONDS));
          var unrelated =
              workers.submit(
                  () -> {
                    helpedThread.complete(Thread.currentThread());
                    helped.countDown();

                    try {
                      return release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException failure) {
                      interrupted.set(true);
                      throw failure;
                    }
                  });
          allowHelping.countDown();
          assertTrue(helped.await(5, TimeUnit.SECONDS));
          assertEquals(
              handlerThread.get(5, TimeUnit.SECONDS), helpedThread.get(5, TimeUnit.SECONDS));
          app.close();
          assertFalse(interrupted.get());
          assertFalse(unrelated.isDone());
          release.countDown();
          assertTrue(unrelated.get(5, TimeUnit.SECONDS));
          assertEquals("/help", completed.get(5, TimeUnit.SECONDS).routePattern());
          assertEquals("usable", workers.submit(() -> "usable").get(5, TimeUnit.SECONDS));

          try {
            sent.get(5, TimeUnit.SECONDS);
          } catch (ExecutionException failure) {
            assertInstanceOf(IOException.class, failure.getCause());
          }
        } finally {
          allowHelping.countDown();
          release.countDown();
        }
      }
    }
  }
}
