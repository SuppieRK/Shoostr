package io.github.suppierk.shoostr.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ExistingApplicationTest {
  @Test
  void doesNotExposeTheOwnedClientThroughItsPublicInterface() {
    assertFalse(
        Arrays.stream(TestServer.class.getMethods())
            .anyMatch(method -> HttpClient.class.isAssignableFrom(method.getReturnType())));
  }

  @Test
  void servesRoutesRegisteredOnTheSuppliedApplication() throws Exception {
    var app = new Shoostr();
    app.routes()
        .path("/api", routes -> routes.get("/value", (_, response) -> response.text("existing")));

    try (var server = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var reply =
          client.send(
              HttpRequest.newBuilder(server.baseUri().resolve("api/value")).build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals("existing", reply.body());
      assertEquals(app.port(), server.baseUri().getPort());
    }

    assertThrows(IllegalStateException.class, app::start);
  }

  @Test
  void rejectsARunningApplicationWithoutStoppingIt() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().get("/value", (_, response) -> response.text("still running"));
      app.start();
      var target = java.net.URI.create("http://127.0.0.1:" + app.port() + "/value");

      assertThrows(IllegalStateException.class, () -> TestServer.start(app));
      assertEquals(
          "still running",
          client
              .send(HttpRequest.newBuilder(target).build(), HttpResponse.BodyHandlers.ofString())
              .body());
    }
  }

  @Test
  void rejectsAnApplicationAlreadyClosedByItsOwner() throws Exception {
    var app = new Shoostr();
    app.close();
    assertThrows(IllegalStateException.class, () -> TestServer.start(app));
  }
}
