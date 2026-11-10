package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Closeable;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.junit.jupiter.api.Test;

class CsrfEqualityTest {
  @Test
  void satisfiesEqualsAndHashCodeContract() throws Exception {
    var firstRandom = new SecureRandom(new byte[] {1});
    var secondRandom = new SecureRandom(new byte[] {2});

    var app = new Shoostr();

    try (Closeable cleanup = app::close) {
      var scopes = new ArrayList<Lifecycle>();
      app.routes()
          .get("/first", (request, response) -> {}, scopes::add)
          .get("/second", (request, response) -> {}, scopes::add);
      EqualsVerifier.forClass(Csrf.class)
          .withNonnullFields("random", "trustedOrigins")
          .withIgnoredFields("random", "lifecycle")
          .suppress(Warning.NONFINAL_FIELDS)
          .withPrefabValues(SecureRandom.class, firstRandom, secondRandom)
          .withPrefabValues(Lifecycle.class, scopes.get(0), scopes.get(1))
          .verify();
    }
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

    {
      var app = new Shoostr().sessions();

      try (Closeable appCleanup = app::close) {
        var routes = app.routes();

        assertThrows(
            IllegalArgumentException.class,
            () ->
                routes.get(
                    "/shared",
                    (ignored, ignoredResponse) -> {},
                    extensions -> {
                      extensions.withExtension(first);
                      extensions.withExtension(second);
                    }));

        app.routes()
            .get(
                "/first",
                (ignoredRequest, ignoredResponse) -> {},
                extensions -> extensions.withExtension(first))
            .get(
                "/second",
                (ignoredRequest, ignoredResponse) -> {},
                extensions -> extensions.withExtension(second));
      }
    }
  }
}
