package io.github.suppierk.shoostr.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.ApplicationCallbacks;
import io.github.suppierk.shoostr.AuthenticationExtension;
import io.github.suppierk.shoostr.Extension;
import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.Response;
import io.github.suppierk.shoostr.Shoostr;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class ApplicationCompatibilityTest {
  @Test
  void preservesInstalledExtensionAndInheritedAuthenticationBehavior() throws Exception {
    @SuppressWarnings("java:S9357") // Extension has no abstract method: it cannot be a lambda.
    var extension =
        new Extension<>() {
          @Override
          public void install(ApplicationCallbacks callbacks) {
            callbacks.beforeRouteHandler(
                (_, response) -> response.setHeader("X-App-Extension", "installed"));
          }
        };
    var authentication =
        new AuthenticationExtension() {
          @Override
          public void handle(Request request, Response response) {
            response.setHeader("X-Route-Extension", "selected");
          }
        };
    var app = new Shoostr().extensions(extension).authentication(authentication);
    app.routes()
        .path(
            "/api",
            routes -> routes.get("/value", (_, response) -> response.text("existing")),
            extensions -> extensions.get(authentication).required());
    app.routes().get("/plain", (_, response) -> response.text("plain"));

    try (var test = TestServer.start(app)) {
      var selected = test.send(request -> request.path("/api/value"));
      assertEquals("installed", selected.headers().firstValue("X-App-Extension").orElseThrow());
      assertEquals("selected", selected.headers().firstValue("X-Route-Extension").orElseThrow());
      var plain = test.send(request -> request.path("/plain"));
      assertEquals("installed", plain.headers().firstValue("X-App-Extension").orElseThrow());
      assertTrue(plain.headers().firstValue("X-Route-Extension").isEmpty());
    }
  }

  @Test
  void servesCallerReconfiguredDependenciesWithoutRebuildingTheApp() throws Exception {
    var value = new AtomicReference<>("first");
    Supplier<String> repository = value::get;
    var app = new Shoostr();
    app.routes().get("/value", (_, response) -> response.text(repository.get()));

    try (var test = TestServer.start(app)) {
      assertEquals(
          "first",
          test.send(request -> request.path("/value"), HttpResponse.BodyHandlers.ofString())
              .body());
      value.set("second");
      assertEquals(
          "second",
          test.send(request -> request.path("/value"), HttpResponse.BodyHandlers.ofString())
              .body());
    }

    assertEquals("second", value.get());
  }
}
