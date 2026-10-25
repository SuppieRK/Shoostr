package io.github.suppierk.shoostr;

import nl.jqno.equalsverifier.EqualsVerifier;
import org.junit.jupiter.api.Test;

class CorsPolicyEqualityTest {
  @Test
  void satisfiesEqualsAndHashCodeContract() {
    EqualsVerifier.forClass(CorsPolicy.class)
        .withNonnullFields("origins", "methods", "allowedHeaders", "exposedHeaders")
        .verify();
  }
}
