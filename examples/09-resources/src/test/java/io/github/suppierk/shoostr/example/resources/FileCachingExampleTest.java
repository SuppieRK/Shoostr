package io.github.suppierk.shoostr.example.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class FileCachingExampleTest {
  @TempDir Path directory;

  @Test
  void servesOneByteRangeWhenItsStrongRevisionStillMatches() throws Exception {
    var file =
        Files.writeString(
            directory.resolve("revision.txt"), "0123456789", StandardCharsets.US_ASCII);

    try (var test = TestServer.start(FileCachingExample.application(file))) {
      var response =
          test.send(
              request ->
                  request
                      .path("/file")
                      .header("Range", "bytes=2-5")
                      .header("If-Range", "\"revision-1\""),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(206, response.statusCode());
      assertEquals("2345", response.body());
      assertEquals("bytes 2-5/10", response.headers().firstValue("Content-Range").orElseThrow());
      assertEquals("4", response.headers().firstValue("Content-Length").orElseThrow());
      assertEquals("bytes", response.headers().firstValue("Accept-Ranges").orElseThrow());
      assertEquals("\"revision-1\"", response.headers().firstValue("ETag").orElseThrow());
    }
  }

  @ParameterizedTest
  @CsvSource({
    "bytes=-3,206,789,bytes 7-9/10",
    "bytes=7-,206,789,bytes 7-9/10",
    "bytes=10-,416,'',bytes */10"
  })
  void selectsSuffixAndOpenRangesOrReportsAnUnsatisfiableInterval(
      String range, int status, String body, String contentRange) throws Exception {
    var file =
        Files.writeString(
            directory.resolve("revision.txt"), "0123456789", StandardCharsets.US_ASCII);

    try (var test = TestServer.start(FileCachingExample.application(file))) {
      var response =
          test.send(
              request -> request.path("/file").header("Range", range),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(status, response.statusCode());
      assertEquals(body, response.body());
      assertEquals(contentRange, response.headers().firstValue("Content-Range").orElseThrow());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"items=0-1", "bytes=0-1,4-5", "bytes=invalid"})
  void ignoresUnsupportedMultipleOrMalformedRanges(String range) throws Exception {
    var file =
        Files.writeString(
            directory.resolve("revision.txt"), "0123456789", StandardCharsets.US_ASCII);

    try (var test = TestServer.start(FileCachingExample.application(file))) {
      var response =
          test.send(
              request -> request.path("/file").header("Range", range),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("0123456789", response.body());
      assertTrue(response.headers().firstValue("Content-Range").isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"\"revision-2\"", "W/\"revision-1\""})
  void sendsTheWholeFileWhenIfRangeDoesNotStronglyMatch(String validator) throws Exception {
    var file =
        Files.writeString(
            directory.resolve("revision.txt"), "0123456789", StandardCharsets.US_ASCII);

    try (var test = TestServer.start(FileCachingExample.application(file))) {
      var response =
          test.send(
              request ->
                  request.path("/file").header("Range", "bytes=2-5").header("If-Range", validator),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("0123456789", response.body());
      assertTrue(response.headers().firstValue("Content-Range").isEmpty());
    }
  }

  @Test
  void returnsNotModifiedForMatchingTagAndDateValidators() throws Exception {
    var file =
        Files.writeString(
            directory.resolve("revision.txt"), "0123456789", StandardCharsets.US_ASCII);

    try (var test = TestServer.start(FileCachingExample.application(file))) {
      var first = test.send(request -> request.path("/file"));
      assertEquals(200, first.statusCode());
      var tag = first.headers().firstValue("ETag").orElseThrow();
      var modified = first.headers().firstValue("Last-Modified").orElseThrow();
      var byTag = test.send(request -> request.path("/file").header("If-None-Match", tag));
      assertEquals(304, byTag.statusCode());
      assertEquals(0, byTag.body().length);
      assertEquals(tag, byTag.headers().firstValue("ETag").orElseThrow());
      var byDate =
          test.send(request -> request.path("/file").header("If-Modified-Since", modified));
      assertEquals(304, byDate.statusCode());
      assertEquals(0, byDate.body().length);
    }
  }

  @Test
  void sendsTheWholeFileWhenIfRangeContainsTheReturnedModificationDate() throws Exception {
    var file =
        Files.writeString(
            directory.resolve("revision.txt"), "0123456789", StandardCharsets.US_ASCII);

    try (var test = TestServer.start(FileCachingExample.application(file))) {
      var first = test.send(request -> request.path("/file"));
      var modified = first.headers().firstValue("Last-Modified").orElseThrow();
      var response =
          test.send(
              request ->
                  request.path("/file").header("Range", "bytes=2-5").header("If-Range", modified),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("0123456789", response.body());
      assertTrue(response.headers().firstValue("Content-Range").isEmpty());
    }
  }

  @Test
  void evaluatesPreconditionsBeforeSelectingARange() throws Exception {
    var file =
        Files.writeString(
            directory.resolve("revision.txt"), "0123456789", StandardCharsets.US_ASCII);

    try (var test = TestServer.start(FileCachingExample.application(file))) {
      var response =
          test.send(
              request ->
                  request
                      .path("/file")
                      .header("If-Match", "\"other\"")
                      .header("Range", "bytes=2-5"));
      assertEquals(412, response.statusCode());
      assertEquals(0, response.body().length);
      assertTrue(response.headers().firstValue("Content-Range").isEmpty());
    }
  }

  @Test
  void givesTagConditionsPrecedenceOverDateConditions() throws Exception {
    var file =
        Files.writeString(
            directory.resolve("revision.txt"), "0123456789", StandardCharsets.US_ASCII);
    Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2020-01-01T00:00:00Z")));

    try (var test = TestServer.start(FileCachingExample.application(file))) {
      var first = test.send(request -> request.path("/file"));
      var modified = first.headers().firstValue("Last-Modified").orElseThrow();
      var noneMatch =
          test.send(
              request ->
                  request
                      .path("/file")
                      .header("If-None-Match", "\"other\"")
                      .header("If-Modified-Since", modified),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, noneMatch.statusCode());
      assertEquals("0123456789", noneMatch.body());
      var match =
          test.send(
              request ->
                  request
                      .path("/file")
                      .header("If-Match", "\"revision-1\"")
                      .header("If-Unmodified-Since", "Thu, 01 Jan 1970 00:00:00 GMT"));
      assertEquals(200, match.statusCode());
      var oldDate =
          test.send(
              request ->
                  request
                      .path("/file")
                      .header("If-Unmodified-Since", "Thu, 01 Jan 1970 00:00:00 GMT"));
      assertEquals(412, oldDate.statusCode());
      assertEquals(0, oldDate.body().length);
    }
  }
}
