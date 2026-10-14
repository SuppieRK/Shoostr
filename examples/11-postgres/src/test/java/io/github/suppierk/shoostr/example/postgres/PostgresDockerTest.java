package io.github.suppierk.shoostr.example.postgres;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.suppierk.shoostr.http.HttpHeaders;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("docker")
class PostgresDockerTest {
  private static final URI APP = URI.create("http://127.0.0.1:19083");
  private static Path directory;
  private static String project;
  private static HttpClient client;

  @BeforeAll
  static void startTheIsolatedStack() throws Exception {
    directory = Path.of(Objects.requireNonNull(System.getProperty("example.directory")));
    project = "shoostr-postgres-test-" + UUID.randomUUID().toString().substring(0, 8);
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    try {
      compose(false, "up", "-d");
      await("Backend readiness")
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
                              HttpRequest.newBuilder(APP.resolve("/health"))
                                  .timeout(Duration.ofSeconds(3))
                                  .GET()
                                  .build(),
                              HttpResponse.BodyHandlers.discarding())
                          .statusCode()));
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
  void retrievesTheStoredNoteAfterTheApplicationRestarts() throws Exception {
    var created =
        client.send(
            HttpRequest.newBuilder(APP.resolve("/notes"))
                .header(HttpHeaders.CONTENT_TYPE.value(), "text/plain; charset=utf-8")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString("Remember Flyway"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertAll(
        () -> assertEquals(201, created.statusCode()),
        () -> assertEquals("Remember Flyway", created.body()));
    var location = created.headers().firstValue(HttpHeaders.LOCATION.value()).orElseThrow();
    assertEquals(
        "/notes/1",
        location,
        "A freshly isolated database starts with its first generated identity");

    var found =
        client.send(
            HttpRequest.newBuilder(APP.resolve(location))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertAll(
        () -> assertEquals(200, found.statusCode()),
        () -> assertEquals("Remember Flyway", found.body()));

    compose(false, "restart", "--timeout", "10", "app");
    await("Application must restart with the existing migrated database")
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .pollInSameThread()
        .ignoreExceptionsInstanceOf(IOException.class)
        .untilAsserted(
            () ->
                assertEquals(
                    200,
                    client
                        .send(
                            HttpRequest.newBuilder(APP.resolve("/health"))
                                .timeout(Duration.ofSeconds(3))
                                .GET()
                                .build(),
                            HttpResponse.BodyHandlers.discarding())
                        .statusCode()));

    var persisted =
        client.send(
            HttpRequest.newBuilder(APP.resolve(location))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertAll(
        () -> assertEquals(200, persisted.statusCode()),
        () -> assertEquals("Remember Flyway", persisted.body()));
    var second =
        client.send(
            HttpRequest.newBuilder(APP.resolve("/notes"))
                .header(HttpHeaders.CONTENT_TYPE.value(), "text/plain; charset=utf-8")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString("Still persisted"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertAll(
        () -> assertEquals(201, second.statusCode()),
        () ->
            assertEquals(
                "/notes/2",
                second.headers().firstValue(HttpHeaders.LOCATION.value()).orElseThrow()));
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
    builder.environment().put("APP_PORT", "19083");
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
