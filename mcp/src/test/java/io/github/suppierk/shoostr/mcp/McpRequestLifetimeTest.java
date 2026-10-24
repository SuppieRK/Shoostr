package io.github.suppierk.shoostr.mcp;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.exceptions.AuthenticationRequiredException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class McpRequestLifetimeTest {
  @Test
  @Timeout(10)
  void doesNotInterruptAuthenticationErrorRenderingAfterAnEarlierExpiryRejection()
      throws Exception {
    var schedulerEntered = new CountDownLatch(1);
    var releaseScheduler = new CountDownLatch(1);

    try (var deadlines = new ScheduledThreadPoolExecutor(1)) {
      var heldDeadlineWorker =
          deadlines.submit(
              () -> {
                schedulerEntered.countDown();
                assertTrue(releaseScheduler.await(5, TimeUnit.SECONDS));
                return null;
              });

      try (var lifetime = new McpRequestLifetime(deadlines, "Bearer error=\"invalid_token\"")) {
        try {
          assertTrue(schedulerEntered.await(3, TimeUnit.SECONDS));
          var expiry = Instant.now().plusMillis(100);
          lifetime.arm(expiry);

          // Expire wall-clock admission while the actual deadline worker is deliberately held.
          await()
              .pollInSameThread()
              .pollDelay(Duration.ZERO)
              .pollInterval(Duration.ofMillis(5))
              .atMost(Duration.ofSeconds(3))
              .until(() -> !expiry.isAfter(Instant.now()));
          assertThrows(AuthenticationRequiredException.class, () -> lifetime.beforeFlush(false));
          lifetime.beforeFlush(true);

          releaseScheduler.countDown();
          heldDeadlineWorker.get(3, TimeUnit.SECONDS);
          // This later marker runs after the overdue deadline, before the owner is released.
          assertEquals("drained", deadlines.submit(() -> "drained").get(3, TimeUnit.SECONDS));
          assertFalse(Thread.currentThread().isInterrupted());
          lifetime.beforeFlush(true);
        } finally {
          releaseScheduler.countDown();
        }
      }
    }
  }
}
