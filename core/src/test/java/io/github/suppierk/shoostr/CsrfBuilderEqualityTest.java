package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.junit.jupiter.api.Test;

class CsrfBuilderEqualityTest {
  @Test
  void satisfiesEqualsAndHashCodeContract() {
    // A builder keeps intermediate configuration; built Csrf values compare origin permissions.
    EqualsVerifier.forClass(Csrf.Builder.class)
        .withNonnullFields("trustedOrigins")
        .suppress(Warning.INHERITED_DIRECTLY_FROM_OBJECT, Warning.ALL_FIELDS_SHOULD_BE_USED)
        .verify();
  }

  @Test
  void separateBuildersRetainIdentityWhileTheirProductsHaveValueEquality() {
    var first = Csrf.builder().trustedOrigins("https://client.example");
    var second = Csrf.builder().trustedOrigins("https://client.example");

    assertNotEquals(first, second);
    assertEquals(first.build(), second.build());
  }
}
