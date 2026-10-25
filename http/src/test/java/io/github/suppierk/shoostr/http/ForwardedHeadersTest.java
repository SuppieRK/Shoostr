package io.github.suppierk.shoostr.http;

import nl.jqno.equalsverifier.EqualsVerifier;
import org.junit.jupiter.api.Test;

class ForwardedHeadersTest {
  @Test
  void satisfiesEqualsAndHashCodeContract() {
    EqualsVerifier.forClass(ForwardedHeaders.class).verify();
  }
}
