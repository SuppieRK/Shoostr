package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ObservationLoggingTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "factory",
        "attach",
        "close",
        "attached-close",
        "complete",
        "terminal",
        "local",
        "healthy"
      })
  void reportsTheOriginalCallbackFailureWithoutInventingCleanupFailures(String phase)
      throws Exception {
    var failure = new IllegalStateException("failed " + phase);
    var logged = new CompletableFuture<Throwable>();
    var finished = new CompletableFuture<Void>();
    var cleanupErrors = new AtomicInteger();
    var logger = Logger.getLogger(Shoostr.class.getName());
    var capture = captureFailure(failure, logged, cleanupErrors);
    logger.addHandler(capture);

    try {
      try (var workers = Executors.newSingleThreadExecutor()) {
        var app = new Shoostr();

        try (Closeable appCleanup = app::close) {
          app.observe(request -> {
            failAt(phase, "factory", failure);
            return observation(phase, failure);
          });
          app.afterRequest(outcome -> failAt(phase, "terminal", failure));
          app.afterRequest(outcome -> finished.complete(null));
          app.routes()
              .get(
                  "/observed",
                  workers,
                  (request, response) -> response.text("ok"),
                  lifecycle -> lifecycle.afterRequest(outcome -> failAt(phase, "local", failure)));

          try (var test = TestServer.start(app)) {
            assertEquals(200, test.send(request -> request.path("/observed")).statusCode());
            finished.get(5, TimeUnit.SECONDS);
            workers.submit(() -> {}).get(5, TimeUnit.SECONDS);

            if ("healthy".equals(phase)) {
              assertEquals(0, cleanupErrors.get());
            } else {
              assertSame(failure, logged.get(5, TimeUnit.SECONDS));
            }
          }
        }
      }
    } finally {
      logger.removeHandler(capture);
      capture.close();
    }
  }

  private RequestObservation observation(String phase, IllegalStateException failure) {
    return new RequestObservation() {
      @Override
      public AutoCloseable attach() {
        failAt(phase, "attach", failure);
        return () -> failAt(phase, "attached-close", failure);
      }

      @Override
      public void close() {
        failAt(phase, "close", failure);
      }

      @Override
      public void complete(RequestOutcome outcome) {
        failAt(phase, "complete", failure);
      }
    };
  }

  private void failAt(String phase, String target, RuntimeException failure) {
    if (target.equals(phase)) {
      throw failure;
    }
  }

  @SuppressWarnings("ReferenceEquality") // Match the exact callback failure across logging threads.
  private Handler captureFailure(
      Throwable failure, CompletableFuture<Throwable> logged, AtomicInteger cleanupErrors) {
    return new Handler() {
      @Override
      public void publish(LogRecord entry) {
        if (entry.getThrown() == failure) {
          logged.complete(failure);
        }

        if ("Request instrumentation scope cleanup failed".equals(entry.getMessage())) {
          cleanupErrors.incrementAndGet();
        }
      }

      @Override
      public void flush() {
        /* Captures records immediately. */
      }

      @Override
      public void close() {
        /* Holds no resources. */
      }
    };
  }
}
