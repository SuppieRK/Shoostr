package io.github.suppierk.shoostr;

import java.time.Duration;
import nl.jqno.equalsverifier.EqualsVerifier;
import org.junit.jupiter.api.Test;

class SseEventTest {
  @Test
  void satisfiesEqualsAndHashCodeContract() {
    var first = new SseEvent("first payload", "created", "first-id", Duration.ofMillis(100));
    var second = new SseEvent("second payload", "updated", "second-id", Duration.ofMillis(200));

    EqualsVerifier.forExamples(first, second).withNonnullFields("data").verify();
  }
}
