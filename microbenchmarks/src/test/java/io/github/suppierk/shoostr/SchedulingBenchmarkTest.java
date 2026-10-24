package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SchedulingBenchmarkTest {
  @ParameterizedTest
  @CsvSource({
    "queued-platform,1",
    "queued-platform,32",
    "queued-platform,4096",
    "queued-virtual,1",
    "queued-virtual,32",
    "queued-virtual,4096"
  })
  void completesEveryFiniteResponseExactlyOnceOnTheSelectedThreadKind(String pool, int batchSize)
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
        assertEquals("queued-virtual".equals(pool), exchange.virtual);
        assertFalse(exchange.producedVirtually);
        var body = new byte[exchange.submitted.remaining()];
        exchange.submitted.duplicate().get(body);
        assertArrayEquals("Hello, World!".getBytes(StandardCharsets.UTF_8), body);
      }
    } finally {
      fixture.close();
    }
  }

  @ParameterizedTest
  @CsvSource({"queued-platform,1", "queued-platform,32", "queued-virtual,1", "queued-virtual,32"})
  void completesSuccessiveBatchesWithoutRepeatingOrLosingAnExchange(String pool, int batchSize)
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
        assertEquals(1, exchange.writes);
        assertEquals(1, exchange.completions);
        assertEquals("queued-virtual".equals(pool), exchange.virtual);
        assertFalse(exchange.producedVirtually);
      }
    } finally {
      fixture.close();
    }
  }
}
