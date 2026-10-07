package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SchedulingBenchmarkTest {
  @ParameterizedTest
  @CsvSource({"queued,1", "queued,32", "virtual,1", "virtual,32", "coalesced,1", "coalesced,4096"})
  void completesEveryFiniteResponseExactlyOnceOnVirtualThreads(String pool, int batchSize)
      throws Exception {
    var fixture = new SchedulingBenchmark();
    fixture.pool = pool;
    fixture.batchSize = batchSize;

    try {
      fixture.setup();
      assertEquals(13, fixture.responses());

      for (var exchange : fixture.exchanges) {
        assertEquals(1, exchange.invocations);
        assertEquals(1, exchange.writes);
        assertEquals(1, exchange.completions);
        assertTrue(exchange.virtual);
        assertEquals(!"queued".equals(pool), exchange.producedVirtually);
        var body = new byte[exchange.submitted.remaining()];
        exchange.submitted.duplicate().get(body);
        assertArrayEquals("Hello, World!".getBytes(StandardCharsets.UTF_8), body);
      }
    } finally {
      fixture.close();
    }
  }

  @ParameterizedTest
  @CsvSource({"queued,1", "queued,32", "virtual,1", "virtual,32", "coalesced,1", "coalesced,32"})
  void selectsTheIntendedStrategyAcrossSuccessiveBatches(String pool, int batchSize)
      throws Exception {
    var fixture = new SchedulingBenchmark();
    fixture.pool = pool;
    fixture.batchSize = batchSize;

    try {
      fixture.setup();
      for (int iteration = 0; iteration < 100; iteration++) {
        assertEquals(13, fixture.responses());
      }

      for (var exchange : fixture.exchanges) {
        assertEquals(100, exchange.invocations);
      }

      assertEquals(0, fixture.strategy.getPCTasksConsumed());
      assertEquals(0, fixture.strategy.getPICTasksExecuted());
      assertEquals(
          100L * batchSize,
          fixture.strategy.getPECTasksExecuted() + fixture.strategy.getEPCTasksConsumed());
      if ("queued".equals(pool)) {
        assertTrue(fixture.strategy.getPECTasksExecuted() > 0);
      } else {
        // Adaptive production can legitimately fall back to PEC during a producer race.
        assertTrue(fixture.strategy.getEPCTasksConsumed() > 0);
      }
    } finally {
      fixture.close();
    }
  }
}
