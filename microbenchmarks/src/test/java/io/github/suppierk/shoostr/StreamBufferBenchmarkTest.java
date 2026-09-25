package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
    benchmark.setupInvocation();
    assertNotNull(benchmark.construct());
    assertEquals(1, benchmark.writes());
  }

  @Test
  void writesSmallExplicitlyFlushedPayload() throws Exception {
    benchmark.setupInvocation();
    assertEquals(128, benchmark.smallExplicitFlush());
    assertEquals(3, benchmark.writes());
  }

  @Test
  void writesSixteenExplicitlyFlushedKilobyteChunks() throws Exception {
    benchmark.setupInvocation();
    assertEquals(16384, benchmark.repeatedExplicitFlush());
    assertEquals(18, benchmark.writes());
  }

  @Test
  void writesLargePayloadAtConfiguredAutomaticFlushBoundaries() throws Exception {
    benchmark.setupInvocation();
    assertEquals(65536, benchmark.largeWrite());
    assertEquals(10, benchmark.writes());
  }

  @Test
  void writesIncrementalPayloadAtConfiguredBoundaryDespitePhysicalGrowth() throws Exception {
    benchmark.setupInvocation();
    assertEquals(300, benchmark.incrementalGrowth());
    assertEquals(3, benchmark.writes());
  }
}
