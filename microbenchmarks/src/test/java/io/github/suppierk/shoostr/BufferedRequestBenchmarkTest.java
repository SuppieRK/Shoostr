package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
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
    Arrays.fill(expected, (byte) 0x5A);
    assertArrayEquals(expected, benchmark.bodyBytes());
  }

  @ParameterizedTest
  @MethodSource("textCases")
  void decodesTheConfiguredTextThroughTheBoundedRequestAdapter(
      int size, String length, String charset, String content, String expected) throws Exception {
    var benchmark = new BufferedRequestBenchmark();
    benchmark.bytes = size;
    benchmark.length = length;
    benchmark.headers = 4;
    benchmark.charset = charset;
    benchmark.content = content;
    benchmark.setup();
    assertEquals(expected, benchmark.bodyText());
  }

  private static Stream<Arguments> textCases() {
    return Stream.of("UTF-8", "ISO-8859-1", "UTF-16BE")
        .flatMap(
            charset ->
                Stream.of("ascii", "unicode")
                    .flatMap(
                        content -> {
                          int unitBytes =
                              "ISO-8859-1".equals(charset)
                                      || ("UTF-8".equals(charset) && "ascii".equals(content))
                                  ? 1
                                  : 2;
                          String unit = "ascii".equals(content) ? "Z" : "é";
                          return Stream.of(0, 1024, 16384, 1048576)
                              .flatMap(
                                  size ->
                                      Stream.of("known", "unknown")
                                          .map(
                                              length ->
                                                  Arguments.of(
                                                      size,
                                                      length,
                                                      charset,
                                                      content,
                                                      unit.repeat(size / unitBytes))));
                        }));
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
