package io.github.suppierk.shoostr;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(300)
class PublishedConsumerTest {
  @TempDir static Path workspace;

  @ParameterizedTest
  @ValueSource(strings = {"pom", "module"})
  void linksAndServesFromFreshPublishedCoreWithoutWorkspaceClasses(String metadata)
      throws Exception {
    assertTrue(runConsumer(metadata, "positive", "", 0).contains("PUBLISHED_HTTP_OK"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"pom", "module"})
  void rejectsUnexpectedTestLibrariesInThePublishedRuntimeGraph(String metadata) throws Exception {
    var output =
        runConsumer(
            metadata,
            "unexpected-runtime",
            "\ndependencies { runtimeOnly 'org.junit.jupiter:junit-jupiter-api:6.1.3' }\n",
            1);
    assertTrue(output.contains("Unexpected runtime dependency"), output);
  }

  @ParameterizedTest
  @ValueSource(strings = {"pom", "module"})
  void cannotResolveTheConsumerWhenThePublishedHttpModuleIsMissing(String metadata)
      throws Exception {
    var output = runConsumer(metadata, "missing-http", "", 1);
    assertTrue(output.contains("Could not find io.github.suppierk:shoostr-http:"), output);
  }

  @ParameterizedTest
  @ValueSource(strings = {"pom", "module"})
  void rejectsResolvedBinariesThatDoNotMatchTheCurrentProducer(String metadata) throws Exception {
    var output = runConsumer(metadata, "stale-core", "", 1);
    assertTrue(output.contains("Artifact hash mismatch: shoostr-core"), output);
  }

  @Test
  void terminatesOwnedWrapperDaemonAndApplicationBeforeReportingAConsumerTimeout()
      throws Exception {
    var failure = assertThrows(AssertionError.class, () -> runConsumer("pom", "timeout", "", 0));
    assertTrue(
        String.valueOf(failure.getMessage()).contains("Consumer timed out"), failure.getMessage());
    assertOwnedProcessesStopped("timeout");
  }

  @Test
  void terminatesOwnedProcessesAndPreservesInterruption() throws Exception {
    try {
      assertThrows(InterruptedException.class, () -> runConsumer("pom", "interrupted", "", 0));
      assertTrue(Thread.interrupted(), "Consumer cleanup must restore interruption");
      assertOwnedProcessesStopped("interrupted");
    } finally {
      Thread.interrupted();
    }
  }

  private static String runConsumer(String metadata, String scenario, String extraBuild, int exit)
      throws Exception {
    var directory = Files.createTempDirectory(workspace, metadata + " consumer ");
    var root = Path.of("..").toAbsolutePath().normalize();
    var repository = directory.resolve("repository");
    copyRepository(root.resolve("build/published-consumer-repository"), repository);
    if ("missing-http".equals(scenario)) {
      try (var paths = Files.walk(repository.resolve("io/github/suppierk/shoostr-http"))) {
        for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
          Files.delete(path);
        }
      }
    }

    var coreJar = root.resolve("core/build/libs/shoostr-core-0.1.0-SNAPSHOT.jar");
    if ("stale-core".equals(scenario)) {
      coreJar = directory.resolve("stale-core.jar");
      Files.writeString(coreJar, "not the current producer bytes");
    }

    var project = directory.resolve("consumer");
    Files.createDirectories(project);
    copyResource("build.gradle", project.resolve("build.gradle"));
    Files.writeString(
        project.resolve("build.gradle"),
        Files.readString(project.resolve("build.gradle")) + extraBuild);
    Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'binary-consumer'\n");
    var source = project.resolve("src/main/java/io/github/suppierk/consumer/Main.java");
    Files.createDirectories(source.getParent());
    copyResource("Main.java", source);
    var waitForReady = "timeout".equals(scenario) || "interrupted".equals(scenario);
    if (waitForReady) {
      Files.writeString(
          source,
          Files.readString(source)
              .replace(
                  "System.out.println(\"PUBLISHED_HTTP_OK\");",
                  """
                  java.nio.file.Files.writeString(java.nio.file.Path.of("owned-application.pid"),
                      Long.toString(ProcessHandle.current().pid()));
                  Thread.sleep(600_000);
                  """));
    }

    var evidence =
        root.resolve("core/build/reports/published-consumer/" + metadata + "-" + scenario);
    Files.createDirectories(evidence);
    Files.writeString(evidence.resolve("working-directory.txt"), project.toString());
    Files.copy(
        project.resolve("build.gradle"),
        evidence.resolve("build.gradle"),
        StandardCopyOption.REPLACE_EXISTING);
    var command = new ArrayList<String>();
    if (System.getProperty("os.name").startsWith("Windows")) {
      command.addAll(List.of("cmd.exe", "/d", "/c", "gradlew.bat"));
    } else {
      command.add(root.resolve("gradlew").toString());
    }

    command.addAll(
        List.of(
            "--project-dir",
            project.toString(),
            "--no-daemon",
            "--no-build-cache",
            "--refresh-dependencies",
            "--console=plain",
            "--max-workers=2",
            "--gradle-user-home",
            workspace.resolve("gradle-home").toString(),
            "-PmetadataMode=" + metadata,
            "-PverificationRepository=" + repository.toUri(),
            "-PevidenceDirectory=" + evidence,
            "-PexpectedCore=" + coreJar,
            "-PexpectedHttp=" + root.resolve("http/build/libs/shoostr-http-0.1.0-SNAPSHOT.jar"),
            "dependencies",
            "--configuration",
            "runtimeClasspath",
            "run"));
    Files.write(evidence.resolve("command.txt"), command);
    Files.writeString(evidence.resolve("wrapper-working-directory.txt"), root.toString());
    var output = evidence.resolve("wrapper.log");
    var process =
        new ProcessBuilder(command)
            .directory(root.toFile())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();

    try (var ignored = (AutoCloseable) () -> stopOwnedProcesses(process)) {
      if (waitForReady) {
        var ready = project.resolve("owned-application.pid");
        await("consumer readiness; output at " + output)
            .atMost(Duration.ofSeconds(60))
            .pollDelay(Duration.ZERO)
            .pollInterval(Duration.ofMillis(25))
            .until(() -> Files.exists(ready) || !process.isAlive());

        assertTrue(Files.exists(ready), () -> "Consumer never became ready: " + readOutput(output));

        try (var descendants = process.descendants()) {
          Files.write(
              evidence.resolve("owned-processes.txt"),
              Stream.concat(Stream.of(process.toHandle()), descendants)
                  .map(handle -> Long.toString(handle.pid()))
                  .toList());
        }

        if ("timeout".equals(scenario)) {
          assertTrue(
              process.waitFor(1, TimeUnit.MILLISECONDS), "Consumer timed out; see " + output);
        } else {
          Thread.currentThread().interrupt();
        }
      }

      assertTrue(process.waitFor(240, TimeUnit.SECONDS), "Consumer timed out; see " + output);
      assertEquals(exit, process.exitValue(), () -> readOutput(output));
      return Files.readString(output);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw failure;
    }
  }

  private static void assertOwnedProcessesStopped(String scenario) throws IOException {
    var evidence =
        Path.of("build/reports/published-consumer/pom-" + scenario + "/owned-processes.txt");
    for (var pid : Files.readAllLines(evidence)) {
      assertFalse(
          ProcessHandle.of(Long.parseLong(pid)).map(ProcessHandle::isAlive).orElse(false),
          "Owned process remains after consumer cleanup: " + pid);
    }
  }

  // Poll owned-process liveness under one deadline, preserving interruption during cleanup.
  @SuppressWarnings("java:S2925")
  private static void stopOwnedProcesses(Process process) {
    List<ProcessHandle> owned;

    try (var descendants = process.descendants()) {
      owned = Stream.concat(Stream.of(process.toHandle()), descendants).toList();
    }

    var interrupted = Thread.interrupted();

    try {
      owned.forEach(ProcessHandle::destroyForcibly);
      var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (owned.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
        try {
          TimeUnit.NANOSECONDS.sleep(
              Math.clamp(deadline - System.nanoTime(), 1L, TimeUnit.MILLISECONDS.toNanos(10)));
        } catch (InterruptedException ignored2) {
          interrupted = true;
        }
      }

      var survivors =
          owned.stream().filter(ProcessHandle::isAlive).map(ProcessHandle::pid).toList();
      assertTrue(survivors.isEmpty(), "Owned processes survived consumer cleanup: " + survivors);
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static void copyRepository(Path source, Path destination) throws IOException {
    try (var paths = Files.walk(source)) {
      for (var path : paths.toList()) {
        var target = destination.resolve(source.relativize(path));
        if (Files.isDirectory(path)) {
          Files.createDirectories(target);
        } else {
          Files.copy(path, target);
        }
      }
    }
  }

  private static void copyResource(String name, Path destination) throws IOException {
    try (var input =
        PublishedConsumerTest.class.getResourceAsStream("/published-consumer/" + name)) {
      if (input == null) {
        throw new IOException("Missing consumer resource " + name);
      }

      Files.copy(input, destination);
    }
  }

  private static String readOutput(Path output) {
    try {
      return Files.readString(output);
    } catch (IOException failure) {
      return failure.toString();
    }
  }
}
