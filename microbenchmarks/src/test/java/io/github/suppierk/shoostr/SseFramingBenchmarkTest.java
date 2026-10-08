package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SseFramingBenchmarkTest {
  @ParameterizedTest
  @MethodSource("cases")
  void sendsExactlyOneEventWithTheExpectedFramingBytes(
      int payloadBytes, String lineShape, String eventShape) throws Exception {
    var benchmark = new SseFramingBenchmark();
    benchmark.payloadBytes = payloadBytes;
    benchmark.lineShape = lineShape;
    benchmark.eventShape = eventShape;
    benchmark.setupTrial();

    benchmark.send();

    int dataFields = "single".equals(lineShape) ? 1 : 2;
    int metadata =
        "metadata".equals(eventShape) ? "event: update\nid: cursor-7\nretry: 2000\n".length() : 0;
    assertEquals(
        payloadBytes + dataFields * "data: ".length() + 2 + metadata, benchmark.bytesWritten());
  }

  @ParameterizedTest
  @MethodSource("cases")
  void resetsTransportCountersBeforeTheNextEventLifetime(
      int payloadBytes, String lineShape, String eventShape) throws Exception {
    var benchmark = new SseFramingBenchmark();
    benchmark.payloadBytes = payloadBytes;
    benchmark.lineShape = lineShape;
    benchmark.eventShape = eventShape;
    benchmark.setupTrial();

    int first = benchmark.send();
    int bytes = benchmark.bytesWritten();

    assertEquals(first, benchmark.send());
    assertEquals(bytes, benchmark.bytesWritten());
  }

  private static Stream<Arguments> cases() {
    return Stream.of(128, 65536)
        .flatMap(
            bytes ->
                Stream.of("single", "multiline")
                    .flatMap(
                        lines ->
                            Stream.of("data", "metadata")
                                .map(event -> Arguments.of(bytes, lines, event))));
  }
}
