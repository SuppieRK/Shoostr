package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ScopedExtensionsTest {
  @Test
  void inheritsRequestLifecycleWithoutApplyingItToSiblingGroupsAndFreezesItsCollector()
      throws Exception {
    var retained = new AtomicReference<Lifecycle>();

    try (var app = new Shoostr()) {
      app.routes()
          .path(
              "/scoped",
              routes -> routes.get("/child", (request, response) -> response.text("child")),
              extensions -> {
                var lifecycle = extensions.lifecycle();
                assertSame(lifecycle, extensions.lifecycle());
                retained.set(lifecycle);
                lifecycle.beforeRouteHandler(
                    (request, response) -> response.setHeader("X-Scope", "parent"));
              })
          .get("/sibling", (request, response) -> response.text("sibling"));
      assertThrows(
          IllegalStateException.class,
          () -> retained.get().beforeRouteHandler((request, response) -> response.text("late")));

      try (var test = TestServer.start(app)) {
        assertEquals(
            "parent",
            test.send(
                    request -> request.path("/scoped/child"), HttpResponse.BodyHandlers.ofString())
                .headers()
                .firstValue("X-Scope")
                .orElseThrow());
        assertEquals(
            java.util.Optional.empty(),
            test.send(request -> request.path("/sibling"), HttpResponse.BodyHandlers.ofString())
                .headers()
                .firstValue("X-Scope"));
      }
    }
  }
}
