package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import nl.jqno.equalsverifier.EqualsVerifier;
import org.junit.jupiter.api.Test;

class CsrfEqualityTest {
  @Test
  void satisfiesEqualsAndHashCodeContract() {
    var firstRandom = new SecureRandom(new byte[] {1});
    var secondRandom = new SecureRandom(new byte[] {2});

    EqualsVerifier.forClass(Csrf.class)
        .withNonnullFields("random", "trustedOrigins")
        .withIgnoredFields("random")
        .withPrefabValues(SecureRandom.class, firstRandom, secondRandom)
        .verify();
  }

  @Test
  void equivalentOriginSetsProduceEqualValuesAndHashes() {
    var first =
        new Csrf(new LinkedHashSet<>(List.of("https://first.example", "https://second.example")));
    var second =
        new Csrf(new LinkedHashSet<>(List.of("https://second.example", "https://first.example")));

    assertNotSame(first, second);
    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());

    var values = new HashSet<Csrf>();
    assertTrue(values.add(first));
    assertFalse(values.add(second));
    assertEquals(1, values.size());
  }

  @Test
  void differentOriginPermissionsProduceUnequalValues() {
    var first = new Csrf(Set.of("https://first.example"));
    var second = new Csrf(Set.of("https://second.example"));

    assertNotEquals(first, second);
    assertNotEquals(new Csrf(), first);
  }

  @Test
  void rejectsSelectingDistinctEqualInstancesForOneEndpoint() throws Exception {
    var first = new Csrf(Set.of("https://client.example"));
    var second = new Csrf(Set.of("https://client.example"));

    assertEquals(first, second);

    try (var app = new Shoostr().sessions().extensions(first, second)) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              app.routes()
                  .get(
                      "/shared",
                      (ignored, ignored2) -> {},
                      extensions -> {
                        extensions.with(first);
                        extensions.with(second);
                      }));
      app.routes()
          .get("/first", (ignored3, ignored4) -> {}, extensions -> extensions.with(first))
          .get("/second", (ignored5, ignored6) -> {}, extensions -> extensions.with(second));
    }
  }
}
