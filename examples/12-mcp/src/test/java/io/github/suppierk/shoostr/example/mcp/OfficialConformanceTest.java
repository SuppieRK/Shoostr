package io.github.suppierk.shoostr.example.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.testing.TestServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("conformance")
class OfficialConformanceTest {
  @ParameterizedTest
  @ValueSource(strings = {"requirements", "server-session-lifecycle", "json-schema-2020-12"})
  @Timeout(value = 10, unit = TimeUnit.MINUTES)
  void satisfiesTheOfficialServerContract(String selection) throws Exception {
    var directory = Path.of(System.getProperty("conformance.directory"));
    var cli = directory.resolve("node_modules/@modelcontextprotocol/conformance/dist/index.js");
    assertTrue(Files.isRegularFile(cli), "Run npm ci --ignore-scripts in examples/12-mcp first");
    var output = directory.resolve("build/conformance/" + selection);
    Files.createDirectories(output);
    var log = output.resolve("runner.log");
    var port = Integer.parseInt(System.getProperty("conformance.port"));

    try (var test =
        TestServer.startOnPort(McpExample.application(Options.defaults().withPort(port)), port)) {
      assertEquals(200, test.send(request -> request.path("/health")).statusCode());
      var command =
          new ArrayList<>(
              List.of(
                  "node",
                  cli.toString(),
                  "server",
                  "--url",
                  test.baseUri().resolve("/mcp").toString(),
                  "--timeout",
                  "30000",
                  "--output-dir",
                  output.toString()));
      if ("requirements".equals(selection)) {
        command.addAll(List.of("--requirements", "2025-11-25"));
      } else {
        command.addAll(List.of("--spec-version", "2025-11-25", "--scenario", selection));
      }

      var process =
          new ProcessBuilder(command)
              .directory(directory.toFile())
              .redirectErrorStream(true)
              .redirectOutput(log.toFile())
              .start();

      try {
        assertTrue(process.waitFor(8, TimeUnit.MINUTES), "Conformance timed out; see " + log);
        assertEquals(0, process.exitValue(), () -> "Conformance failed; see " + log);
      } finally {
        if (process.isAlive()) {
          process.destroy();
          if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Cannot stop conformance process");
          }
        }
      }
    }
  }
}
