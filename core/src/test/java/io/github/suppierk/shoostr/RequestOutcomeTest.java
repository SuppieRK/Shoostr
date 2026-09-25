package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RequestOutcomeTest {
  @ParameterizedTest
  @ValueSource(ints = {0, 101, 200, 599})
  void acceptsUncommittedUpgradeAndFinalHttpStatuses(int status) {
    assertEquals(status, new RequestOutcome("GET", null, status, 0, null, null).statusCode());
  }

  @Test
  @SuppressWarnings("NullAway") // Null method is deliberately rejected at construction.
  void rejectsNullMethodAtConstruction() {
    assertThrows(
        NullPointerException.class, () -> new RequestOutcome(null, null, 0, 0, null, null));
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 199, 600})
  void rejectsStatusOutsideUncommittedOrFinalHttpRange(int status) {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RequestOutcome("GET", null, status, 0, null, null));
  }

  @Test
  void rejectsNegativeDurationAtConstruction() {
    assertThrows(
        IllegalArgumentException.class, () -> new RequestOutcome("GET", null, 200, -1, null, null));
  }
}
