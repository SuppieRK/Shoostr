package io.github.suppierk.shoostr;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import io.github.suppierk.shoostr.http.exceptions.ContentTooLargeException;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.ServerConnector;
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
            + "\r\nContent-Disposition: form-data; name=\"document\";"
            + " filename=\"first.txt\"\r\n\r\n"
            + "first\r\n--"
            + BOUNDARY
            + "\r\nContent-Disposition: form-data; name=\"document\";"
            + " filename=\"second.txt\"\r\n\r\n"
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
                + "\r\nContent-Disposition: form-data; name=\"document\";"
                + " filename=\"binary.bin\"\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    output.write(BINARY_CONTENT);
    output.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));

    return output.toByteArray();
  }

  private static byte[] malformedBody() {
    return ("--"
            + BOUNDARY
            + "\r\nContent-Disposition: form-data; name=\"document\";"
            + " filename=\"report.txt\"\r\n\r\n"
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
            + "\r\nContent-Disposition: form-data; name=\"title\"\r\n"
            + "Content-Type: text/plain; charset=ISO-8859-1\r\n\r\n"
            + "report\r\n--"
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

  private static List<Path> awaitSpoolFiles(Path directory, int expected) {
    return await()
        .pollInterval(Duration.ofMillis(20))
        .atMost(Duration.ofSeconds(3))
        .until(
            () -> {
              try (var files = Files.list(directory)) {
                return files.toList();
              }
            },
            files -> files.size() == expected);
  }

  private static void sendMultipartPrefix(
      Socket socket, int port, String path, byte[] body, int withheld) throws IOException {
    socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1000);
    socket.setSoTimeout(3000);
    socket
        .getOutputStream()
        .write(
            ("POST "
                    + path
                    + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                    + "Content-Type: multipart/form-data; boundary="
                    + BOUNDARY
                    + "\r\nContent-Length: "
                    + body.length
                    + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
    socket.getOutputStream().write(body, 0, body.length - withheld);
    socket.getOutputStream().flush();
  }

  private static void writeChunk(OutputStream output, byte[] chunk) throws IOException {
    output.write(Integer.toHexString(chunk.length).getBytes(StandardCharsets.UTF_8));
    output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    output.write(chunk);
    output.write("\r\n".getBytes(StandardCharsets.UTF_8));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "text/plain", "application/json", "application/x-www-form-urlencoded"})
  void preservesNonmultipartBodyBytesWhenFileAccessorsAreCalledFirst(String contentType)
      throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close) {
        app.routes()
            .post(
                "/files",
                (request, response) -> {
                  assertNoUploads(request);

                  response.body("application/octet-stream", request.bodyBytes());
                });

        try (var test = TestServer.start(app)) {

          var result =
              sendResultForPreservesNonmultipartBodyBytesWhenFileAccessorsAreCalledFirst(
                  test, contentType);

          assertEquals(200, result.statusCode());
          assertArrayEquals(BINARY_CONTENT, result.body());
        }
      }
    }
  }

  private HttpResponse<byte[]>
      sendResultForPreservesNonmultipartBodyBytesWhenFileAccessorsAreCalledFirst(
          TestServer test, String contentType) throws IOException, InterruptedException {
    return test.send(
        request -> {
          request.path("/files").timeout(Duration.ofSeconds(3)).method("POST").body(BINARY_CONTENT);
          if (!contentType.isEmpty()) {
            request.header("Content-Type", contentType);
          }
        });
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "text/plain", "application/json", "application/x-www-form-urlencoded"})
  void allowsNonmultipartFileAccessAfterBufferedBodyConsumption(String contentType)
      throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup1 = app::close) {
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
              sendResultForAllowsNonmultipartFileAccessAfterBufferedBodyConsumption(
                  test, contentType);

          assertEquals(200, result.statusCode());
          assertArrayEquals(BINARY_CONTENT, result.body());
        }
      }
    }
  }

  private HttpResponse<byte[]>
      sendResultForAllowsNonmultipartFileAccessAfterBufferedBodyConsumption(
          TestServer test, String contentType) throws IOException, InterruptedException {
    return test.send(
        request -> {
          request.path("/files").timeout(Duration.ofSeconds(3)).method("POST").body(BINARY_CONTENT);
          if (!contentType.isEmpty()) {
            request.header("Content-Type", contentType);
          }
        });
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void preservesNonmultipartInputRegardlessOfFileAccessOrder(boolean filesFirst) throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup2 = app::close) {
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
          var result = sendResultForPreservesNonmultipartInputRegardlessOfFileAccessOrder(test);

          assertEquals(200, result.statusCode());
          assertArrayEquals(BINARY_CONTENT, result.body());
        }
      }
    }
  }

  private HttpResponse<byte[]> sendResultForPreservesNonmultipartInputRegardlessOfFileAccessOrder(
      TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/files")
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/octet-stream")
                .method("POST")
                .body(BINARY_CONTENT));
  }

  @Test
  void returnsNoUploadsForBodylessRequestWithoutContentType() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup3 = app::close) {
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
  }

  @Test
  void preservesNonmultipartUtf8TextAfterFileLookup() throws Exception {
    var content = "café + %23\n";

    {
      var app = new Shoostr();

      try (Closeable appCleanup4 = app::close) {
        app.routes()
            .post(
                "/files",
                (request, response) -> {
                  assertNoUploads(request);

                  response.text(request.bodyText());
                });

        try (var test = TestServer.start(app)) {
          var result = sendResultForPreservesNonmultipartUtf8TextAfterFileLookup(test, content);

          assertEquals(200, result.statusCode());
          assertEquals(content, result.body());
        }
      }
    }
  }

  private HttpResponse<String> sendResultForPreservesNonmultipartUtf8TextAfterFileLookup(
      TestServer test, String content) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/files")
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "text/plain")
                .method("POST")
                .body(content.getBytes(StandardCharsets.UTF_8)),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  @Test
  void keepsMultipartWithoutFilesExclusiveFromRawBodyAccess() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup5 = app::close) {
        app.routes()
            .post(
                "/files",
                (request, response) -> {
                  assertNoUploads(request);
                  assertThrows(IllegalStateException.class, request::bodyBytes);

                  response.text(request.formParam("document").orElseThrow());
                });

        try (var test = TestServer.start(app)) {
          var result = sendResultForKeepsMultipartWithoutFilesExclusiveFromRawBodyAccess(test);

          assertEquals(200, result.statusCode());
          assertEquals("contents", result.body());
        }
      }
    }
  }

  private HttpResponse<String> sendResultForKeepsMultipartWithoutFilesExclusiveFromRawBodyAccess(
      TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/files")
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method("POST")
                .body(partBody("Content-Disposition: form-data; name=\"document\"")),
        HttpResponse.BodyHandlers.ofString());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "multipart/form-data",
        "multipart/form-data; boundary=" + BOUNDARY,
        "multipart/form-data; boundary=\"unterminated"
      })
  void rejectsDeclaredMalformedMultipartDuringFileLookup(String contentType) throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup6 = app::close) {
        app.routes()
            .post(
                "/files",
                (request, response) -> response.text(Integer.toString(request.files().size())));

        try (var test = TestServer.start(app)) {
          var result =
              sendResultForRejectsDeclaredMalformedMultipartDuringFileLookup(test, contentType);

          assertEquals(400, result.statusCode());
          assertEquals("Bad Request", result.body());
        }
      }
    }
  }

  private HttpResponse<String> sendResultForRejectsDeclaredMalformedMultipartDuringFileLookup(
      TestServer test, String contentType) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/files")
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", contentType)
                .method("POST")
                .body(malformedBody()),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void sendsContinueBeforeReceivingAndPreservingBinaryMultipartUpload() throws Exception {
    var body = binaryBody();

    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup7 = app::close) {
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
  }

  @Test
  void preservesLargeUtf8MultipartFieldAsTextAndCleansItsTemporaryStorage() throws Exception {
    var content = "é".repeat(95 * 1024);
    var directory = Files.createDirectory(temporaryDirectory.resolve("large-text"));
    var multipart = new MultipartOptions(300_000, 250_000, 2, 8192, 1024, directory);
    var completed = new CompletableFuture<Void>();
    var body =
        createBodyForPreservesLargeUtf8MultipartFieldAsTextAndCleansItsTemporaryStorage(content);

    var configuredOptions = new Options();
    configuredOptions.setMultipart(multipart);

    {
      var app = new Shoostr(configuredOptions);

      try (Closeable appCleanup8 = app::close) {
        app.afterRequest(ignored -> completed.complete(null));
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
              sendResultForPreservesLargeUtf8MultipartFieldAsTextAndCleansItsTemporaryStorage(
                  test, body);

          assertEquals(200, result.statusCode());
          assertArrayEquals(content.getBytes(StandardCharsets.UTF_8), result.body());

          completed.get(3, TimeUnit.SECONDS);
          awaitFile(directory, false);
        }
      }
    }
  }

  private byte[] createBodyForPreservesLargeUtf8MultipartFieldAsTextAndCleansItsTemporaryStorage(
      String content) {
    return ("--"
            + BOUNDARY
            + "\r\nContent-Disposition: form-data; name=\"document\"\r\n\r\n"
            + content
            + "\r\n--"
            + BOUNDARY
            + "--\r\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  private HttpResponse<byte[]>
      sendResultForPreservesLargeUtf8MultipartFieldAsTextAndCleansItsTemporaryStorage(
          TestServer test, byte[] body) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/upload")
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method("POST")
                .body(body));
  }

  @Test
  void preservesSameSizedNamedMultipartPartAsFileAndCleansItsTemporaryStorage() throws Exception {
    var content = "é".repeat(95 * 1024);
    var directory = Files.createDirectory(temporaryDirectory.resolve("large-file"));
    var multipart = new MultipartOptions(300_000, 250_000, 2, 8192, 1024, directory);
    var completed = new CompletableFuture<Void>();

    var configuredOptions = new Options();
    configuredOptions.setMultipart(multipart);

    {
      var app = new Shoostr(configuredOptions);

      try (Closeable appCleanup9 = app::close) {
        app.afterRequest(ignoredRequestOutcome -> completed.complete(null));
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
              sendResultForPreservesSameSizedNamedMultipartPartAsFileAndCleansItsTemporaryStorage(
                  test, content);

          assertEquals(200, result.statusCode());
          assertArrayEquals(content.getBytes(StandardCharsets.UTF_8), result.body());

          completed.get(3, TimeUnit.SECONDS);
          awaitFile(directory, false);
        }
      }
    }
  }

  private HttpResponse<byte[]>
      sendResultForPreservesSameSizedNamedMultipartPartAsFileAndCleansItsTemporaryStorage(
          TestServer test, String content) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/upload")
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method("POST")
                .body(fileBody(content)));
  }

  @Test
  void readsMultipartTextAndFileFields() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup10 = app::close) {
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
          var result = sendResultForReadsMultipartTextAndFileFields(test);

          assertEquals(200, result.statusCode());
          assertEquals("report|document|report.txt|contents", result.body());
        }
      }
    }
  }

  private HttpResponse<String> sendResultForReadsMultipartTextAndFileFields(TestServer test)
      throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/upload")
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method("POST")
                .body(body()),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void preservesBinaryUploadContent() throws Exception {
    var body = binaryBody();

    {
      var app = new Shoostr();

      try (Closeable appCleanup11 = app::close) {
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
          var result = sendResultForPreservesBinaryUploadContent(test, body);

          assertEquals(200, result.statusCode());
          assertEquals("binary", result.body());
        }
      }
    }
  }

  private HttpResponse<String> sendResultForPreservesBinaryUploadContent(
      TestServer test, byte[] body) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/upload")
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method("POST")
                .body(body),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void spillsOnlyUploadsAboveTheMemoryThresholdToTheConfiguredDirectory() throws Exception {
    var directory = Files.createTempDirectory(temporaryDirectory, "multipart-threshold-test");
    var multipart = new MultipartOptions(1000, 1000, 100, 8192, 8, directory);

    var configuredOptions = new Options();
    configuredOptions.setMultipart(multipart);

    try {
      var app = new Shoostr(configuredOptions);

      try (Closeable appCleanup12 = app::close) {
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
          var inMemory = sendInMemoryRequestForUploadSpill(test);

          assertEquals(200, inMemory.statusCode());
          assertEquals("0", inMemory.body());

          awaitFile(directory, false);
          var onDisk = sendOnDiskRequestForUploadSpill(test);

          assertEquals(200, onDisk.statusCode());
          assertEquals("1", onDisk.body());

          awaitFile(directory, false);
        }
      }
    } finally {
      Files.deleteIfExists(directory);
    }
  }

  private HttpResponse<String> sendInMemoryRequestForUploadSpill(TestServer test)
      throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/upload")
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method("POST")
                .body(fileBody("12345678")),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> sendOnDiskRequestForUploadSpill(TestServer test)
      throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/upload")
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method("POST")
                .body(fileBody("123456789")),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void treatsHostileFilenamesAsMetadataInsteadOfDestinationPaths() throws Exception {
    var directory = Files.createTempDirectory(temporaryDirectory, "multipart-filename-test");
    var destination = directory.resolve("saved.bin");
    var multipart = new MultipartOptions(1000, 1000, 100, 8192, 0, directory);

    var configuredOptions = new Options();
    configuredOptions.setMultipart(multipart);

    try {
      var app = new Shoostr(configuredOptions);

      try (Closeable appCleanup13 = app::close) {
        app.routes()
            .post(
                "/upload",
                (request, response) -> {
                  var upload = request.file("document").orElseThrow();
                  upload.persistTo(destination);
                  response.text(upload.fileName());
                });

        try (var test = TestServer.start(app)) {
          var result = sendResultForTreatsHostileFilenamesAsMetadataInsteadOfDestinationPaths(test);

          assertEquals(200, result.statusCode());
          assertEquals("../../outside.txt", result.body());
          assertEquals("contents", Files.readString(destination));

          try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
          }
        }
      }
    } finally {
      Files.deleteIfExists(destination);
      Files.deleteIfExists(directory);
    }
  }

  private HttpResponse<String>
      sendResultForTreatsHostileFilenamesAsMetadataInsteadOfDestinationPaths(TestServer test)
          throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/upload")
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method("POST")
                .body(fileBody("../../outside.txt", "contents")),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void rejectsMultipartAccessAfterRawBodyAccess() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup14 = app::close) {
        app.routes()
            .post(
                "/upload",
                (request, response) -> {
                  request.bodyBytes();

                  assertThrows(IllegalStateException.class, () -> request.file("document"));

                  response.text("exclusive");
                });

        try (var test = TestServer.start(app)) {
          var result = sendResultForRejectsMultipartAccessAfterRawBodyAccess(test);

          assertEquals(200, result.statusCode());
          assertEquals("exclusive", result.body());
        }
      }
    }
  }

  private HttpResponse<String> sendResultForRejectsMultipartAccessAfterRawBodyAccess(
      TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/upload")
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method("POST")
                .body(body()),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void rejectsRawBodyAccessAfterMultipartAccess() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup15 = app::close) {
        app.routes()
            .post(
                "/upload",
                (request, response) -> {
                  request.file("document");

                  assertThrows(IllegalStateException.class, request::bodyBytes);

                  response.text("exclusive");
                });

        try (var test = TestServer.start(app)) {
          var result = sendResultForRejectsRawBodyAccessAfterMultipartAccess(test);

          assertEquals(200, result.statusCode());
          assertEquals("exclusive", result.body());
        }
      }
    }
  }

  private HttpResponse<String> sendResultForRejectsRawBodyAccessAfterMultipartAccess(
      TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/upload")
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method("POST")
                .body(body()),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void preservesRepeatedMultipartTextFieldsInArrivalOrder() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup16 = app::close) {
        app.routes()
            .post(
                "/upload",
                (request, response) -> response.text(String.join(",", request.formParams("tag"))));

        try (var test = TestServer.start(app)) {
          var result = sendResultForPreservesRepeatedMultipartTextFieldsInArrivalOrder(test);

          assertEquals(200, result.statusCode());
          assertEquals("first,second", result.body());
        }
      }
    }
  }

  private HttpResponse<String> sendResultForPreservesRepeatedMultipartTextFieldsInArrivalOrder(
      TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/upload")
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method("POST")
                .body(repeatedTextBody()),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void persistsAnUploadBeyondRequestCompletion() throws Exception {
    var directory = Files.createTempDirectory(temporaryDirectory, "multipart-request-test");
    var destination = directory.resolve("saved.txt");
    var multipart = new MultipartOptions(1000, 1000, 100, 8192, 0, directory);

    var configuredOptions = new Options();
    configuredOptions.setMultipart(multipart);

    {
      var app = new Shoostr(configuredOptions);

      try (Closeable appCleanup17 = app::close) {
        app.routes()
            .post(
                "/upload",
                (request, response) -> {
                  request.file("document").orElseThrow().persistTo(destination);
                  response.text("saved");
                });

        try (var test = TestServer.start(app)) {
          var result = sendResultForPersistsAnUploadBeyondRequestCompletion(test);

          assertEquals(200, result.statusCode());
          assertEquals("saved", result.body());
        }
      }
    }

    try {
      assertEquals("contents", Files.readString(destination));
    } finally {
      Files.deleteIfExists(destination);
      Files.deleteIfExists(directory);
    }
  }

  private HttpResponse<String> sendResultForPersistsAnUploadBeyondRequestCompletion(TestServer test)
      throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/upload")
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method("POST")
                .body(fileBody("contents")),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void rejectsReadingRetainedUploadContentAfterHandlerCompletion() throws Exception {
    var retained = new AtomicReference<InputStream>();

    {
      var app = new Shoostr();

      try (Closeable appCleanup18 = app::close) {
        app.routes()
            .post(
                "/upload",
                (request, response) -> {
                  retained.set(request.file("document").orElseThrow().content());
                  response.text("ready");
                });

        try (var test = TestServer.start(app)) {
          var result = sendResultForRejectsReadingRetainedUploadContentAfterHandlerCompletion(test);

          assertEquals(200, result.statusCode());
          assertThrows(IllegalStateException.class, retained.get()::read);
        }
      }
    }
  }

  private HttpResponse<String>
      sendResultForRejectsReadingRetainedUploadContentAfterHandlerCompletion(TestServer test)
          throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/upload")
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method("POST")
                .body(body()),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void rejectsSkippingUploadContentOutsideItsHandlerLifetime() throws Exception {
    var retained = new AtomicReference<InputStream>();

    {
      var app = new Shoostr();

      try (Closeable appCleanup19 = app::close) {
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
          var result = sendResultForRejectsSkippingUploadContentOutsideItsHandlerLifetime(test);

          assertEquals(200, result.statusCode());
          assertEquals("ready", result.body());

          var closedInput = retained.get();

          assertThrows(IllegalStateException.class, () -> closedInput.skip(1));
          assertThrows(IllegalStateException.class, () -> closedInput.skipNBytes(1));
        }
      }
    }
  }

  private HttpResponse<String> sendResultForRejectsSkippingUploadContentOutsideItsHandlerLifetime(
      TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/upload")
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method("POST")
                .body(body()),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void rejectsMultipartBodiesExceedingTheAggregateLimit() throws Exception {
    var multipart = new MultipartOptions(100, 100, 100, 8192, 16_384, null);

    var configuredOptions = new Options();
    configuredOptions.setMultipart(multipart);

    {
      var app = new Shoostr(configuredOptions);

      try (Closeable appCleanup20 = app::close) {
        app.routes()
            .post(
                "/upload",
                (request, response) -> response.text(request.formParam("title").orElseThrow()));

        try (var test = TestServer.start(app)) {
          var result = sendResultForRejectsMultipartBodiesExceedingTheAggregateLimit(test);

          assertEquals(413, result.statusCode());
          assertEquals("Content Too Large", result.body());
        }
      }
    }
  }

  private HttpResponse<String> sendResultForRejectsMultipartBodiesExceedingTheAggregateLimit(
      TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/upload")
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method("POST")
                .body(body()),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void acceptsFixedLengthMultipartBodiesWithinTheMultipartAggregateLimit() throws Exception {
    var content = "x".repeat(2_000_000);

    {
      var app = new Shoostr();

      try (Closeable appCleanup21 = app::close) {
        app.routes()
            .post(
                "/upload",
                (request, response) ->
                    response.text(Long.toString(request.file("document").orElseThrow().size())));

        try (var test = TestServer.start(app)) {
          var result =
              sendResultForAcceptsFixedLengthMultipartBodiesWithinTheMultipartAggregateLimit(
                  test, content);

          assertEquals(200, result.statusCode());
          assertEquals("2000000", result.body());
        }
      }
    }
  }

  private HttpResponse<String>
      sendResultForAcceptsFixedLengthMultipartBodiesWithinTheMultipartAggregateLimit(
          TestServer test, String content) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/upload")
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method("POST")
                .body(fileBody(content)),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void rejectsChunkedMultipartBodiesExceedingTheAggregateLimit() throws Exception {
    var multipart = new MultipartOptions(100, 100, 100, 8192, 16_384, null);

    var configuredOptions = new Options();
    configuredOptions.setMultipart(multipart);

    {
      var app =
          new Shoostr(configuredOptions)
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup22 = app::close;
          var client = HttpClient.newHttpClient()) {
        app.routes()
            .post(
                "/upload",
                (request, response) -> response.text(request.formParam("title").orElseThrow()));
        app.start();
        var result =
            sendResultForRejectsChunkedMultipartBodiesExceedingTheAggregateLimit(client, app);

        assertEquals(413, result.statusCode());
        assertEquals("Content Too Large", result.body());
      }
    }
  }

  private HttpResponse<String> sendResultForRejectsChunkedMultipartBodiesExceedingTheAggregateLimit(
      HttpClient client, Shoostr app) throws IOException, InterruptedException {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/upload"))
            .timeout(Duration.ofSeconds(3))
            .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
            .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body())))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void cleansUpAndRepeatsLimitRejectionsWithoutPublishingPartialParts() throws Exception {
    var directory = Files.createTempDirectory(temporaryDirectory, "multipart-limit-test");
    var multipart = new MultipartOptions(100, 100, 100, 8192, 0, directory);

    var configuredOptions = new Options();
    configuredOptions.setMultipart(multipart);

    try {
      var app =
          new Shoostr(configuredOptions)
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup23 = app::close;
          var client = HttpClient.newHttpClient()) {
        app.routes()
            .post(
                "/upload",
                (request, response) -> {
                  assertThrows(ContentTooLargeException.class, request::files);
                  assertThrows(ContentTooLargeException.class, request::formParamMap);

                  response.text("rejected");
                });
        app.start();
        var result =
            sendResultForCleansUpAndRepeatsLimitRejectionsWithoutPublishingPartialParts(
                client, app);

        assertEquals(200, result.statusCode());
        assertEquals("rejected", result.body());

        awaitFile(directory, false);
      }
    } finally {
      Files.deleteIfExists(directory);
    }
  }

  private HttpResponse<String>
      sendResultForCleansUpAndRepeatsLimitRejectionsWithoutPublishingPartialParts(
          HttpClient client, Shoostr app) throws IOException, InterruptedException {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/upload"))
            .timeout(Duration.ofSeconds(3))
            .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
            .POST(
                HttpRequest.BodyPublishers.ofInputStream(
                    () -> new ByteArrayInputStream(fileBody("x".repeat(200)))))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void cleansUpTemporaryPartsCreatedBeforeChunkedLimitRejection() throws Exception {
    var directory = Files.createTempDirectory(temporaryDirectory, "multipart-chunked-limit-test");
    var multipart = new MultipartOptions(1000, 1000, 100, 8192, 0, directory);

    var configuredOptions = new Options();
    configuredOptions.setMultipart(multipart);

    try {
      var app =
          new Shoostr(configuredOptions)
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup24 = app::close) {
        app.routes()
            .post(
                "/upload",
                (request, response) -> {
                  assertThrows(ContentTooLargeException.class, request::files);
                  assertThrows(ContentTooLargeException.class, request::formParamMap);

                  response.text("rejected");
                });
        app.start();

        try (var socket = new Socket(InetAddress.getAllByName("127.0.0.1")[0], app.port())) {
          socket.setSoTimeout(3000);
          var output = socket.getOutputStream();
          output.write(
              ("POST /upload HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                      + "Connection: close\r\nTransfer-Encoding: chunked\r\n"
                      + "Content-Type: multipart/form-data; boundary="
                      + BOUNDARY
                      + "\r\n\r\n")
                  .getBytes(StandardCharsets.UTF_8));
          writeChunk(
              output,
              ("--"
                      + BOUNDARY
                      + "\r\nContent-Disposition: form-data; name=\"document\";"
                      + " filename=\"report.txt\"\r\n\r\n"
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
      }
    } finally {
      Files.deleteIfExists(directory);
    }
  }

  @Test
  void rejectsFilesExceedingThePerFileLimit() throws Exception {
    var multipart = new MultipartOptions(1000, 3, 100, 8192, 16_384, null);

    var configuredOptions = new Options();
    configuredOptions.setMultipart(multipart);

    {
      var app = new Shoostr(configuredOptions);

      try (Closeable appCleanup25 = app::close) {
        app.routes()
            .post(
                "/upload",
                (request, response) ->
                    response.text(request.file("document").orElseThrow().fileName()));

        try (var test = TestServer.start(app)) {
          var result = sendResultForRejectsFilesExceedingThePerFileLimit(test);

          assertEquals(413, result.statusCode());
          assertEquals("Content Too Large", result.body());
        }
      }
    }
  }

  private HttpResponse<String> sendResultForRejectsFilesExceedingThePerFileLimit(TestServer test)
      throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/upload")
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method("POST")
                .body(fileBody("contents")),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void rejectsMultipartRequestsExceedingThePartLimit() throws Exception {
    var multipart = new MultipartOptions(1000, 1000, 1, 8192, 16_384, null);

    var configuredOptions = new Options();
    configuredOptions.setMultipart(multipart);

    {
      var app = new Shoostr(configuredOptions);

      try (Closeable appCleanup26 = app::close) {
        app.routes()
            .post(
                "/upload",
                (request, response) -> response.text(request.files("document").toString()));

        try (var test = TestServer.start(app)) {
          var result = sendResultForRejectsMultipartRequestsExceedingThePartLimit(test);

          assertEquals(413, result.statusCode());
          assertEquals("Content Too Large", result.body());
        }
      }
    }
  }

  private HttpResponse<String> sendResultForRejectsMultipartRequestsExceedingThePartLimit(
      TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/upload")
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method("POST")
                .body(repeatedFilesBody()),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void rejectsMultipartPartsExceedingTheHeaderLimit() throws Exception {
    var multipart = new MultipartOptions(1000, 1000, 100, 10, 16_384, null);

    var configuredOptions = new Options();
    configuredOptions.setMultipart(multipart);

    {
      var app = new Shoostr(configuredOptions);

      try (Closeable appCleanup27 = app::close) {
        app.routes()
            .post(
                "/upload",
                (request, response) ->
                    response.text(request.file("document").orElseThrow().fileName()));

        try (var test = TestServer.start(app)) {
          var result = sendResultForRejectsMultipartPartsExceedingTheHeaderLimit(test);

          assertEquals(413, result.statusCode());
          assertEquals("Content Too Large", result.body());
        }
      }
    }
  }

  private HttpResponse<String> sendResultForRejectsMultipartPartsExceedingTheHeaderLimit(
      TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/upload")
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method("POST")
                .body(body()),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void rejectsMultipartRequestsWithoutBoundary() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup28 = app::close) {
        app.routes()
            .post(
                "/upload",
                (request, response) -> response.text(request.formParam("title").orElseThrow()));

        try (var test = TestServer.start(app)) {
          var result = sendResultForRejectsMultipartRequestsWithoutBoundary(test);

          assertEquals(400, result.statusCode());
          assertEquals("Bad Request", result.body());
        }
      }
    }
  }

  private HttpResponse<String> sendResultForRejectsMultipartRequestsWithoutBoundary(TestServer test)
      throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/upload")
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "multipart/form-data")
                .method("POST")
                .body(body()),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void rejectsMultipartTextFieldsWithNonUtf8Charset() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup29 = app::close) {
        app.routes()
            .post(
                "/upload",
                (request, response) -> response.text(request.formParam("title").orElseThrow()));

        try (var test = TestServer.start(app)) {
          var result = sendResultForRejectsMultipartTextFieldsWithNonUtf8Charset(test);

          assertEquals(415, result.statusCode());
          assertEquals("Unsupported Media Type", result.body());
        }
      }
    }
  }

  private HttpResponse<String> sendResultForRejectsMultipartTextFieldsWithNonUtf8Charset(
      TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/upload")
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method("POST")
                .body(nonUtf8TextFieldBody()),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void rejectsEveryAccessorAfterMultipartMetadataValidationFails() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup30 = app::close) {
        app.routes()
            .post(
                "/upload",
                (request, response) -> {
                  assertThrows(BadRequestException.class, request::files);
                  assertThrows(BadRequestException.class, request::formParamMap);

                  response.text("rejected");
                });

        try (var test = TestServer.start(app)) {
          var result = sendResultForRejectsEveryAccessorAfterMultipartMetadataValidationFails(test);

          assertEquals(200, result.statusCode());
          assertEquals("rejected", result.body());
        }
      }
    }
  }

  private HttpResponse<String>
      sendResultForRejectsEveryAccessorAfterMultipartMetadataValidationFails(TestServer test)
          throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/upload")
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method("POST")
                .body(invalidMetadataBody()),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void rejectsEachRequiredMultipartMetadataBranch() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup31 = app::close) {
        app.routes()
            .post(
                "/upload",
                (request, response) -> {
                  assertThrows(BadRequestException.class, request::files);
                  assertThrows(BadRequestException.class, request::formParamMap);

                  response.text("rejected");
                });

        try (var test = TestServer.start(app)) {

          for (var body :
              List.of(wrongDispositionBody(), missingDispositionBody(), missingNameBody())) {
            var result = sendResultForRejectsEachRequiredMultipartMetadataBranch(test, body);

            assertEquals(200, result.statusCode());
            assertEquals("rejected", result.body());
          }
        }
      }
    }
  }

  private HttpResponse<String> sendResultForRejectsEachRequiredMultipartMetadataBranch(
      TestServer test, byte[] body) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/upload")
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method("POST")
                .body(body),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void deletesTemporaryUploadsAfterMalformedMultipartInput() throws Exception {
    var directory = Files.createTempDirectory(temporaryDirectory, "multipart-cleanup-test");
    var multipart = new MultipartOptions(1000, 1000, 100, 8192, 0, directory);

    var configuredOptions = new Options();
    configuredOptions.setMultipart(multipart);

    {
      var app = new Shoostr(configuredOptions);

      try (Closeable appCleanup32 = app::close) {
        app.routes()
            .post(
                "/upload",
                (request, response) ->
                    response.text(request.file("document").orElseThrow().fileName()));

        try (var test = TestServer.start(app)) {
          var result = sendResultForDeletesTemporaryUploadsAfterMalformedMultipartInput(test);

          assertEquals(400, result.statusCode());
          assertEquals("Bad Request", result.body());

          awaitFile(directory, false);
        }
      }
    }

    try {
      awaitFile(directory, false);
    } finally {
      Files.deleteIfExists(directory);
    }
  }

  private HttpResponse<String> sendResultForDeletesTemporaryUploadsAfterMalformedMultipartInput(
      TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/upload")
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method("POST")
                .body(malformedBody()),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void deletesTemporaryUploadsAfterHandlerFailure() throws Exception {
    var directory = Files.createTempDirectory(temporaryDirectory, "multipart-cleanup-test");
    var multipart = new MultipartOptions(1000, 1000, 100, 8192, 0, directory);
    var firstByte = new AtomicInteger(-1);

    var configuredOptions = new Options();
    configuredOptions.setMultipart(multipart);

    {
      var app = new Shoostr(configuredOptions);

      try (Closeable appCleanup33 = app::close) {
        app.routes()
            .post(
                "/upload",
                (request, ignoredResponse) -> {
                  var content = request.file("document").orElseThrow().content();
                  firstByte.set(content.read());
                  throw new IllegalStateException("expected failure");
                });

        try (var test = TestServer.start(app)) {
          var result = sendResultForDeletesTemporaryUploadsAfterHandlerFailure(test);

          assertEquals(500, result.statusCode());
          assertEquals("Internal Server Error", result.body());
          assertEquals('c', firstByte.get());

          awaitFile(directory, false);
        }
      }
    }

    try {
      awaitFile(directory, false);
    } finally {
      Files.deleteIfExists(directory);
    }
  }

  private HttpResponse<String> sendResultForDeletesTemporaryUploadsAfterHandlerFailure(
      TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/upload")
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method("POST")
                .body(body()),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void deletesTemporaryUploadsAfterSuccessfulResponse() throws Exception {
    var directory = Files.createTempDirectory(temporaryDirectory, "multipart-cleanup-test");
    var multipart = new MultipartOptions(1000, 1000, 100, 8192, 0, directory);

    var configuredOptions = new Options();
    configuredOptions.setMultipart(multipart);

    {
      var app = new Shoostr(configuredOptions);

      try (Closeable appCleanup34 = app::close) {
        app.routes()
            .post(
                "/upload",
                (request, response) ->
                    response.text(request.file("document").orElseThrow().fileName()));

        try (var test = TestServer.start(app)) {
          var result = sendResultForDeletesTemporaryUploadsAfterSuccessfulResponse(test);

          assertEquals(200, result.statusCode());
          assertEquals("report.txt", result.body());

          awaitFile(directory, false);
        }
      }
    }

    try {
      awaitFile(directory, false);
    } finally {
      Files.deleteIfExists(directory);
    }
  }

  private HttpResponse<String> sendResultForDeletesTemporaryUploadsAfterSuccessfulResponse(
      TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/upload")
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method("POST")
                .body(body()),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void reportsServerErrorsWhenTemporaryUploadStorageCannotBeCreated() throws Exception {
    var storage = Files.createTempFile(temporaryDirectory, "multipart-storage-test", ".tmp");
    var multipart = new MultipartOptions(1000, 1000, 100, 8192, 0, storage);

    var configuredOptions = new Options();
    configuredOptions.setMultipart(multipart);

    try {
      var app = new Shoostr(configuredOptions);

      try (Closeable appCleanup35 = app::close) {
        app.routes()
            .post(
                "/upload",
                (request, response) ->
                    response.text(request.file("document").orElseThrow().fileName()));

        try (var test = TestServer.start(app)) {
          var result =
              sendResultForReportsServerErrorsWhenTemporaryUploadStorageCannotBeCreated(test);

          assertEquals(500, result.statusCode());
          assertEquals("Internal Server Error", result.body());
        }
      }
    } finally {
      Files.deleteIfExists(storage);
    }
  }

  private HttpResponse<String>
      sendResultForReportsServerErrorsWhenTemporaryUploadStorageCannotBeCreated(TestServer test)
          throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/upload")
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method("POST")
                .body(body()),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void deletesTemporaryUploadsAfterClientDisconnect() throws Exception {
    var directory = Files.createTempDirectory(temporaryDirectory, "multipart-cleanup-test");
    var multipart = new MultipartOptions(1000, 1000, 100, 8192, 0, directory);

    var configuredOptions = new Options();
    configuredOptions.setMultipart(multipart);

    try {
      var app =
          new Shoostr(configuredOptions)
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup36 = app::close) {
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
                  + "\r\nContent-Disposition: form-data; name=\"document\";"
                  + " filename=\"report.txt\"\r\n\r\ncontents";
          var request =
              "POST /upload HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                  + "Content-Type: multipart/form-data; boundary="
                  + BOUNDARY
                  + "\r\nContent-Length: 1000\r\n\r\n"
                  + prefix;
          socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
          socket.getOutputStream().flush();
          awaitFile(directory, true);
        }

        awaitFile(directory, false);
      }
    } finally {
      Files.deleteIfExists(directory);
    }
  }

  @Test
  void abortingOneSpilledUploadPreservesAnotherUntilItsHandlerCompletes() throws Exception {
    for (var repeat = 0; repeat < 2; repeat++) {
      var directory = Files.createDirectory(temporaryDirectory.resolve("overlap-" + repeat));
      var multipart = new MultipartOptions(32_768, 16_384, 2, 8192, 128, directory);
      var abandonedPublished = new AtomicBoolean();
      var abandonedCompleted = new CompletableFuture<Void>();
      var survivorCompleted = new CompletableFuture<Void>();
      var survivorParsed = new CountDownLatch(1);
      var releaseSurvivor = new CountDownLatch(1);
      var abandonedBody = fileBody("abandoned.txt", "A".repeat(8192));
      var survivorContent = "survivor".repeat(1024).getBytes(StandardCharsets.US_ASCII);
      var survivorBody = fileBody("survivor.txt", "survivor".repeat(1024));
      var closingBoundary = ("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII);

      var configuredOptions = new Options();
      configuredOptions.setMultipart(multipart);

      {
        var app = new Shoostr(configuredOptions);

        try (Closeable appCleanup37 = app::close) {
          app.afterRequest(
              outcome -> {
                if ("/abandon".equals(outcome.routePattern())) {
                  abandonedCompleted.complete(null);
                } else if ("/survive".equals(outcome.routePattern())) {
                  survivorCompleted.complete(null);
                }
              });
          app.routes()
              .post(
                  "/abandon",
                  (request, response) -> {
                    request.files();
                    abandonedPublished.set(true);
                    response.text("unexpected publication");
                  });
          app.routes()
              .post(
                  "/survive",
                  (request, response) -> {
                    var upload = request.file("document").orElseThrow();
                    survivorParsed.countDown();

                    assertTrue(releaseSurvivor.await(5, TimeUnit.SECONDS));

                    try (var content = upload.content()) {
                      response
                          .setHeader("X-Upload-Name", upload.fileName())
                          .body("application/octet-stream", content.readAllBytes());
                    }
                  });
          app.routes().get("/healthy", (ignoredRequest, response) -> response.text("healthy"));

          try (var test = TestServer.start(app)) {
            try (var abandoned = new Socket();
                var survivor = new Socket()) {
              try {
                sendMultipartPrefix(
                    abandoned, app.port(), "/abandon", abandonedBody, closingBoundary.length);
                var abandonedSpool = awaitSpoolFiles(directory, 1).getFirst();
                sendMultipartPrefix(
                    survivor, app.port(), "/survive", survivorBody, closingBoundary.length);
                final var survivorSpool =
                    awaitSpoolFiles(directory, 2).stream()
                        .filter(path -> !path.equals(abandonedSpool))
                        .findFirst()
                        .orElseThrow();
                survivor.getOutputStream().write(closingBoundary);
                survivor.getOutputStream().flush();

                assertTrue(survivorParsed.await(3, TimeUnit.SECONDS));

                abandoned.close();
                abandonedCompleted.get(3, TimeUnit.SECONDS);
                await()
                    .atMost(Duration.ofSeconds(3))
                    .untilAsserted(() -> assertFalse(Files.exists(abandonedSpool)));

                assertFalse(abandonedPublished.get());
                assertEquals(List.of(survivorSpool), awaitSpoolFiles(directory, 1));
                assertTrue(Files.exists(survivorSpool));
                assertFalse(survivorCompleted.isDone());

                releaseSurvivor.countDown();
                var response = HttpTester.parseResponse(survivor.getInputStream());

                assertNotNull(response);
                assertEquals(200, response.getStatus());
                assertEquals("survivor.txt", response.get("X-Upload-Name"));
                assertArrayEquals(survivorContent, response.getContentBytes());

                survivorCompleted.get(3, TimeUnit.SECONDS);
                awaitFile(directory, false);
              } finally {
                releaseSurvivor.countDown();
              }
            }

            var healthy =
                test.send(
                    request -> request.path("/healthy").timeout(Duration.ofSeconds(1)),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, healthy.statusCode());
            assertEquals("healthy", healthy.body());
          }
        }
      }
    }
  }

  @Test
  void retainsRepeatedUploadsInArrivalOrder() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup38 = app::close) {
        app.routes()
            .post(
                "/upload",
                (request, response) ->
                    response.text(
                        request.files("document").stream()
                            .map(Upload::fileName)
                            .collect(Collectors.joining(","))));

        try (var test = TestServer.start(app)) {
          var result = sendResultForRetainsRepeatedUploadsInArrivalOrder(test);

          assertEquals(200, result.statusCode());
          assertEquals("first.txt,second.txt", result.body());
        }
      }
    }
  }

  private HttpResponse<String> sendResultForRetainsRepeatedUploadsInArrivalOrder(TestServer test)
      throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/upload")
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method("POST")
                .body(repeatedFilesBody()),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void treatsAnEmptyFilenameAsAnUpload() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup39 = app::close) {
        app.routes()
            .post(
                "/upload",
                (request, response) ->
                    response.text("<" + request.file("document").orElseThrow().fileName() + ">"));

        try (var test = TestServer.start(app)) {
          var result = sendResultForTreatsAnEmptyFilenameAsAnUpload(test);

          assertEquals(200, result.statusCode());
          assertEquals("<>", result.body());
        }
      }
    }
  }

  private HttpResponse<String> sendResultForTreatsAnEmptyFilenameAsAnUpload(TestServer test)
      throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/upload")
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method("POST")
                .body(emptyFilenameBody()),
        HttpResponse.BodyHandlers.ofString());
  }
}
