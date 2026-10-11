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
              (_, response) -> response.text("available"),
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
      assertThrows(
          IllegalArgumentException.class,
          () ->
              app.routes()
                  .get(
                      "/capability",
                      (_, response) -> response.text("rejected"),
                      extensions -> {
                        extensions.onRoute((_, path) -> facts.add(path));
                        extensions.requireInstalled(capability);
                      }));
      app.routes()
          .get(
              "/capability",
              (_, response) -> response.text("corrected"),
              extensions -> extensions.onRoute((_, path) -> facts.add(path)));

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

      assertThrows(
          IllegalArgumentException.class,
          () ->
              other.routes().get("/capability", (_, _) -> {}, e -> e.requireInstalled(capability)));
    }
  }

  @Test
  void rejectsAnEqualButDistinctExtensionInstance() throws Exception {
    var installed = new Capability("same");
    var missing = new Capability("same");

    try (var app = new Shoostr()) {
      app.extensions(installed);
      assertEquals(installed, missing);

      assertThrows(
          IllegalArgumentException.class,
          () -> app.routes().get("/capability", (_, _) -> {}, e -> e.requireInstalled(missing)));
    }
  }

  @Test
  void rejectsANullInstallationRequirement() throws Exception {
    try (var app = new Shoostr()) {
      assertThrows(
          NullPointerException.class,
          () -> app.routes().get("/capability", (_, _) -> {}, e -> e.requireInstalled(null)));
    }
  }

  @Test
  void rejectsInstallationChecksAfterEndpointConfigurationReturns() throws Exception {
    var capability = new Extension<Void>() {};
    var retained = new AtomicReference<Extensions>();

    try (var app = new Shoostr()) {
      app.extensions(capability);
      app.routes().get("/capability", (_, _) -> {}, retained::set);

      assertThrows(IllegalStateException.class, () -> retained.get().requireInstalled(capability));
    }
  }

  @Test
  void rejectsInstallationChecksAfterGroupConfigurationReturns() throws Exception {
    var capability = new Extension<Void>() {};
    var retained = new AtomicReference<Extensions>();

    try (var app = new Shoostr()) {
      app.extensions(capability);
      app.routes().path("/capability", child -> child.get((_, _) -> {}), retained::set);

      assertThrows(IllegalStateException.class, () -> retained.get().requireInstalled(capability));
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
                      (_, response) -> response.text("available"),
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
