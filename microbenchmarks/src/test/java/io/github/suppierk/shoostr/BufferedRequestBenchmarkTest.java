package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class BufferedRequestBenchmarkTest {
  @ParameterizedTest
  @MethodSource("cases")
  void readsRealJettyAdapterForEachMeasuredBodyAndHeaderShape(int size, String length, int headers)
      throws Exception {
    var benchmark = new BufferedRequestBenchmark();
    benchmark.bytes = size;
    benchmark.length = length;
    benchmark.headers = headers;
    benchmark.setup();
    var expected = new byte[size];
    java.util.Arrays.fill(expected, (byte) 0x5A);
    assertArrayEquals(expected, benchmark.bodyBytes());
  }

  private static Stream<Arguments> cases() {
    return Stream.of(0, 1024, 16384, 1048576)
        .flatMap(
            bytes ->
                Stream.of("known", "unknown")
                    .flatMap(
                        length ->
                            Stream.of(0, 4, 12)
                                .map(headers -> Arguments.of(bytes, length, headers))));
  }
}
