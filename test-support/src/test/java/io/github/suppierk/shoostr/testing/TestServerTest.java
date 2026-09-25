package io.github.suppierk.shoostr.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.Shoostr;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.junit.jupiter.api.Test;

class TestServerTest {
  @Test
  void startsIsolatedServersOnEphemeralPortsAndStopsThemAtScopeExit() throws Exception {
    TestServer first;

    try (var running =
            TestServer.start(app -> app.routes().get("/id", (req, res) -> res.text("one")));
        var second =
            TestServer.start(app -> app.routes().get("/id", (req, res) -> res.text("two")));
        var client = HttpClient.newHttpClient()) {
      first = running;
      assertNotEquals(running.baseUri().getPort(), second.baseUri().getPort());
      assertEquals("one", body(client, running));
      assertEquals("two", body(client, second));
    }

    assertThrows(IllegalStateException.class, first::baseUri);
  }

  @Test
  void closesAppWhenConfigurationFails() {
    var captured = new AtomicReference<Shoostr>();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TestServer.start(
                app -> {
                  captured.set(app);
                  app.routes().get("/", (req, res) -> res.text("unused"));
                  throw new IllegalArgumentException("invalid fixture configuration");
                }));
    assertThrows(
        IllegalStateException.class,
        () -> captured.get().routes().get("/later", (req, res) -> res.text("late")));
  }

  @Test
  void reportsNativeStopFailuresThroughClose() throws Exception {
    var server =
        TestServer.start(
            app -> {
              app.modifyServer(
                  jetty ->
                      jetty.addBean(
                          new AbstractLifeCycle() {
                            @Override
                            protected void doStop() throws Exception {
                              throw new IOException("forced native stop failure");
                            }
                          }));
              app.routes().get("/", (request, response) -> response.text("ready"));
            });

    var failure = assertThrows(IOException.class, server::close);
    assertEquals("Could not stop HTTP server", failure.getMessage());
    assertThrows(IllegalStateException.class, server::baseUri);
  }

  private static String body(HttpClient client, TestServer server) throws Exception {
    return client
        .send(
            HttpRequest.newBuilder(server.baseUri().resolve("id")).build(),
            HttpResponse.BodyHandlers.ofString())
        .body();
  }
}
