package io.github.suppierk.shoostr.example.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DownloadExampleTest {
  @TempDir Path directory;

  @Test
  void downloadsTheReportToAFileWithAsciiAndUtf8AttachmentFilenames() throws Exception {
    var report =
        Files.writeString(directory.resolve("report.csv"), "name\nAda\n", StandardCharsets.UTF_8);
    var downloaded = directory.resolve("downloaded.csv");

    try (var test = TestServer.start(DownloadExample.application(report))) {
      // Save the HTTP response to a different file rather than buffering it as text.
      var response =
          test.send(
              request -> request.path("/download"), HttpResponse.BodyHandlers.ofFile(downloaded));
      assertEquals(200, response.statusCode());
      assertEquals(downloaded, response.body());
      assertEquals(
          "text/csv; charset=utf-8", response.headers().firstValue("Content-Type").orElseThrow());
      assertEquals(
          "attachment; filename=\"r_sum_.csv\"; filename*=UTF-8''r%C3%A9sum%C3%A9.csv",
          response.headers().firstValue("Content-Disposition").orElseThrow());
    }

    assertTrue(Files.isRegularFile(downloaded));
    assertEquals("name\nAda\n", Files.readString(downloaded, StandardCharsets.UTF_8));
  }

  @ParameterizedTest
  @CsvSource({"GET,/file", "HEAD,/file", "HEAD,/download"})
  void distinguishesInlineFilesFromAttachmentsAndRegistersHeadExplicitly(String method, String path)
      throws Exception {
    var report =
        Files.writeString(directory.resolve("report.csv"), "name\nAda\n", StandardCharsets.UTF_8);

    try (var test = TestServer.start(DownloadExample.application(report))) {
      var response =
          test.send(
              request -> request.path(path).method(method), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("HEAD".equals(method) ? "" : "name\nAda\n", response.body());
      assertEquals("9", response.headers().firstValue("Content-Length").orElseThrow());
      if ("/download".equals(path)) {
        assertTrue(
            response
                .headers()
                .firstValue("Content-Disposition")
                .orElseThrow()
                .startsWith("attachment;"));
      } else {
        assertTrue(response.headers().firstValue("Content-Disposition").isEmpty());
      }
    }

    assertEquals("name\nAda\n", Files.readString(report));
  }
}
