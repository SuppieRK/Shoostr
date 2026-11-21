package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScopedExtensionsTest {
  @Test
  void inheritsRequestLifecycleWithoutApplyingItToSiblingGroupsAndFreezesItsCollector()
      throws Exception {
    var retained = new AtomicReference<Lifecycle>();

    {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close) {
        app.routes()
            .path(
                "/scoped",
                routes -> registerScopedChild(routes, retained),
                extensions -> {
                  var lifecycle = extensions;

                  assertSame(lifecycle, extensions);

                  retained.set(lifecycle);
                  lifecycle.beforeRouteHandler(
                      (request, response) -> response.setHeader("X-Scope", "parent"));
                })
            .get("/sibling", (request, response) -> response.text("sibling"));
        var lifecycle = retained.get();

        assertThrows(
            IllegalStateException.class,
            () -> lifecycle.beforeRouteHandler((request, response) -> response.text("late")));

        try (var test = TestServer.start(app)) {
          assertEquals(
              "parent",
              test.send(
                      request -> request.path("/scoped/child"),
                      HttpResponse.BodyHandlers.ofString())
                  .headers()
                  .firstValue("X-Scope")
                  .orElseThrow());
          assertEquals(
              Optional.empty(),
              test.send(request -> request.path("/sibling"), HttpResponse.BodyHandlers.ofString())
                  .headers()
                  .firstValue("X-Scope"));
        }
      }
    }
  }

  private void registerScopedChild(Routes routes, AtomicReference<Lifecycle> retained) {
    assertThrows(
        IllegalStateException.class,
        () -> retained.get().beforeRouteHandler((request, response) -> response.text("late")));
    routes.get("/child", (request, response) -> response.text("child"));
  }

  @ParameterizedTest(name = "fatal={0}")
  @ValueSource(booleans = {false, true})
  void freezesFailedGroupConfigurationAndRestoresGroupingDepth(boolean fatal) throws Exception {
    var retained = new AtomicReference<Lifecycle>();
    var registered = new AtomicBoolean();
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      var cors = new Cors(CorsPolicy.builder().origins("https://example.com").build());
      Class<? extends Throwable> failureType = fatal ? Error.class : IllegalStateException.class;
      assertEquals(
          "configuration failed",
          assertThrows(
                  failureType,
                  () ->
                      app.routes()
                          .path(
                              "/failed",
                              routes -> registered.set(true),
                              lifecycle -> {
                                retained.set(lifecycle);
                                lifecycle.withExtension(cors);
                                if (fatal) {
                                  throw new Error("configuration failed");
                                }

                                throw new IllegalStateException("configuration failed");
                              }))
              .getMessage());

      assertFalse(registered.get());
      assertThrows(IllegalStateException.class, () -> retained.get().withoutExtension(cors));
      assertDoesNotThrow(() -> registerTenGroups(app.routes()));
    }
  }

  private void registerTenGroups(Routes routes) {
    var scopes = new ArrayList<Routes>();
    scopes.add(routes);
    for (var depth = 0; depth < 10; depth++) {
      scopes.getLast().path("/level", scopes::add);
    }
  }

  @ParameterizedTest(name = "inherited={0}")
  @ValueSource(booleans = {false, true})
  void rejectsChildRegistrationWhenGroupConfigurationClosesApplication(boolean inherited)
      throws Exception {
    var registered = new AtomicBoolean();
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      assertThrows(
          IllegalStateException.class,
          () -> {
            if (inherited) {
              app.routes()
                  .path(
                      "/parent",
                      parent -> parent.path("/child", child -> registered.set(true)),
                      lifecycle -> lifecycle.withExtension(closingInheritedProvider(app)));
            } else {
              app.routes()
                  .path(
                      "/child",
                      child -> registered.set(true),
                      lifecycle -> assertDoesNotThrow(app::close));
            }
          });

      assertFalse(registered.get());
      assertThrows(IllegalStateException.class, app::start);
    }
  }

  private RouteExtension<Object> closingInheritedProvider(Shoostr app) {
    return (lifecycle, inherited) -> {
      if (inherited != null) {
        assertDoesNotThrow(app::close);
      }

      return new Object();
    };
  }
}
