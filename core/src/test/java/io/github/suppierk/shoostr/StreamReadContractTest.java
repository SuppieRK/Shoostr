package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.http.HttpTester;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class StreamReadContractTest {
  private static final String BOUNDARY = "stream-read-contract";
  private static final String CHUNKED_HEADERS =
      """
      POST /input HTTP/1.1\r
      Host: localhost\r
      Transfer-Encoding: chunked\r
      Connection: close\r
      \r
      """;
  @TempDir Path temporaryDirectory;

  @Test
  void returnsAnExactRequestPrefixBeforeAnotherChunkArrives() throws Exception {
    var prefix = new CompletableFuture<byte[]>();

    try (var app = new Shoostr()) {
      app.routes()
          .post(
              "/input",
              (request, response) -> {
                var input = request.input();
                prefix.complete(input.readNBytes(1));
                response.text(new String(input.readAllBytes(), StandardCharsets.US_ASCII));
              });

      try (var test = TestServer.start(app);
          var socket = new Socket()) {
        connect(socket, test);
        write(socket, CHUNKED_HEADERS + "1\r\nA\r\n");

        assertArrayEquals(new byte[] {'A'}, prefix.get(3, TimeUnit.SECONDS));

        write(socket, "1\r\nB\r\n0\r\n\r\n");
        assertResponse(socket, "B");
      }
    }
  }

  @Test
  void returnsZeroBeforeAnyRequestBodyBytesArrive() throws Exception {
    var emptyRead = new CompletableFuture<Integer>();

    try (var app = new Shoostr()) {
      app.routes()
          .post(
              "/input",
              (request, response) -> {
                var input = request.input();
                assertEquals(0, input.read(new byte[0]));
                emptyRead.complete(input.read(new byte[2], 2, 0));
                response.text(new String(input.readAllBytes(), StandardCharsets.US_ASCII));
              });

      try (var test = TestServer.start(app);
          var socket = new Socket()) {
        connect(socket, test);
        write(socket, CHUNKED_HEADERS);

        assertEquals(0, emptyRead.get(3, TimeUnit.SECONDS));

        write(socket, "2\r\nAB\r\n0\r\n\r\n");
        assertResponse(socket, "AB");
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"false, false", "true, false", "true, true"})
  void preservesContentAcrossEmptyReadsBeforeAndAfterEof(boolean multipart, boolean diskBacked)
      throws Exception {
    try (var app = application(diskBacked)) {
      app.routes()
          .post(
              "/input",
              (request, response) -> {
                try (var input = content(request, multipart)) {
                  var buffer = new byte[] {'x', 'y'};
                  assertEquals(0, input.read(new byte[0]));
                  assertEquals(0, input.read(buffer, 1, 0));
                  assertEquals(0, input.read(buffer, buffer.length, 0));
                  assertArrayEquals(new byte[] {'A'}, input.readNBytes(1));
                  assertEquals(0, input.read(buffer, 0, 0));
                  assertArrayEquals(new byte[] {'B'}, input.readAllBytes());
                  assertEquals(-1, input.read());
                  assertEquals(0, input.read(new byte[0]));
                  assertEquals(0, input.read(buffer, 1, 0));
                  assertEquals(0, input.read(buffer, buffer.length, 0));
                  assertArrayEquals(new byte[] {'x', 'y'}, buffer);
                }

                response.text("ok");
              });

      try (var test = TestServer.start(app)) {
        var result = post(test, multipart);
        assertEquals(200, result.statusCode());
        assertEquals("ok", result.body());
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"false, false", "true, false", "true, true"})
  void rejectsInvalidRangesBeforeAndAfterEofWithoutConsumingContent(
      boolean multipart, boolean diskBacked) throws Exception {
    try (var app = application(diskBacked)) {
      app.routes()
          .post(
              "/input",
              (request, response) -> {
                try (var input = content(request, multipart)) {
                  assertInvalidRanges(input);
                  assertArrayEquals(new byte[] {'A', 'B'}, input.readAllBytes());
                  assertEquals(-1, input.read());
                  assertInvalidRanges(input);
                }

                response.text("ok");
              });

      try (var test = TestServer.start(app)) {
        var result = post(test, multipart);
        assertEquals(200, result.statusCode());
        assertEquals("ok", result.body());
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"false, false", "true, false", "true, true"})
  void rejectsEmptyAndInvalidReadsAfterHandlerCompletionBeforeValidatingArguments(
      boolean multipart, boolean diskBacked) throws Exception {
    var retained = new AtomicReference<InputStream>();

    try (var app = application(diskBacked)) {
      app.routes()
          .post(
              "/input",
              (request, response) -> {
                retained.set(content(request, multipart));
                response.text("ok");
              });

      try (var test = TestServer.start(app)) {
        var result = post(test, multipart);
        assertEquals(200, result.statusCode());
        assertEquals("ok", result.body());
        var input = retained.get();
        assertNotNull(input);
        assertThrows(IllegalStateException.class, () -> input.read(new byte[0], 0, 0));
        assertThrows(IllegalStateException.class, () -> input.read(new byte[0], 1, 0));
        assertThrows(IllegalStateException.class, () -> input.read(null, 0, 0));
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"AB, 200", "ABC, 413"})
  void countsOnlyConsumedRequestBytesAcrossEmptyReads(String body, int expectedStatus)
      throws Exception {
    try (var app = new Shoostr(Options.defaults().withMaxRequestBytes(2))) {
      app.routes()
          .post(
              "/input",
              (request, response) -> {
                var input = request.input();
                assertEquals(0, input.read(new byte[0], 0, 0));
                assertArrayEquals(new byte[] {'A', 'B'}, input.readNBytes(2));
                assertEquals(0, input.read(new byte[2], 2, 0));
                assertEquals(-1, input.read());
                response.text("ok");
              });

      try (var test = TestServer.start(app);
          var client = HttpClient.newHttpClient()) {
        var bytes = body.getBytes(StandardCharsets.US_ASCII);
        var result =
            client.send(
                HttpRequest.newBuilder(test.baseUri().resolve("input"))
                    .timeout(Duration.ofSeconds(3))
                    .POST(
                        HttpRequest.BodyPublishers.ofInputStream(
                            () -> new ByteArrayInputStream(bytes)))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus, result.statusCode());
        assertEquals(expectedStatus == 200 ? "ok" : "Content Too Large", result.body());
      }
    }
  }

  private static void connect(Socket socket, TestServer test) throws IOException {
    socket.connect(
        new InetSocketAddress(InetAddress.getAllByName("127.0.0.1")[0], test.baseUri().getPort()),
        3000);
    socket.setSoTimeout(3000);
  }

  private static void write(Socket socket, String content) throws IOException {
    var output = socket.getOutputStream();
    output.write(content.getBytes(StandardCharsets.US_ASCII));
    output.flush();
  }

  private static void assertResponse(Socket socket, String expected) throws IOException {
    var result = HttpTester.parseResponse(HttpTester.from(socket.getInputStream()));
    assertNotNull(result);
    assertEquals(200, result.getStatus());
    assertEquals(expected, result.getContent());
  }

  private static InputStream content(Request request, boolean multipart) throws IOException {
    return multipart ? request.file("document").orElseThrow().content() : request.input();
  }

  private static void assertInvalidRanges(InputStream input) {
    var buffer = new byte[] {'x', 'y'};
    assertThrows(NullPointerException.class, () -> input.read(null, 0, 0));
    assertThrows(NullPointerException.class, () -> input.read(null, 0, 1));
    assertThrows(IndexOutOfBoundsException.class, () -> input.read(buffer, -1, 0));
    assertThrows(IndexOutOfBoundsException.class, () -> input.read(buffer, 3, 0));
    assertThrows(IndexOutOfBoundsException.class, () -> input.read(buffer, 2, 1));
    assertThrows(IndexOutOfBoundsException.class, () -> input.read(buffer, 0, -1));
    assertThrows(IndexOutOfBoundsException.class, () -> input.read(buffer, 1, Integer.MAX_VALUE));
    assertThrows(IndexOutOfBoundsException.class, () -> input.read(buffer, Integer.MAX_VALUE, 1));
    assertArrayEquals(new byte[] {'x', 'y'}, buffer);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void returnsZeroForAnEmptyUploadReadAfterEof(boolean diskBacked) throws Exception {
    try (var app = application(diskBacked)) {
      app.routes()
          .post(
              "/input",
              (request, response) -> {
                try (var input = request.file("document").orElseThrow().content()) {
                  assertArrayEquals(new byte[] {'A', 'B'}, input.readAllBytes());
                  assertEquals(-1, input.read());
                  response.text(Integer.toString(input.read(new byte[0], 0, 0)));
                }
              });

      try (var test = TestServer.start(app)) {
        var result = post(test, true);
        assertEquals(200, result.statusCode());
        assertEquals("0", result.body());
      }
    }
  }

  private Shoostr application(boolean diskBacked) {
    var multipart = new MultipartOptions(1024, 16, 1, 512, diskBacked ? 0 : 16, temporaryDirectory);
    return new Shoostr(Options.defaults().withMultipart(multipart));
  }

  private static HttpResponse<String> post(TestServer test, boolean multipart) throws Exception {
    var body =
        ("--"
                + BOUNDARY
                + "\r\nContent-Disposition: form-data; name=\"document\"; filename=\"bytes.bin\""
                + "\r\nContent-Type: application/octet-stream\r\n\r\nAB\r\n--"
                + BOUNDARY
                + "--\r\n")
            .getBytes(StandardCharsets.US_ASCII);
    return test.send(
        request -> {
          request
              .path("/input")
              .method("POST")
              .timeout(Duration.ofSeconds(3))
              .body(multipart ? body : new byte[] {'A', 'B'});
          if (multipart) {
            request.header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
          }
        },
        HttpResponse.BodyHandlers.ofString());
  }
}
