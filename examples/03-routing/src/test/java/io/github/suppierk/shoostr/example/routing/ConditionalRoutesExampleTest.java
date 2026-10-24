package io.github.suppierk.shoostr.example.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConditionalRoutesExampleTest {
  @Test
  void changesAvailabilityWithoutRestartingTheApplication() throws Exception {
    var enabled = new AtomicBoolean(false);

    try (var test = TestServer.start(ConditionalRoutesExample.application(enabled::get))) {
      assertEquals(
          404, test.send(request -> request.path("/preview/latest?preview=true")).statusCode());

      enabled.set(true);
      var available =
          test.send(
              request -> request.path("/preview/latest?preview=true"),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, available.statusCode());
      assertEquals("preview", available.body());

      enabled.set(false);
      assertEquals(
          404, test.send(request -> request.path("/preview/latest?preview=true")).statusCode());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"/preview/latest", "/preview/latest?preview=false"})
  void requiresTheRequestConditionEvenWhenTheFlagIsEnabled(String path) throws Exception {
    try (var test = TestServer.start(ConditionalRoutesExample.application(() -> true))) {
      var response = test.send(request -> request.path(path));

      assertEquals(404, response.statusCode());
      assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }
  }

  @Test
  void skipsTheRequestConditionWhileTheOuterFlagIsDisabled() throws Exception {
    var enabled = new AtomicBoolean(false);

    try (var test = TestServer.start(ConditionalRoutesExample.application(enabled::get))) {
      // Query decoding fails only if the inner availability condition runs.
      var disabled = test.send(request -> request.path("/preview/latest?preview=%FF"));
      assertEquals(404, disabled.statusCode());
      assertEquals("no-store", disabled.headers().firstValue("Cache-Control").orElseThrow());

      enabled.set(true);
      assertEquals(
          400, test.send(request -> request.path("/preview/latest?preview=%FF")).statusCode());
    }
  }

  @Test
  void doesNotRetryTheCatchAllAfterAConditionFails() throws Exception {
    try (var test = TestServer.start(ConditionalRoutesExample.application(() -> false))) {
      var unavailable = test.send(request -> request.path("/preview/latest?preview=true"));
      assertEquals(404, unavailable.statusCode());
      assertEquals("no-store", unavailable.headers().firstValue("Cache-Control").orElseThrow());

      var fallback =
          test.send(
              request -> request.path("/preview/other"), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, fallback.statusCode());
      assertEquals("fallback", fallback.body());
    }
  }

  @Test
  void retainsMethodDiscoveryWhileTheRouteIsUnavailable() throws Exception {
    try (var app = ConditionalRoutesExample.application(() -> false)) {
      app.routes()
          .when(
              () -> false,
              unavailable ->
                  unavailable.post(
                      "/preview/latest", (ignored, response) -> response.text("unused")));

      try (var test = TestServer.start(app)) {
        var response = test.send(request -> request.path("/preview/latest").method("PUT"));

        assertEquals(405, response.statusCode());
        assertEquals(
            Set.of("GET", "POST"),
            Set.of(response.headers().firstValue("Allow").orElseThrow().split(",\\s*")));
      }
    }
  }

  @Test
  void rejectsANullFlagBeforeCreatingAnApplication() {
    assertThrows(NullPointerException.class, () -> ConditionalRoutesExample.application(null));
  }
}
