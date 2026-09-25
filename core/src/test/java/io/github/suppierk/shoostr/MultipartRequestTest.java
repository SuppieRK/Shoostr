package io.github.suppierk.shoostr;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
class MultipartRequestTest {
  private static final String BOUNDARY = "multipart-test-boundary";
  private static final byte[] BINARY_CONTENT = {0, 1, -1, 127, -128};
  @TempDir Path temporaryDirectory;

  @Test
  void readsMultipartTextAndFileFields() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/upload",
              (request, response) -> {
                var upload = Objects.requireNonNull(request.file("document"));
                response.text(
                    request.formParam("title")
                        + "|"
                        + upload.name()
                        + "|"
                        + upload.fileName()
                        + "|"
                        + new String(upload.content().readAllBytes(), StandardCharsets.UTF_8));
              });
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/upload"))
                  .timeout(Duration.ofSeconds(3))
                  .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                  .POST(HttpRequest.BodyPublishers.ofByteArray(body()))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("report|document|report.txt|contents", result.body());
    }
  }

  @Test
  void preservesBinaryUploadContent() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/upload",
              (request, response) -> {
                assertArrayEquals(
                    BINARY_CONTENT,
                    Objects.requireNonNull(request.file("document")).content().readAllBytes());
                response.text("binary");
              });
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/upload"))
                  .timeout(Duration.ofSeconds(3))
                  .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                  .POST(HttpRequest.BodyPublishers.ofByteArray(binaryBody()))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("binary", result.body());
    }
  }

  @Test
  void spillsOnlyUploadsAboveTheMemoryThresholdToTheConfiguredDirectory() throws Exception {
    var directory = Files.createTempDirectory(temporaryDirectory, "multipart-threshold-test");
    var multipart = new MultipartOptions(1000, 1000, 100, 8192, 8, directory);

    try (var app = new Shoostr(Options.defaults().withMultipart(multipart).withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/upload",
              (request, response) -> {
                request.file("document");

                try (var files = Files.list(directory)) {
                  response.text(Long.toString(files.count()));
                }
              });
      app.start();
      var inMemory =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/upload"))
                  .timeout(Duration.ofSeconds(3))
                  .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                  .POST(HttpRequest.BodyPublishers.ofByteArray(fileBody("12345678")))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, inMemory.statusCode());
      assertEquals("0", inMemory.body());
      awaitFile(directory, false);
      var onDisk =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/upload"))
                  .timeout(Duration.ofSeconds(3))
                  .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                  .POST(HttpRequest.BodyPublishers.ofByteArray(fileBody("123456789")))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, onDisk.statusCode());
      assertEquals("1", onDisk.body());
      awaitFile(directory, false);
    } finally {
      Files.deleteIfExists(directory);
    }
  }

  @Test
  void treatsHostileFilenamesAsMetadataInsteadOfDestinationPaths() throws Exception {
    var directory = Files.createTempDirectory(temporaryDirectory, "multipart-filename-test");
    var destination = directory.resolve("saved.bin");
    var multipart = new MultipartOptions(1000, 1000, 100, 8192, 0, directory);

    try (var app = new Shoostr(Options.defaults().withMultipart(multipart).withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/upload",
              (request, response) -> {
                var upload = Objects.requireNonNull(request.file("document"));
                upload.persistTo(destination);
                response.text(upload.fileName());
              });
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/upload"))
                  .timeout(Duration.ofSeconds(3))
                  .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                  .POST(
                      HttpRequest.BodyPublishers.ofByteArray(
                          fileBody("../../outside.txt", "contents")))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("../../outside.txt", result.body());
      assertEquals("contents", Files.readString(destination));

      try (var files = Files.list(directory)) {
        assertEquals(1, files.count());
      }
    } finally {
      Files.deleteIfExists(destination);
      Files.deleteIfExists(directory);
    }
  }

  @Test
  void rejectsMultipartAccessAfterRawBodyAccess() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/upload",
              (request, response) -> {
                request.bodyBytes();
                assertThrows(IllegalStateException.class, () -> request.file("document"));
                response.text("exclusive");
              });
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/upload"))
                  .timeout(Duration.ofSeconds(3))
                  .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                  .POST(HttpRequest.BodyPublishers.ofByteArray(body()))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("exclusive", result.body());
    }
  }

  @Test
  void rejectsRawBodyAccessAfterMultipartAccess() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/upload",
              (request, response) -> {
                request.file("document");
                assertThrows(IllegalStateException.class, request::bodyBytes);
                response.text("exclusive");
              });
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/upload"))
                  .timeout(Duration.ofSeconds(3))
                  .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                  .POST(HttpRequest.BodyPublishers.ofByteArray(body()))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("exclusive", result.body());
    }
  }

  @Test
  void preservesRepeatedMultipartTextFieldsInArrivalOrder() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/upload",
              (request, response) -> response.text(String.join(",", request.formParams("tag"))));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/upload"))
                  .timeout(Duration.ofSeconds(3))
                  .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                  .POST(HttpRequest.BodyPublishers.ofByteArray(repeatedTextBody()))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("first,second", result.body());
    }
  }

  @Test
  void persistsAnUploadBeyondRequestCompletion() throws Exception {
    var directory = Files.createTempDirectory(temporaryDirectory, "multipart-request-test");
    var destination = directory.resolve("saved.txt");
    var multipart = new MultipartOptions(1000, 1000, 100, 8192, 0, directory);

    try (var app = new Shoostr(Options.defaults().withMultipart(multipart).withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/upload",
              (request, response) -> {
                Objects.requireNonNull(request.file("document")).persistTo(destination);
                response.text("saved");
              });
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/upload"))
                  .timeout(Duration.ofSeconds(3))
                  .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                  .POST(HttpRequest.BodyPublishers.ofByteArray(fileBody("contents")))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("saved", result.body());
    }

    try {
      assertEquals("contents", Files.readString(destination));
    } finally {
      Files.deleteIfExists(destination);
      Files.deleteIfExists(directory);
    }
  }

  @Test
  void rejectsReadingRetainedUploadContentAfterHandlerCompletion() throws Exception {
    var retained = new AtomicReference<InputStream>();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/upload",
              (request, response) -> {
                retained.set(Objects.requireNonNull(request.file("document")).content());
                response.text("ready");
              });
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/upload"))
                  .timeout(Duration.ofSeconds(3))
                  .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                  .POST(HttpRequest.BodyPublishers.ofByteArray(body()))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertThrows(IllegalStateException.class, retained.get()::read);
    }
  }

  @Test
  void rejectsSkippingUploadContentOutsideItsHandlerLifetime() throws Exception {
    var retained = new AtomicReference<InputStream>();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/upload",
              (request, response) -> {
                var content = Objects.requireNonNull(request.file("document")).content();
                retained.set(content);

                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                  executor
                      .submit(
                          () -> assertThrows(IllegalStateException.class, () -> content.skip(2)))
                      .get(3, TimeUnit.SECONDS);
                  executor
                      .submit(
                          () ->
                              assertThrows(
                                  IllegalStateException.class, () -> content.skipNBytes(2)))
                      .get(3, TimeUnit.SECONDS);
                }

                assertEquals(
                    "contents", new String(content.readAllBytes(), StandardCharsets.UTF_8));
                response.text("ready");
              });
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/upload"))
                  .timeout(Duration.ofSeconds(3))
                  .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                  .POST(HttpRequest.BodyPublishers.ofByteArray(body()))
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(200, result.statusCode());
      assertEquals("ready", result.body());
      var closedInput = retained.get();
      assertThrows(IllegalStateException.class, () -> closedInput.skip(1));
      assertThrows(IllegalStateException.class, () -> closedInput.skipNBytes(1));
    }
  }

  @Test
  void rejectsMultipartBodiesExceedingTheAggregateLimit() throws Exception {
    var multipart = new MultipartOptions(100, 100, 100, 8192, 16_384, null);

    try (var app = new Shoostr(Options.defaults().withMultipart(multipart).withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/upload",
              (request, response) ->
                  response.text(Objects.requireNonNull(request.formParam("title"))));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/upload"))
                  .timeout(Duration.ofSeconds(3))
                  .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                  .POST(HttpRequest.BodyPublishers.ofByteArray(body()))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(413, result.statusCode());
      assertEquals("Content Too Large", result.body());
    }
  }

  @Test
  void acceptsFixedLengthMultipartBodiesWithinTheMultipartAggregateLimit() throws Exception {
    var content = "x".repeat(2_000_000);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/upload",
              (request, response) ->
                  response.text(
                      Long.toString(Objects.requireNonNull(request.file("document")).size())));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/upload"))
                  .timeout(Duration.ofSeconds(3))
                  .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                  .POST(HttpRequest.BodyPublishers.ofByteArray(fileBody(content)))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("2000000", result.body());
    }
  }

  @Test
  void rejectsChunkedMultipartBodiesExceedingTheAggregateLimit() throws Exception {
    var multipart = new MultipartOptions(100, 100, 100, 8192, 16_384, null);

    try (var app = new Shoostr(Options.defaults().withMultipart(multipart).withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/upload",
              (request, response) ->
                  response.text(Objects.requireNonNull(request.formParam("title"))));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/upload"))
                  .timeout(Duration.ofSeconds(3))
                  .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                  .POST(
                      HttpRequest.BodyPublishers.ofInputStream(
                          () -> new ByteArrayInputStream(body())))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(413, result.statusCode());
      assertEquals("Content Too Large", result.body());
    }
  }

  @Test
  void cleansUpAndRepeatsLimitRejectionsWithoutPublishingPartialParts() throws Exception {
    var directory = Files.createTempDirectory(temporaryDirectory, "multipart-limit-test");
    var multipart = new MultipartOptions(100, 100, 100, 8192, 0, directory);

    try (var app = new Shoostr(Options.defaults().withMultipart(multipart).withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/upload",
              (request, response) -> {
                assertThrows(
                    io.github.suppierk.shoostr.http.exceptions.ContentTooLargeException.class,
                    request::files);
                assertThrows(
                    io.github.suppierk.shoostr.http.exceptions.ContentTooLargeException.class,
                    request::formParamMap);
                response.text("rejected");
              });
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/upload"))
                  .timeout(Duration.ofSeconds(3))
                  .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                  .POST(
                      HttpRequest.BodyPublishers.ofInputStream(
                          () -> new ByteArrayInputStream(fileBody("x".repeat(200)))))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("rejected", result.body());
      awaitFile(directory, false);
    } finally {
      Files.deleteIfExists(directory);
    }
  }

  @Test
  void cleansUpTemporaryPartsCreatedBeforeAChunkedLimitRejection() throws Exception {
    var directory = Files.createTempDirectory(temporaryDirectory, "multipart-chunked-limit-test");
    var multipart = new MultipartOptions(1000, 1000, 100, 8192, 0, directory);

    try (var app = new Shoostr(Options.defaults().withMultipart(multipart).withPort(0))) {
      app.routes()
          .post(
              "/upload",
              (request, response) -> {
                assertThrows(
                    io.github.suppierk.shoostr.http.exceptions.ContentTooLargeException.class,
                    request::files);
                assertThrows(
                    io.github.suppierk.shoostr.http.exceptions.ContentTooLargeException.class,
                    request::formParamMap);
                response.text("rejected");
              });
      app.start();

      try (var socket = new Socket(InetAddress.getAllByName("127.0.0.1")[0], app.port())) {
        socket.setSoTimeout(3000);
        var output = socket.getOutputStream();
        output.write(
            ("POST /upload HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\nTransfer-Encoding: chunked\r\nContent-Type: multipart/form-data; boundary="
                    + BOUNDARY
                    + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        writeChunk(
            output,
            ("--"
                    + BOUNDARY
                    + "\r\nContent-Disposition: form-data; name=\"document\"; filename=\"report.txt\"\r\n\r\n"
                    + "x".repeat(100))
                .getBytes(StandardCharsets.UTF_8));
        output.flush();
        awaitFile(directory, true);
        writeChunk(output, "x".repeat(1000).getBytes(StandardCharsets.UTF_8));
        output.write("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        output.flush();
        var response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(response.startsWith("HTTP/1.1 200"));
        assertTrue(response.endsWith("rejected"));
        awaitFile(directory, false);
      }
    } finally {
      Files.deleteIfExists(directory);
    }
  }

  @Test
  void rejectsFilesExceedingThePerFileLimit() throws Exception {
    var multipart = new MultipartOptions(1000, 3, 100, 8192, 16_384, null);

    try (var app = new Shoostr(Options.defaults().withMultipart(multipart).withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/upload",
              (request, response) ->
                  response.text(Objects.requireNonNull(request.file("document")).fileName()));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/upload"))
                  .timeout(Duration.ofSeconds(3))
                  .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                  .POST(HttpRequest.BodyPublishers.ofByteArray(fileBody("contents")))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(413, result.statusCode());
      assertEquals("Content Too Large", result.body());
    }
  }

  @Test
  void rejectsMultipartRequestsExceedingThePartLimit() throws Exception {
    var multipart = new MultipartOptions(1000, 1000, 1, 8192, 16_384, null);

    try (var app = new Shoostr(Options.defaults().withMultipart(multipart).withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/upload",
              (request, response) -> response.text(request.files("document").toString()));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/upload"))
                  .timeout(Duration.ofSeconds(3))
                  .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                  .POST(HttpRequest.BodyPublishers.ofByteArray(repeatedFilesBody()))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(413, result.statusCode());
      assertEquals("Content Too Large", result.body());
    }
  }

  @Test
  void rejectsMultipartPartsExceedingTheHeaderLimit() throws Exception {
    var multipart = new MultipartOptions(1000, 1000, 100, 10, 16_384, null);

    try (var app = new Shoostr(Options.defaults().withMultipart(multipart).withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/upload",
              (request, response) ->
                  response.text(Objects.requireNonNull(request.file("document")).fileName()));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/upload"))
                  .timeout(Duration.ofSeconds(3))
                  .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                  .POST(HttpRequest.BodyPublishers.ofByteArray(body()))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(413, result.statusCode());
      assertEquals("Content Too Large", result.body());
    }
  }

  @Test
  void rejectsMultipartRequestsWithoutABoundary() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/upload",
              (request, response) ->
                  response.text(Objects.requireNonNull(request.formParam("title"))));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/upload"))
                  .timeout(Duration.ofSeconds(3))
                  .header("Content-Type", "multipart/form-data")
                  .POST(HttpRequest.BodyPublishers.ofByteArray(body()))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(400, result.statusCode());
      assertEquals("Bad Request", result.body());
    }
  }

  @Test
  void rejectsMultipartTextFieldsWithANonUtf8Charset() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/upload",
              (request, response) ->
                  response.text(Objects.requireNonNull(request.formParam("title"))));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/upload"))
                  .timeout(Duration.ofSeconds(3))
                  .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                  .POST(HttpRequest.BodyPublishers.ofByteArray(nonUtf8TextFieldBody()))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(415, result.statusCode());
      assertEquals("Unsupported Media Type", result.body());
    }
  }

  @Test
  void rejectsEveryAccessorAfterMultipartMetadataValidationFails() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/upload",
              (request, response) -> {
                assertThrows(
                    io.github.suppierk.shoostr.http.exceptions.BadRequestException.class,
                    request::files);
                assertThrows(
                    io.github.suppierk.shoostr.http.exceptions.BadRequestException.class,
                    request::formParamMap);
                response.text("rejected");
              });
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/upload"))
                  .timeout(Duration.ofSeconds(3))
                  .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                  .POST(HttpRequest.BodyPublishers.ofByteArray(invalidMetadataBody()))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("rejected", result.body());
    }
  }

  @Test
  void rejectsEachRequiredMultipartMetadataBranch() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/upload",
              (request, response) -> {
                assertThrows(
                    io.github.suppierk.shoostr.http.exceptions.BadRequestException.class,
                    request::files);
                assertThrows(
                    io.github.suppierk.shoostr.http.exceptions.BadRequestException.class,
                    request::formParamMap);
                response.text("rejected");
              });
      app.start();

      for (var body :
          List.of(wrongDispositionBody(), missingDispositionBody(), missingNameBody())) {
        var result =
            client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/upload"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("rejected", result.body());
      }
    }
  }

  @Test
  void deletesTemporaryUploadsAfterMalformedMultipartInput() throws Exception {
    var directory = Files.createTempDirectory(temporaryDirectory, "multipart-cleanup-test");
    var multipart = new MultipartOptions(1000, 1000, 100, 8192, 0, directory);

    try (var app = new Shoostr(Options.defaults().withMultipart(multipart).withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/upload",
              (request, response) ->
                  response.text(Objects.requireNonNull(request.file("document")).fileName()));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/upload"))
                  .timeout(Duration.ofSeconds(3))
                  .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                  .POST(HttpRequest.BodyPublishers.ofByteArray(malformedBody()))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(400, result.statusCode());
      assertEquals("Bad Request", result.body());
      awaitFile(directory, false);
    }

    try {
      awaitFile(directory, false);
    } finally {
      Files.deleteIfExists(directory);
    }
  }

  @Test
  void deletesTemporaryUploadsAfterAHandlerFailure() throws Exception {
    var directory = Files.createTempDirectory(temporaryDirectory, "multipart-cleanup-test");
    var multipart = new MultipartOptions(1000, 1000, 100, 8192, 0, directory);

    try (var app = new Shoostr(Options.defaults().withMultipart(multipart).withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/upload",
              (request, response) -> {
                var content = Objects.requireNonNull(request.file("document")).content();
                content.read();
                throw new IllegalStateException("expected failure");
              });
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/upload"))
                  .timeout(Duration.ofSeconds(3))
                  .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                  .POST(HttpRequest.BodyPublishers.ofByteArray(body()))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(500, result.statusCode());
      assertEquals("Internal Server Error", result.body());
      awaitFile(directory, false);
    }

    try {
      awaitFile(directory, false);
    } finally {
      Files.deleteIfExists(directory);
    }
  }

  @Test
  void deletesTemporaryUploadsAfterASuccessfulResponse() throws Exception {
    var directory = Files.createTempDirectory(temporaryDirectory, "multipart-cleanup-test");
    var multipart = new MultipartOptions(1000, 1000, 100, 8192, 0, directory);

    try (var app = new Shoostr(Options.defaults().withMultipart(multipart).withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/upload",
              (request, response) ->
                  response.text(Objects.requireNonNull(request.file("document")).fileName()));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/upload"))
                  .timeout(Duration.ofSeconds(3))
                  .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                  .POST(HttpRequest.BodyPublishers.ofByteArray(body()))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("report.txt", result.body());
      awaitFile(directory, false);
    }

    try {
      awaitFile(directory, false);
    } finally {
      Files.deleteIfExists(directory);
    }
  }

  @Test
  void reportsServerErrorsWhenTemporaryUploadStorageCannotBeCreated() throws Exception {
    var storage = Files.createTempFile(temporaryDirectory, "multipart-storage-test", ".tmp");
    var multipart = new MultipartOptions(1000, 1000, 100, 8192, 0, storage);

    try (var app = new Shoostr(Options.defaults().withMultipart(multipart).withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/upload",
              (request, response) ->
                  response.text(Objects.requireNonNull(request.file("document")).fileName()));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/upload"))
                  .timeout(Duration.ofSeconds(3))
                  .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                  .POST(HttpRequest.BodyPublishers.ofByteArray(body()))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(500, result.statusCode());
      assertEquals("Internal Server Error", result.body());
    } finally {
      Files.deleteIfExists(storage);
    }
  }

  @Test
  void deletesTemporaryUploadsAfterClientDisconnect() throws Exception {
    var directory = Files.createTempDirectory(temporaryDirectory, "multipart-cleanup-test");
    var multipart = new MultipartOptions(1000, 1000, 100, 8192, 0, directory);

    try (var app = new Shoostr(Options.defaults().withMultipart(multipart).withPort(0))) {
      app.routes()
          .post(
              "/upload",
              (request, response) ->
                  response.text(Objects.requireNonNull(request.file("document")).fileName()));
      app.start();

      try (var socket = new Socket(InetAddress.getAllByName("127.0.0.1")[0], app.port())) {
        var prefix =
            "--"
                + BOUNDARY
                + "\r\nContent-Disposition: form-data; name=\"document\"; filename=\"report.txt\"\r\n\r\ncontents";
        var request =
            "POST /upload HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: multipart/form-data; boundary="
                + BOUNDARY
                + "\r\nContent-Length: 1000\r\n\r\n"
                + prefix;
        socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
        awaitFile(directory, true);
      }

      awaitFile(directory, false);
    } finally {
      Files.deleteIfExists(directory);
    }
  }

  @Test
  void retainsRepeatedUploadsInArrivalOrder() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/upload",
              (request, response) ->
                  response.text(
                      request.files("document").stream()
                          .map(Upload::fileName)
                          .collect(java.util.stream.Collectors.joining(","))));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/upload"))
                  .timeout(Duration.ofSeconds(3))
                  .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                  .POST(HttpRequest.BodyPublishers.ofByteArray(repeatedFilesBody()))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("first.txt,second.txt", result.body());
    }
  }

  @Test
  void treatsAnEmptyFilenameAsAnUpload() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/upload",
              (request, response) ->
                  response.text(
                      "<" + Objects.requireNonNull(request.file("document")).fileName() + ">"));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/upload"))
                  .timeout(Duration.ofSeconds(3))
                  .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                  .POST(HttpRequest.BodyPublishers.ofByteArray(emptyFilenameBody()))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("<>", result.body());
    }
  }

  private static byte[] body() {
    return ("--"
            + BOUNDARY
            + "\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\nreport\r\n--"
            + BOUNDARY
            + "\r\nContent-Disposition: form-data; name=\"document\"; filename=\"report.txt\"\r\n"
            + "Content-Type: text/plain\r\n\r\ncontents\r\n--"
            + BOUNDARY
            + "--\r\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] repeatedFilesBody() {
    return ("--"
            + BOUNDARY
            + "\r\nContent-Disposition: form-data; name=\"document\"; filename=\"first.txt\"\r\n\r\n"
            + "first\r\n--"
            + BOUNDARY
            + "\r\nContent-Disposition: form-data; name=\"document\"; filename=\"second.txt\"\r\n\r\n"
            + "second\r\n--"
            + BOUNDARY
            + "--\r\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] repeatedTextBody() {
    return ("--"
            + BOUNDARY
            + "\r\nContent-Disposition: form-data; name=\"tag\"\r\n\r\nfirst\r\n--"
            + BOUNDARY
            + "\r\nContent-Disposition: form-data; name=\"tag\"\r\n\r\nsecond\r\n--"
            + BOUNDARY
            + "--\r\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] fileBody(String content) {
    return fileBody("report.txt", content);
  }

  private static byte[] fileBody(String fileName, String content) {
    return ("--"
            + BOUNDARY
            + "\r\nContent-Disposition: form-data; name=\"document\"; filename=\""
            + fileName
            + "\"\r\n\r\n"
            + content
            + "\r\n--"
            + BOUNDARY
            + "--\r\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] binaryBody() throws IOException {
    var output = new ByteArrayOutputStream();
    output.write(
        ("--"
                + BOUNDARY
                + "\r\nContent-Disposition: form-data; name=\"document\"; filename=\"binary.bin\"\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    output.write(BINARY_CONTENT);
    output.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
    return output.toByteArray();
  }

  private static byte[] malformedBody() {
    return ("--"
            + BOUNDARY
            + "\r\nContent-Disposition: form-data; name=\"document\"; filename=\"report.txt\"\r\n\r\n"
            + "contents")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] emptyFilenameBody() {
    return ("--"
            + BOUNDARY
            + "\r\nContent-Disposition: form-data; name=\"document\"; filename=\"\"\r\n\r\n"
            + "contents\r\n--"
            + BOUNDARY
            + "--\r\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] nonUtf8TextFieldBody() {
    return ("--"
            + BOUNDARY
            + "\r\nContent-Disposition: form-data; name=\"title\"\r\nContent-Type: text/plain; charset=ISO-8859-1\r\n\r\nreport\r\n--"
            + BOUNDARY
            + "--\r\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] invalidMetadataBody() {
    return ("--"
            + BOUNDARY
            + "\r\nContent-Disposition: attachment; filename=\"report.txt\"\r\n\r\ncontents\r\n--"
            + BOUNDARY
            + "--\r\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] wrongDispositionBody() {
    return partBody("Content-Disposition: attachment; name=\"document\"");
  }

  private static byte[] missingDispositionBody() {
    return partBody("Content-Type: text/plain");
  }

  private static byte[] missingNameBody() {
    return partBody("Content-Disposition: form-data");
  }

  private static byte[] partBody(String header) {
    return ("--" + BOUNDARY + "\r\n" + header + "\r\n\r\ncontents\r\n--" + BOUNDARY + "--\r\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static void awaitFile(Path directory, boolean expected) {
    await()
        .pollInterval(Duration.ofMillis(20))
        .atMost(Duration.ofSeconds(1))
        .untilAsserted(
            () -> {
              try (var files = Files.list(directory)) {
                assertEquals(expected, files.count() > 0);
              }
            });
  }

  private static void writeChunk(OutputStream output, byte[] chunk) throws IOException {
    output.write(Integer.toHexString(chunk.length).getBytes(StandardCharsets.UTF_8));
    output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    output.write(chunk);
    output.write("\r\n".getBytes(StandardCharsets.UTF_8));
  }
}
