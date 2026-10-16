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

  @Test
  void writesFixedTotalWithSmallExplicitChunkFlushes() throws Exception {
    var fixedTotal = fixedTotalState(128, 8192, true);

    assertEquals(16384, benchmark.fixedTotalWriteMatrix(fixedTotal));
    assertEquals(130, benchmark.writes());
  }

  @Test
  void writesFixedTotalWithinLargeAutomaticBuffer() throws Exception {
    var fixedTotal = fixedTotalState(1024, 16384, false);

    assertEquals(16384, benchmark.fixedTotalWriteMatrix(fixedTotal));
    assertEquals(3, benchmark.writes());
  }

  @Test
  void writesFixedTotalWithFullBufferChunkAndExplicitFlush() throws Exception {
    var fixedTotal = fixedTotalState(16384, 8192, true);

    assertEquals(16384, benchmark.fixedTotalWriteMatrix(fixedTotal));
    assertEquals(5, benchmark.writes());
  }

  @Test
  void writesFixedTotalAtSmallAutomaticBufferBoundaries() throws Exception {
    var fixedTotal = fixedTotalState(1024, 1024, false);

    assertEquals(16384, benchmark.fixedTotalWriteMatrix(fixedTotal));
    assertEquals(18, benchmark.writes());
  }

  private StreamBufferBenchmark.FixedTotalWriteState fixedTotalState(
      int chunkBytes, int bufferBytes, boolean explicitFlush) {
    var fixedTotal = new StreamBufferBenchmark.FixedTotalWriteState();
    fixedTotal.configure(chunkBytes, bufferBytes, explicitFlush);
    fixedTotal.setupTrial();
    return fixedTotal;
  }
}
