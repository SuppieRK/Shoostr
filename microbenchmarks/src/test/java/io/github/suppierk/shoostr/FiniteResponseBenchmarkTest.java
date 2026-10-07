package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class FiniteResponseBenchmarkTest {
  @ParameterizedTest
  @MethodSource("cases")
  void stagesTheExpectedUtf8Bytes(int characters, String shape, String representation)
      throws Exception {
    var benchmark = new FiniteResponseBenchmark();
    benchmark.characters = characters;
    benchmark.shape = shape;
    benchmark.representation = representation;
    benchmark.setup();

    benchmark.stage();

    var expected = ("ascii".equals(shape) ? "x" : "é").repeat(characters);
    assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), benchmark.complete());
  }

  @ParameterizedTest
  @MethodSource("cases")
  void reusesTheOpenResponseWithoutAccumulatingEarlierStaging(
      int characters, String shape, String representation) throws Exception {
    var benchmark = new FiniteResponseBenchmark();
    benchmark.characters = characters;
    benchmark.shape = shape;
    benchmark.representation = representation;
    benchmark.setup();

    var first = benchmark.stage();
    assertSame(first, benchmark.stage());

    var expected = ("ascii".equals(shape) ? "x" : "é").repeat(characters);
    assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), benchmark.complete());
  }

  private static Stream<Arguments> cases() {
    return Stream.of(0, 13, 1024, 16384)
        .flatMap(
            characters ->
                Stream.of("ascii", "unicode")
                    .flatMap(
                        shape ->
                            Stream.of("bytes", "text")
                                .map(
                                    representation ->
                                        Arguments.of(characters, shape, representation))));
  }
}
