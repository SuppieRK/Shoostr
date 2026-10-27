package io.github.suppierk.shoostr.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
class ServerMainTest {
  @TempDir Path directory;

  @Test
  void servesDistinctShallowAndNestedFilesFromOneConfiguredMountOnPlatformThreads()
      throws Exception {
    try (var stream = Files.newDirectoryStream(directory)) {
      assumeTrue(stream instanceof SecureDirectoryStream<?>);
    }

    Files.createDirectories(directory.resolve("nested/deeper"));
    Files.writeString(directory.resolve("file-0.bin"), "shallow zero");
    Files.writeString(directory.resolve("file-1.bin"), "shallow one");
    Files.writeString(directory.resolve("nested/deeper/file-0.bin"), "nested zero");
    Files.writeString(directory.resolve("nested/deeper/file-1.bin"), "nested one");

    try (var server = TestServer.start(ServerMain.fixture(1, directory))) {
      for (var location :
          new String[] {
            "file-0.bin", "file-1.bin", "nested/deeper/file-0.bin", "nested/deeper/file-1.bin"
          }) {
        var response =
            server.send(
                request -> request.path("/static/" + location),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals(Files.readString(directory.resolve(location)), response.body());
        assertEquals(
            "application/octet-stream",
            response.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("no-cache", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("bytes", response.headers().firstValue("Accept-Ranges").orElseThrow());
        assertTrue(response.headers().firstValue("ETag").orElseThrow().startsWith("W/\""));
        assertTrue(response.headers().firstValue("Last-Modified").isPresent());
      }
      assertEquals(
          "false",
          server
              .send(request -> request.path("/virtual"), HttpResponse.BodyHandlers.ofString())
              .body());
    }
  }

  @Test
  void retainsExistingDiagnosticsWhenNoStaticRootIsSelected() throws Exception {
    try (var server = TestServer.start(ServerMain.fixture(1, null))) {
      assertEquals(
          "Hello, World!",
          server
              .send(request -> request.path("/plaintext"), HttpResponse.BodyHandlers.ofString())
              .body());
      assertEquals(
          "false",
          server
              .send(request -> request.path("/virtual"), HttpResponse.BodyHandlers.ofString())
              .body());
      assertEquals(404, server.send(request -> request.path("/static/file-0.bin")).statusCode());
      assertEquals(
          "order-42",
          server
              .send(
                  request -> request.path("/api/resources/0/orders/order-42"),
                  HttpResponse.BodyHandlers.ofString())
              .body());
    }
  }

  @Test
  void rejectsUnresolvableAndRegularFileRootsBeforePublishingAnApplication() throws Exception {
    assertThrows(
        IllegalArgumentException.class, () -> ServerMain.fixture(1, directory.resolve("missing")));
    var file = Files.writeString(directory.resolve("file.bin"), "file");
    assertThrows(IllegalArgumentException.class, () -> ServerMain.fixture(1, file));
  }

  @Test
  void rejectsNonpositiveRouteGroupCountsBeforeAllocatingAnApplication() {
    assertThrows(IllegalArgumentException.class, () -> ServerMain.fixture(0, null));
    assertThrows(IllegalArgumentException.class, () -> ServerMain.fixture(-1, null));
  }
}
