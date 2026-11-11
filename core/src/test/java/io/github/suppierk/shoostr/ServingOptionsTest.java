package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.ServerSocketChannel;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class ServingOptionsTest {
  private static Shoostr application(Options options) {
    return new Shoostr(options)
        .routes(
            routes ->
                routes
                    .get("/health", (request, response) -> response.text("ready"))
                    .post("/echo", (request, response) -> response.text(request.bodyText())));
  }

  private static HttpResponse<String> get(HttpClient client, int port, String header)
      throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
            .timeout(Duration.ofSeconds(3))
            .header("X-Test", header)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void bindsTheConfiguredHostAndFixedPortWithoutNativeConfiguration() throws Exception {
    int port;

    try (var reservation = ServerSocketChannel.open()) {
      reservation.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      port = ((InetSocketAddress) reservation.getLocalAddress()).getPort();
    }

    var options = new Options();
    options.setHost("127.0.0.1");
    options.setPort(port);

    {
      var app = application(options);

      try (Closeable appCleanup = app::close;
          var client = HttpClient.newHttpClient()) {
        app.start();

        assertEquals(port, app.port());
        assertEquals("ready", get(client, app.port(), "").body());
      }
    }
  }

  @Test
  void capturesSharedConfigurationForIndependentEphemeralListeners() throws Exception {
    var options = new Options();
    options.setHost("127.0.0.1");
    options.setPort(0);

    {
      var first = application(options);

      try (Closeable firstCleanup = first::close) {
        var second = application(options);

        try (Closeable secondCleanup = second::close;
            var client = HttpClient.newHttpClient()) {
          first.start();
          second.start();

          assertTrue(first.port() > 0);
          assertNotEquals(first.port(), second.port());
          assertEquals("ready", get(client, first.port(), "").body());
          assertEquals("ready", get(client, second.port(), "").body());
          assertEquals(0, options.getPort());
        }
      }
    }
  }

  @Test
  void snapshotsSettingsAtConstructionAndLetsSubsequentApplicationsCaptureChanges()
      throws Exception {
    var options = new Options();
    options.setHost("127.0.0.1");
    options.setPort(0);
    options.setMaxRequestBytes(4);

    {
      var first = application(options);

      try (Closeable firstCleanup1 = first::close;
          var client = HttpClient.newHttpClient()) {
        options.setMaxRequestBytes(2);

        {
          var second = application(options);

          try (Closeable secondCleanup1 = second::close) {
            options.setHost("unresolved.invalid");
            options.setPort(65535);
            first.start();
            second.start();
            var firstResponse = sendFirstResponseRequestForSettingsSnapshots(client, first);

            assertEquals(200, firstResponse.statusCode());
            assertEquals("four", firstResponse.body());

            var secondResponse = sendSecondResponseRequestForSettingsSnapshots(client, second);

            assertEquals(413, secondResponse.statusCode());
          }
        }
      }
    }
  }

  private HttpResponse<String> sendFirstResponseRequestForSettingsSnapshots(
      HttpClient client, Shoostr first) throws IOException, InterruptedException {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + first.port() + "/echo"))
            .timeout(Duration.ofSeconds(3))
            .POST(HttpRequest.BodyPublishers.ofString("four"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<Void> sendSecondResponseRequestForSettingsSnapshots(
      HttpClient client, Shoostr second) throws IOException, InterruptedException {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + second.port() + "/echo"))
            .timeout(Duration.ofSeconds(3))
            .POST(HttpRequest.BodyPublishers.ofString("four"))
            .build(),
        HttpResponse.BodyHandlers.discarding());
  }

  @Test
  void rejectsOversizedHeadersBeforeInvokingApplicationHandlers() throws Exception {
    var handled = new AtomicInteger();
    var options = new Options();
    options.setHost("127.0.0.1");
    options.setPort(0);
    options.setMaxRequestHeaderBytes(512);

    {
      var app = application(options);

      try (Closeable appCleanup1 = app::close;
          var client = HttpClient.newHttpClient()) {
        app.beforeRouteHandler((request, response) -> handled.incrementAndGet());
        app.start();

        assertEquals(431, get(client, app.port(), "x".repeat(2048)).statusCode());
        assertEquals(0, handled.get());
        assertEquals(200, get(client, app.port(), "small").statusCode());
        assertEquals(1, handled.get());
      }
    }
  }

  @Test
  void nativeCallbacksOverrideOptionsAndObserveConfiguredTimeouts() throws Exception {
    var options = new Options();
    options.setHost("localhost");
    options.setPort(9090);
    options.setIdleTimeout(Duration.ofSeconds(12));
    options.setShutdownTimeout(Duration.ofSeconds(2));
    options.setMaxRequestHeaderBytes(256);

    {
      var app = application(options);

      try (Closeable appCleanup2 = app::close;
          var client = HttpClient.newHttpClient()) {
        app.modifyHttpConfiguration(
            http -> {
              assertEquals(256, http.getRequestHeaderSize());

              http.setRequestHeaderSize(4096);
            });
        app.modifyServer(
            server -> {
              var connector = (ServerConnector) server.getConnectors()[0];

              assertEquals("localhost", connector.getHost());
              assertEquals(9090, connector.getPort());
              assertEquals(12_000, connector.getIdleTimeout());
              assertEquals(2000, server.getStopTimeout());

              connector.setHost("127.0.0.1");
              connector.setPort(0);
              connector.setIdleTimeout(15_000);
              server.setStopTimeout(3000);
            });
        app.modifyServer(
            server -> {
              var connector = (ServerConnector) server.getConnectors()[0];

              assertEquals(15_000, connector.getIdleTimeout());
              assertEquals(3000, server.getStopTimeout());
            });
        app.start();

        assertEquals(200, get(client, app.port(), "x".repeat(1024)).statusCode());
      }
    }
  }

  @Test
  void testFixtureOverridesConfiguredHostAndPort() throws Exception {
    var options = new Options();
    options.setHost("unresolved.invalid");
    options.setPort(65535);

    try (var test = TestServer.start(application(options))) {
      assertEquals("127.0.0.1", test.baseUri().getHost());
      assertTrue(test.baseUri().getPort() > 0);
      assertEquals(200, test.send(request -> request.path("/health")).statusCode());
      assertEquals("unresolved.invalid", options.getHost());
      assertEquals(65535, options.getPort());
    }
  }
}
