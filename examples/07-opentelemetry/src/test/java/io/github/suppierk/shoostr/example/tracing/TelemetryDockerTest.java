package io.github.suppierk.shoostr.example.tracing;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.suppierk.shoostr.http.HttpHeaders;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
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
class TelemetryDockerTest {
  private static final URI APP = URI.create("http://127.0.0.1:19082");
  private static final URI JAEGER = URI.create("http://127.0.0.1:19686");
  private static final URI GRAFANA = URI.create("http://127.0.0.1:13001");
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
    project = "shoostr-traces-test-" + UUID.randomUUID().toString().substring(0, 8);
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    try {
      compose(false, "up", "-d");
      for (var uri :
          List.of(
              APP.resolve("/hello/Ready"), JAEGER.resolve("/"), GRAFANA.resolve("/api/health"))) {
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
  void displaysTheActualServerSpanThroughGrafana() throws Exception {
    var traceId = UUID.randomUUID().toString().replace("-", "");
    var response =
        client.send(
            HttpRequest.newBuilder(APP.resolve("/hello/Ada"))
                .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertAll(
        () -> assertEquals(200, response.statusCode()),
        () -> assertEquals("Hello, Ada!", response.body()),
        () -> assertEquals(traceId, response.headers().firstValue("X-Trace-Id").orElseThrow()));

    var deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    JsonNode trace = JSON.createObjectNode();
    while (System.nanoTime() < deadline && trace.path("spans").isEmpty()) {
      var backend =
          client.send(
              HttpRequest.newBuilder(JAEGER.resolve("/api/traces/" + traceId))
                  .timeout(Duration.ofSeconds(5))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (backend.statusCode() == 200) {
        trace = JSON.readTree(backend.body()).path("data").path(0);
      }

      if (trace.path("spans").isEmpty()) {
        TimeUnit.MILLISECONDS.sleep(200);
      }
    }
    assertEquals(1, trace.path("spans").size(), "Jaeger must receive the actual server span");
    var span = trace.path("spans").get(0);
    var process = trace.path("processes").path(span.path("processID").asText());
    assertAll(
        () -> assertEquals("GET /hello/{name}", span.path("operationName").asText()),
        () -> assertEquals("shoostr-tracing-example", process.path("serviceName").asText()),
        () ->
            assertEquals(
                "00f067aa0ba902b7", span.path("references").get(0).path("spanID").asText()));
    var tags = new HashMap<String, String>();
    span.path("tags")
        .forEach(tag -> tags.put(tag.path("key").asText(), tag.path("value").asText()));
    assertAll(
        () -> assertEquals("server", tags.get("span.kind")),
        () -> assertEquals("/hello/{name}", tags.get("http.route")),
        () -> assertEquals("200", tags.get("http.status_code")));

    var body =
        JSON.writeValueAsString(
            Map.of(
                "queries",
                List.of(
                    Map.of(
                        "refId",
                        "A",
                        "datasource",
                        Map.of("uid", "shoostr-jaeger", "type", "jaeger"),
                        "query",
                        traceId)),
                "from",
                "now-5m",
                "to",
                "now"));
    var grafana =
        client.send(
            HttpRequest.newBuilder(GRAFANA.resolve("/api/ds/query"))
                .header(HttpHeaders.AUTHORIZATION.value(), AUTHORIZATION)
                .header(HttpHeaders.CONTENT_TYPE.value(), "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, grafana.statusCode(), grafana.body());
    var result = JSON.readTree(grafana.body()).path("results").path("A");
    assertEquals(200, result.path("status").asInt(), grafana.body());
    assertTrue(
        result.path("frames").toString().contains(traceId),
        "Grafana must retrieve the same real trace");
    assertTrue(
        result.path("frames").toString().contains("GET /hello/{name}"),
        "Grafana must display the route span");
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
        .putAll(Map.of("APP_PORT", "19082", "JAEGER_PORT", "19686", "GRAFANA_PORT", "13001"));
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
