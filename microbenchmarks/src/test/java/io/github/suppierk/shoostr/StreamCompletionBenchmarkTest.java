package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Timeout(10)
class StreamCompletionBenchmarkTest {
  @ParameterizedTest
  @CsvSource({"fresh,inline", "shared,inline", "fresh,deferred", "shared,deferred"})
  void completesEveryWriteBeforeStartingTheNextStream(String blocker, String completion)
      throws Exception {
    var fixture = new StreamCompletionBenchmark();
    fixture.blocker = blocker;
    fixture.completion = completion;

    try {
      fixture.setup();
      assertEquals(18, fixture.flushes());
      assertEquals(18, fixture.flushes());
    } finally {
      fixture.close();
    }
  }
}
