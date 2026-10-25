package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.junit.jupiter.api.Test;

class CorsConfigurationEqualityTest {
  @Test
  void satisfiesEqualsAndHashCodeContract() throws Exception {
    var cors = new Cors(CorsPolicy.builder().origins("https://first.example").build());
    var secondPolicy = CorsPolicy.builder().origins("https://second.example").build();
    var endpoints = new ArrayList<Extensions>();

    try (var app = new Shoostr().extensions(cors)) {
      // Real scopes avoid recursively fabricating the extension selection graph.
      app.routes()
          .get(
              "/first",
              (ignored, ignored2) -> {},
              extensions -> {
                extensions.with(cors);
                endpoints.add(extensions);
              })
          .get(
              "/second",
              (ignored3, ignored4) -> {},
              extensions -> {
                extensions.with(cors).policy(secondPolicy);
                endpoints.add(extensions);
              });

      // Each mutable configurator belongs to one registration scope.
      EqualsVerifier.forClass(Cors.Configuration.class)
          .withPrefabValues(Extensions.class, endpoints.get(0), endpoints.get(1))
          .withNonnullFields("endpoint", "cors", "policy")
          .suppress(Warning.INHERITED_DIRECTLY_FROM_OBJECT, Warning.ALL_FIELDS_SHOULD_BE_USED)
          .verify();
    }
  }

  @Test
  void independentScopesRetainConfigurationIdentityWhenTheirPoliciesChange() throws Exception {
    var cors = new Cors(CorsPolicy.builder().origins("https://client.example").build());
    var replacement = CorsPolicy.builder().origins("https://admin.example").build();
    var configurations = new HashSet<Cors.Configuration>();

    try (var app = new Shoostr().extensions(cors)) {
      app.routes()
          .get(
              "/first",
              (ignored, ignored2) -> {},
              extensions -> {
                var configuration = extensions.with(cors);
                var hash = configuration.hashCode();
                assertTrue(configurations.add(configuration));

                configuration.policy(replacement);

                assertSame(configuration, extensions.with(cors));
                assertEquals(hash, configuration.hashCode());
                assertTrue(configurations.contains(configuration));
              })
          .get(
              "/second",
              (ignored3, ignored4) -> {},
              extensions -> {
                var configuration = extensions.with(cors);
                assertSame(configuration, extensions.with(cors));
                assertTrue(configurations.add(configuration));
              });
    }

    assertEquals(2, configurations.size());
  }
}
