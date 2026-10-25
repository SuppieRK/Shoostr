package io.github.suppierk.shoostr;

import java.util.Set;
import nl.jqno.equalsverifier.EqualsVerifier;
import org.junit.jupiter.api.Test;

class EndpointBehaviorEqualityTest {
  @Test
  void satisfiesEqualsAndHashCodeContract() {
    var firstCsrf = new Csrf(Set.of("https://first.example"));
    var secondCsrf = new Csrf(Set.of("https://second.example"));

    EqualsVerifier.forClass(EndpointBehavior.class)
        .withPrefabValues(Csrf.class, firstCsrf, secondCsrf)
        .withNonnullFields(
            "matched",
            "before",
            "after",
            "beforeFlush",
            "afterFlush",
            "observers",
            "instrumentation",
            "errors",
            "statuses",
            "conditions")
        .verify();
  }
}
