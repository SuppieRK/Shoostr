package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import nl.jqno.equalsverifier.EqualsVerifier;
import org.junit.jupiter.api.Test;

class CorsEqualityTest {
  @Test
  void satisfiesEqualsAndHashCodeContract() {
    EqualsVerifier.forClass(Cors.class).withNonnullFields("policy").verify();
  }

  @Test
  void equivalentPoliciesProduceEqualCorsValuesWithMatchingHashes() {
    var first = new Cors(CorsPolicy.builder().origins("https://client.example").build());
    var second = new Cors(CorsPolicy.builder().origins("https://client.example").build());

    assertEquals(first.policy(), second.policy());
    assertNotSame(first, second);
    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());

    var owners = new HashSet<Cors>();
    assertTrue(owners.add(first));
    assertFalse(owners.add(second));
    assertEquals(1, owners.size());
  }

  @Test
  void rejectsSelectingDistinctOwnersWithEqualPoliciesForOneEndpoint() throws Exception {
    var policy = CorsPolicy.builder().origins("https://client.example").build();
    var first = new Cors(policy);
    var second = new Cors(policy);

    try (var app = new Shoostr().extensions(first, second)) {
      var routes = app.routes();
      assertThrows(
          IllegalArgumentException.class,
          () ->
              routes.get(
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
