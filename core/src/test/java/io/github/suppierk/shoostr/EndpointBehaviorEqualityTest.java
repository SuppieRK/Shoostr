package io.github.suppierk.shoostr;

import java.util.Set;
import nl.jqno.equalsverifier.EqualsVerifier;
import org.junit.jupiter.api.Test;

class EndpointBehaviorEqualityTest {
  @Test
  void satisfiesEqualsAndHashCodeContract() {
    var firstCsrf = Csrf.builder().trustedOrigins(Set.of("https://first.example")).build();
    var secondCsrf = Csrf.builder().trustedOrigins(Set.of("https://second.example")).build();

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
