package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Closeable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class ShoostrLockTest {
  @Test
  void interruptedPortLookupWaitsForStartupAndPreservesInterruptStatus() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var attempted = new CountDownLatch(1);
    var app = pausedStartupApplication(entered, release);

    try (Closeable appCleanup = app::close;
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var startup = workers.submit(app::start);

      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var lookup = workers.submit(() -> interruptedPort(app, attempted));

        assertTrue(attempted.await(5, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> lookup.get(150, TimeUnit.MILLISECONDS));
        release.countDown();

        assertSame(app, startup.get(5, TimeUnit.SECONDS));
        assertEquals(app.port(), lookup.get(5, TimeUnit.SECONDS));
      } finally {
        release.countDown();
      }
    }
  }

  private Shoostr pausedStartupApplication(CountDownLatch entered, CountDownLatch release) {
    var options = new Options();
    options.setHost("127.0.0.1");
    options.setPort(0);

    return new Shoostr(options)
        .modifyServer(server -> {
          entered.countDown();

          try {
            assertTrue(release.await(5, TimeUnit.SECONDS));
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
          }
        });
  }

  private int interruptedPort(Shoostr app, CountDownLatch attempted) {
    Thread.currentThread().interrupt();
    attempted.countDown();

    try {
      var port = app.port();
      assertTrue(Thread.currentThread().isInterrupted());

      return port;
    } finally {
      Thread.interrupted();
    }
  }
}
