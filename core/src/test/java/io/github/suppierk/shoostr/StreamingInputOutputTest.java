package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StreamingInputOutputTest {
  @TempDir Path temporaryDirectory;

  @Test
  void readsRequestInputWithoutBufferingTheWholeBody() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes()
          .post(
              "/echo",
              (request, response) ->
                  response.text(
                      new String(request.input().readAllBytes(), StandardCharsets.UTF_8)));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/echo")
                        .method("POST")
                        .body("streamed".getBytes(StandardCharsets.UTF_8)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("streamed", result.body());
      }
    }
  }

  @Test
  void consumesAnInitialChunkBeforeTheRemainingChunkArrives() throws Exception {
    var initialConsumed = new CountDownLatch(1);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var socket = new Socket()) {
      app.routes()
          .post(
              "/chunks",
              (request, response) -> {
                var input = request.input();
                var first = input.read();
                initialConsumed.countDown();
                var second = input.read();
                response.text("" + (char) first + (char) second);
              });
      app.start();
      socket.connect(new InetSocketAddress(InetAddress.getAllByName("127.0.0.1")[0], app.port()));
      var output = socket.getOutputStream();
      output.write(
          "POST /chunks HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n1\r\na\r\n"
              .getBytes(StandardCharsets.US_ASCII));
      output.flush();
      assertTrue(initialConsumed.await(3, TimeUnit.SECONDS));
      output.write("1\r\nb\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
      output.flush();
      socket.shutdownOutput();
      assertTrue(
          new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII)
              .contains("ab"));
    }
  }

  @Test
  void rejectsBufferedBodyAccessAfterStreamingInputAccess() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes()
          .post(
              "/input",
              (request, response) -> {
                assertEquals('b', request.input().read());
                assertThrows(IllegalStateException.class, request::bodyBytes);
                response.text("exclusive");
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/input")
                        .method("POST")
                        .body("body".getBytes(StandardCharsets.UTF_8)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("exclusive", result.body());
      }
    }
  }

  @Test
  void rejectsStreamingInputAccessAfterBufferedBodyAccess() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes()
          .post(
              "/buffered",
              (request, response) -> {
                request.bodyBytes();
                assertThrows(IllegalStateException.class, request::input);
                response.text("exclusive");
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/buffered")
                        .method("POST")
                        .body("body".getBytes(StandardCharsets.UTF_8)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("exclusive", result.body());
      }
    }
  }

  @Test
  void rejectsChunkedStreamingInputAfterTheConsumedByteLimit() throws Exception {
    var options = new Options("127.0.0.1", 0, 3, 1_048_576, 8192, 30_000);

    try (var app = new Shoostr(options);
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/input",
              (request, response) ->
                  response.text(
                      new String(request.input().readAllBytes(), StandardCharsets.UTF_8)));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/input"))
                  .POST(
                      HttpRequest.BodyPublishers.ofInputStream(
                          () -> new ByteArrayInputStream("four".getBytes(StandardCharsets.UTF_8))))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(413, result.statusCode());
      assertEquals("Content Too Large", result.body());
    }
  }

  @Test
  void rejectsChunkedStreamingInputSkippedPastTheConsumedByteLimit() throws Exception {
    var options = new Options("127.0.0.1", 0, 3, 1_048_576, 8192, 30_000);

    try (var app = new Shoostr(options);
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/input",
              (request, response) -> {
                request.input().skipNBytes(4);
                response.text("unreachable");
              });
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/input"))
                  .POST(
                      HttpRequest.BodyPublishers.ofInputStream(
                          () -> new ByteArrayInputStream("four".getBytes(StandardCharsets.UTF_8))))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(413, result.statusCode());
      assertEquals("Content Too Large", result.body());
    }
  }

  @Test
  void rejectsMultipartAccessAfterStreamingInputAccess() throws Exception {
    var boundary = "streaming-input-boundary";
    var body =
        "--"
            + boundary
            + "\r\nContent-Disposition: form-data; name=field\r\n\r\nvalue\r\n--"
            + boundary
            + "--\r\n";

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes()
          .post(
              "/input",
              (request, response) -> {
                request.input();
                assertThrows(IllegalStateException.class, () -> request.formParam("field"));
                response.text("exclusive");
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/input")
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .method("POST")
                        .body(body.getBytes(StandardCharsets.UTF_8)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("exclusive", result.body());
      }
    }
  }

  @Test
  void rejectsStreamingInputAccessAfterMultipartAccess() throws Exception {
    var boundary = "multipart-input-boundary";
    var body =
        "--"
            + boundary
            + "\r\nContent-Disposition: form-data; name=field\r\n\r\nvalue\r\n--"
            + boundary
            + "--\r\n";

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes()
          .post(
              "/multipart",
              (request, response) -> {
                request.formParam("field");
                assertThrows(IllegalStateException.class, request::input);
                response.text("exclusive");
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/multipart")
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .method("POST")
                        .body(body.getBytes(StandardCharsets.UTF_8)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("exclusive", result.body());
      }
    }
  }

  @Test
  void writesAFileWithoutStagingItsWholeContent() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "response-file-test", ".txt");
    Files.writeString(file, "file output");

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes().get("/file", (_, response) -> response.file(file, "text/plain"));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(request -> request.path("/file"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("file output", result.body());
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void rejectsOversizedFiniteBodiesWithoutChangingThePreviouslyStagedBody() throws Exception {
    var options = new Options("127.0.0.1", 0, 1024, 3, 2, 5000);
    var oversizedBody = "abcd".getBytes(StandardCharsets.UTF_8);

    try (var app = new Shoostr(options)) {
      app.routes()
          .get(
              "/finite",
              (_, response) -> {
                response.body("text/plain", "abc".getBytes(StandardCharsets.UTF_8));
                assertThrows(
                    IllegalArgumentException.class,
                    () -> response.body("text/plain", oversizedBody));
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(request -> request.path("/finite"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("abc", result.body());
      }
    }
  }

  @Test
  void streamsOutputLargerThanTheFiniteResponseLimit() throws Exception {
    var options = new Options("127.0.0.1", 0, 1_048_576, 3, 2, 30_000);

    try (var app = new Shoostr(options)) {
      app.routes()
          .get(
              "/stream",
              (_, response) ->
                  response.input(
                      new ByteArrayInputStream("streamed".getBytes(StandardCharsets.UTF_8)),
                      "text/plain"));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(request -> request.path("/stream"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("streamed", result.body());
      }
    }
  }

  @Test
  void writesAnInputStreamWithoutStagingItsWholeContent() throws Exception {
    var closed = new AtomicBoolean();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes()
          .get(
              "/stream",
              (_, response) ->
                  response.input(
                      new ByteArrayInputStream("stream output".getBytes(StandardCharsets.UTF_8)) {
                        @Override
                        public void close() {
                          closed.set(true);
                        }
                      },
                      "text/plain"));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(request -> request.path("/stream"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("stream output", result.body());
        assertTrue(closed.get());
      }
    }
  }

  @Test
  void suppressesFileBytesForHeadRequests() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "response-head-file-test", ".txt");
    Files.writeString(file, "file output");

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes().head("/file", (_, response) -> response.file(file, "text/plain"));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request -> request.path("/file").method("HEAD"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("", result.body());
        assertEquals("text/plain", result.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("11", result.headers().firstValue("Content-Length").orElseThrow());
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void closesInputWithoutReadingItForBodylessResponses() throws Exception {
    var closed = new AtomicBoolean();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes()
          .get(
              "/empty",
              (_, response) -> {
                response.status(204);
                response.input(
                    new InputStream() {
                      @Override
                      public int read() {
                        return fail("bodyless response read its source");
                      }

                      @Override
                      public int read(byte[] bytes, int offset, int length) {
                        return fail("bodyless response read its source");
                      }

                      @Override
                      public void close() {
                        closed.set(true);
                      }
                    },
                    "text/plain");
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(request -> request.path("/empty"), HttpResponse.BodyHandlers.ofString());
        assertEquals(204, result.statusCode());
        assertEquals("", result.body());
        assertTrue(closed.get());
      }
    }
  }

  @Test
  void closesInputWhenStreamingSetupFails() throws Exception {
    var closed = new AtomicBoolean();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes()
          .get(
              "/invalid",
              (_, response) ->
                  response.input(
                      new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8)) {
                        @Override
                        public void close() {
                          closed.set(true);
                        }
                      },
                      "text/plain\ninvalid"));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(request -> request.path("/invalid"), HttpResponse.BodyHandlers.ofString());
        assertEquals(500, result.statusCode());
        assertTrue(closed.get());
      }
    }
  }

  @Test
  void closesInputWhenReadingItFails() throws Exception {
    var closed = new AtomicBoolean();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes()
          .get(
              "/read-error",
              (_, response) ->
                  response.input(
                      new InputStream() {
                        @Override
                        public int read() throws IOException {
                          throw new IOException("read failed");
                        }

                        @Override
                        public int read(byte[] bytes, int offset, int length) throws IOException {
                          throw new IOException("read failed");
                        }

                        @Override
                        public void close() {
                          closed.set(true);
                        }
                      },
                      "text/plain"));

      try (var test = TestServer.start(app)) {
        assertThrows(
            java.io.IOException.class,
            () ->
                test.send(
                    request -> request.path("/read-error").timeout(Duration.ofSeconds(3)),
                    HttpResponse.BodyHandlers.ofString()));
        assertTrue(closed.get());
      }
    }
  }

  @Test
  void closesInputWhenTheClientDisconnectsDuringStreaming() throws Exception {
    var started = new CountDownLatch(1);
    var closed = new CountDownLatch(1);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var socket = new Socket()) {
      app.routes()
          .get(
              "/disconnect",
              (_, response) ->
                  response.input(
                      new InputStream() {
                        @Override
                        public int read(byte[] value, int offset, int length) {
                          started.countDown();
                          Arrays.fill(value, offset, offset + length, (byte) 'x');
                          return length;
                        }

                        @Override
                        public int read() {
                          return 'x';
                        }

                        @Override
                        public void close() {
                          closed.countDown();
                        }
                      },
                      "application/octet-stream"));
      app.start();
      socket.connect(new InetSocketAddress(InetAddress.getAllByName("127.0.0.1")[0], app.port()));
      socket.setSoLinger(true, 0);
      socket
          .getOutputStream()
          .write(
              "GET /disconnect HTTP/1.1\r\nHost: localhost\r\n\r\n"
                  .getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      assertTrue(started.await(3, TimeUnit.SECONDS));
      socket.close();
      assertTrue(closed.await(3, TimeUnit.SECONDS));
    }
  }

  @Test
  void allowsExplicitHeadStreamingWithoutWireContent() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes()
          .head("/stream", (_, response) -> response.startStream("text/plain").write("suppressed"));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request -> request.path("/stream").method("HEAD"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("", result.body());
      }
    }
  }

  @Test
  void rejectsRetainedStreamingInputAfterHandlerCompletion() throws Exception {
    var retained = new AtomicReference<InputStream>();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes()
          .post(
              "/input",
              (request, response) -> {
                retained.set(request.input());
                response.text("ready");
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/input")
                        .method("POST")
                        .body("body".getBytes(StandardCharsets.UTF_8)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertThrows(IllegalStateException.class, retained.get()::read);
      }
    }
  }
}
