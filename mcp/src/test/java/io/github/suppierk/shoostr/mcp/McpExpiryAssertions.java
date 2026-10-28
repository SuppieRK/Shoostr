package io.github.suppierk.shoostr.mcp;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;

final class McpExpiryAssertions {
  private McpExpiryAssertions() {}

  static void awaitExpiry(Instant expiry) {
    await("verified MCP token expiry")
        .pollInSameThread()
        .pollDelay(Duration.ZERO)
        .pollInterval(Duration.ofMillis(10))
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              var now = Instant.now();
              assertFalse(
                  now.isBefore(expiry), "Token remains fresh: now=" + now + ", expiry=" + expiry);
            });
  }

  static void awaitTermination(Instant expiry, BooleanSupplier terminated) {
    awaitExpiry(expiry);
    var wallStart = Instant.now();
    var nanoStart = System.nanoTime();
    // One reaction budget applies to every required completion, after the actual wall-clock expiry.
    await("MCP HTTP termination after verified expiry")
        .pollInSameThread()
        .pollDelay(Duration.ZERO)
        .pollInterval(Duration.ofMillis(10))
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () ->
                assertTrue(
                    terminated.getAsBoolean(),
                    () ->
                        "Unfinished expired request: expiry="
                            + expiry
                            + ", now="
                            + Instant.now()
                            + ", wallElapsed="
                            + Duration.between(wallStart, Instant.now())
                            + ", monotonicElapsed="
                            + Duration.ofNanos(System.nanoTime() - nanoStart)));
  }

  static void assertNotBeforeExpiry(Instant expiry, Instant observed) {
    assertFalse(
        observed.isBefore(expiry),
        "Premature termination: observed=" + observed + ", expiry=" + expiry);
  }
}
