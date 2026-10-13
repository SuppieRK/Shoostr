package io.github.suppierk.shoostr.example.metrics;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.suppierk.shoostr.http.HttpHeaders;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("docker")
class MetricsDockerTest {
  private static final URI APP = URI.create("http://127.0.0.1:19081");
  private static final URI PROMETHEUS = URI.create("http://127.0.0.1:19090");
  private static final URI GRAFANA = URI.create("http://127.0.0.1:13000");
  private static final String AUTHORIZATION =
      "Basic "
          + Base64.getEncoder().encodeToString("demo:local-only".getBytes(StandardCharsets.UTF_8));
  private static final ObjectMapper JSON = new ObjectMapper();
  private static Path directory;
  private static String project;
  private static HttpClient client;

  @BeforeAll
  static void startTheIsolatedStack() throws Exception {
    directory = Path.of(Objects.requireNonNull(System.getProperty("example.directory")));
    project = "shoostr-metrics-test-" + UUID.randomUUID().toString().substring(0, 8);
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    try {
      compose(false, "up", "-d");
      for (var uri :
          List.of(
              APP.resolve("/metrics"),
              PROMETHEUS.resolve("/-/ready"),
              GRAFANA.resolve("/api/health"))) {
        var deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        boolean ready = false;
        while (System.nanoTime() < deadline) {
          try {
            var response =
                client.send(
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            ready = response.statusCode() == 200;
          } catch (IOException unavailable) {
            // Container listeners can refuse/reset connections while starting.
          }

          if (ready) {
            break;
          }

          TimeUnit.MILLISECONDS.sleep(200);
        }

        assertTrue(ready, "Backend did not become ready: " + uri);
      }
    } catch (Exception | Error failure) {
      try {
        stopTheIsolatedStack();
      } catch (Exception cleanup) {
        failure.addSuppressed(cleanup);
      }

      throw failure;
    }
  }

  @AfterAll
  static void stopTheIsolatedStack() throws Exception {
    try {
      compose(true, "logs", "--no-color");
    } finally {
      try {
        compose(false, "down", "--timeout", "10", "--volumes");
      } finally {
        client.close();
      }
    }
  }

  @Test
  void exportsThreeGreetingRequestsUnderTheirTemplate() throws Exception {
    var before =
        client.send(
            HttpRequest.newBuilder(APP.resolve("/metrics"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    var baseline =
        before
            .body()
            .lines()
            .filter(
                line ->
                    line.startsWith("http_server_requests_seconds_count{")
                        && line.contains("route=\"/hello/{name}\"")
                        && line.contains("status=\"200\""))
            .mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
            .sum();
    var target = baseline + 3;
    for (var name : List.of("Ada", "Grace", "Linus")) {
      var response =
          client.send(
              HttpRequest.newBuilder(APP.resolve("/hello/" + name))
                  .timeout(Duration.ofSeconds(5))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals("Hello, " + name + "!", response.body());
    }

    var query =
        URLEncoder.encode(
            "http_server_requests_seconds_count{route=\"/hello/{name}\",status=\"200\"}",
            StandardCharsets.UTF_8);
    var deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    double count = 0;
    while (System.nanoTime() < deadline && count < target) {
      var response =
          client.send(
              HttpRequest.newBuilder(PROMETHEUS.resolve("/api/v1/query?query=" + query))
                  .timeout(Duration.ofSeconds(5))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      var result = JSON.readTree(response.body()).path("data").path("result");
      if (!result.isEmpty()) {
        assertEquals("/hello/{name}", result.get(0).path("metric").path("route").asText());
        count = Double.parseDouble(result.get(0).path("value").get(1).asText());
      }

      if (count < target) {
        TimeUnit.MILLISECONDS.sleep(200);
      }
    }

    assertEquals(target, count, "The three additional completed requests must reach Prometheus");
  }

  @Test
  void provisionsTheRequestDashboardAndExactDatasource() throws Exception {
    var response =
        client.send(
            HttpRequest.newBuilder(GRAFANA.resolve("/api/dashboards/uid/shoostr-requests"))
                .header(HttpHeaders.AUTHORIZATION.value(), AUTHORIZATION)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode());
    var dashboard = JSON.readTree(response.body()).path("dashboard");
    assertAll(
        () -> assertEquals("Shoostr requests", dashboard.path("title").asText()),
        () -> assertEquals(5, dashboard.path("panels").size()),
        () ->
            assertEquals(
                "shoostr-prometheus",
                dashboard.path("panels").get(0).path("datasource").path("uid").asText()),
        () ->
            assertEquals(
                "http_server_requests_active_seconds_count",
                dashboard.path("panels").get(4).path("targets").get(0).path("expr").asText()));
  }

  @Test
  void queriesActualRequestSamplesThroughGrafana() throws Exception {
    assertEquals(
        200,
        client
            .send(
                HttpRequest.newBuilder(APP.resolve("/hello/Grafana"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.discarding())
            .statusCode());
    var body =
        JSON.writeValueAsString(
            Map.of(
                "queries",
                List.of(
                    Map.of(
                        "refId",
                        "A",
                        "datasource",
                        Map.of("uid", "shoostr-prometheus", "type", "prometheus"),
                        "expr",
                        "http_server_requests_seconds_count{route=\"/hello/{name}\",status=\"200\"}",
                        "instant",
                        true,
                        "range",
                        false,
                        "format",
                        "table")),
                "from",
                "now-5m",
                "to",
                "now"));
    var deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    double count = 0;
    while (System.nanoTime() < deadline && count < 1) {
      var response =
          client.send(
              HttpRequest.newBuilder(GRAFANA.resolve("/api/ds/query"))
                  .header(HttpHeaders.AUTHORIZATION.value(), AUTHORIZATION)
                  .header(HttpHeaders.CONTENT_TYPE.value(), "application/json")
                  .timeout(Duration.ofSeconds(5))
                  .POST(HttpRequest.BodyPublishers.ofString(body))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      var frames = JSON.readTree(response.body()).path("results").path("A").path("frames");
      if (!frames.isEmpty()) {
        count = frames.get(0).path("data").path("values").get(1).get(0).asDouble();
      }

      if (count < 1) {
        TimeUnit.MILLISECONDS.sleep(200);
      }
    }

    assertTrue(
        count >= 1, "Grafana must query actual backend samples, not just provision successfully");
  }

  private static void compose(boolean diagnostics, String... arguments) throws Exception {
    var command =
        new ArrayList<>(
            List.of(
                "docker",
                "compose",
                "-p",
                project,
                "-f",
                directory.resolve("compose.yaml").toString()));
    command.addAll(List.of(arguments));
    var builder =
        new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
    builder
        .environment()
        .putAll(Map.of("APP_PORT", "19081", "PROMETHEUS_PORT", "19090", "GRAFANA_PORT", "13000"));
    if (diagnostics) {
      var output = directory.resolve("build/docker/compose.log");
      Files.createDirectories(output.getParent());
      builder.redirectOutput(output.toFile());
    } else {
      builder.inheritIO();
    }

    var process = builder.start();
    if (!process.waitFor(180, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IOException("Compose command timed out: " + command);
    }

    assertEquals(0, process.exitValue(), "Compose command failed: " + command);
  }
}
