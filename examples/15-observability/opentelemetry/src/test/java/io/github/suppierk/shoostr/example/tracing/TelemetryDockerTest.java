package io.github.suppierk.shoostr.example.tracing;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.stream.StreamSupport;
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
      compose(false, "pull", "--ignore-buildable");
      compose(false, "up", "--no-build", "--pull", "never", "-d");
      for (var uri :
          List.of(
              APP.resolve("/hello/Ready"), JAEGER.resolve("/"), GRAFANA.resolve("/api/health"))) {
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
  void opensTheTraceDashboardByDefault() throws Exception {
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
        "/d/default-home-dashboard/shoostr-traces",
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
        () -> assertEquals("Shoostr traces", dashboard.path("title").asText()),
        () -> assertEquals("5s", dashboard.path("refresh").asText()),
        () -> assertEquals(1, dashboard.path("panels").size()),
        () -> assertEquals("table", dashboard.path("panels").get(0).path("type").asText()));
  }

  @Test
  void discoversRecentServerTracesThroughTheDashboardQuery() throws Exception {
    var definition =
        client.send(
            HttpRequest.newBuilder(GRAFANA.resolve("/api/dashboards/uid/shoostr-traces"))
                .header(HttpHeaders.AUTHORIZATION.value(), AUTHORIZATION)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, definition.statusCode(), definition.body());
    var target =
        JSON.readTree(definition.body())
            .path("dashboard")
            .path("panels")
            .get(0)
            .path("targets")
            .get(0);
    assertEquals("search", target.path("queryType").asText());

    var traceId = UUID.randomUUID().toString().replace("-", "");
    var request =
        client.send(
            HttpRequest.newBuilder(APP.resolve("/hello/Dashboard"))
                .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, request.statusCode());
    var body =
        JSON.writeValueAsString(Map.of("queries", List.of(target), "from", "now-15m", "to", "now"));

    await("The dashboard search must discover the generated trace without its ID as a query")
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
              assertEquals(200, response.statusCode(), response.body());
              var frames = JSON.readTree(response.body()).path("results").path("A").path("frames");
              assertFalse(frames.isEmpty(), "No recent traces have reached Grafana yet");
              var frame = frames.get(0);
              assertTrue(frame.path("data").path("values").get(0).toString().contains(traceId));
              var field = frame.path("schema").path("fields").get(0);
              assertEquals("traceID", field.path("name").asText());
              var link = field.path("config").path("links").get(0).path("internal");
              assertEquals("shoostr-jaeger", link.path("datasourceUid").asText());
              assertEquals("${__value.raw}", link.path("query").path("query").asText());
            });
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
        () -> assertTrue(response.headers().firstValue("X-Trace-Id").isEmpty()));

    var trace =
        await("Jaeger must receive the actual server span")
            .atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofMillis(200))
            .pollInSameThread()
            .until(
                () -> {
                  var backend =
                      client.send(
                          HttpRequest.newBuilder(JAEGER.resolve("/api/traces/" + traceId))
                              .timeout(Duration.ofSeconds(5))
                              .GET()
                              .build(),
                          HttpResponse.BodyHandlers.ofString());
                  if (backend.statusCode() != 200) {
                    return JSON.createObjectNode();
                  }

                  return JSON.readTree(backend.body()).path("data").path(0);
                },
                value ->
                    StreamSupport.stream(value.path("spans").spliterator(), false)
                        .anyMatch(
                            span ->
                                "GET /hello/{name}".equals(span.path("operationName").asText())));
    var span =
        StreamSupport.stream(trace.path("spans").spliterator(), false)
            .filter(value -> "GET /hello/{name}".equals(value.path("operationName").asText()))
            .findFirst()
            .orElseThrow();
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
        () -> assertEquals("200", tags.get("http.response.status_code")));

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
    assertTrue(
        result.path("frames").toString().contains("greeting"),
        "Grafana must display the internal operation in the same trace");
  }

  @Test
  void retainsTheGreetingSpanContextAcrossInternalMethodCalls() throws Exception {
    var traceId = UUID.randomUUID().toString().replace("-", "");
    var response =
        client.send(
            HttpRequest.newBuilder(APP.resolve("/hello/Methods"))
                .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.discarding());
    assertEquals(200, response.statusCode());

    await("The internal greeting and formatting call must belong to the same server trace")
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .pollInSameThread()
        .untilAsserted(
            () -> {
              var backend =
                  client.send(
                      HttpRequest.newBuilder(JAEGER.resolve("/api/traces/" + traceId))
                          .timeout(Duration.ofSeconds(5))
                          .GET()
                          .build(),
                      HttpResponse.BodyHandlers.ofString());
              assertEquals(200, backend.statusCode(), backend.body());
              var spans = JSON.readTree(backend.body()).path("data").get(0).path("spans");
              assertEquals(2, spans.size(), "Expected SERVER and greeting spans");
              var server =
                  StreamSupport.stream(spans.spliterator(), false)
                      .filter(
                          span -> "GET /hello/{name}".equals(span.path("operationName").asText()))
                      .findFirst()
                      .orElseThrow();
              var greeting =
                  StreamSupport.stream(spans.spliterator(), false)
                      .filter(span -> "greeting".equals(span.path("operationName").asText()))
                      .findFirst()
                      .orElseThrow();
              assertAll(
                  () -> assertEquals(traceId, server.path("traceID").asText()),
                  () -> assertEquals(traceId, greeting.path("traceID").asText()),
                  () ->
                      assertNotEquals(
                          server.path("spanID").asText(), greeting.path("spanID").asText()),
                  () ->
                      assertEquals(
                          server.path("spanID").asText(),
                          greeting.path("references").get(0).path("spanID").asText()),
                  () ->
                      assertTrue(
                          greeting.path("logs").toString().contains("Greeting formatted"),
                          "The formatting method must add its event to the current greeting span"));
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
