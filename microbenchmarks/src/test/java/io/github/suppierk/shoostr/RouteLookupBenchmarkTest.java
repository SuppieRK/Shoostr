package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.suppierk.shoostr.http.HttpMethods;
import java.util.Set;
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
        case "hit" -> assertInstanceOf(Handler.class, expected[i]);
        case "earlyMiss", "lateMiss" -> assertSame(RouteLookupBenchmark.NOT_FOUND, expected[i]);
        case "wrongMethod" -> assertSame(RouteLookupBenchmark.METHOD_NOT_ALLOWED, expected[i]);
        default -> assertNotNull(expected[i]);
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

  @ParameterizedTest
  @MethodSource("catchAllCases")
  void radixCatchAllQueriesHaveTheirIntendedOutcome(int count, String outcome) {
    var fixture = new RouteLookupBenchmark();
    fixture.routeCount = count;
    fixture.shape = "shared";
    fixture.outcome = outcome;
    fixture.distribution = "uniform";
    fixture.setup();

    for (String path : fixture.paths) {
      if ("catchAllMiss".equals(outcome)) {
        assertNull(fixture.radix.match(path, HttpMethods.GET));
        assertEquals(Set.of(), fixture.radix.allowedMethods(path));
      } else {
        var endpoint = fixture.radix.match(path, HttpMethods.GET);
        assertNotNull(endpoint);
        assertEquals(
            "/api/v1/accounts/" + path.split("/")[4] + "/orders/" + path.split("/")[6] + "/{*tail}",
            endpoint.routePattern());
        if ("catchAllWrongMethod".equals(outcome)) {
          assertEquals(
              Set.of(HttpMethods.GET, HttpMethods.POST), fixture.radix.allowedMethods(path));
        }
      }
    }

    for (int index = 0; index < fixture.paths.length; index++) {
      Object value = fixture.radixReused();
      if ("catchAllMiss".equals(outcome)) {
        assertSame(RouteLookupBenchmark.NOT_FOUND, value);
      } else if ("catchAllWrongMethod".equals(outcome)) {
        assertSame(RouteLookupBenchmark.METHOD_NOT_ALLOWED, value);
      } else {
        assertInstanceOf(Handler.class, value);
      }
    }
    for (int index = 0; index < fixture.paths.length; index++) {
      Object value = fixture.radixFresh();
      if ("catchAllMiss".equals(outcome)) {
        assertSame(RouteLookupBenchmark.NOT_FOUND, value);
      } else if ("catchAllWrongMethod".equals(outcome)) {
        assertSame(RouteLookupBenchmark.METHOD_NOT_ALLOWED, value);
      } else {
        assertInstanceOf(Handler.class, value);
      }
    }
  }

  private static Stream<Arguments> catchAllCases() {
    return Stream.of(5, 5000)
        .flatMap(
            count ->
                Stream.of("catchAllHit", "catchAllFallback", "catchAllWrongMethod", "catchAllMiss")
                    .map(outcome -> Arguments.of(count, outcome)));
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
