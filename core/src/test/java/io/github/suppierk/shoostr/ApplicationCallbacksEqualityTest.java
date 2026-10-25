package io.github.suppierk.shoostr;

import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.junit.jupiter.api.Test;

class ApplicationCallbacksEqualityTest {
  @Test
  void satisfiesEqualsAndHashCodeContract() {
    // This collector holds intermediate registrations for one extension installation.
    EqualsVerifier.forClass(ApplicationCallbacks.class)
        .withNonnullFields(
            "headers",
            "matched",
            "before",
            "after",
            "beforeFlush",
            "afterFlush",
            "completed",
            "observations")
        .suppress(Warning.INHERITED_DIRECTLY_FROM_OBJECT, Warning.ALL_FIELDS_SHOULD_BE_USED)
        .verify();
  }
}
