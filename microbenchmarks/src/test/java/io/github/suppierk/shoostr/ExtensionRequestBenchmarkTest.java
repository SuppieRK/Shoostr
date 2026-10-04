package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ExtensionRequestBenchmarkTest {
  @ParameterizedTest
  @MethodSource("cases")
  void dispatchesEachConfigurationTableAndOutcome(String configuration, int groups, String outcome)
      throws Exception {
    var benchmark = new ExtensionRequestBenchmark();
    benchmark.configuration = configuration;
    benchmark.groups = groups;
    benchmark.outcome = outcome;

    try {
      benchmark.setup();
      String expected =
          "missed".equals(outcome) ? "404" : "wrongMethod".equals(outcome) ? "405" : "200";
      assertTrue(benchmark.request().startsWith("HTTP/1.1 " + expected));
    } finally {
      benchmark.close();
    }
  }

  private static Stream<Arguments> cases() {
    return Stream.of("plain", "inactive", "callbacks", "authentication")
        .flatMap(
            configuration ->
                Stream.of(1, 1000)
                    .flatMap(
                        groups ->
                            Stream.of("matched", "missed", "wrongMethod")
                                .map(outcome -> Arguments.of(configuration, groups, outcome))));
  }
}
