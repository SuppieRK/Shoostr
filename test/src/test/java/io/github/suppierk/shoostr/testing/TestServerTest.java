package io.github.suppierk.shoostr.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.Shoostr;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.junit.jupiter.api.Test;

class TestServerTest {
  @Test
  void startsIsolatedServersOnEphemeralPortsAndStopsThemAtScopeExit() throws Exception {
    TestServer first;
    var firstApp = new Shoostr();
    firstApp.routes().get("/id", (_, res) -> res.text("one"));
    var secondApp = new Shoostr();
    secondApp.routes().get("/id", (_, res) -> res.text("two"));

    try (var running = TestServer.start(firstApp);
        var second = TestServer.start(secondApp);
        var client = HttpClient.newHttpClient()) {
      first = running;
      assertNotEquals(running.baseUri().getPort(), second.baseUri().getPort());
      assertEquals(
          "one",
          client
              .send(
                  HttpRequest.newBuilder(running.baseUri().resolve("id")).build(),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertEquals(
          "two",
          client
              .send(
                  HttpRequest.newBuilder(second.baseUri().resolve("id")).build(),
                  HttpResponse.BodyHandlers.ofString())
              .body());
    }

    assertThrows(IllegalStateException.class, first::baseUri);
  }

  @Test
  void closesAppWhenConfigurationFails() {
    var app = new Shoostr();
    app.modifyServer(
        _ -> {
          throw new IllegalArgumentException("invalid fixture configuration");
        });
    assertThrows(IllegalArgumentException.class, () -> TestServer.start(app));
    var routes = app.routes();
    assertThrows(
        IllegalStateException.class, () -> routes.get("/later", (_, res) -> res.text("late")));
  }

  @Test
  void reportsNativeStopFailuresThroughClose() throws Exception {
    var app = new Shoostr();
    app.modifyServer(
        jetty ->
            jetty.addBean(
                new AbstractLifeCycle() {
                  @Override
                  protected void doStop() throws Exception {
                    throw new IOException("forced native stop failure");
                  }
                }));
    app.routes().get("/", (_, response) -> response.text("ready"));
    var server = TestServer.start(app);

    var failure = assertThrows(IOException.class, server::close);
    assertEquals("Could not stop HTTP server", failure.getMessage());
    assertThrows(IllegalStateException.class, server::baseUri);
  }
}
