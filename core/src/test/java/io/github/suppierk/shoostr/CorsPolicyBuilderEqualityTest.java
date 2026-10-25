package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.junit.jupiter.api.Test;

class CorsPolicyBuilderEqualityTest {
  @Test
  void satisfiesEqualsAndHashCodeContract() {
    // Builders retain Object's instance equality.
    EqualsVerifier.forClass(CorsPolicy.Builder.class)
        .withNonnullFields("origins", "methods", "allowedHeaders", "exposedHeaders")
        .suppress(Warning.INHERITED_DIRECTLY_FROM_OBJECT, Warning.ALL_FIELDS_SHOULD_BE_USED)
        .verify();
  }

  @Test
  void separateBuildersRetainIdentityWhileTheirProductsHaveValueEquality() {
    var first = CorsPolicy.builder().origins("https://client.example");
    var second = CorsPolicy.builder().origins("https://client.example");

    assertNotEquals(first, second);
    assertEquals(first.build(), second.build());
  }
}
