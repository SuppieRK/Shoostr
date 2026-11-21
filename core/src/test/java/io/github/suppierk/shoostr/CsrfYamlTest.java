package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

class CsrfYamlTest {
  @Test
  void emptyNullAndResetPropertiesKeepSameOriginDefaults() {
    for (var mapping : new String[] {"{}", "trustedOrigins: null"}) {
      var settings = new Yaml().loadAs(mapping, Csrf.class);

      assertEquals(Set.of(), settings.getTrustedOrigins());

      settings.setTrustedOrigins(Set.of("https://client.example"));
      settings.setTrustedOrigins(null);

      assertEquals(Set.of(), settings.getTrustedOrigins());
    }
  }

  @Test
  void invalidOriginsRetainPreviousSettingsAndRuntimeStateIsNotProperty() {
    var settings = new Yaml().loadAs("trustedOrigins: [https://client.example]", Csrf.class);
    for (var invalid : new String[] {"*", "null", "not-an-origin", "https://client.example/path"}) {
      var origins = Set.of(invalid);

      assertThrows(IllegalArgumentException.class, () -> settings.setTrustedOrigins(origins));
      assertEquals(Set.of("https://client.example"), settings.getTrustedOrigins());
    }

    var yaml = new Yaml();

    assertThrows(YAMLException.class, () -> yaml.loadAs("trustedOrigins: ['*']", Csrf.class));
    assertThrows(YAMLException.class, () -> yaml.loadAs("random: {}", Csrf.class));
  }

  @Test
  void configuredOriginsAreCapturedWhenApplicationDefaultsAreSelected() throws Exception {
    var settings = new Yaml().loadAs("trustedOrigins: [https://client.example]", Csrf.class);
    var protection = settings;
    var app = new Shoostr().sessions();

    try (Closeable cleanup = app::close) {
      app.withExtension(protection);
      settings.setTrustedOrigins(Set.of("https://other.example"));
      app.routes()
          .get("/token", (request, response) -> response.text(protection.token(request)))
          .post(
              "/write",
              (request, response) -> response.text("accepted"),
              lifecycle -> lifecycle.withExtension(protection));

      try (var server = TestServer.start(app)) {
        var token =
            server
                .send(request -> request.path("/token"), HttpResponse.BodyHandlers.ofString())
                .body();

        assertEquals(
            200,
            server
                .send(request ->
                    request
                        .path("/write")
                        .method("POST")
                        .header("Origin", "https://client.example")
                        .header("X-CSRF-Token", token))
                .statusCode());
        assertEquals(
            403,
            server
                .send(request ->
                    request
                        .path("/write")
                        .method("POST")
                        .header("Origin", "https://other.example")
                        .header("X-CSRF-Token", token))
                .statusCode());
      }
    }
  }

  @Test
  void applicationAndRouteSelectionsCustomizeIndependentCopiesAndFreezeAtRegistrationEnd()
      throws Exception {
    var source = new Yaml().loadAs("trustedOrigins: [https://source.example]", Csrf.class);
    var global = new AtomicReference<Csrf>();
    var local = new AtomicReference<Csrf>();
    var app = new Shoostr().sessions();

    try (Closeable cleanup = app::close) {
      app.withExtension(
          source,
          selected -> {
            selected.setTrustedOrigins(Set.of("https://app.example"));
            global.set(selected);
          });
      source.setTrustedOrigins(Set.of("https://changed.example"));
      app.routes()
          .get("/token", (request, response) -> response.text(source.token(request)))
          .post("/default", (request, response) -> response.text("ok"))
          .path(
              "/group",
              group -> group.post("/write", (request, response) -> response.text("ok")),
              lifecycle ->
                  lifecycle
                      .withExtension(source)
                      .setTrustedOrigins(Set.of("https://group.example")))
          .post(
              "/local",
              (request, response) -> response.text("ok"),
              lifecycle -> {
                var selected = lifecycle.withExtension(source);
                selected.setTrustedOrigins(Set.of("https://local.example"));
                local.set(selected);
              })
          .post(
              "/excluded",
              (request, response) -> response.text("ok"),
              lifecycle -> lifecycle.withoutExtension(source))
          .post(
              "/reselected",
              (request, response) -> response.text("ok"),
              lifecycle -> {
                lifecycle.withoutExtension(source);
                lifecycle.withExtension(source).setTrustedOrigins(Set.of("https://again.example"));
              });

      try (var server = TestServer.start(app)) {
        var frozenGlobal = global.get();

        assertThrows(IllegalStateException.class, () -> frozenGlobal.setTrustedOrigins(null));
        var frozenLocal = local.get();
        var changedOrigins = Set.of("https://changed.example");

        assertThrows(
            IllegalStateException.class, () -> frozenLocal.setTrustedOrigins(changedOrigins));

        source.setTrustedOrigins(null);
        var token =
            server
                .send(request -> request.path("/token"), HttpResponse.BodyHandlers.ofString())
                .body();
        for (var entry : selectedOrigins().entrySet()) {
          assertEquals(
              200,
              server
                  .send(request ->
                      request
                          .path(entry.getKey())
                          .method("POST")
                          .header("Origin", entry.getValue())
                          .header("X-CSRF-Token", token))
                  .statusCode());
          assertEquals(
              403,
              server
                  .send(request ->
                      request
                          .path(entry.getKey())
                          .method("POST")
                          .header("Origin", "https://changed.example")
                          .header("X-CSRF-Token", token))
                  .statusCode());
        }

        assertEquals(
            200, server.send(request -> request.path("/excluded").method("POST")).statusCode());
      }
    }
  }

  private Map<String, String> selectedOrigins() {
    var entries = new LinkedHashMap<String, String>();
    entries.put("/default", "https://app.example");
    entries.put("/group/write", "https://group.example");
    entries.put("/local", "https://local.example");
    entries.put("/reselected", "https://again.example");

    return Map.copyOf(entries);
  }
}
