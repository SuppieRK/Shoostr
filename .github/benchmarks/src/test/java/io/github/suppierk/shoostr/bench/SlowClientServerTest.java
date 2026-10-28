package io.github.suppierk.shoostr.bench;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class SlowClientServerTest {
  @Test
  void servesHealthyRequestsWhileABodyIsIncompleteAndRecoversAfterItsClientDisconnects()
      throws Exception {
    try (var server = new SlowClientServer(0);
        var client = HttpClient.newHttpClient()) {
      server.start();

      try (var socket = new Socket()) {
        socket.connect(
            new InetSocketAddress(InetAddress.getAllByName("127.0.0.1")[0], server.port()), 1000);
        socket
            .getOutputStream()
            .write(
                "POST /slow-body HTTP/1.1\r\nHost: localhost\r\nContent-Length: 64\r\n\r\na"
                    .getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        awaitState(
            client,
            server.port(),
            state ->
                state.contains("\"active_bodies\":1")
                    && state.contains("\"active_exchanges\":1")
                    && state.contains("\"connections\":2")
                    && state.contains("\"admitted\":1")
                    && state.contains("\"completed\":0"));

        var healthy = get(client, server.port(), "/healthy");
        assertEquals(200, healthy.statusCode());
        assertEquals("healthy", healthy.body());
      }

      awaitState(
          client,
          server.port(),
          state ->
              state.contains("\"active_bodies\":0")
                  && state.contains("\"active_exchanges\":0")
                  && state.contains("\"connections\":1")
                  && state.contains("\"admitted\":1")
                  && state.contains("\"completed\":1"));
      assertEquals("healthy", get(client, server.port(), "/healthy").body());
    }
  }

  @Test
  void releasesBlockedWorkAndRecoversWithoutRestartingTheListener() throws Exception {
    try (var client = HttpClient.newHttpClient();
        var server = new SlowClientServer(0)) {
      server.start();
      var blocked =
          client.sendAsync(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/blocked"))
                  .timeout(Duration.ofSeconds(10))
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      try {
        awaitState(
            client,
            server.port(),
            state ->
                state.contains("\"active_blocked\":1") && state.contains("\"active_exchanges\":1"));
        assertFalse(blocked.isDone());
        var release =
            client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/release"))
                    .timeout(Duration.ofSeconds(1))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, release.statusCode());
        assertEquals("released", release.body());
        var response = blocked.get(1, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode());
        assertEquals("released", response.body());
        awaitState(
            client,
            server.port(),
            state ->
                state.contains("\"active_blocked\":0") && state.contains("\"active_exchanges\":0"));
        assertEquals("healthy", get(client, server.port(), "/healthy").body());
      } finally {
        blocked.cancel(true);
      }
    }
  }

  @Test
  void refusesRearmingDuringWorkAndRepeatsReleaseCyclesOnTheSameListener() throws Exception {
    try (var client = HttpClient.newHttpClient();
        var server = new SlowClientServer(0)) {
      server.start();
      for (var cycle = 0; cycle < 2; cycle++) {
        var armed = post(client, server.port(), "/arm");
        assertEquals(200, armed.statusCode());
        assertEquals("armed", armed.body());
        var blocked =
            client.sendAsync(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/blocked"))
                    .timeout(Duration.ofSeconds(10))
                    .build(),
                HttpResponse.BodyHandlers.ofString());

        try {
          awaitState(
              client,
              server.port(),
              state ->
                  state.contains("\"active_blocked\":1")
                      && state.contains("\"active_exchanges\":1"));
          assertEquals(409, post(client, server.port(), "/arm").statusCode());
          assertFalse(blocked.isDone());
          assertEquals("released", post(client, server.port(), "/release").body());
          assertEquals("released", post(client, server.port(), "/release").body());
          var response = blocked.get(1, TimeUnit.SECONDS);
          assertEquals(200, response.statusCode());
          assertEquals("released", response.body());
          awaitState(
              client,
              server.port(),
              state ->
                  state.contains("\"active_blocked\":0")
                      && state.contains("\"active_exchanges\":0"));
        } finally {
          blocked.cancel(true);
        }
      }

      assertEquals("healthy", get(client, server.port(), "/healthy").body());
    }
  }

  @Test
  void reportsEffectiveDefaultProtectionsWithoutInstallingARatePolicy() throws Exception {
    try (var client = HttpClient.newHttpClient();
        var server = new SlowClientServer(0)) {
      server.start();
      var result = get(client, server.port(), "/configuration");
      assertEquals(200, result.statusCode());
      assertTrue(result.body().contains("connector_idle_timeout_ms=[30000]"));
      assertTrue(result.body().contains("max_request_bytes=1048576"));
      assertTrue(result.body().contains("minRequestDataRate=0"));
      assertTrue(result.body().contains("minResponseDataRate=0"));
      assertTrue(result.body().contains("httpCompliance=RFC9110"));
    }
  }

  private static void awaitState(HttpClient client, int port, Predicate<String> expected) {
    await("fixture state recovers")
        .atMost(Duration.ofSeconds(3))
        .pollDelay(Duration.ZERO)
        .pollInterval(Duration.ofMillis(10))
        .until(
            () -> {
              var response = get(client, port, "/state");
              assertEquals(200, response.statusCode());
              return response.body();
            },
            expected);
  }

  private static HttpResponse<String> get(HttpClient client, int port, String path)
      throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(1))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> post(HttpClient client, int port, String path)
      throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(1))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
