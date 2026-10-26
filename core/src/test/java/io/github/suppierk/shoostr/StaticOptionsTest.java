package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import nl.jqno.equalsverifier.EqualsVerifier;
import org.junit.jupiter.api.Test;

class StaticOptionsTest {
  @Test
  void satisfiesEqualsAndHashCodeContract() {
    EqualsVerifier.forClass(StaticOptions.class).verify();
  }

  @Test
  void equivalentFilenamesCompareEqualRegardlessOfConfigurationOrder() {
    var first = StaticOptions.defaults().withWelcomeFile("index.html").withSpaFallback("app.html");
    var second = StaticOptions.defaults().withSpaFallback("app.html").withWelcomeFile("index.html");

    assertNotSame(first, second);
    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());
  }
}
