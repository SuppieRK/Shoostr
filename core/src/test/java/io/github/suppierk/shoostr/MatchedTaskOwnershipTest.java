package io.github.suppierk.shoostr;

import static io.github.suppierk.shoostr.Locks.getUnderLock;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Handler.Wrapper;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(20)
class MatchedTaskOwnershipTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void retiresTaskOwnershipWhenWorkerObservationCleanupFailsFatally(boolean handlerFailure)
      throws Exception {
    var fatal = new LinkageError("attached cleanup failed");
    var terminal = new CompletableFuture<RequestOutcome>();
    var completions = new AtomicInteger();
    var invoked = new AtomicInteger();

    try (var workers = Executors.newSingleThreadExecutor()) {
      var executor = new TrackingExecutor(workers, false);
      var app = new Shoostr();

      try (Closeable appCleanup = app::close;
          var drivers = Executors.newVirtualThreadPerTaskExecutor()) {
        configureApplication(
            app, executor, handlerFailure ? "failure" : "success", terminal, completions, invoked);
        app.observe(ignored -> fatalAttachedCleanup(fatal));

        try (var test = TestServer.start(app)) {
          var response = submitRequest(test, drivers);

          try {
            var task = executor.submitted.get(3, TimeUnit.SECONDS);
            assertSame(fatal, executor.executed.get(3, TimeUnit.SECONDS));
            retiredReferences(task).forEach((field, retired) -> assertTrue(retired, field));
            assertEquals(0, pendingMatches(app));
            assertEquals(1, invoked.get());
          } finally {
            response.cancel(true);
          }
        }
      }
    }
  }

  private RequestObservation fatalAttachedCleanup(Error fatal) {
    return new RequestObservation() {
      @Override
      public AutoCloseable attach() {
        return () -> {
          throw fatal;
        };
      }

      @Override
      public void complete(RequestOutcome outcome) {}
    };
  }

  @Test
  void submissionErrorAfterWorkerClaimPreservesTheRunningExchange() throws Exception {
    var entered = new CountDownLatch(1);
    var releaseHandler = new CountDownLatch(1);
    var admissionFinished = new CompletableFuture<Throwable>();
    var nativeCompletions = new AtomicInteger();
    var terminal = new CompletableFuture<RequestOutcome>();
    var completions = new AtomicInteger();
    var invoked = new AtomicInteger();
    var submissionFailure = new LinkageError("executor hook failed after delegation");

    try (var workers = Executors.newSingleThreadExecutor()) {
      var executor =
          new TrackingExecutor(workers, true, startThenFail(workers, entered, submissionFailure));
      var app = new Shoostr();

      try (Closeable appCleanup = app::close;
          var drivers = Executors.newVirtualThreadPerTaskExecutor()) {
        app.modifyServer(server -> server.setStopTimeout(0));
        app.afterRequest(outcome -> {
          completions.incrementAndGet();
          terminal.complete(outcome);
        });
        app.routes()
            .get(
                "/selected",
                executor,
                (request, response) -> {
                  invoked.incrementAndGet();
                  entered.countDown();
                  assertTrue(releaseHandler.await(5, TimeUnit.SECONDS));
                  response.text("success");
                });
        app.modifyServer(server ->
            server.setHandler(
                trackNativeCompletion(server.getHandler(), nativeCompletions, admissionFinished)));

        try (var test = TestServer.start(app)) {
          var response = submitRequest(test, drivers);

          try {
            final var task = executor.submitted.get(3, TimeUnit.SECONDS);
            assertNull(admissionFinished.get(3, TimeUnit.SECONDS));
            assertEquals(1L, releaseHandler.getCount());
            assertEquals(0, nativeCompletions.get());
            retiredReferences(task).forEach((field, retired) -> assertFalse(retired, field));
            assertEquals(1, pendingMatches(app));

            releaseHandler.countDown();
            var result = response.get(5, TimeUnit.SECONDS);
            assertEquals(200, result.statusCode());
            assertEquals("success", result.body());
            terminal.get(3, TimeUnit.SECONDS);
            workers.submit(() -> {}).get(3, TimeUnit.SECONDS);

            assertEquals(1, nativeCompletions.get());
            assertEquals(1, completions.get());
            assertEquals(1, invoked.get());
            retiredReferences(task).forEach((field, retired) -> assertTrue(retired, field));
            assertEquals(0, pendingMatches(app));
          } finally {
            releaseHandler.countDown();
            response.cancel(true);
          }
        }
      } finally {
        releaseHandler.countDown();
      }
    }
  }

  private Consumer<Runnable> startThenFail(
      ExecutorService workers, CountDownLatch entered, Error failure) {
    return command -> {
      workers.execute(command);
      awaitRelease(entered);
      throw failure;
    };
  }

  private Handler trackNativeCompletion(
      Handler delegate, AtomicInteger completions, CompletableFuture<Throwable> admissionFinished) {
    return new Wrapper(delegate) {
      @Override
      public boolean handle(Request request, Response response, Callback callback)
          throws Exception {
        try {
          return super.handle(request, response, countCompletion(callback, completions));
        } catch (Exception | Error failure) {
          admissionFinished.complete(failure);
          throw failure;
        } finally {
          admissionFinished.complete(null);
        }
      }
    };
  }

  private Callback countCompletion(Callback callback, AtomicInteger completions) {
    return Callback.from(
        () -> {
          completions.incrementAndGet();
          callback.succeeded();
        },
        failure -> {
          completions.incrementAndGet();
          callback.failed(failure);
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"success", "failure", "rejected", "cancelled"})
  void retiresTheActualSubmittedTaskAndRegistryEntry(String mode) throws Exception {
    var releaseWorker = new CountDownLatch(1);
    var terminal = new CompletableFuture<RequestOutcome>();
    var completions = new AtomicInteger();
    var invoked = new AtomicInteger();

    try (var workers = Executors.newSingleThreadExecutor()) {
      var executor = new TrackingExecutor(workers, "rejected".equals(mode));
      var app = new Shoostr();

      try (Closeable appCleanup = app::close;
          var drivers = Executors.newVirtualThreadPerTaskExecutor()) {
        if ("cancelled".equals(mode)) {
          occupyWorker(workers, releaseWorker);
        }

        configureApplication(app, executor, mode, terminal, completions, invoked);

        try (var test = TestServer.start(app)) {
          var response = submitRequest(test, drivers);

          try {
            var task = executor.submitted.get(3, TimeUnit.SECONDS);
            if ("cancelled".equals(mode)) {
              retiredReferences(task).forEach((field, retired) -> assertFalse(retired, field));
              app.close();
            } else {
              var result = response.get(5, TimeUnit.SECONDS);
              assertEquals(expectedStatus(mode), result.statusCode());
              assertEquals(mode, result.body());
            }

            terminal.get(3, TimeUnit.SECONDS);

            if (!"rejected".equals(mode) && !"cancelled".equals(mode)) {
              assertNull(executor.executed.get(3, TimeUnit.SECONDS));
            }

            assertEquals(1, completions.get());
            retiredReferences(task).forEach((field, retired) -> assertTrue(retired, field));
            assertEquals(0, pendingMatches(app));

            releaseWorker.countDown();
            if ("cancelled".equals(mode)) {
              assertNull(executor.executed.get(3, TimeUnit.SECONDS));

              try {
                response.get(3, TimeUnit.SECONDS);
              } catch (ExecutionException expectedTransportClosure) {
                // Native shutdown can close the transport before an HTTP response is written.
              }
            }

            assertEquals("success".equals(mode) || "failure".equals(mode) ? 1 : 0, invoked.get());
            assertEquals(1, completions.get());
            assertFalse(workers.isShutdown());
          } finally {
            releaseWorker.countDown();
            response.cancel(true);
          }
        }
      } finally {
        releaseWorker.countDown();
      }
    }
  }

  @Test
  void doesNotSubmitSelectedWorkAfterShutdownStopsAdmission() throws Exception {
    var headersEntered = new CountDownLatch(1);
    var releaseHeaders = new CountDownLatch(1);
    var closeEntered = new CountDownLatch(1);
    var releaseClose = new CountDownLatch(1);
    var terminal = new CompletableFuture<RequestOutcome>();
    var completions = new AtomicInteger();
    var invoked = new AtomicInteger();

    try (var workers = Executors.newSingleThreadExecutor()) {
      var executor = new TrackingExecutor(workers, false);
      var app = new Shoostr();

      try (Closeable appCleanup = app::close;
          var drivers = Executors.newVirtualThreadPerTaskExecutor()) {
        configureApplication(app, executor, "success", terminal, completions, invoked);
        app.onRequestHeaders((request, response) -> {
          headersEntered.countDown();
          assertTrue(releaseHeaders.await(5, TimeUnit.SECONDS));
        });
        app.beforeClose(() -> {
          closeEntered.countDown();
          awaitRelease(releaseClose);
        });

        try (var test = TestServer.start(app)) {
          var response = submitRequest(test, drivers);
          Future<?> closing = null;

          try {
            assertTrue(headersEntered.await(3, TimeUnit.SECONDS));
            closing =
                drivers.submit(() -> {
                  app.close();
                  return null;
                });
            assertTrue(closeEntered.await(3, TimeUnit.SECONDS));
            releaseHeaders.countDown();

            var result = response.get(5, TimeUnit.SECONDS);
            assertEquals(503, result.statusCode());
            assertEquals("rejected", result.body());
            terminal.get(3, TimeUnit.SECONDS);
            assertEquals(1, completions.get());
            assertEquals(0, invoked.get());
            assertFalse(executor.submitted.isDone());
            assertEquals(0, pendingMatches(app));
          } finally {
            releaseHeaders.countDown();
            releaseClose.countDown();
            if (closing != null) {
              closing.get(5, TimeUnit.SECONDS);
            }

            response.cancel(true);
          }
        }
      } finally {
        releaseHeaders.countDown();
        releaseClose.countDown();
      }
    }
  }

  private Future<HttpResponse<String>> submitRequest(TestServer test, ExecutorService drivers) {
    return drivers.submit(() ->
        test.send(
            request -> request.path("/selected").timeout(Duration.ofSeconds(3)),
            HttpResponse.BodyHandlers.ofString()));
  }

  private void awaitRelease(CountDownLatch release) {
    try {
      assertTrue(release.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(failure);
    }
  }

  private void occupyWorker(ExecutorService workers, CountDownLatch release) throws Exception {
    var entered = new CountDownLatch(1);
    workers.execute(() -> {
      entered.countDown();

      try {
        release.await();
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
      }
    });
    assertTrue(entered.await(3, TimeUnit.SECONDS));
  }

  private void configureApplication(
      Shoostr app,
      ExecutorService executor,
      String mode,
      CompletableFuture<RequestOutcome> terminal,
      AtomicInteger completions,
      AtomicInteger invoked) {
    app.modifyServer(server -> server.setStopTimeout(0));
    app.afterRequest(outcome -> {
      completions.incrementAndGet();
      terminal.complete(outcome);
    });
    app.exception(
        RejectedExecutionException.class,
        (ignoredFailure, ignoredRequest, response) -> response.status(503).text("rejected"));
    app.exception(
        IllegalArgumentException.class,
        (ignoredFailure, ignoredRequest, response) -> response.status(409).text("failure"));
    app.routes()
        .get(
            "/selected",
            executor,
            (request, response) -> {
              invoked.incrementAndGet();
              if ("failure".equals(mode)) {
                throw new IllegalArgumentException("handler failed");
              }

              response.text("success");
            });
  }

  private int expectedStatus(String mode) {
    return switch (mode) {
      case "failure" -> 409;
      case "rejected" -> 503;
      default -> 200;
    };
  }

  private Map<String, Boolean> retiredReferences(Runnable task)
      throws ReflectiveOperationException {
    var field = task.getClass().getDeclaredField("lock");
    field.setAccessible(true);
    var lock = (ReentrantLock) field.get(task);
    return Objects.requireNonNull(getUnderLock(lock, () -> lockedRetiredReferences(task)));
  }

  private Map<String, Boolean> lockedRetiredReferences(Runnable task)
      throws ReflectiveOperationException {
    var result = new LinkedHashMap<String, Boolean>();
    for (var name :
        List.of(
            "owner", "endpoint", "request", "response", "callback", "observation", "admission")) {
      var field = task.getClass().getDeclaredField(name);
      field.setAccessible(true);
      result.put(name, field.get(task) == null);
    }
    return result;
  }

  private int pendingMatches(Shoostr app) throws ReflectiveOperationException {
    var lockField = Shoostr.class.getDeclaredField("pendingMatchesLock");
    lockField.setAccessible(true);
    var lock = (ReentrantLock) lockField.get(app);
    var tasksField = Shoostr.class.getDeclaredField("pendingMatches");
    tasksField.setAccessible(true);
    return Objects.requireNonNull(
        getUnderLock(lock, () -> ((Collection<?>) tasksField.get(app)).size()));
  }

  private static final class TrackingExecutor extends AbstractExecutorService {
    private final ExecutorService delegate;
    private final boolean rejected;
    private final Consumer<Runnable> beforeRejection;
    private final CompletableFuture<Runnable> submitted;
    private final CompletableFuture<Throwable> executed;

    private TrackingExecutor(ExecutorService delegate, boolean rejected) {
      this(delegate, rejected, ignored -> {});
    }

    private TrackingExecutor(
        ExecutorService delegate, boolean rejected, Consumer<Runnable> beforeRejection) {
      this.delegate = Objects.requireNonNull(delegate);
      this.rejected = rejected;
      this.beforeRejection = Objects.requireNonNull(beforeRejection);
      this.submitted = new CompletableFuture<>();
      this.executed = new CompletableFuture<>();
    }

    @Override
    public void execute(Runnable command) {
      submitted.complete(command);
      if (rejected) {
        beforeRejection.accept(command);
        throw new RejectedExecutionException("selected executor rejected");
      }

      delegate.execute(() -> {
        try {
          command.run();
        } catch (RuntimeException | Error failure) {
          executed.complete(failure);
          throw failure;
        } finally {
          executed.complete(null);
        }
      });
    }

    @Override
    public void shutdown() {
      delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
      return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
      return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
      return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      return delegate.awaitTermination(timeout, unit);
    }
  }
}
