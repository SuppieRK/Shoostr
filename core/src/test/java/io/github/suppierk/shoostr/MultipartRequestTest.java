package io.github.suppierk.shoostr;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.http.HttpTester;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class MultipartRequestTest {
  private static final String BOUNDARY = "multipart-test-boundary";
  private static final byte[] BINARY_CONTENT = {0, 1, -1, 127, -128};
  @TempDir Path temporaryDirectory;

  @ParameterizedTest
  @ValueSource(
      strings = {"", "text/plain", "application/json", "application/x-www-form-urlencoded"})
  void preservesNonmultipartBodyBytesWhenFileAccessorsAreCalledFirst(String contentType)
      throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes()
          .post(
              "/files",
              (request, response) -> {
                assertNoUploads(request);
                response.body("application/octet-stream", request.bodyBytes());
              });

      try (var test = TestServer.start(app)) {

        var result =
            test.send(
                request -> {
                  request
                      .path("/files")
                      .timeout(Duration.ofSeconds(3))
                      .method("POST")
                      .body(BINARY_CONTENT);
                  if (!contentType.isEmpty()) {
                    request.header("Content-Type", contentType);
                  }
                });
        assertEquals(200, result.statusCode());
        assertArrayEquals(BINARY_CONTENT, result.body());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "text/plain", "application/json", "application/x-www-form-urlencoded"})
  void allowsNonmultipartFileAccessAfterBufferedBodyConsumption(String contentType)
      throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes()
          .post(
              "/files",
              (request, response) -> {
                request.bodyBytes();
                assertNoUploads(request);
                response.body("application/octet-stream", request.bodyBytes());
              });

      try (var test = TestServer.start(app)) {

        var result =
            test.send(
                request -> {
                  request
                      .path("/files")
                      .timeout(Duration.ofSeconds(3))
                      .method("POST")
                      .body(BINARY_CONTENT);
                  if (!contentType.isEmpty()) {
                    request.header("Content-Type", contentType);
                  }
                });
        assertEquals(200, result.statusCode());
        assertArrayEquals(BINARY_CONTENT, result.body());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void preservesNonmultipartInputRegardlessOfFileAccessOrder(boolean filesFirst) throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes()
          .post(
              "/files",
              (request, response) -> {
                if (filesFirst) {
                  assertNoUploads(request);
                }

                try (var input = request.input()) {
                  var content = input.readAllBytes();
                  if (!filesFirst) {
                    assertNoUploads(request);
                  }

                  assertEquals(-1, input.read());
                  response.body("application/octet-stream", content);
                }
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/files")
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "application/octet-stream")
                        .method("POST")
                        .body(BINARY_CONTENT));
        assertEquals(200, result.statusCode());
        assertArrayEquals(BINARY_CONTENT, result.body());
      }
    }
  }

  @Test
  void returnsNoUploadsForABodylessRequestWithoutAContentType() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes()
          .get(
              "/files",
              (request, response) -> {
                assertNoUploads(request);
                response.text(request.bodyText());
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request -> request.path("/files").timeout(Duration.ofSeconds(3)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("", result.body());
      }
    }
  }

  @Test
  void preservesNonmultipartUtf8TextAfterFileLookup() throws Exception {
    var content = "café + %23\n";

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes()
          .post(
              "/files",
              (request, response) -> {
                assertNoUploads(request);
                response.text(request.bodyText());
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/files")
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "text/plain")
                        .method("POST")
                        .body(content.getBytes(StandardCharsets.UTF_8)),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, result.statusCode());
        assertEquals(content, result.body());
      }
    }
  }

  @Test
  void keepsMultipartWithoutFilesExclusiveFromRawBodyAccess() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes()
          .post(
              "/files",
              (request, response) -> {
                assertNoUploads(request);
                assertThrows(IllegalStateException.class, request::bodyBytes);
                response.text(request.formParam("document").orElseThrow());
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/files")
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .method("POST")
                        .body(partBody("Content-Disposition: form-data; name=\"document\"")),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("contents", result.body());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "multipart/form-data",
        "multipart/form-data; boundary=" + BOUNDARY,
        "multipart/form-data; boundary=\"unterminated"
      })
  void rejectsDeclaredMalformedMultipartDuringFileLookup(String contentType) throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes()
          .post(
              "/files",
              (request, response) -> response.text(Integer.toString(request.files().size())));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/files")
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", contentType)
                        .method("POST")
                        .body(malformedBody()),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, result.statusCode());
        assertEquals("Bad Request", result.body());
      }
    }
  }

  @Test
  void sendsContinueBeforeReceivingAndPreservingABinaryMultipartUpload() throws Exception {
    var body = binaryBody();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes()
          .post(
              "/upload",
              (request, response) -> {
                var upload = request.file("document").orElseThrow();
                assertEquals("document", upload.name());
                assertEquals("binary.bin", upload.fileName());
                assertEquals(5, upload.size());

                try (var content = upload.content()) {
                  response.body("application/octet-stream", content.readAllBytes());
                }
              });
      app.start();

      try (var socket = new Socket()) {
        socket.setSoTimeout(3000);
        socket.connect(
            new InetSocketAddress(InetAddress.getAllByName("127.0.0.1")[0], app.port()), 3000);
        var output = socket.getOutputStream();
        var input = HttpTester.from(socket.getInputStream());
        output.write(
            ("POST /upload HTTP/1.1\r\nHost: localhost\r\n"
                    + "Expect: 100-continue\r\n"
                    + "Content-Type: multipart/form-data; boundary="
                    + BOUNDARY
                    + "\r\nContent-Length: "
                    + body.length
                    + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        output.flush();

        var interim = HttpTester.parseResponse(input);
        assertNotNull(interim);
        assertEquals(100, interim.getStatus());

        output.write(body);
        output.flush();
        var result = HttpTester.parseResponse(input);
        assertNotNull(result);
        assertEquals(200, result.getStatus());
        assertEquals("application/octet-stream", result.get("Content-Type"));
        assertArrayEquals(BINARY_CONTENT, result.getContentBytes());
      }
    }
  }

  @Test
  void preservesALargeUtf8MultipartFieldAsTextAndCleansItsTemporaryStorage() throws Exception {
    var content = "é".repeat(95 * 1024);
    var directory = Files.createDirectory(temporaryDirectory.resolve("large-text"));
    var multipart = new MultipartOptions(300_000, 250_000, 2, 8192, 1024, directory);
    var completed = new CompletableFuture<Void>();
    var body =
        ("--"
                + BOUNDARY
                + "\r\nContent-Disposition: form-data; name=\"document\"\r\n\r\n"
                + content
                + "\r\n--"
                + BOUNDARY
                + "--\r\n")
            .getBytes(StandardCharsets.UTF_8);

    try (var app = new Shoostr(Options.defaults().withPort(0).withMultipart(multipart))) {
      app.afterRequest(_ -> completed.complete(null));
      app.routes()
          .post(
              "/upload",
              (request, response) -> {
                var fields = request.formParamMap();
                assertEquals(Map.of("document", List.of(content)), fields);
                assertTrue(request.files().isEmpty());

                try (var files = Files.list(directory)) {
                  assertTrue(files.findAny().isPresent());
                }

                response.text(request.formParam("document").orElseThrow());
              });

      try (var test = TestServer.start(app)) {

        var result =
            test.send(
                request ->
                    request
                        .path("/upload")
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .method("POST")
                        .body(body));

        assertEquals(200, result.statusCode());
        assertArrayEquals(content.getBytes(StandardCharsets.UTF_8), result.body());
        completed.get(3, TimeUnit.SECONDS);
        awaitFile(directory, false);
      }
    }
  }

  @Test
  void preservesASameSizedNamedMultipartPartAsAFileAndCleansItsTemporaryStorage() throws Exception {
    var content = "é".repeat(95 * 1024);
    var directory = Files.createDirectory(temporaryDirectory.resolve("large-file"));
    var multipart = new MultipartOptions(300_000, 250_000, 2, 8192, 1024, directory);
    var completed = new CompletableFuture<Void>();

    try (var app = new Shoostr(Options.defaults().withPort(0).withMultipart(multipart))) {
      app.afterRequest(_ -> completed.complete(null));
      app.routes()
          .post(
              "/upload",
              (request, response) -> {
                assertTrue(request.formParamMap().isEmpty());
                assertEquals(1, request.files().size());
                assertEquals(1, request.files("document").size());
                var upload = request.file("document").orElseThrow();
                assertEquals("document", upload.name());
                assertEquals("report.txt", upload.fileName());
                assertEquals(194_560, upload.size());

                try (var files = Files.list(directory)) {
                  assertTrue(files.findAny().isPresent());
                }

                try (var input = upload.content()) {
                  response.body("application/octet-stream", input.readAllBytes());
                }
              });

      try (var test = TestServer.start(app)) {

        var result =
            test.send(
                request ->
                    request
                        .path("/upload")
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .method("POST")
                        .body(fileBody(content)));

        assertEquals(200, result.statusCode());
        assertArrayEquals(content.getBytes(StandardCharsets.UTF_8), result.body());
        completed.get(3, TimeUnit.SECONDS);
        awaitFile(directory, false);
      }
    }
  }

  @Test
  void readsMultipartTextAndFileFields() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes()
          .post(
              "/upload",
              (request, response) -> {
                var upload = request.file("document").orElseThrow();
                assertTrue(request.file("missing").isEmpty());
                response.text(
                    request.formParam("title").orElseThrow()
                        + "|"
                        + upload.name()
                        + "|"
                        + upload.fileName()
                        + "|"
                        + new String(upload.content().readAllBytes(), StandardCharsets.UTF_8));
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/upload")
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .method("POST")
                        .body(body()),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("report|document|report.txt|contents", result.body());
      }
    }
  }

  @Test
  void preservesBinaryUploadContent() throws Exception {
    var body = binaryBody();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes()
          .post(
              "/upload",
              (request, response) -> {
                assertArrayEquals(
                    BINARY_CONTENT,
                    request.file("document").orElseThrow().content().readAllBytes());
                response.text("binary");
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/upload")
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .method("POST")
                        .body(body),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("binary", result.body());
      }
    }
  }

  @Test
  void spillsOnlyUploadsAboveTheMemoryThresholdToTheConfiguredDirectory() throws Exception {
    var directory = Files.createTempDirectory(temporaryDirectory, "multipart-threshold-test");
    var multipart = new MultipartOptions(1000, 1000, 100, 8192, 8, directory);

    try (var app = new Shoostr(Options.defaults().withMultipart(multipart).withPort(0))) {
      app.routes()
          .post(
              "/upload",
              (request, response) -> {
                request.file("document");

                try (var files = Files.list(directory)) {
                  response.text(Long.toString(files.count()));
                }
              });

      try (var test = TestServer.start(app)) {
        var inMemory =
            test.send(
                request ->
                    request
                        .path("/upload")
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .method("POST")
                        .body(fileBody("12345678")),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, inMemory.statusCode());
        assertEquals("0", inMemory.body());
        awaitFile(directory, false);
        var onDisk =
            test.send(
                request ->
                    request
                        .path("/upload")
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .method("POST")
                        .body(fileBody("123456789")),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, onDisk.statusCode());
        assertEquals("1", onDisk.body());
        awaitFile(directory, false);
      }
    } finally {
      Files.deleteIfExists(directory);
    }
  }

  @Test
  void treatsHostileFilenamesAsMetadataInsteadOfDestinationPaths() throws Exception {
    var directory = Files.createTempDirectory(temporaryDirectory, "multipart-filename-test");
    var destination = directory.resolve("saved.bin");
    var multipart = new MultipartOptions(1000, 1000, 100, 8192, 0, directory);

    try (var app = new Shoostr(Options.defaults().withMultipart(multipart).withPort(0))) {
      app.routes()
          .post(
              "/upload",
              (request, response) -> {
                var upload = request.file("document").orElseThrow();
                upload.persistTo(destination);
                response.text(upload.fileName());
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/upload")
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .method("POST")
                        .body(fileBody("../../outside.txt", "contents")),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("../../outside.txt", result.body());
        assertEquals("contents", Files.readString(destination));

        try (var files = Files.list(directory)) {
          assertEquals(1, files.count());
        }
      }
    } finally {
      Files.deleteIfExists(destination);
      Files.deleteIfExists(directory);
    }
  }

  @Test
  void rejectsMultipartAccessAfterRawBodyAccess() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes()
          .post(
              "/upload",
              (request, response) -> {
                request.bodyBytes();
                assertThrows(IllegalStateException.class, () -> request.file("document"));
                response.text("exclusive");
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/upload")
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .method("POST")
                        .body(body()),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("exclusive", result.body());
      }
    }
  }

  @Test
  void rejectsRawBodyAccessAfterMultipartAccess() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes()
          .post(
              "/upload",
              (request, response) -> {
                request.file("document");
                assertThrows(IllegalStateException.class, request::bodyBytes);
                response.text("exclusive");
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/upload")
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .method("POST")
                        .body(body()),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("exclusive", result.body());
      }
    }
  }

  @Test
  void preservesRepeatedMultipartTextFieldsInArrivalOrder() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes()
          .post(
              "/upload",
              (request, response) -> response.text(String.join(",", request.formParams("tag"))));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/upload")
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .method("POST")
                        .body(repeatedTextBody()),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("first,second", result.body());
      }
    }
  }

  @Test
  void persistsAnUploadBeyondRequestCompletion() throws Exception {
    var directory = Files.createTempDirectory(temporaryDirectory, "multipart-request-test");
    var destination = directory.resolve("saved.txt");
    var multipart = new MultipartOptions(1000, 1000, 100, 8192, 0, directory);

    try (var app = new Shoostr(Options.defaults().withMultipart(multipart).withPort(0))) {
      app.routes()
          .post(
              "/upload",
              (request, response) -> {
                request.file("document").orElseThrow().persistTo(destination);
                response.text("saved");
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/upload")
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .method("POST")
                        .body(fileBody("contents")),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("saved", result.body());
      }
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

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes()
          .post(
              "/upload",
              (request, response) -> {
                retained.set(request.file("document").orElseThrow().content());
                response.text("ready");
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/upload")
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .method("POST")
                        .body(body()),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertThrows(IllegalStateException.class, retained.get()::read);
      }
    }
  }

  @Test
  void rejectsSkippingUploadContentOutsideItsHandlerLifetime() throws Exception {
    var retained = new AtomicReference<InputStream>();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes()
          .post(
              "/upload",
              (request, response) -> {
                var content = request.file("document").orElseThrow().content();
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

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/upload")
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .method("POST")
                        .body(body()),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, result.statusCode());
        assertEquals("ready", result.body());
        var closedInput = retained.get();
        assertThrows(IllegalStateException.class, () -> closedInput.skip(1));
        assertThrows(IllegalStateException.class, () -> closedInput.skipNBytes(1));
      }
    }
  }

  @Test
  void rejectsMultipartBodiesExceedingTheAggregateLimit() throws Exception {
    var multipart = new MultipartOptions(100, 100, 100, 8192, 16_384, null);

    try (var app = new Shoostr(Options.defaults().withMultipart(multipart).withPort(0))) {
      app.routes()
          .post(
              "/upload",
              (request, response) -> response.text(request.formParam("title").orElseThrow()));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/upload")
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .method("POST")
                        .body(body()),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(413, result.statusCode());
        assertEquals("Content Too Large", result.body());
      }
    }
  }

  @Test
  void acceptsFixedLengthMultipartBodiesWithinTheMultipartAggregateLimit() throws Exception {
    var content = "x".repeat(2_000_000);

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes()
          .post(
              "/upload",
              (request, response) ->
                  response.text(Long.toString(request.file("document").orElseThrow().size())));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/upload")
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .method("POST")
                        .body(fileBody(content)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("2000000", result.body());
      }
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
              (request, response) -> response.text(request.formParam("title").orElseThrow()));
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

    try (var app = new Shoostr(Options.defaults().withMultipart(multipart).withPort(0))) {
      app.routes()
          .post(
              "/upload",
              (request, response) ->
                  response.text(request.file("document").orElseThrow().fileName()));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/upload")
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .method("POST")
                        .body(fileBody("contents")),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(413, result.statusCode());
        assertEquals("Content Too Large", result.body());
      }
    }
  }

  @Test
  void rejectsMultipartRequestsExceedingThePartLimit() throws Exception {
    var multipart = new MultipartOptions(1000, 1000, 1, 8192, 16_384, null);

    try (var app = new Shoostr(Options.defaults().withMultipart(multipart).withPort(0))) {
      app.routes()
          .post(
              "/upload",
              (request, response) -> response.text(request.files("document").toString()));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/upload")
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .method("POST")
                        .body(repeatedFilesBody()),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(413, result.statusCode());
        assertEquals("Content Too Large", result.body());
      }
    }
  }

  @Test
  void rejectsMultipartPartsExceedingTheHeaderLimit() throws Exception {
    var multipart = new MultipartOptions(1000, 1000, 100, 10, 16_384, null);

    try (var app = new Shoostr(Options.defaults().withMultipart(multipart).withPort(0))) {
      app.routes()
          .post(
              "/upload",
              (request, response) ->
                  response.text(request.file("document").orElseThrow().fileName()));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/upload")
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .method("POST")
                        .body(body()),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(413, result.statusCode());
        assertEquals("Content Too Large", result.body());
      }
    }
  }

  @Test
  void rejectsMultipartRequestsWithoutABoundary() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes()
          .post(
              "/upload",
              (request, response) -> response.text(request.formParam("title").orElseThrow()));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/upload")
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "multipart/form-data")
                        .method("POST")
                        .body(body()),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, result.statusCode());
        assertEquals("Bad Request", result.body());
      }
    }
  }

  @Test
  void rejectsMultipartTextFieldsWithANonUtf8Charset() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes()
          .post(
              "/upload",
              (request, response) -> response.text(request.formParam("title").orElseThrow()));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/upload")
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .method("POST")
                        .body(nonUtf8TextFieldBody()),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(415, result.statusCode());
        assertEquals("Unsupported Media Type", result.body());
      }
    }
  }

  @Test
  void rejectsEveryAccessorAfterMultipartMetadataValidationFails() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
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

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/upload")
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .method("POST")
                        .body(invalidMetadataBody()),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("rejected", result.body());
      }
    }
  }

  @Test
  void rejectsEachRequiredMultipartMetadataBranch() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
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

      try (var test = TestServer.start(app)) {

        for (var body :
            List.of(wrongDispositionBody(), missingDispositionBody(), missingNameBody())) {
          var result =
              test.send(
                  request ->
                      request
                          .path("/upload")
                          .timeout(Duration.ofSeconds(3))
                          .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                          .method("POST")
                          .body(body),
                  HttpResponse.BodyHandlers.ofString());
          assertEquals(200, result.statusCode());
          assertEquals("rejected", result.body());
        }
      }
    }
  }

  @Test
  void deletesTemporaryUploadsAfterMalformedMultipartInput() throws Exception {
    var directory = Files.createTempDirectory(temporaryDirectory, "multipart-cleanup-test");
    var multipart = new MultipartOptions(1000, 1000, 100, 8192, 0, directory);

    try (var app = new Shoostr(Options.defaults().withMultipart(multipart).withPort(0))) {
      app.routes()
          .post(
              "/upload",
              (request, response) ->
                  response.text(request.file("document").orElseThrow().fileName()));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/upload")
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .method("POST")
                        .body(malformedBody()),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, result.statusCode());
        assertEquals("Bad Request", result.body());
        awaitFile(directory, false);
      }
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
    var firstByte = new AtomicInteger(-1);

    try (var app = new Shoostr(Options.defaults().withMultipart(multipart).withPort(0))) {
      app.routes()
          .post(
              "/upload",
              (request, _) -> {
                var content = request.file("document").orElseThrow().content();
                firstByte.set(content.read());
                throw new IllegalStateException("expected failure");
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/upload")
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .method("POST")
                        .body(body()),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(500, result.statusCode());
        assertEquals("Internal Server Error", result.body());
        assertEquals('c', firstByte.get());
        awaitFile(directory, false);
      }
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

    try (var app = new Shoostr(Options.defaults().withMultipart(multipart).withPort(0))) {
      app.routes()
          .post(
              "/upload",
              (request, response) ->
                  response.text(request.file("document").orElseThrow().fileName()));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/upload")
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .method("POST")
                        .body(body()),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("report.txt", result.body());
        awaitFile(directory, false);
      }
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

    try (var app = new Shoostr(Options.defaults().withMultipart(multipart).withPort(0))) {
      app.routes()
          .post(
              "/upload",
              (request, response) ->
                  response.text(request.file("document").orElseThrow().fileName()));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/upload")
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .method("POST")
                        .body(body()),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(500, result.statusCode());
        assertEquals("Internal Server Error", result.body());
      }
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
                  response.text(request.file("document").orElseThrow().fileName()));
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
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes()
          .post(
              "/upload",
              (request, response) ->
                  response.text(
                      request.files("document").stream()
                          .map(Upload::fileName)
                          .collect(java.util.stream.Collectors.joining(","))));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/upload")
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .method("POST")
                        .body(repeatedFilesBody()),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("first.txt,second.txt", result.body());
      }
    }
  }

  @Test
  void treatsAnEmptyFilenameAsAnUpload() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes()
          .post(
              "/upload",
              (request, response) ->
                  response.text("<" + request.file("document").orElseThrow().fileName() + ">"));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/upload")
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .method("POST")
                        .body(emptyFilenameBody()),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("<>", result.body());
      }
    }
  }

  private static void assertNoUploads(Request request) throws IOException {
    var files = request.files();
    var namedFiles = request.files("document");
    assertEquals(Map.of(), files);
    assertEquals(List.of(), namedFiles);
    assertTrue(request.file("document").isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> files.put("document", namedFiles));
    assertThrows(UnsupportedOperationException.class, namedFiles::clear);
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
                assertEquals(expected, files.findAny().isPresent());
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
