package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TelemetryObservationBenchmarkTest {
  @ParameterizedTest
  @MethodSource("cases")
  void completesEachObserverFixtureAndRestoresItsCallingContext(
      String configuration, String parent, String outcome) throws Exception {
    var benchmark = new TelemetryObservationBenchmark();
    benchmark.configuration = configuration;
    benchmark.parent = parent;
    benchmark.outcome = outcome;
    var original = Context.current().with(ContextKey.named("benchmark-caller"), "caller");

    try (var ignored = original.makeCurrent()) {
      benchmark.setup();
      assertSame(original, Context.current());
      assertNotNull(benchmark.observe());
      assertSame(original, Context.current());
    } finally {
      benchmark.close();
    }
  }

  private static Stream<Arguments> cases() {
    return Stream.of("minimal", "micrometer", "otelNoop", "otelDropped", "otelRecording")
        .flatMap(
            configuration ->
                Stream.of("absent", "remote")
                    .flatMap(
                        parent ->
                            Stream.of("matched", "missed", "serverError")
                                .map(outcome -> Arguments.of(configuration, parent, outcome))));
  }
}
