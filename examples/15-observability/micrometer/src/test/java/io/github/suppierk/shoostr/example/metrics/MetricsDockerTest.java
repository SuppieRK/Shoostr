package io.github.suppierk.shoostr.example.metrics;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
      compose(false, "pull", "--ignore-buildable");
      compose(false, "up", "--no-build", "--pull", "never", "-d");
      for (var uri :
          List.of(
              APP.resolve("/metrics"),
              PROMETHEUS.resolve("/-/ready"),
              GRAFANA.resolve("/api/health"))) {
        await("Backend readiness: " + uri)
            .atMost(Duration.ofMinutes(2))
            .pollInterval(Duration.ofMillis(200))
            .pollInSameThread()
            .ignoreExceptionsInstanceOf(IOException.class)
            .untilAsserted(
                () ->
                    assertEquals(
                        200,
                        client
                            .send(
                                HttpRequest.newBuilder(uri)
                                    .timeout(Duration.ofSeconds(3))
                                    .GET()
                                    .build(),
                                HttpResponse.BodyHandlers.discarding())
                            .statusCode()));
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
    await("The three additional completed requests must reach Prometheus")
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .pollInSameThread()
        .untilAsserted(
            () -> {
              var response =
                  client.send(
                      HttpRequest.newBuilder(PROMETHEUS.resolve("/api/v1/query?query=" + query))
                          .timeout(Duration.ofSeconds(5))
                          .GET()
                          .build(),
                      HttpResponse.BodyHandlers.ofString());
              assertEquals(200, response.statusCode());
              var result = JSON.readTree(response.body()).path("data").path("result");
              assertFalse(result.isEmpty(), "Prometheus has not scraped the request metric yet");
              assertAll(
                  () ->
                      assertEquals(
                          "/hello/{name}", result.get(0).path("metric").path("route").asText()),
                  () ->
                      assertEquals(
                          target, Double.parseDouble(result.get(0).path("value").get(1).asText())));
            });
  }

  @Test
  void opensTheRequestDashboardByDefault() throws Exception {
    var response =
        client.send(
            HttpRequest.newBuilder(GRAFANA.resolve("/api/dashboards/home"))
                .header(HttpHeaders.AUTHORIZATION.value(), AUTHORIZATION)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, response.statusCode(), response.body());
    assertEquals(
        "/d/default-home-dashboard/shoostr-requests",
        JSON.readTree(response.body()).path("redirectUri").asText(),
        response.body());

    var resolved =
        client.send(
            HttpRequest.newBuilder(GRAFANA.resolve("/api/dashboards/uid/default-home-dashboard"))
                .header(HttpHeaders.AUTHORIZATION.value(), AUTHORIZATION)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, resolved.statusCode(), resolved.body());
    var dashboard = JSON.readTree(resolved.body()).path("dashboard");
    assertAll(
        () -> assertEquals("Shoostr requests", dashboard.path("title").asText()),
        () -> assertEquals(5, dashboard.path("panels").size()),
        () -> assertEquals("5s", dashboard.path("refresh").asText()),
        () ->
            assertEquals(
                "shoostr-prometheus",
                dashboard.path("panels").get(0).path("datasource").path("uid").asText()));
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
    var definition =
        client.send(
            HttpRequest.newBuilder(GRAFANA.resolve("/api/dashboards/uid/shoostr-requests"))
                .header(HttpHeaders.AUTHORIZATION.value(), AUTHORIZATION)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, definition.statusCode(), definition.body());
    ObjectNode target =
        JSON.readTree(definition.body())
            .path("dashboard")
            .path("panels")
            .get(0)
            .path("targets")
            .get(0)
            .deepCopy();
    target.put("instant", true).put("range", false).put("format", "table");
    var body =
        JSON.writeValueAsString(Map.of("queries", List.of(target), "from", "now-5m", "to", "now"));
    await("Grafana must query actual backend samples, not just provision successfully")
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .pollInSameThread()
        .untilAsserted(
            () -> {
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
              assertFalse(frames.isEmpty(), "Grafana has not received backend samples yet");
              assertTrue(frames.get(0).path("data").path("values").get(1).get(0).asDouble() >= 1);
            });
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
