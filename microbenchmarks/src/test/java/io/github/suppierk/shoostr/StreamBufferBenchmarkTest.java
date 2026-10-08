package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StreamBufferBenchmarkTest {
  private StreamBufferBenchmark benchmark;

  @BeforeEach
  void preparesTransport() {
    benchmark = new StreamBufferBenchmark();
    benchmark.setupTrial();
  }

  @Test
  void constructsOneStreamBeforeAnyBodyBytes() throws Exception {
    assertNotNull(benchmark.construct());
    assertEquals(1, benchmark.writes());
  }

  @Test
  void writesSmallExplicitlyFlushedPayload() throws Exception {
    assertEquals(128, benchmark.smallExplicitFlush());
    assertEquals(3, benchmark.writes());
  }

  @Test
  void writesSixteenExplicitlyFlushedKilobyteChunks() throws Exception {
    assertEquals(16384, benchmark.repeatedExplicitFlush());
    assertEquals(18, benchmark.writes());
    assertTrue(benchmark.submitted().capacity() >= 1024);
  }

  @Test
  void writesLargePayloadAtConfiguredAutomaticFlushBoundaries() throws Exception {
    assertEquals(65536, benchmark.largeWrite());
    assertEquals(10, benchmark.writes());
  }

  @Test
  void writesIncrementalPayloadAtConfiguredBoundaryDespitePhysicalGrowth() throws Exception {
    assertEquals(300, benchmark.incrementalGrowth());
    assertEquals(3, benchmark.writes());
  }
}
