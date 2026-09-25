package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RouteLookupBenchmarkTest {
  @ParameterizedTest
  @MethodSource("cases")
  void bothIndexesResolveEveryQueryIdentically(
      int count, String shape, String outcome, String distribution) {
    var fixture = new RouteLookupBenchmark();
    fixture.routeCount = count;
    fixture.shape = shape;
    fixture.outcome = outcome;
    fixture.distribution = distribution;
    fixture.setup();
    var expected = new Object[16384];
    for (int i = 0; i < expected.length; i++) {
      expected[i] = fixture.mapReused();
      switch (outcome) {
        case "hit" -> assertTrue(expected[i] instanceof Handler);
        case "earlyMiss", "lateMiss" -> assertSame(RouteLookupBenchmark.NOT_FOUND, expected[i]);
        case "wrongMethod" -> assertSame(RouteLookupBenchmark.METHOD_NOT_ALLOWED, expected[i]);
        default -> assertTrue(expected[i] != null);
      }
    }
    for (Object value : expected) {
      assertSame(value, fixture.radixReused());
    }
    for (Object value : expected) {
      assertSame(value, fixture.mapFresh());
    }
    for (Object value : expected) {
      assertSame(value, fixture.radixFresh());
    }
  }

  private static Stream<Arguments> cases() {
    return Stream.of(5, 50, 500, 5000)
        .flatMap(
            count ->
                Stream.of("shared", "divergent", "prefix")
                    .flatMap(
                        shape ->
                            Stream.of("hit", "earlyMiss", "lateMiss", "wrongMethod", "mixed")
                                .flatMap(
                                    outcome ->
                                        Stream.of("uniform", "hot")
                                            .map(
                                                distribution ->
                                                    Arguments.of(
                                                        count, shape, outcome, distribution)))));
  }
}
