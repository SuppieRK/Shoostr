package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Closeable;
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
    var endpoints = new ArrayList<Lifecycle>();

    {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close) {
        // Real scopes avoid recursively fabricating the extension selection graph.
        app.routes()
            .get(
                "/first",
                (ignored, ignoredResponse) -> {},
                extensions -> {
                  extensions.withExtension(cors);
                  endpoints.add(extensions);
                })
            .get(
                "/second",
                (ignoredRequest, ignoredResponse) -> {},
                extensions -> {
                  extensions.withExtension(cors).policy(secondPolicy);
                  endpoints.add(extensions);
                });

        // Each mutable configurator belongs to one registration scope.
        EqualsVerifier.forClass(Cors.Configuration.class)
            .withPrefabValues(Lifecycle.class, endpoints.get(0), endpoints.get(1))
            .withPrefabValues(Cors.class, cors, new Cors(secondPolicy))
            .withNonnullFields("endpoint", "cors", "policy")
            .suppress(Warning.INHERITED_DIRECTLY_FROM_OBJECT, Warning.ALL_FIELDS_SHOULD_BE_USED)
            .verify();
      }
    }
  }

  @Test
  void independentScopesRetainConfigurationIdentityWhenTheirPoliciesChange() throws Exception {
    var cors = new Cors(CorsPolicy.builder().origins("https://client.example").build());
    var replacement = CorsPolicy.builder().origins("https://admin.example").build();
    var configurations = new HashSet<Cors.Configuration>();

    {
      var app = new Shoostr();

      try (Closeable appCleanup1 = app::close) {
        app.routes()
            .get(
                "/first",
                (ignored, ignoredResponse) -> {},
                extensions -> {
                  var configuration = extensions.withExtension(cors);
                  var hash = configuration.hashCode();

                  assertTrue(configurations.add(configuration));

                  configuration.policy(replacement);

                  assertSame(configuration, extensions.withExtension(cors));
                  assertEquals(hash, configuration.hashCode());
                  assertTrue(configurations.contains(configuration));
                })
            .get(
                "/second",
                (ignoredRequest, ignoredResponse) -> {},
                extensions -> {
                  var configuration = extensions.withExtension(cors);

                  assertSame(configuration, extensions.withExtension(cors));
                  assertTrue(configurations.add(configuration));
                });
      }
    }

    assertEquals(2, configurations.size());
  }
}
