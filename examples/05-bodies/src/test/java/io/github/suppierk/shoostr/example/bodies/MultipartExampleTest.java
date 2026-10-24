package io.github.suppierk.shoostr.example.bodies;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MultipartExampleTest {
  @TempDir Path directory;

  @Test
  void readsAFieldAndUploadedBytes() throws Exception {
    try (var test = TestServer.start(MultipartExample.application(directory))) {
      var response =
          send(
              test,
              multipart(
                  part("title", null, "Notes".getBytes(StandardCharsets.UTF_8)),
                  part("document", "notes.txt", new byte[] {'a', 'b', 'c'})));
      assertEquals(200, response.statusCode());
      assertEquals(
          "title=Notes\nfirst=notes.txt\nfiles=1\nfields=1\nbytes=3\nhex=616263", response.body());
    }
  }

  @Test
  void preservesRepeatedFilesBinaryContentAndAnEmptyFilename() throws Exception {
    try (var test = TestServer.start(MultipartExample.application(directory))) {
      var response =
          send(
              test,
              multipart(
                  part("title", null, "A+B".getBytes(StandardCharsets.UTF_8)),
                  part("document", "", new byte[] {0, -1}),
                  part("document", "other.bin", new byte[] {127, -128}),
                  part("attachment", "extra.txt", new byte[0])));
      assertEquals(200, response.statusCode());
      assertEquals("title=A+B\nfirst=\nfiles=2\nfields=2\nbytes=4\nhex=00ff7f80", response.body());
    }
  }

  @ParameterizedTest
  @CsvSource({"1024, 200", "1025, 413"})
  void enforcesThePartLimitForBothFieldsAndFiles(int size, int status) throws Exception {
    try (var test = TestServer.start(MultipartExample.application(directory))) {
      assertEquals(
          status, send(test, multipart(part("document", "data.bin", new byte[size]))).statusCode());
      assertEquals(status, send(test, multipart(part("title", null, new byte[size]))).statusCode());
    }

    awaitEmptyDirectory();
  }

  @ParameterizedTest
  @CsvSource({"8, 200", "9, 413"})
  void countsEveryMultipartPart(int count, int status) throws Exception {
    var parts = new byte[count][];
    for (var i = 0; i < count; i++) {
      parts[i] = part("title", null, new byte[0]);
    }

    try (var test = TestServer.start(MultipartExample.application(directory))) {
      assertEquals(status, send(test, multipart(parts)).statusCode());
    }
  }

  @Test
  void rejectsAggregateOverflowEvenWhenEveryPartFits() throws Exception {
    var parts = new byte[8][];
    for (var i = 0; i < parts.length; i++) {
      parts[i] = part("document", "data.bin", new byte[1024]);
    }

    try (var test = TestServer.start(MultipartExample.application(directory))) {
      assertEquals(413, send(test, multipart(parts)).statusCode());
    }

    awaitEmptyDirectory();
  }

  @Test
  void rejectsOversizedHeadersWithTinyContent() throws Exception {
    try (var test = TestServer.start(MultipartExample.application(directory))) {
      assertEquals(
          413, send(test, multipart(part("document", "x".repeat(1100), new byte[1]))).statusCode());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Content-Disposition: form-data",
        "Content-Disposition: attachment; name=\"title\""
      })
  void rejectsMalformedPartMetadata(String disposition) throws Exception {
    var body =
        ("--example\r\n" + disposition + "\r\n\r\ntext\r\n--example--\r\n")
            .getBytes(StandardCharsets.UTF_8);

    try (var test = TestServer.start(MultipartExample.application(directory))) {
      assertEquals(400, send(test, body).statusCode());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"application/json", "text/plain; charset=ISO-8859-1"})
  void rejectsUnsupportedTextFieldRepresentations(String contentType) throws Exception {
    var body =
        ("--example\r\nContent-Disposition: form-data; name=\"title\"\r\nContent-Type: "
                + contentType
                + "\r\n\r\ntext\r\n--example--\r\n")
            .getBytes(StandardCharsets.UTF_8);

    try (var test = TestServer.start(MultipartExample.application(directory))) {
      assertEquals(415, send(test, body).statusCode());
    }
  }

  @ParameterizedTest
  @CsvSource({"128, 0", "129, 1"})
  void spillsAboveTheThresholdAndCleansUpAfterTheExchange(int size, int expectedFiles)
      throws Exception {
    try (var app = MultipartExample.application(directory)) {
      app.routes()
          .post(
              "/storage",
              (request, response) -> {
                request.files();

                try (var files = Files.list(directory)) {
                  response.text(Long.toString(files.count()));
                }
              });

      try (var test = TestServer.start(app)) {
        var response =
            test.send(
                request ->
                    request
                        .path("/storage")
                        .method("POST")
                        .header("Content-Type", "multipart/form-data; boundary=example")
                        .body(multipart(part("document", "data.bin", new byte[size]))),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals(Integer.toString(expectedFiles), response.body());
        awaitEmptyDirectory();
      }
    }
  }

  @Test
  void rejectsBufferedAndStreamingAccessAfterMultipartParsing() throws Exception {
    try (var app = MultipartExample.application(directory)) {
      app.routes()
          .post(
              "/modes",
              (request, response) -> {
                request.files();
                assertThrows(IllegalStateException.class, request::bodyBytes);
                assertThrows(IllegalStateException.class, request::input);
                response.text("checked");
              });

      try (var test = TestServer.start(app)) {
        var response =
            test.send(
                request ->
                    request
                        .path("/modes")
                        .method("POST")
                        .header("Content-Type", "multipart/form-data; boundary=example")
                        .body(multipart(part("document", "a.txt", new byte[0]))),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("checked", response.body());
      }
    }
  }

  @Test
  void rejectsAFileAsTheStorageDirectoryBeforeStartup() throws Exception {
    var file = Files.createFile(directory.resolve("not-a-directory"));
    assertThrows(IOException.class, () -> MultipartExample.application(file));
    assertThrows(NullPointerException.class, () -> MultipartExample.application(null));
  }

  private static HttpResponse<String> send(TestServer test, byte[] body) throws Exception {
    return test.send(
        request ->
            request
                .path("/documents")
                .method("POST")
                .header("Content-Type", "multipart/form-data; boundary=example")
                .body(body),
        HttpResponse.BodyHandlers.ofString());
  }

  private void awaitEmptyDirectory() {
    await()
        .atMost(Duration.ofSeconds(3))
        .untilAsserted(
            () -> {
              try (var files = Files.list(directory)) {
                assertEquals(0, files.count());
              }
            });
  }

  private static byte[] multipart(byte[]... parts) {
    var body = new ByteArrayOutputStream();
    for (var part : parts) {
      body.writeBytes(part);
    }
    body.writeBytes("--example--\r\n".getBytes(StandardCharsets.US_ASCII));
    return body.toByteArray();
  }

  private static byte[] part(String name, String filename, byte[] content) {
    var body = new ByteArrayOutputStream();
    var disposition = "--example\r\nContent-Disposition: form-data; name=\"" + name + "\"";
    if (filename != null) {
      disposition += "; filename=\"" + filename + "\"";
    }

    body.writeBytes((disposition + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
    body.writeBytes(content);
    body.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
    return body.toByteArray();
  }
}
