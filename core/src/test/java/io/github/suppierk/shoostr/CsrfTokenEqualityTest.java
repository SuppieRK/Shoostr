package io.github.suppierk.shoostr;

import nl.jqno.equalsverifier.EqualsVerifier;
import org.junit.jupiter.api.Test;

class CsrfTokenEqualityTest {
  @Test
  void satisfiesEqualsAndHashCodeContract() throws ClassNotFoundException {
    var token = Class.forName(Csrf.class.getName() + "$Token");

    EqualsVerifier.forClass(token).withNonnullFields("sessionId", "value").verify();
  }
}
