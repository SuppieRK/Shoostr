package io.github.suppierk.shoostr;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
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
import org.eclipse.jetty.http.MultiPartConfig;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.EagerContentHandler;
import org.eclipse.jetty.server.handler.EagerContentHandler.MultiPartContentLoaderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class StreamReadContractTest {
  private static final String BOUNDARY = "stream-read-contract";
  private static final String CHUNKED_HEADERS = chunkedHeaders();
  @TempDir Path temporaryDirectory;

  private static String chunkedHeaders() {
    return
        """
        POST /input HTTP/1.1\r
        Host: localhost\r
        Transfer-Encoding: chunked\r
        Connection: close\r
        \r
        """;
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

  private static HttpResponse<String> post(TestServer test, boolean multipart) throws Exception {
    var body = createBodyForPost();

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

  private static byte[] createBodyForPost() {
    return createBodyForPost("AB");
  }

  private static byte[] createBodyForPost(String content) {
    return ("--"
            + BOUNDARY
            + "\r\nContent-Disposition: form-data; name=\"document\"; filename=\"bytes.bin\""
            + "\r\nContent-Type: application/octet-stream\r\n\r\n"
            + content
            + "\r\n--"
            + BOUNDARY
            + "--\r\n")
        .getBytes(StandardCharsets.US_ASCII);
  }

  @Test
  void returnsAnExactRequestPrefixBeforeAnotherChunkArrives() throws Exception {
    var prefix = new CompletableFuture<byte[]>();

    {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close) {
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
  }

  @Test
  void returnsZeroBeforeAnyRequestBodyBytesArrive() throws Exception {
    var emptyRead = new CompletableFuture<Integer>();

    {
      var app = new Shoostr();

      try (Closeable appCleanup1 = app::close) {
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
  }

  @ParameterizedTest
  @CsvSource({"false, false", "true, false", "true, true"})
  void preservesContentAcrossEmptyReadsBeforeAndAfterEof(boolean multipart, boolean diskBacked)
      throws Exception {
    {
      var app = application(diskBacked);

      try (Closeable appCleanup2 = app::close) {
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
  }

  @ParameterizedTest
  @CsvSource({"false, false", "true, false", "true, true"})
  void rejectsInvalidRangesBeforeAndAfterEofWithoutConsumingContent(
      boolean multipart, boolean diskBacked) throws Exception {
    {
      var app = application(diskBacked);

      try (Closeable appCleanup3 = app::close) {
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
  }

  @ParameterizedTest
  @CsvSource({"false, false", "true, false", "true, true"})
  void rejectsEmptyAndInvalidReadsAfterHandlerCompletionBeforeValidatingArguments(
      boolean multipart, boolean diskBacked) throws Exception {
    var retained = new AtomicReference<InputStream>();

    {
      var app = application(diskBacked);

      try (Closeable appCleanup4 = app::close) {
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
  }

  @ParameterizedTest
  @CsvSource({"AB, 200", "ABC, 413"})
  void countsOnlyConsumedRequestBytesAcrossEmptyReads(String body, int expectedStatus)
      throws Exception {
    var configuredOptions = new Options();
    configuredOptions.setMaxRequestBytes(2);

    {
      var app = new Shoostr(configuredOptions);

      try (Closeable appCleanup5 = app::close) {
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
          var result = postUnknownLengthInput(client, test, bytes);

          assertEquals(expectedStatus, result.statusCode());
          assertEquals(expectedStatus == 200 ? "ok" : "Content Too Large", result.body());
        }
      }
    }
  }

  private HttpResponse<String> postUnknownLengthInput(
      HttpClient client, TestServer test, byte[] bytes) throws IOException, InterruptedException {
    return client.send(
        HttpRequest.newBuilder(test.baseUri().resolve("input"))
            .timeout(Duration.ofSeconds(3))
            .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void skipsExactlyTheAvailableBytesWithoutOverflowingOrReadingPastEof() throws Exception {
    var options = new Options();
    options.setMaxRequestBytes(16_387);
    var app = requestSkippingApplication(options);

    try (Closeable appCleanup = app::close;
        var server = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var result =
          postUnknownLengthInput(
              client, server, ("ABC" + "x".repeat(16_384)).getBytes(StandardCharsets.US_ASCII));

      assertEquals(200, result.statusCode());
      assertEquals("ok", result.body());
    }
  }

  private Shoostr requestSkippingApplication(Options options) {
    return new Shoostr(options)
        .routes(routes ->
            routes.post(
                "/input",
                (request, response) -> {
                  var input = request.input();

                  assertEquals(0L, input.skip(0));
                  assertEquals(0L, input.skip(-1));
                  assertEquals(2L, input.skip(2));
                  assertEquals('C', input.read());
                  assertEquals(16_384L, input.skip(Long.MAX_VALUE));
                  assertEquals(-1, input.read());
                  assertEquals(0L, input.skip(1));

                  response.text("ok");
                }));
  }

  @ParameterizedTest
  @CsvSource({"2,200", "3,413"})
  void countsZeroValuedBytesReadIndividuallyTowardTheRequestLimit(int size, int status)
      throws Exception {
    var options = new Options();
    options.setMaxRequestBytes(2);
    var app = zeroByteInputApplication(options);

    try (Closeable appCleanup = app::close;
        var server = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      var result = postUnknownLengthInput(client, server, new byte[size]);

      assertEquals(status, result.statusCode());
      assertEquals(status == 200 ? "2" : "Content Too Large", result.body());
    }
  }

  private Shoostr zeroByteInputApplication(Options options) {
    return new Shoostr(options)
        .routes(routes ->
            routes.post(
                "/input",
                (request, response) -> {
                  var input = request.input();
                  var count = 0;
                  while (input.read() != -1) {
                    count++;
                  }

                  response.text(Integer.toString(count));
                }));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void releasesPartiallyConsumedRequestAndUploadInputAfterCompletion(boolean multipart)
      throws Exception {
    var nativeServer = new AtomicReference<Server>();
    var app = partialInputApplication(nativeServer, multipart);

    try (Closeable appCleanup = app::close;
        var server = TestServer.start(app);
        var socket = new Socket()) {
      connect(socket, server);
      write(socket, partialInputRequest(multipart));

      assertResponse(socket, "ok");
      assertEquals(-1, socket.getInputStream().read());
      assertNativeBuffersReleased(nativeServer.get());
    }
  }

  private Shoostr partialInputApplication(AtomicReference<Server> nativeServer, boolean multipart) {
    return application(false)
        .modifyServer(nativeServer::set)
        .routes(routes ->
            routes.post(
                "/input",
                (request, response) -> {
                  var input = content(request, multipart);
                  assertEquals('A', input.read());

                  response.text("ok");
                }));
  }

  private String partialInputRequest(boolean multipart) {
    var body = multipart ? createBodyForPost() : new byte[] {'A', 'B'};

    return inputRequest(body, multipart);
  }

  private String inputRequest(byte[] body, boolean multipart) {
    var contentType =
        multipart ? "Content-Type: multipart/form-data; boundary=" + BOUNDARY + "\r\n" : "";

    return "POST /input HTTP/1.1\r\nHost: localhost\r\n"
        + contentType
        + "Content-Length: "
        + body.length
        + "\r\nConnection: close\r\n\r\n"
        + new String(body, StandardCharsets.US_ASCII);
  }

  private void assertNativeBuffersReleased(Server server) {
    var buffers = assertInstanceOf(ArrayByteBufferPool.class, server.getByteBufferPool());

    assertBuffersReleased(buffers);
  }

  private void assertBuffersReleased(ArrayByteBufferPool buffers) {
    await()
        .atMost(Duration.ofSeconds(3))
        .untilAsserted(() -> {
          assertEquals(0L, buffers.getReserved());
          assertEquals(buffers.getHeapMemory(), buffers.getAvailableHeapMemory());
          assertEquals(buffers.getDirectMemory(), buffers.getAvailableDirectMemory());
        });
  }

  @Test
  void releasesNativePooledUploadContentAfterRequestCompletion() throws Exception {
    var buffers = new ArrayByteBufferPool();
    var retained = new AtomicReference<InputStream>();
    var app = pooledUploadApplication(buffers, retained);

    try (Closeable appCleanup = app::close;
        var server = TestServer.start(app);
        var socket = new Socket()) {
      try {
        connect(socket, server);
        write(socket, inputRequest(createBodyForPost("A".repeat(32_768)), true));

        assertResponse(socket, "ok");
        assertEquals(-1, socket.getInputStream().read());
        assertBuffersReleased(buffers);
      } finally {
        var input = retained.get();
        if (input != null) {
          input.close();
        }
      }
    }
  }

  private Shoostr pooledUploadApplication(
      ArrayByteBufferPool buffers, AtomicReference<InputStream> retained) {
    return new Shoostr()
        .modifyServer(server ->
            server.setHandler(
                new EagerContentHandler(
                    server.getHandler(),
                    new MultiPartContentLoaderFactory(nativeMultipartConfig(buffers)))))
        .routes(routes ->
            routes.post(
                "/input",
                (request, response) -> {
                  var input = request.file("document").orElseThrow().content();
                  retained.set(input);
                  assertEquals('A', input.read());
                  assertTrue(buffers.getReserved() > 0);

                  response.text("ok");
                }));
  }

  private MultiPartConfig nativeMultipartConfig(ArrayByteBufferPool buffers) {
    return new MultiPartConfig.Builder()
        .bufferPool(new ByteBufferPool.Sized(buffers, false, 8192))
        .maxMemoryPartSize(0)
        .maxSize(131_072)
        .maxPartSize(65_536)
        .maxParts(1)
        .maxHeadersSize(512)
        .location(temporaryDirectory)
        .build();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void returnsZeroForAnEmptyUploadReadAfterEof(boolean diskBacked) throws Exception {
    {
      var app = application(diskBacked);

      try (Closeable appCleanup6 = app::close) {
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
  }

  private Shoostr application(boolean diskBacked) {
    var multipart = new MultipartOptions();
    multipart.setMaxFileBytes(16L);
    multipart.setMaxBytes(1024L);
    multipart.setMaxParts(1);
    multipart.setMaxPartHeadersBytes(512);
    multipart.setMaxMemoryPartBytes(diskBacked ? 0L : 16L);
    multipart.setTemporaryDirectory(temporaryDirectory);
    var configuredOptions = new Options();
    configuredOptions.setMultipart(multipart);

    return new Shoostr(configuredOptions);
  }
}
