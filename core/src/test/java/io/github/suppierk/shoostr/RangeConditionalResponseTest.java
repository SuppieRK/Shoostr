package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RangeConditionalResponseTest {
  @TempDir Path temporaryDirectory;

  @Test
  void servesOneInclusiveByteRangeFromFile() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "range-response-test", ".txt");
    Files.writeString(file, "0123456789", StandardCharsets.US_ASCII);

    try {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close) {
        app.routes().get("/file", (ignoredRequest, response) -> response.file(file, "text/plain"));

        try (var test = TestServer.start(app)) {
          var result =
              test.send(
                  request -> request.path("/file").header("Range", "bytes=2-5"),
                  HttpResponse.BodyHandlers.ofString());

          assertEquals(206, result.statusCode());
          assertEquals("2345", result.body());
          assertEquals("bytes 2-5/10", result.headers().firstValue("Content-Range").orElseThrow());
          assertEquals("4", result.headers().firstValue("Content-Length").orElseThrow());
          assertEquals("bytes", result.headers().firstValue("Accept-Ranges").orElseThrow());
        }
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void rejectsAnUnsatisfiableByteRange() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "range-response-test", ".txt");
    Files.writeString(file, "0123456789", StandardCharsets.US_ASCII);

    try {
      var app = new Shoostr();

      try (Closeable appCleanup1 = app::close) {
        app.routes().get("/file", (ignoredRequest, response) -> response.file(file, "text/plain"));

        try (var test = TestServer.start(app)) {
          var result =
              test.send(
                  request -> request.path("/file").header("Range", "bytes=10-"),
                  HttpResponse.BodyHandlers.ofString());

          assertEquals(416, result.statusCode());
          assertEquals("bytes */10", result.headers().firstValue("Content-Range").orElseThrow());
          assertEquals("", result.body());
        }
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void returnsNotModifiedForMatchingIfNoneMatchValidator() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "conditional-response-test", ".txt");
    Files.writeString(file, "cached", StandardCharsets.US_ASCII);

    try {
      var app = new Shoostr();

      try (Closeable appCleanup2 = app::close) {
        app.routes().get("/file", (ignoredRequest, response) -> response.file(file, "text/plain"));

        try (var test = TestServer.start(app)) {
          var first =
              test.send(request -> request.path("/file"), HttpResponse.BodyHandlers.ofString());
          var validator = first.headers().firstValue("ETag").orElseThrow();
          var second =
              test.send(
                  request -> request.path("/file").header("If-None-Match", validator),
                  HttpResponse.BodyHandlers.ofString());

          assertEquals(304, second.statusCode());
          assertEquals("", second.body());
          assertEquals(validator, second.headers().firstValue("ETag").orElseThrow());
        }
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void servesSuffixByteRangeFromFile() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "range-response-test", ".txt");
    Files.writeString(file, "0123456789", StandardCharsets.US_ASCII);

    try {
      var app = new Shoostr();

      try (Closeable appCleanup3 = app::close) {
        app.routes().get("/file", (ignoredRequest, response) -> response.file(file, "text/plain"));

        try (var test = TestServer.start(app)) {
          var result =
              test.send(
                  request -> request.path("/file").header("Range", "bytes=-3"),
                  HttpResponse.BodyHandlers.ofString());

          assertEquals(206, result.statusCode());
          assertEquals("789", result.body());
          assertEquals("bytes 7-9/10", result.headers().firstValue("Content-Range").orElseThrow());
        }
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void servesAnOpenEndedByteRangeFromFile() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "range-response-test", ".txt");
    Files.writeString(file, "0123456789", StandardCharsets.US_ASCII);

    try {
      var app = new Shoostr();

      try (Closeable appCleanup4 = app::close) {
        app.routes().get("/file", (ignoredRequest, response) -> response.file(file, "text/plain"));

        try (var test = TestServer.start(app)) {
          var result =
              test.send(
                  request -> request.path("/file").header("Range", "bytes=7-"),
                  HttpResponse.BodyHandlers.ofString());

          assertEquals(206, result.statusCode());
          assertEquals("789", result.body());
          assertEquals("bytes 7-9/10", result.headers().firstValue("Content-Range").orElseThrow());
        }
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void ignoresUnsupportedAndMultipleByteRanges() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "range-response-test", ".txt");
    Files.writeString(file, "0123456789", StandardCharsets.US_ASCII);

    try {
      var app = new Shoostr();

      try (Closeable appCleanup5 = app::close) {
        app.routes().get("/file", (ignoredRequest, response) -> response.file(file, "text/plain"));

        try (var test = TestServer.start(app)) {
          var commaSeparated =
              test.send(
                  request -> request.path("/file").header("Range", "bytes=0-1,4-5"),
                  HttpResponse.BodyHandlers.ofString());
          var unsupportedUnit =
              test.send(
                  request -> request.path("/file").header("Range", "items=0-1"),
                  HttpResponse.BodyHandlers.ofString());

          assertEquals(200, commaSeparated.statusCode());
          assertEquals("0123456789", commaSeparated.body());
          assertEquals(200, unsupportedUnit.statusCode());
          assertEquals("0123456789", unsupportedUnit.body());
        }
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void rejectsIfMatchBeforeEvaluatingRange() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "conditional-response-test", ".txt");
    Files.writeString(file, "0123456789", StandardCharsets.US_ASCII);

    try {
      var app = new Shoostr();

      try (Closeable appCleanup6 = app::close) {
        app.routes()
            .get(
                "/file",
                (ignoredRequest, response) ->
                    response
                        .setHeader(HttpHeaders.ETAG.value(), "\"revision-1\"")
                        .file(file, "text/plain"));

        try (var test = TestServer.start(app)) {
          var result = sendResultForRejectsIfMatchBeforeEvaluatingRange(test);

          assertEquals(412, result.statusCode());
          assertEquals("", result.body());
        }
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  private HttpResponse<String> sendResultForRejectsIfMatchBeforeEvaluatingRange(TestServer test)
      throws IOException, InterruptedException {
    return test.send(
        request ->
            request.path("/file").header("If-Match", "\"different\"").header("Range", "bytes=2-5"),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void sendsFullFileMetadataWithoutContentForHead() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "range-response-test", ".txt");
    Files.writeString(file, "0123456789", StandardCharsets.US_ASCII);

    try {
      var app = new Shoostr();

      try (Closeable appCleanup7 = app::close) {
        app.routes().head("/file", (ignoredRequest, response) -> response.file(file, "text/plain"));

        try (var test = TestServer.start(app)) {
          var result =
              test.send(
                  request -> request.path("/file").header("Range", "bytes=2-5").method("HEAD"),
                  HttpResponse.BodyHandlers.ofString());

          assertEquals(200, result.statusCode());
          assertEquals("10", result.headers().firstValue("Content-Length").orElseThrow());
          assertEquals("bytes", result.headers().firstValue("Accept-Ranges").orElseThrow());
          assertEquals("", result.body());
        }
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void returnsNotModifiedForAnUnchangedIfModifiedSinceDate() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "conditional-response-test", ".txt");
    Files.writeString(file, "cached", StandardCharsets.US_ASCII);

    try {
      var app = new Shoostr();

      try (Closeable appCleanup8 = app::close) {
        app.routes().get("/file", (ignoredRequest, response) -> response.file(file, "text/plain"));

        try (var test = TestServer.start(app)) {
          var first =
              test.send(request -> request.path("/file"), HttpResponse.BodyHandlers.ofString());
          var lastModified = first.headers().firstValue("Last-Modified").orElseThrow();
          var second =
              test.send(
                  request -> request.path("/file").header("If-Modified-Since", lastModified),
                  HttpResponse.BodyHandlers.ofString());

          assertEquals(304, second.statusCode());
          assertEquals("", second.body());
        }
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void servesTheCurrentFileWhenModifiedAfterTheIfModifiedSinceDate() throws Exception {
    var file = temporaryDirectory.resolve("modified.txt");
    Files.writeString(file, "current", StandardCharsets.US_ASCII);
    Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2024-01-02T00:00:00Z")));

    {
      var app = new Shoostr();

      try (Closeable appCleanup9 = app::close) {
        app.routes().get("/file", (ignoredRequest, response) -> response.file(file, "text/plain"));

        try (var test = TestServer.start(app)) {
          var result =
              sendResultForServesTheCurrentFileWhenModifiedAfterTheIfModifiedSinceDate(test);

          assertEquals(200, result.statusCode());
          assertEquals("current", result.body());
        }
      }
    }
  }

  private HttpResponse<String>
      sendResultForServesTheCurrentFileWhenModifiedAfterTheIfModifiedSinceDate(TestServer test)
          throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/file")
                .header(HttpHeaders.IF_MODIFIED_SINCE.value(), "Mon, 01 Jan 2024 00:00:00 GMT"),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void rejectsAnOlderIfUnmodifiedSinceDate() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "conditional-response-test", ".txt");
    Files.writeString(file, "current", StandardCharsets.US_ASCII);

    try {
      var app = new Shoostr();

      try (Closeable appCleanup10 = app::close) {
        app.routes().get("/file", (ignoredRequest, response) -> response.file(file, "text/plain"));

        try (var test = TestServer.start(app)) {
          var result = sendResultForRejectsAnOlderIfUnmodifiedSinceDate(test);

          assertEquals(412, result.statusCode());
          assertEquals("", result.body());
        }
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  private HttpResponse<String> sendResultForRejectsAnOlderIfUnmodifiedSinceDate(TestServer test)
      throws IOException, InterruptedException {
    return test.send(
        request ->
            request.path("/file").header("If-Unmodified-Since", "Thu, 01 Jan 1970 00:00:00 GMT"),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void usesRangeOnlyWhenIfRangeMatchesStrongValidator() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "conditional-response-test", ".txt");
    Files.writeString(file, "0123456789", StandardCharsets.US_ASCII);

    try {
      var app = new Shoostr();

      try (Closeable appCleanup11 = app::close) {
        app.routes()
            .get(
                "/file",
                (ignoredRequest, response) ->
                    response
                        .setHeader(HttpHeaders.ETAG.value(), "\"revision-1\"")
                        .file(file, "text/plain"));

        try (var test = TestServer.start(app)) {
          var matched = sendMatchedRequestForUsesRangeOnlyWhenIfRangeMatchesStrongValidator(test);
          var mismatched =
              sendMismatchedRequestForUsesRangeOnlyWhenIfRangeMatchesStrongValidator(test);

          assertEquals(206, matched.statusCode());
          assertEquals("2345", matched.body());
          assertEquals(200, mismatched.statusCode());
          assertEquals("0123456789", mismatched.body());
        }
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  private HttpResponse<String> sendMatchedRequestForUsesRangeOnlyWhenIfRangeMatchesStrongValidator(
      TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request.path("/file").header("Range", "bytes=2-5").header("If-Range", "\"revision-1\""),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String>
      sendMismatchedRequestForUsesRangeOnlyWhenIfRangeMatchesStrongValidator(TestServer test)
          throws IOException, InterruptedException {
    return test.send(
        request ->
            request.path("/file").header("Range", "bytes=2-5").header("If-Range", "\"revision-2\""),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void matchesAnEntityTagContainingCommaWithinAnIfMatchList() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "conditional-response-test", ".txt");
    Files.writeString(file, "current", StandardCharsets.US_ASCII);

    try {
      var app = new Shoostr();

      try (Closeable appCleanup12 = app::close) {
        app.routes()
            .get(
                "/file",
                (ignoredRequest, response) ->
                    response
                        .setHeader(HttpHeaders.ETAG.value(), "\"revision,1\"")
                        .file(file, "text/plain"));

        try (var test = TestServer.start(app)) {
          var result =
              test.send(
                  request -> request.path("/file").header("If-Match", "\"other\", \"revision,1\""),
                  HttpResponse.BodyHandlers.ofString());

          assertEquals(200, result.statusCode());
          assertEquals("current", result.body());
        }
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void rejectsMatchingIfNoneMatchForAnUnsafeMethod() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "conditional-response-test", ".txt");
    Files.writeString(file, "current", StandardCharsets.US_ASCII);

    try {
      var app = new Shoostr();

      try (Closeable appCleanup13 = app::close) {
        app.routes()
            .post(
                "/file",
                (ignoredRequest, response) ->
                    response
                        .setHeader(HttpHeaders.ETAG.value(), "\"revision-1\"")
                        .file(file, "text/plain"));

        try (var test = TestServer.start(app)) {
          var result = sendResultForRejectsMatchingIfNoneMatchForAnUnsafeMethod(test);

          assertEquals(412, result.statusCode());
          assertEquals("", result.body());
        }
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  private HttpResponse<String> sendResultForRejectsMatchingIfNoneMatchForAnUnsafeMethod(
      TestServer test) throws IOException, InterruptedException {
    return test.send(
        request -> request.path("/file").header("If-None-Match", "\"revision-1\"").method("POST"),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void rejectsFileOutputWhenFiniteOutputIsAlreadyStaged() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "range-response-test", ".txt");
    Files.writeString(file, "file", StandardCharsets.US_ASCII);

    try {
      var app = new Shoostr();

      try (Closeable appCleanup14 = app::close) {
        app.routes()
            .get(
                "/file",
                (ignoredRequest, response) -> {
                  response.text("staged");

                  assertThrows(
                      IllegalStateException.class, () -> response.file(file, "text/plain"));
                });

        try (var test = TestServer.start(app)) {
          var result =
              test.send(request -> request.path("/file"), HttpResponse.BodyHandlers.ofString());

          assertEquals(200, result.statusCode());
          assertEquals("staged", result.body());
        }
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void ignoresRangeWhenIfRangeUsesWeakFilesystemDate() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "conditional-response-test", ".txt");
    Files.writeString(file, "0123456789", StandardCharsets.US_ASCII);

    try {
      var app = new Shoostr();

      try (Closeable appCleanup15 = app::close) {
        app.routes().get("/file", (ignoredRequest, response) -> response.file(file, "text/plain"));

        try (var test = TestServer.start(app)) {
          var first =
              test.send(request -> request.path("/file"), HttpResponse.BodyHandlers.ofString());
          var lastModified = first.headers().firstValue("Last-Modified").orElseThrow();
          var range =
              sendRangeRequestForIgnoresRangeWhenIfRangeUsesWeakFilesystemDate(test, lastModified);

          assertEquals(200, range.statusCode());
          assertEquals("0123456789", range.body());
        }
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  private HttpResponse<String> sendRangeRequestForIgnoresRangeWhenIfRangeUsesWeakFilesystemDate(
      TestServer test, String lastModified) throws IOException, InterruptedException {
    return test.send(
        request ->
            request.path("/file").header("Range", "bytes=2-5").header("If-Range", lastModified),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void keepsAnExplicitErrorStatusWhenServingFile() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "range-response-test", ".txt");
    Files.writeString(file, "missing", StandardCharsets.US_ASCII);

    try {
      var app = new Shoostr();

      try (Closeable appCleanup16 = app::close) {
        app.routes()
            .get(
                "/file",
                (ignoredRequest, response) -> response.status(404).file(file, "text/plain"));

        try (var test = TestServer.start(app)) {
          var result =
              test.send(
                  request -> request.path("/file").header("Range", "bytes=0-1"),
                  HttpResponse.BodyHandlers.ofString());

          assertEquals(404, result.statusCode());
          assertEquals("missing", result.body());
        }
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void rejectsOutputModesAfterSelectingFile() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "range-response-test", ".txt");
    Files.writeString(file, "file", StandardCharsets.US_ASCII);

    try {
      var app = new Shoostr();

      try (Closeable appCleanup17 = app::close) {
        app.routes()
            .get(
                "/file",
                (ignoredRequest, response) -> {
                  response.file(file, "text/plain");

                  assertThrows(IllegalStateException.class, () -> response.text("replacement"));
                  assertThrows(
                      IllegalStateException.class, () -> response.startStream("text/plain"));
                });

        try (var test = TestServer.start(app)) {
          var result =
              test.send(request -> request.path("/file"), HttpResponse.BodyHandlers.ofString());

          assertEquals(200, result.statusCode());
          assertEquals("file", result.body());
        }
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void matchesIfMatchWildcardAgainstAnExistingGeneratedValidator() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "conditional-response-test", ".txt");
    Files.writeString(file, "current", StandardCharsets.US_ASCII);

    try {
      var app = new Shoostr();

      try (Closeable appCleanup18 = app::close) {
        app.routes().get("/file", (ignoredRequest, response) -> response.file(file, "text/plain"));

        try (var test = TestServer.start(app)) {
          var result =
              test.send(
                  request -> request.path("/file").header("If-Match", "*"),
                  HttpResponse.BodyHandlers.ofString());

          assertEquals(200, result.statusCode());
          assertEquals("current", result.body());
        }
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void ignoresMalformedAndOverflowingByteRanges() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "range-response-test", ".txt");
    Files.writeString(file, "0123456789", StandardCharsets.US_ASCII);

    try {
      var app = new Shoostr();

      try (Closeable appCleanup19 = app::close) {
        app.routes().get("/file", (ignoredRequest, response) -> response.file(file, "text/plain"));

        try (var test = TestServer.start(app)) {
          for (var range :
              List.of("bytes=99-invalid", "bytes=99-1", "bytes=0-999999999999999999999")) {
            var result =
                test.send(
                    request -> request.path("/file").header("Range", range),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, result.statusCode());
            assertEquals("0123456789", result.body());
          }
        }
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void evaluatesPreconditionsForAnExplicitSuccessfulStatus() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "conditional-response-test", ".txt");
    Files.writeString(file, "created", StandardCharsets.US_ASCII);

    try {
      var app = new Shoostr();

      try (Closeable appCleanup20 = app::close) {
        app.routes()
            .post(
                "/file",
                (ignoredRequest, response) -> response.status(201).file(file, "text/plain"));

        try (var test = TestServer.start(app)) {
          var result =
              test.send(
                  request -> request.path("/file").header("If-None-Match", "*").method("POST"),
                  HttpResponse.BodyHandlers.ofString());

          assertEquals(412, result.statusCode());
          assertEquals("", result.body());
        }
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void rejectsOutputModesAfterAnUnsatisfiableFileRange() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "range-response-test", ".txt");
    Files.writeString(file, "file", StandardCharsets.US_ASCII);

    try {
      var app = new Shoostr();

      try (Closeable appCleanup21 = app::close) {
        app.routes()
            .get(
                "/file",
                (ignoredRequest, response) -> {
                  response.file(file, "text/plain");

                  assertThrows(IllegalStateException.class, () -> response.text("replacement"));
                  assertThrows(
                      IllegalStateException.class, () -> response.startStream("text/plain"));
                });

        try (var test = TestServer.start(app)) {
          var result =
              test.send(
                  request -> request.path("/file").header("Range", "bytes=99-"),
                  HttpResponse.BodyHandlers.ofString());

          assertEquals(416, result.statusCode());
          assertEquals("", result.body());
        }
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void rejectsInvalidApplicationEntityTags() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "conditional-response-test", ".txt");
    Files.writeString(file, "current", StandardCharsets.US_ASCII);

    try {
      var app = new Shoostr();

      try (Closeable appCleanup22 = app::close) {
        app.routes()
            .get(
                "/file",
                (ignoredRequest, response) ->
                    response
                        .setHeader(HttpHeaders.ETAG.value(), "\"revision 1\"")
                        .file(file, "text/plain"));

        try (var test = TestServer.start(app)) {
          var result =
              test.send(request -> request.path("/file"), HttpResponse.BodyHandlers.ofString());

          assertEquals(500, result.statusCode());
        }
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void appliesTheDocumentedZeroSuffixClampAndRepeatedRangePolicies() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "range-response-test", ".txt");
    Files.writeString(file, "0123456789", StandardCharsets.US_ASCII);

    try {
      var app = new Shoostr();

      try (Closeable appCleanup23 = app::close) {
        app.routes().get("/file", (ignoredRequest, response) -> response.file(file, "text/plain"));

        try (var test = TestServer.start(app)) {
          var zeroSuffix =
              test.send(
                  request -> request.path("/file").header("Range", "bytes=-0"),
                  HttpResponse.BodyHandlers.ofString());
          var clamped =
              test.send(
                  request -> request.path("/file").header("Range", "bytes=7-99"),
                  HttpResponse.BodyHandlers.ofString());
          final var repeated =
              sendRepeatedRequestForAppliesTheDocumentedZeroSuffixClampAndRepeatedRangePolicies(
                  test);

          assertEquals(416, zeroSuffix.statusCode());
          assertEquals(206, clamped.statusCode());
          assertEquals("789", clamped.body());
          assertEquals(200, repeated.statusCode());
          assertEquals("0123456789", repeated.body());
        }
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  private HttpResponse<String>
      sendRepeatedRequestForAppliesTheDocumentedZeroSuffixClampAndRepeatedRangePolicies(
          TestServer test) throws IOException, InterruptedException {
    return test.send(
        request -> request.path("/file").header("Range", "bytes=0-1").header("Range", "bytes=4-5"),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void givesEntityTagConditionsPrecedenceOverDateConditions() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "conditional-response-test", ".txt");
    Files.writeString(file, "current", StandardCharsets.US_ASCII);

    try {
      var app = new Shoostr();

      try (Closeable appCleanup24 = app::close) {
        app.routes()
            .get(
                "/file",
                (ignoredRequest, response) ->
                    response
                        .setHeader(HttpHeaders.ETAG.value(), "\"revision-1\"")
                        .file(file, "text/plain"));

        try (var test = TestServer.start(app)) {
          var result = sendResultForGivesEntityTagConditionsPrecedenceOverDateConditions(test);

          assertEquals(200, result.statusCode());
          assertEquals("current", result.body());
        }
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  private HttpResponse<String> sendResultForGivesEntityTagConditionsPrecedenceOverDateConditions(
      TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/file")
                .header("If-None-Match", "\"different\"")
                .header("If-Modified-Since", "Sun, 06 Nov 2094 08:49:37 GMT"),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void ignoresRepeatedConditionalDatesAndClampsFutureModificationTimes() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "conditional-response-test", ".txt");
    Files.writeString(file, "current", StandardCharsets.US_ASCII);
    Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(86_400)));

    try {
      var app = new Shoostr();

      try (Closeable appCleanup25 = app::close) {
        app.routes().get("/file", (ignoredRequest, response) -> response.file(file, "text/plain"));

        try (var test = TestServer.start(app)) {
          var result =
              sendResultForIgnoresRepeatedConditionalDatesAndClampsFutureModificationTimes(test);
          var lastModified =
              ZonedDateTime.parse(
                  result.headers().firstValue("Last-Modified").orElseThrow(),
                  DateTimeFormatter.RFC_1123_DATE_TIME);
          final var date =
              ZonedDateTime.parse(
                  result.headers().firstValue("Date").orElseThrow(),
                  DateTimeFormatter.RFC_1123_DATE_TIME);

          assertEquals(200, result.statusCode());
          assertEquals("current", result.body());
          assertFalse(lastModified.toInstant().isAfter(Instant.now()));
          assertFalse(lastModified.toInstant().isAfter(date.toInstant()));
        }
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  private HttpResponse<String>
      sendResultForIgnoresRepeatedConditionalDatesAndClampsFutureModificationTimes(TestServer test)
          throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/file")
                .header("If-Modified-Since", "Sun, 06 Nov 2094 08:49:37 GMT")
                .header("If-Modified-Since", "Sun, 06 Nov 2094 08:49:37 GMT"),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void rejectsRangesForAnEmptyFileAndReleasesItAfterTheResponse() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "range-response-test", ".txt");

    try {
      var app = new Shoostr();

      try (Closeable appCleanup26 = app::close) {
        app.routes().get("/file", (ignoredRequest, response) -> response.file(file, "text/plain"));

        try (var test = TestServer.start(app)) {
          var result =
              test.send(
                  request -> request.path("/file").header("Range", "bytes=0-"),
                  HttpResponse.BodyHandlers.ofString());

          assertEquals(416, result.statusCode());
          assertEquals("bytes */0", result.headers().firstValue("Content-Range").orElseThrow());
          assertEquals("", result.body());
        }
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void servesSimultaneousIndependentRangesFromTheSameFile() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "range-response-test", ".txt");
    Files.writeString(file, "0123456789", StandardCharsets.US_ASCII);
    var staged = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    var waitTimedOut = new AtomicBoolean();

    try {
      var app = new Shoostr();

      try (Closeable appCleanup27 = app::close;
          var testRequests = Executors.newVirtualThreadPerTaskExecutor()) {
        app.routes()
            .get(
                "/file",
                (ignoredRequest, response) -> {
                  response.file(file, "text/plain");
                  staged.countDown();
                  if (!release.await(5, TimeUnit.SECONDS)) {
                    waitTimedOut.set(true);
                  }
                });

        try (var test = TestServer.start(app)) {
          var first =
              submitFirstForServesSimultaneousIndependentRangesFromTheSameFile(testRequests, test);
          var second =
              submitSecondForServesSimultaneousIndependentRangesFromTheSameFile(testRequests, test);

          assertTrue(staged.await(5, TimeUnit.SECONDS));

          release.countDown();
          var firstResult = first.get(5, TimeUnit.SECONDS);
          var secondResult = second.get(5, TimeUnit.SECONDS);

          assertFalse(waitTimedOut.get());
          assertEquals(206, firstResult.statusCode());
          assertEquals(206, secondResult.statusCode());
          assertEquals("012", firstResult.body());
          assertEquals("789", secondResult.body());
        }
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  private Future<HttpResponse<String>>
      submitFirstForServesSimultaneousIndependentRangesFromTheSameFile(
          ExecutorService testRequests, TestServer test) {
    return testRequests.submit(
        () ->
            test.send(
                request -> request.path("/file").header("Range", "bytes=0-2"),
                HttpResponse.BodyHandlers.ofString()));
  }

  private Future<HttpResponse<String>>
      submitSecondForServesSimultaneousIndependentRangesFromTheSameFile(
          ExecutorService testRequests, TestServer test) {
    return testRequests.submit(
        () ->
            test.send(
                request -> request.path("/file").header("Range", "bytes=7-9"),
                HttpResponse.BodyHandlers.ofString()));
  }

  @Test
  void continuesServingFilesAfterClientDisconnectsDuringTransfer() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "range-response-test", ".txt");
    Files.write(file, new byte[8 * 1024 * 1024]);
    var completed = new CountDownLatch(1);
    var outcome = new AtomicReference<RequestOutcome>();

    try {
      var app = new Shoostr();

      try (Closeable appCleanup28 = app::close) {
        app.afterRequest(
            value -> {
              outcome.set(value);
              completed.countDown();
            });
        app.routes()
            .get(
                "/file",
                (ignoredRequest, response) -> response.file(file, "application/octet-stream"));

        try (var test = TestServer.start(app)) {

          try (var socket = new Socket(InetAddress.getAllByName("127.0.0.1")[0], app.port())) {
            socket
                .getOutputStream()
                .write(
                    "GET /file HTTP/1.1\r\nHost: localhost\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            var initial = socket.getInputStream().readNBytes(1024);
            var response = new String(initial, StandardCharsets.ISO_8859_1);
            var headerEnd = response.indexOf("\r\n\r\n");
            var contentStart = headerEnd + 4;

            assertTrue(response.startsWith("HTTP/1.1 200"));
            assertTrue(headerEnd >= 0);
            assertTrue(initial.length > contentStart);

            socket.setSoLinger(true, 0);
          }

          assertTrue(completed.await(5, TimeUnit.SECONDS));
          assertEquals(200, outcome.get().statusCode());
          assertNotNull(outcome.get().transportFailure());

          var result =
              test.send(
                  request -> request.path("/file").header("Range", "bytes=0-2"),
                  HttpResponse.BodyHandlers.ofString());

          assertEquals(206, result.statusCode());
          assertEquals("\u0000\u0000\u0000", result.body());
        }
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void releasesCompletedFileBeforeTheApplicationStops() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "range-response-test", ".txt");
    Files.writeString(file, "complete", StandardCharsets.US_ASCII);
    var completed = new CountDownLatch(1);

    {
      var app = new Shoostr();

      try (Closeable appCleanup29 = app::close) {
        app.afterRequest(ignored -> completed.countDown());
        app.routes().get("/file", (ignoredRequest, response) -> response.file(file, "text/plain"));

        try (var test = TestServer.start(app)) {
          var result =
              test.send(
                  request -> request.path("/file").header("Range", "bytes=0-2"),
                  HttpResponse.BodyHandlers.ofString());

          assertEquals(206, result.statusCode());
          assertTrue(completed.await(5, TimeUnit.SECONDS));

          Files.delete(file);

          assertFalse(Files.exists(file));
        }
      }
    }
  }

  @Test
  void reportsTerminalFailureWhenStagedFileDisappearsBeforeTransfer() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "range-response-test", ".txt");
    Files.writeString(file, "missing", StandardCharsets.US_ASCII);
    var completed = new CountDownLatch(1);
    var outcome = new AtomicReference<RequestOutcome>();

    {
      var app = new Shoostr();

      try (Closeable appCleanup30 = app::close) {
        app.afterRequest(
            value -> {
              outcome.set(value);
              completed.countDown();
            });
        app.routes()
            .get(
                "/file",
                (ignoredRequest, response) -> {
                  response.file(file, "text/plain");
                  Files.delete(file);
                });

        try (var test = TestServer.start(app)) {

          try {
            test.send(
                request -> request.path("/file").header("Range", "bytes=0-2"),
                HttpResponse.BodyHandlers.ofString());
          } catch (IOException ignoredFailure) {
            // The transport can fail before a complete response reaches the client.
          }

          assertTrue(completed.await(5, TimeUnit.SECONDS));
          assertNotNull(outcome.get().transportFailure());
        }
      }
    }
  }

  @Test
  void rejectsArchiveFilesystemPathsWithoutTakingOwnershipOfTheirFilesystem() throws Exception {
    var archive = temporaryDirectory.resolve("content.zip");

    try (var filesystem =
        FileSystems.newFileSystem(URI.create("jar:" + archive.toUri()), Map.of("create", "true"))) {
      var app = new Shoostr();

      try (Closeable appCleanup31 = app::close) {
        var file = filesystem.getPath("/file.txt");
        Files.writeString(file, "archive", StandardCharsets.US_ASCII);
        app.routes().get("/file", (ignoredRequest, response) -> response.file(file, "text/plain"));

        try (var test = TestServer.start(app)) {
          var result =
              test.send(
                  request -> request.path("/file").header("Range", "bytes=0-2"),
                  HttpResponse.BodyHandlers.ofString());

          assertEquals(500, result.statusCode());
        }
      }
    }
  }
}
