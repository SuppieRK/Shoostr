package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BufferedRequestBenchmarkTest {
  @ParameterizedTest
  @CsvSource({
    "0,known",
    "0,unknown",
    "1024,known",
    "1024,unknown",
    "16384,known",
    "16384,unknown",
    "1048576,known",
    "1048576,unknown"
  })
  void readsRealJettyAdapterForEachMeasuredBodyShape(int size, String length) throws Exception {
    var benchmark = new BufferedRequestBenchmark();
    benchmark.bytes = size;
    benchmark.length = length;
    benchmark.setup();
    var expected = new byte[size];
    java.util.Arrays.fill(expected, (byte) 0x5A);
    assertArrayEquals(expected, benchmark.bodyBytes());
  }
}
