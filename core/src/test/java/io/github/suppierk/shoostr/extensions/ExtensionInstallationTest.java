package io.github.suppierk.shoostr.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.Extension;
import io.github.suppierk.shoostr.Extensions;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ExtensionInstallationTest {
  @Test
  void verifiesAnAppOnlyExtensionWithoutSelectingRouteConfiguration() throws Exception {
    var capability = new Extension<Void>() {};

    try (var app = new Shoostr()) {
      app.extensions(capability);
      app.routes()
          .get(
              "/capability",
              (ignored, response) -> response.text("available"),
              extensions -> assertSame(extensions, extensions.requireInstalled(capability)));

      try (var test = TestServer.start(app)) {
        assertEquals(
            "available",
            test.send(request -> request.path("/capability"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void rejectsMissingInstallationWithoutPublishingTheEndpointOrItsMetadata() throws Exception {
    var facts = new ArrayList<String>();

    try (var capability = new Extension<Void>() {};
        var app = new Shoostr()) {
      var routes = app.routes();
      assertThrows(
          IllegalArgumentException.class,
          () ->
              routes.get(
                  "/capability",
                  (ignored2, response) -> response.text("rejected"),
                  extensions -> {
                    extensions.onRoute((ignored3, path) -> facts.add(path));
                    extensions.requireInstalled(capability);
                  }));
      app.routes()
          .get(
              "/capability",
              (ignored4, response) -> response.text("corrected"),
              extensions -> extensions.onRoute((ignored5, path) -> facts.add(path)));

      try (var test = TestServer.start(app)) {
        assertEquals(List.of("/capability"), facts);
        assertEquals(
            "corrected",
            test.send(request -> request.path("/capability"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void rejectsAnExtensionInstalledOnlyInAnotherApplication() throws Exception {
    var capability = new Extension<Void>() {};

    try (var owner = new Shoostr();
        var other = new Shoostr()) {
      owner.extensions(capability);
      var routes = other.routes();

      assertThrows(
          IllegalArgumentException.class,
          () ->
              routes.get(
                  "/capability", (ignored6, ignored7) -> {}, e -> e.requireInstalled(capability)));
    }
  }

  @Test
  void rejectsAnEqualButDistinctExtensionInstance() throws Exception {
    var installed = new Capability("same");
    var missing = new Capability("same");

    try (var app = new Shoostr()) {
      app.extensions(installed);
      assertEquals(installed, missing);
      var routes = app.routes();

      assertThrows(
          IllegalArgumentException.class,
          () ->
              routes.get(
                  "/capability", (ignored8, ignored9) -> {}, e -> e.requireInstalled(missing)));
    }
  }

  @Test
  void rejectsANullInstallationRequirement() throws Exception {
    try (var app = new Shoostr()) {
      var routes = app.routes();
      assertThrows(
          NullPointerException.class,
          () ->
              routes.get(
                  "/capability", (ignored10, ignored11) -> {}, e -> e.requireInstalled(null)));
    }
  }

  @Test
  void rejectsInstallationChecksAfterEndpointConfigurationReturns() throws Exception {
    var capability = new Extension<Void>() {};
    var retained = new AtomicReference<Extensions>();

    try (var app = new Shoostr()) {
      app.extensions(capability);
      app.routes().get("/capability", (ignored12, ignored13) -> {}, retained::set);
      var frozen = Objects.requireNonNull(retained.get());

      assertThrows(IllegalStateException.class, () -> frozen.requireInstalled(capability));
    }
  }

  @Test
  void rejectsInstallationChecksAfterGroupConfigurationReturns() throws Exception {
    var capability = new Extension<Void>() {};
    var retained = new AtomicReference<Extensions>();

    try (var app = new Shoostr()) {
      app.extensions(capability);
      app.routes()
          .path("/capability", child -> child.get((ignored14, ignored15) -> {}), retained::set);
      var frozen = Objects.requireNonNull(retained.get());

      assertThrows(IllegalStateException.class, () -> frozen.requireInstalled(capability));
    }
  }

  @Test
  void verifiesTheSameAppRegistryInBothGroupAndChildConfiguration() throws Exception {
    var capability = new Extension<Void>() {};

    try (var app = new Shoostr()) {
      app.extensions(capability);
      app.routes()
          .path(
              "/capability",
              child ->
                  child.get(
                      (ignored16, response) -> response.text("available"),
                      e -> e.requireInstalled(capability)),
              e -> e.requireInstalled(capability));

      try (var test = TestServer.start(app)) {
        assertEquals(
            "available",
            test.send(request -> request.path("/capability"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  private record Capability(String name) implements Extension<Void> {
    private Capability {
      Objects.requireNonNull(name);
    }
  }
}
