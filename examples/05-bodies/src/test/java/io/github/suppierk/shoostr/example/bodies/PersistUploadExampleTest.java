package io.github.suppierk.shoostr.example.bodies;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PersistUploadExampleTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(ints = {4, 20_000})
  void keepsInMemoryAndSpilledUploadsAfterTheApplicationCloses(int size) throws Exception {
    Path saved;
    var bytes = new byte[size];
    bytes[0] = -1;
    bytes[size - 1] = -128;

    try (var test = TestServer.start(PersistUploadExample.application(directory))) {
      var response =
          test.send(
              request ->
                  request
                      .path("/documents")
                      .method("POST")
                      .header("Content-Type", "multipart/form-data; boundary=example")
                      .body(multipart("notes.bin", bytes)),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(201, response.statusCode());
      saved = directory.resolve(response.body());
      assertArrayEquals(bytes, Files.readAllBytes(saved));
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(
              () -> {
                try (var files = Files.list(directory)) {
                  assertEquals(Set.of(saved), files.collect(java.util.stream.Collectors.toSet()));
                }
              });
    }

    assertArrayEquals(bytes, Files.readAllBytes(saved));
  }

  @Test
  void generatesUniqueNamesAndPreservesExistingFiles() throws Exception {
    var sentinel = Files.writeString(directory.resolve("existing.txt"), "keep");

    try (var test = TestServer.start(PersistUploadExample.application(directory))) {
      var first = send(test, "../../existing.txt", new byte[] {1});
      var second = send(test, "../../existing.txt", new byte[] {2});
      assertEquals(201, first.statusCode());
      assertEquals(201, second.statusCode());
      assertNotEquals(first.body(), second.body());
      assertTrue(first.body().matches("upload-[0-9]+\\.bin"));
      assertTrue(second.body().matches("upload-[0-9]+\\.bin"));
      assertArrayEquals(new byte[] {1}, Files.readAllBytes(directory.resolve(first.body())));
      assertArrayEquals(new byte[] {2}, Files.readAllBytes(directory.resolve(second.body())));
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(
              () -> {
                try (var files = Files.list(directory)) {
                  assertEquals(
                      Set.of(sentinel.getFileName().toString(), first.body(), second.body()),
                      files
                          .map(path -> path.getFileName().toString())
                          .collect(java.util.stream.Collectors.toSet()));
                }
              });
    }

    assertEquals("keep", Files.readString(sentinel));
  }

  @Test
  void rejectsMissingUploadsWithoutCreatingADestination() throws Exception {
    try (var test = TestServer.start(PersistUploadExample.application(directory))) {
      var response =
          test.send(
              request -> request.path("/documents").method("POST"),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(400, response.statusCode());

      try (var files = Files.list(directory)) {
        assertEquals(0, files.count());
      }
    }
  }

  @Test
  void createsTheStorageDirectoryBeforeStartupAndRejectsInvalidPaths() throws Exception {
    var storage = directory.resolve("nested/files");

    try (var app = PersistUploadExample.application(storage)) {
      assertTrue(Files.isDirectory(storage));
    }

    var file = Files.createFile(directory.resolve("not-a-directory"));
    assertThrows(IOException.class, () -> PersistUploadExample.application(file));
    assertThrows(NullPointerException.class, () -> PersistUploadExample.application(null));
  }

  private static HttpResponse<String> send(TestServer test, String filename, byte[] bytes)
      throws Exception {
    return test.send(
        request ->
            request
                .path("/documents")
                .method("POST")
                .header("Content-Type", "multipart/form-data; boundary=example")
                .body(multipart(filename, bytes)),
        HttpResponse.BodyHandlers.ofString());
  }

  private static byte[] multipart(String filename, byte[] bytes) {
    var body = new ByteArrayOutputStream();
    body.writeBytes(
        ("--example\r\nContent-Disposition: form-data; name=\"document\"; filename=\""
                + filename
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    body.writeBytes(bytes);
    body.writeBytes("\r\n--example--\r\n".getBytes(StandardCharsets.US_ASCII));
    return body.toByteArray();
  }
}
