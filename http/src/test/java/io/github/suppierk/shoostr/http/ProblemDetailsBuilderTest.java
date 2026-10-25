package io.github.suppierk.shoostr.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.junit.jupiter.api.Test;

class ProblemDetailsBuilderTest {
  @Test
  void satisfiesEqualsAndHashCodeContract() {
    // Builders retain Object's instance equality.
    EqualsVerifier.forClass(ProblemDetails.Builder.class)
        .withNonnullFields("extensions")
        .suppress(Warning.INHERITED_DIRECTLY_FROM_OBJECT, Warning.ALL_FIELDS_SHOULD_BE_USED)
        .verify();
  }

  @Test
  void separateBuildersRetainIdentityWhileTheirProductsHaveValueEquality() {
    var first = ProblemDetails.builder().title("Invalid order").status(422);
    var second = ProblemDetails.builder().title("Invalid order").status(422);

    assertNotEquals(first, second);
    assertEquals(first.build(), second.build());
  }
}
