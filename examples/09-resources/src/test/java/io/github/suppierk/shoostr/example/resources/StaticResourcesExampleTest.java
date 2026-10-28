package io.github.suppierk.shoostr.example.resources;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StaticResourcesExampleTest {
  @TempDir Path directory;

  @Test
  void rejectsAnUnavailableClasspathDirectoryBeforeReturningTheApplication() {
    assertThrows(
        IllegalArgumentException.class, () -> StaticResourcesExample.application(directory));
  }

  @Test
  void servesOnlyTheMountedPublicFileWithItsMediaType() throws Exception {
    var files = Files.createDirectory(directory.resolve("files"));
    Files.writeString(files.resolve("notes.txt"), "public notes");
    var bundled = Files.createDirectory(directory.resolve("recipe-public"));
    Files.writeString(bundled.resolve("site.css"), "body { color: navy; }");
    var previous = Thread.currentThread().getContextClassLoader();

    try (var loader = new URLClassLoader(new URL[] {directory.toUri().toURL()}, previous)) {
      Thread.currentThread().setContextClassLoader(loader);

      try (var test = TestServer.start(StaticResourcesExample.application(files))) {
        var response =
            test.send(
                request -> request.path("/files/notes.txt"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("public notes", response.body());
        assertEquals("text/plain", response.headers().firstValue("Content-Type").orElseThrow());
      }
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }

  @Test
  void servesBundledFilesOnlyBelowTheClasspathMount() throws Exception {
    var files = Files.createDirectory(directory.resolve("files"));
    var bundled = Files.createDirectory(directory.resolve("recipe-public"));
    Files.writeString(bundled.resolve("site.css"), "body { color: navy; }");
    Files.writeString(directory.resolve("private.txt"), "private data");
    var previous = Thread.currentThread().getContextClassLoader();

    try (var loader = new URLClassLoader(new URL[] {directory.toUri().toURL()}, previous)) {
      Thread.currentThread().setContextClassLoader(loader);

      try (var test = TestServer.start(StaticResourcesExample.application(files))) {
        var response =
            test.send(
                request -> request.path("/bundled/site.css"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("body { color: navy; }", response.body());
        assertEquals("text/css", response.headers().firstValue("Content-Type").orElseThrow());
        assertEquals(404, test.send(request -> request.path("/bundled/private.txt")).statusCode());
        assertEquals(404, test.send(request -> request.path("/private.txt")).statusCode());
      }
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }

  @ParameterizedTest
  @CsvSource({
    "/app/,shell",
    "/app/folder/,folder welcome",
    "/app/missing/page,shell",
    "/app/version,v1"
  })
  void appliesWelcomeAndSpaFallbackWithoutReplacingExplicitRoutes(String path, String expected)
      throws Exception {
    var files = Files.createDirectory(directory.resolve("files"));
    Files.writeString(files.resolve("index.html"), "shell");
    Files.writeString(files.resolve("version"), "on disk");
    var folder = Files.createDirectory(files.resolve("folder"));
    Files.writeString(folder.resolve("index.html"), "folder welcome");
    Files.createDirectory(directory.resolve("recipe-public"));
    var previous = Thread.currentThread().getContextClassLoader();

    try (var loader = new URLClassLoader(new URL[] {directory.toUri().toURL()}, previous)) {
      Thread.currentThread().setContextClassLoader(loader);

      try (var test = TestServer.start(StaticResourcesExample.application(files))) {
        var response =
            test.send(request -> request.path(path), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals(expected, response.body());
        assertEquals(404, test.send(request -> request.path("/files/")).statusCode());
      }
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }

  @Test
  void preservesHeadMetadataAndUsesBinaryFallbackForUnknownExtensions() throws Exception {
    var files = Files.createDirectory(directory.resolve("files"));
    Files.writeString(files.resolve("notes.txt"), "public notes");
    Files.write(files.resolve("data.shoostr-unknown"), new byte[] {0, -1, 3});
    Files.createDirectory(directory.resolve("recipe-public"));
    var previous = Thread.currentThread().getContextClassLoader();

    try (var loader = new URLClassLoader(new URL[] {directory.toUri().toURL()}, previous)) {
      Thread.currentThread().setContextClassLoader(loader);

      try (var test = TestServer.start(StaticResourcesExample.application(files))) {
        var head = test.send(request -> request.path("/files/notes.txt").method("HEAD"));
        assertEquals(200, head.statusCode());
        assertEquals(0, head.body().length);
        assertEquals("12", head.headers().firstValue("Content-Length").orElseThrow());
        assertEquals("text/plain", head.headers().firstValue("Content-Type").orElseThrow());
        var binary = test.send(request -> request.path("/files/data.shoostr-unknown"));
        assertEquals(200, binary.statusCode());
        assertEquals(
            "application/octet-stream", binary.headers().firstValue("Content-Type").orElseThrow());
        assertArrayEquals(new byte[] {0, -1, 3}, binary.body());
      }
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }

  @Test
  void rejectsTraversalAndNeverServesTheTargetOfAnExternalDirectoryAlias() throws Exception {
    var files = Files.createDirectory(directory.resolve("files"));
    Files.writeString(files.resolve("index.html"), "shell");
    var external = Files.createDirectory(directory.resolve("external"));
    Files.writeString(external.resolve("private.txt"), "private data");
    directoryAlias(files.resolve("escape"), external);
    assertEquals("private data", Files.readString(files.resolve("escape/private.txt")));
    Files.createDirectory(directory.resolve("recipe-public"));
    var previous = Thread.currentThread().getContextClassLoader();

    try (var loader = new URLClassLoader(new URL[] {directory.toUri().toURL()}, previous)) {
      Thread.currentThread().setContextClassLoader(loader);

      try (var test = TestServer.start(StaticResourcesExample.application(files))) {
        assertEquals(
            400,
            test.send(request -> request.path("/files/%2e%2e/external/private.txt")).statusCode());
        assertEquals(
            404, test.send(request -> request.path("/files/escape/private.txt")).statusCode());
        var fallback =
            test.send(
                request -> request.path("/app/escape/private.txt"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, fallback.statusCode());
        assertEquals("shell", fallback.body());
      }
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }

  private static void directoryAlias(Path alias, Path target) throws Exception {
    if (!System.getProperty("os.name").startsWith("Windows")) {
      Files.createSymbolicLink(alias, target);
      return;
    }

    // Windows directory junctions exercise alias rejection without symlink privileges.
    var log = Files.createTempFile(alias.getParent(), "junction-", ".log");
    var process =
        new ProcessBuilder(
                "cmd.exe", "/d", "/c", "mklink", "/J", alias.toString(), target.toString())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();

    try {
      assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Junction creation timed out");
      assertEquals(
          0, process.exitValue(), new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
    } finally {
      process.destroyForcibly();
      assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Junction command did not exit");
      Files.deleteIfExists(log);
    }
  }
}
