package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.HttpHeaders;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RangeConditionalResponseTest {
  @TempDir Path temporaryDirectory;

  @Test
  void servesOneInclusiveByteRangeFromAFile() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "range-response-test", ".txt");
    Files.writeString(file, "0123456789", StandardCharsets.US_ASCII);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().get("/file", (request, response) -> response.file(file, "text/plain"));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/file"))
                  .header("Range", "bytes=2-5")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(206, result.statusCode());
      assertEquals("2345", result.body());
      assertEquals("bytes 2-5/10", result.headers().firstValue("Content-Range").orElseThrow());
      assertEquals("4", result.headers().firstValue("Content-Length").orElseThrow());
      assertEquals("bytes", result.headers().firstValue("Accept-Ranges").orElseThrow());
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void rejectsAnUnsatisfiableByteRange() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "range-response-test", ".txt");
    Files.writeString(file, "0123456789", StandardCharsets.US_ASCII);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().get("/file", (request, response) -> response.file(file, "text/plain"));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/file"))
                  .header("Range", "bytes=10-")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(416, result.statusCode());
      assertEquals("bytes */10", result.headers().firstValue("Content-Range").orElseThrow());
      assertEquals("", result.body());
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void returnsNotModifiedForAMatchingIfNoneMatchValidator() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "conditional-response-test", ".txt");
    Files.writeString(file, "cached", StandardCharsets.US_ASCII);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().get("/file", (request, response) -> response.file(file, "text/plain"));
      app.start();
      var first =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/file"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      var validator = first.headers().firstValue("ETag").orElseThrow();
      var second =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/file"))
                  .header("If-None-Match", validator)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(304, second.statusCode());
      assertEquals("", second.body());
      assertEquals(validator, second.headers().firstValue("ETag").orElseThrow());
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void servesASuffixByteRangeFromAFile() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "range-response-test", ".txt");
    Files.writeString(file, "0123456789", StandardCharsets.US_ASCII);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().get("/file", (request, response) -> response.file(file, "text/plain"));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/file"))
                  .header("Range", "bytes=-3")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(206, result.statusCode());
      assertEquals("789", result.body());
      assertEquals("bytes 7-9/10", result.headers().firstValue("Content-Range").orElseThrow());
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void servesAnOpenEndedByteRangeFromAFile() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "range-response-test", ".txt");
    Files.writeString(file, "0123456789", StandardCharsets.US_ASCII);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().get("/file", (request, response) -> response.file(file, "text/plain"));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/file"))
                  .header("Range", "bytes=7-")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(206, result.statusCode());
      assertEquals("789", result.body());
      assertEquals("bytes 7-9/10", result.headers().firstValue("Content-Range").orElseThrow());
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void ignoresUnsupportedAndMultipleByteRanges() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "range-response-test", ".txt");
    Files.writeString(file, "0123456789", StandardCharsets.US_ASCII);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().get("/file", (request, response) -> response.file(file, "text/plain"));
      app.start();
      var commaSeparated =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/file"))
                  .header("Range", "bytes=0-1,4-5")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      var unsupportedUnit =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/file"))
                  .header("Range", "items=0-1")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, commaSeparated.statusCode());
      assertEquals("0123456789", commaSeparated.body());
      assertEquals(200, unsupportedUnit.statusCode());
      assertEquals("0123456789", unsupportedUnit.body());
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void rejectsIfMatchBeforeEvaluatingARange() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "conditional-response-test", ".txt");
    Files.writeString(file, "0123456789", StandardCharsets.US_ASCII);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/file",
              (request, response) ->
                  response
                      .header(HttpHeaders.ETAG.value(), "\"revision-1\"")
                      .file(file, "text/plain"));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/file"))
                  .header("If-Match", "\"different\"")
                  .header("Range", "bytes=2-5")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(412, result.statusCode());
      assertEquals("", result.body());
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void sendsFullFileMetadataWithoutContentForHead() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "range-response-test", ".txt");
    Files.writeString(file, "0123456789", StandardCharsets.US_ASCII);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().head("/file", (request, response) -> response.file(file, "text/plain"));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/file"))
                  .header("Range", "bytes=2-5")
                  .method("HEAD", HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("10", result.headers().firstValue("Content-Length").orElseThrow());
      assertEquals("bytes", result.headers().firstValue("Accept-Ranges").orElseThrow());
      assertEquals("", result.body());
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void returnsNotModifiedForAnUnchangedIfModifiedSinceDate() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "conditional-response-test", ".txt");
    Files.writeString(file, "cached", StandardCharsets.US_ASCII);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().get("/file", (request, response) -> response.file(file, "text/plain"));
      app.start();
      var first =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/file"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      var lastModified = first.headers().firstValue("Last-Modified").orElseThrow();
      var second =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/file"))
                  .header("If-Modified-Since", lastModified)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(304, second.statusCode());
      assertEquals("", second.body());
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void rejectsAnOlderIfUnmodifiedSinceDate() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "conditional-response-test", ".txt");
    Files.writeString(file, "current", StandardCharsets.US_ASCII);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().get("/file", (request, response) -> response.file(file, "text/plain"));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/file"))
                  .header("If-Unmodified-Since", "Thu, 01 Jan 1970 00:00:00 GMT")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(412, result.statusCode());
      assertEquals("", result.body());
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void usesRangeOnlyWhenIfRangeMatchesAStrongValidator() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "conditional-response-test", ".txt");
    Files.writeString(file, "0123456789", StandardCharsets.US_ASCII);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/file",
              (request, response) ->
                  response
                      .header(HttpHeaders.ETAG.value(), "\"revision-1\"")
                      .file(file, "text/plain"));
      app.start();
      var matched =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/file"))
                  .header("Range", "bytes=2-5")
                  .header("If-Range", "\"revision-1\"")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      var mismatched =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/file"))
                  .header("Range", "bytes=2-5")
                  .header("If-Range", "\"revision-2\"")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(206, matched.statusCode());
      assertEquals("2345", matched.body());
      assertEquals(200, mismatched.statusCode());
      assertEquals("0123456789", mismatched.body());
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void matchesAnEntityTagContainingACommaWithinAnIfMatchList() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "conditional-response-test", ".txt");
    Files.writeString(file, "current", StandardCharsets.US_ASCII);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/file",
              (request, response) ->
                  response
                      .header(HttpHeaders.ETAG.value(), "\"revision,1\"")
                      .file(file, "text/plain"));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/file"))
                  .header("If-Match", "\"other\", \"revision,1\"")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("current", result.body());
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void rejectsMatchingIfNoneMatchForAnUnsafeMethod() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "conditional-response-test", ".txt");
    Files.writeString(file, "current", StandardCharsets.US_ASCII);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/file",
              (request, response) ->
                  response
                      .header(HttpHeaders.ETAG.value(), "\"revision-1\"")
                      .file(file, "text/plain"));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/file"))
                  .header("If-None-Match", "\"revision-1\"")
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(412, result.statusCode());
      assertEquals("", result.body());
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void rejectsFileOutputWhenFiniteOutputIsAlreadyStaged() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "range-response-test", ".txt");
    Files.writeString(file, "file", StandardCharsets.US_ASCII);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/file",
              (request, response) -> {
                response.text("staged");
                assertThrows(IllegalStateException.class, () -> response.file(file, "text/plain"));
              });
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/file"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("staged", result.body());
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void ignoresRangeWhenIfRangeUsesAWeakFilesystemDate() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "conditional-response-test", ".txt");
    Files.writeString(file, "0123456789", StandardCharsets.US_ASCII);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().get("/file", (request, response) -> response.file(file, "text/plain"));
      app.start();
      var first =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/file"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      var lastModified = first.headers().firstValue("Last-Modified").orElseThrow();
      var range =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/file"))
                  .header("Range", "bytes=2-5")
                  .header("If-Range", lastModified)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, range.statusCode());
      assertEquals("0123456789", range.body());
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void keepsAnExplicitErrorStatusWhenServingAFile() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "range-response-test", ".txt");
    Files.writeString(file, "missing", StandardCharsets.US_ASCII);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get("/file", (request, response) -> response.status(404).file(file, "text/plain"));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/file"))
                  .header("Range", "bytes=0-1")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(404, result.statusCode());
      assertEquals("missing", result.body());
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void rejectsOutputModesAfterSelectingAFile() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "range-response-test", ".txt");
    Files.writeString(file, "file", StandardCharsets.US_ASCII);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/file",
              (request, response) -> {
                response.file(file, "text/plain");
                assertThrows(IllegalStateException.class, () -> response.text("replacement"));
                assertThrows(IllegalStateException.class, () -> response.startStream("text/plain"));
              });
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/file"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("file", result.body());
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void matchesIfMatchWildcardAgainstAnExistingGeneratedValidator() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "conditional-response-test", ".txt");
    Files.writeString(file, "current", StandardCharsets.US_ASCII);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().get("/file", (request, response) -> response.file(file, "text/plain"));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/file"))
                  .header("If-Match", "*")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("current", result.body());
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void ignoresMalformedAndOverflowingByteRanges() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "range-response-test", ".txt");
    Files.writeString(file, "0123456789", StandardCharsets.US_ASCII);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().get("/file", (request, response) -> response.file(file, "text/plain"));
      app.start();
      for (var range : List.of("bytes=99-invalid", "bytes=99-1", "bytes=0-999999999999999999999")) {
        var result =
            client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/file"))
                    .header("Range", range)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("0123456789", result.body());
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void evaluatesPreconditionsForAnExplicitSuccessfulStatus() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "conditional-response-test", ".txt");
    Files.writeString(file, "created", StandardCharsets.US_ASCII);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post("/file", (request, response) -> response.status(201).file(file, "text/plain"));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/file"))
                  .header("If-None-Match", "*")
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(412, result.statusCode());
      assertEquals("", result.body());
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void rejectsOutputModesAfterAnUnsatisfiableFileRange() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "range-response-test", ".txt");
    Files.writeString(file, "file", StandardCharsets.US_ASCII);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/file",
              (request, response) -> {
                response.file(file, "text/plain");
                assertThrows(IllegalStateException.class, () -> response.text("replacement"));
                assertThrows(IllegalStateException.class, () -> response.startStream("text/plain"));
              });
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/file"))
                  .header("Range", "bytes=99-")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(416, result.statusCode());
      assertEquals("", result.body());
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void rejectsInvalidApplicationEntityTags() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "conditional-response-test", ".txt");
    Files.writeString(file, "current", StandardCharsets.US_ASCII);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/file",
              (request, response) ->
                  response
                      .header(HttpHeaders.ETAG.value(), "\"revision 1\"")
                      .file(file, "text/plain"));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/file"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(500, result.statusCode());
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void appliesTheDocumentedZeroSuffixClampAndRepeatedRangePolicies() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "range-response-test", ".txt");
    Files.writeString(file, "0123456789", StandardCharsets.US_ASCII);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().get("/file", (request, response) -> response.file(file, "text/plain"));
      app.start();
      var zeroSuffix = client.send(request(app, "bytes=-0"), HttpResponse.BodyHandlers.ofString());
      var clamped = client.send(request(app, "bytes=7-99"), HttpResponse.BodyHandlers.ofString());
      var repeated =
          client.send(
              HttpRequest.newBuilder(uri(app))
                  .header("Range", "bytes=0-1")
                  .header("Range", "bytes=4-5")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(416, zeroSuffix.statusCode());
      assertEquals(206, clamped.statusCode());
      assertEquals("789", clamped.body());
      assertEquals(200, repeated.statusCode());
      assertEquals("0123456789", repeated.body());
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void givesEntityTagConditionsPrecedenceOverDateConditions() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "conditional-response-test", ".txt");
    Files.writeString(file, "current", StandardCharsets.US_ASCII);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/file",
              (request, response) ->
                  response
                      .header(HttpHeaders.ETAG.value(), "\"revision-1\"")
                      .file(file, "text/plain"));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(uri(app))
                  .header("If-None-Match", "\"different\"")
                  .header("If-Modified-Since", "Sun, 06 Nov 2094 08:49:37 GMT")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("current", result.body());
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void ignoresRepeatedConditionalDatesAndClampsFutureModificationTimes() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "conditional-response-test", ".txt");
    Files.writeString(file, "current", StandardCharsets.US_ASCII);
    Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(86_400)));

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().get("/file", (request, response) -> response.file(file, "text/plain"));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(uri(app))
                  .header("If-Modified-Since", "Sun, 06 Nov 2094 08:49:37 GMT")
                  .header("If-Modified-Since", "Sun, 06 Nov 2094 08:49:37 GMT")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      var lastModified =
          ZonedDateTime.parse(
              result.headers().firstValue("Last-Modified").orElseThrow(),
              DateTimeFormatter.RFC_1123_DATE_TIME);
      var date =
          ZonedDateTime.parse(
              result.headers().firstValue("Date").orElseThrow(),
              DateTimeFormatter.RFC_1123_DATE_TIME);
      assertEquals(200, result.statusCode());
      assertEquals("current", result.body());
      assertFalse(lastModified.toInstant().isAfter(Instant.now()));
      assertFalse(lastModified.toInstant().isAfter(date.toInstant()));
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void rejectsRangesForAnEmptyFileAndReleasesItAfterTheResponse() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "range-response-test", ".txt");

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().get("/file", (request, response) -> response.file(file, "text/plain"));
      app.start();
      var result = client.send(request(app, "bytes=0-"), HttpResponse.BodyHandlers.ofString());
      assertEquals(416, result.statusCode());
      assertEquals("bytes */0", result.headers().firstValue("Content-Range").orElseThrow());
      assertEquals("", result.body());
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

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/file",
              (request, response) -> {
                response.file(file, "text/plain");
                staged.countDown();
                release.await(5, TimeUnit.SECONDS);
              });
      app.start();
      var first = client.sendAsync(request(app, "bytes=0-2"), HttpResponse.BodyHandlers.ofString());
      var second =
          client.sendAsync(request(app, "bytes=7-9"), HttpResponse.BodyHandlers.ofString());
      assertEquals(true, staged.await(5, TimeUnit.SECONDS));
      release.countDown();
      CompletableFuture.allOf(first, second).join();
      assertEquals(206, first.join().statusCode());
      assertEquals(206, second.join().statusCode());
      assertEquals("012", first.join().body());
      assertEquals("789", second.join().body());
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void continuesServingFilesAfterAClientDisconnectsDuringTransfer() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "range-response-test", ".txt");
    Files.write(file, new byte[8 * 1024 * 1024]);
    var completed = new CountDownLatch(1);
    var outcome = new AtomicReference<RequestOutcome>();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.afterRequest(
          value -> {
            outcome.set(value);
            completed.countDown();
          });
      app.routes()
          .get("/file", (request, response) -> response.file(file, "application/octet-stream"));
      app.start();

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
        assertEquals(true, response.startsWith("HTTP/1.1 200"));
        assertTrue(headerEnd >= 0);
        assertEquals(true, initial.length > contentStart);
        socket.setSoLinger(true, 0);
      }

      assertEquals(true, completed.await(5, TimeUnit.SECONDS));
      assertEquals(200, outcome.get().statusCode());
      assertNotNull(outcome.get().transportFailure());
      var result = client.send(request(app, "bytes=0-2"), HttpResponse.BodyHandlers.ofString());
      assertEquals(206, result.statusCode());
      assertEquals("\u0000\u0000\u0000", result.body());
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void releasesACompletedFileBeforeTheApplicationStops() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "range-response-test", ".txt");
    Files.writeString(file, "complete", StandardCharsets.US_ASCII);
    var completed = new CountDownLatch(1);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.afterRequest(ignored -> completed.countDown());
      app.routes().get("/file", (request, response) -> response.file(file, "text/plain"));
      app.start();
      var result = client.send(request(app, "bytes=0-2"), HttpResponse.BodyHandlers.ofString());
      assertEquals(206, result.statusCode());
      assertTrue(completed.await(5, TimeUnit.SECONDS));
      Files.delete(file);
      assertFalse(Files.exists(file));
    }
  }

  @Test
  void reportsTerminalFailureWhenAStagedFileDisappearsBeforeTransfer() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "range-response-test", ".txt");
    Files.writeString(file, "missing", StandardCharsets.US_ASCII);
    var completed = new CountDownLatch(1);
    var outcome = new AtomicReference<RequestOutcome>();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.afterRequest(
          value -> {
            outcome.set(value);
            completed.countDown();
          });
      app.routes()
          .get(
              "/file",
              (request, response) -> {
                response.file(file, "text/plain");
                Files.delete(file);
              });
      app.start();

      try {
        client.send(request(app, "bytes=0-2"), HttpResponse.BodyHandlers.ofString());
      } catch (IOException _) {
        // The transport can fail before a complete response reaches the client.
      }

      assertTrue(completed.await(5, TimeUnit.SECONDS));
      assertNotNull(outcome.get().transportFailure());
    }
  }

  @Test
  void rejectsArchiveFilesystemPathsWithoutTakingOwnershipOfTheirFilesystem() throws Exception {
    var archive = temporaryDirectory.resolve("content.zip");

    try (var filesystem =
            java.nio.file.FileSystems.newFileSystem(
                URI.create("jar:" + archive.toUri()), Map.of("create", "true"));
        var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      var file = filesystem.getPath("/file.txt");
      Files.writeString(file, "archive", StandardCharsets.US_ASCII);
      app.routes().get("/file", (request, response) -> response.file(file, "text/plain"));
      app.start();
      var result = client.send(request(app, "bytes=0-2"), HttpResponse.BodyHandlers.ofString());
      assertEquals(500, result.statusCode());
    }
  }

  private static HttpRequest request(Shoostr app, String range) {
    return HttpRequest.newBuilder(uri(app)).header("Range", range).GET().build();
  }

  private static URI uri(Shoostr app) {
    return URI.create("http://127.0.0.1:" + app.port() + "/file");
  }
}
