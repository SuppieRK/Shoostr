package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class CorsYamlTest {
  @Test
  void emptyAndExplicitlyNullMappingsKeepTheEstablishedDefaults() {
    for (var mapping :
        new String[] {
          "{}",
          """
        origins: null
        methods: null
        allMethods: null
        allowedHeaders: null
        credentials: null
        exposedHeaders: null
        maxAgeSeconds: null
        """
        }) {
      var policy = new Yaml().loadAs(mapping, CorsPolicy.class);
      assertEquals(Set.of(), policy.origins());
      assertEquals(Set.of(HttpMethods.GET, HttpMethods.HEAD), policy.methods());
      assertFalse(policy.allMethods());
      assertEquals(Set.of(), policy.allowedHeaders());
      assertFalse(policy.credentials());
      assertEquals(Set.of(), policy.exposedHeaders());
      assertEquals(5, policy.maxAgeSeconds());
    }
  }

  @Test
  void wildcardMethodsAndConflictsDoNotDependOnYamlKeyOrder() {
    for (var mapping :
        new String[] {"methods: []\nallMethods: true", "allMethods: true\nmethods: []"}) {
      var policy = new Yaml().loadAs(mapping, CorsPolicy.class);
      assertTrue(policy.allMethods());
      assertEquals(Set.of(), policy.methods());
    }
    for (var mapping :
        new String[] {"methods: [GET]\nallMethods: true", "allMethods: true\nmethods: [GET]"}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new Cors(new Yaml().loadAs(mapping, CorsPolicy.class)));
    }
  }

  @Test
  void settersRestoreDefaultsAndInvalidValuesRetainPreviousSettings() {
    var settings =
        new Yaml()
            .loadAs(
                """
        origins: [https://client.example]
        methods: [POST]
        allowedHeaders: [X-Proof]
        credentials: true
        exposedHeaders: [ETag]
        maxAgeSeconds: 60
        """,
                CorsPolicy.class);
    var snapshot = new Cors(settings).policy();
    assertThrows(
        IllegalArgumentException.class, () -> settings.setOrigins(Set.of("not-an-origin")));
    assertThrows(IllegalArgumentException.class, () -> settings.setMaxAgeSeconds(-1L));
    assertEquals(Set.of("https://client.example"), settings.getOrigins());
    assertEquals(60L, settings.getMaxAgeSeconds());
    settings.setOrigins(null);
    settings.setMethods(null);
    settings.setAllMethods(null);
    settings.setAllowedHeaders(null);
    settings.setCredentials(null);
    settings.setExposedHeaders(null);
    settings.setMaxAgeSeconds(null);
    var defaults = settings;
    assertEquals(Set.of(), defaults.origins());
    assertEquals(Set.of(HttpMethods.GET, HttpMethods.HEAD), defaults.methods());
    assertFalse(defaults.allMethods());
    assertEquals(Set.of(), defaults.allowedHeaders());
    assertFalse(defaults.credentials());
    assertEquals(Set.of(), defaults.exposedHeaders());
    assertEquals(5, defaults.maxAgeSeconds());
    assertEquals(Set.of("https://client.example"), snapshot.origins());
    assertEquals(Set.of(HttpMethods.POST), snapshot.methods());
    assertTrue(snapshot.credentials());
    assertEquals(60, snapshot.maxAgeSeconds());
  }

  @Test
  void wildcardCredentialsRemainInvalidWhenLoadedFromYaml() {
    for (var mapping :
        new String[] {
          "origins: ['*']\ncredentials: true",
          "origins: [https://client.example]\ncredentials: true\nexposedHeaders: ['*']"
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new Cors(new Yaml().loadAs(mapping, CorsPolicy.class)));
    }
  }

  @Test
  void mapsEveryExistingPolicyPropertyToTypedValues() {
    var policy =
        new Yaml()
            .loadAs(
                """
        origins: [https://client.example]
        methods: [POST, OPTIONS]
        allMethods: false
        allowedHeaders: [Content-Type, X-Proof]
        credentials: true
        exposedHeaders: [ETag, X-Request-Id]
        maxAgeSeconds: 60
        """,
                CorsPolicy.class);
    assertEquals(Set.of("https://client.example"), policy.origins());
    assertEquals(Set.of(HttpMethods.POST, HttpMethods.OPTIONS), policy.methods());
    assertFalse(policy.allMethods());
    assertEquals(
        Set.of(HttpHeaders.CONTENT_TYPE, HttpHeaders.of("X-Proof")), policy.allowedHeaders());
    assertTrue(policy.credentials());
    assertEquals(Set.of(HttpHeaders.ETAG, HttpHeaders.of("X-Request-Id")), policy.exposedHeaders());
    assertEquals(60, policy.maxAgeSeconds());
  }

  @Test
  void partialConfigurationPreservesTheExistingPolicyDefaults() {
    var policy = new Yaml().loadAs("origins: [https://client.example]", CorsPolicy.class);
    assertEquals(Set.of("https://client.example"), policy.origins());
    assertEquals(Set.of(HttpMethods.GET, HttpMethods.HEAD), policy.methods());
    assertFalse(policy.allMethods());
    assertFalse(policy.credentials());
    assertEquals(Set.of(), policy.allowedHeaders());
    assertEquals(Set.of(), policy.exposedHeaders());
    assertEquals(5, policy.maxAgeSeconds());
  }

  @Test
  void sourceAccessorAndReplacementEditsCannotChangeRegisteredPermissions() throws Exception {
    var source = new Yaml().loadAs("origins: [https://client.example]", CorsPolicy.class);
    var cors = new Cors(source);
    source.setOrigins(Set.of("https://changed.example"));
    cors.policy().setOrigins(Set.of("https://changed.example"));
    var replacement = new CorsPolicy(Set.of("https://reports.example"));
    var invalid = new CorsPolicy(Set.of("*"));
    invalid.setCredentials(true);
    var app = new Shoostr();

    try (Closeable cleanup = app::close) {
      app.withExtension(cors);
      app.routes()
          .get("/default", (request, response) -> response.text("ok"))
          .get(
              "/reports",
              (request, response) -> response.text("ok"),
              lifecycle -> {
                var selected = lifecycle.withExtension(cors).policy(replacement);
                assertThrows(IllegalArgumentException.class, () -> selected.policy(invalid));
              })
          .get("/sibling", (request, response) -> response.text("ok"));
      replacement.setOrigins(Set.of("https://changed.example"));

      try (var server = TestServer.start(app)) {
        assertEquals(
            200,
            server
                .send(
                    request -> request.path("/default").header("Origin", "https://client.example"))
                .statusCode());
        assertEquals(
            200,
            server
                .send(
                    request -> request.path("/sibling").header("Origin", "https://client.example"))
                .statusCode());
        assertEquals(
            200,
            server
                .send(
                    request -> request.path("/reports").header("Origin", "https://reports.example"))
                .statusCode());
        assertEquals(
            403,
            server
                .send(
                    request -> request.path("/reports").header("Origin", "https://client.example"))
                .statusCode());
        for (var path : new String[] {"/default", "/reports", "/sibling"}) {
          assertEquals(
              403,
              server
                  .send(request -> request.path(path).header("Origin", "https://changed.example"))
                  .statusCode());
        }
      }
    }
  }
}
