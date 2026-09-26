package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

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
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/echo",
              (request, response) ->
                  response.text(
                      new String(request.input().readAllBytes(), StandardCharsets.UTF_8)));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/echo"))
                  .POST(HttpRequest.BodyPublishers.ofString("streamed"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("streamed", result.body());
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
      assertEquals(true, initialConsumed.await(3, TimeUnit.SECONDS));
      output.write("1\r\nb\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
      output.flush();
      socket.shutdownOutput();
      assertEquals(
          true,
          new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII)
              .contains("ab"));
    }
  }

  @Test
  void rejectsBufferedBodyAccessAfterStreamingInputAccess() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/input",
              (request, response) -> {
                request.input().read();
                assertThrows(IllegalStateException.class, request::bodyBytes);
                response.text("exclusive");
              });
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/input"))
                  .POST(HttpRequest.BodyPublishers.ofString("body"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("exclusive", result.body());
    }
  }

  @Test
  void rejectsStreamingInputAccessAfterBufferedBodyAccess() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/buffered",
              (request, response) -> {
                request.bodyBytes();
                assertThrows(IllegalStateException.class, request::input);
                response.text("exclusive");
              });
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/buffered"))
                  .POST(HttpRequest.BodyPublishers.ofString("body"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("exclusive", result.body());
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

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/input",
              (request, response) -> {
                request.input();
                assertThrows(IllegalStateException.class, () -> request.formParam("field"));
                response.text("exclusive");
              });
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/input"))
                  .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                  .POST(HttpRequest.BodyPublishers.ofString(body))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("exclusive", result.body());
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

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/multipart",
              (request, response) -> {
                request.formParam("field");
                assertThrows(IllegalStateException.class, request::input);
                response.text("exclusive");
              });
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/multipart"))
                  .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                  .POST(HttpRequest.BodyPublishers.ofString(body))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("exclusive", result.body());
    }
  }

  @Test
  void writesAFileWithoutStagingItsWholeContent() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "response-file-test", ".txt");
    Files.writeString(file, "file output");

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().get("/file", (request, response) -> response.file(file, "text/plain"));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/file"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("file output", result.body());
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void rejectsOversizedFiniteBodiesWithoutChangingThePreviouslyStagedBody() throws Exception {
    var options = new Options("127.0.0.1", 0, 1024, 3, 2, 5000);

    try (var app = new Shoostr(options);
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/finite",
              (request, response) -> {
                response.body("text/plain", "abc".getBytes(StandardCharsets.UTF_8));
                assertThrows(
                    IllegalArgumentException.class,
                    () -> response.body("text/plain", "abcd".getBytes(StandardCharsets.UTF_8)));
              });
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/finite"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("abc", result.body());
    }
  }

  @Test
  void streamsOutputLargerThanTheFiniteResponseLimit() throws Exception {
    var options = new Options("127.0.0.1", 0, 1_048_576, 3, 2, 30_000);

    try (var app = new Shoostr(options);
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/stream",
              (request, response) ->
                  response.input(
                      new ByteArrayInputStream("streamed".getBytes(StandardCharsets.UTF_8)),
                      "text/plain"));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/stream"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("streamed", result.body());
    }
  }

  @Test
  void writesAnInputStreamWithoutStagingItsWholeContent() throws Exception {
    var closed = new AtomicBoolean();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/stream",
              (request, response) ->
                  response.input(
                      new ByteArrayInputStream("stream output".getBytes(StandardCharsets.UTF_8)) {
                        @Override
                        public void close() {
                          closed.set(true);
                        }
                      },
                      "text/plain"));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/stream"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("stream output", result.body());
      assertEquals(true, closed.get());
    }
  }

  @Test
  void suppressesFileBytesForHeadRequests() throws Exception {
    var file = Files.createTempFile(temporaryDirectory, "response-head-file-test", ".txt");
    Files.writeString(file, "file output");

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().head("/file", (request, response) -> response.file(file, "text/plain"));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/file"))
                  .method("HEAD", HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("", result.body());
      assertEquals("text/plain", result.headers().firstValue("Content-Type").orElseThrow());
      assertEquals("11", result.headers().firstValue("Content-Length").orElseThrow());
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void closesInputWithoutReadingItForBodylessResponses() throws Exception {
    var closed = new AtomicBoolean();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/empty",
              (request, response) -> {
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
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/empty"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(204, result.statusCode());
      assertEquals("", result.body());
      assertEquals(true, closed.get());
    }
  }

  @Test
  void closesInputWhenStreamingSetupFails() throws Exception {
    var closed = new AtomicBoolean();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/invalid",
              (request, response) ->
                  response.input(
                      new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8)) {
                        @Override
                        public void close() {
                          closed.set(true);
                        }
                      },
                      "text/plain\ninvalid"));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/invalid"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(500, result.statusCode());
      assertEquals(true, closed.get());
    }
  }

  @Test
  void closesInputWhenReadingItFails() throws Exception {
    var closed = new AtomicBoolean();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/read-error",
              (request, response) ->
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
      app.start();
      assertThrows(
          java.io.IOException.class,
          () ->
              client.send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + app.port() + "/read-error"))
                      .GET()
                      .timeout(Duration.ofSeconds(3))
                      .build(),
                  HttpResponse.BodyHandlers.ofString()));
      assertEquals(true, closed.get());
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
              (request, response) ->
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
      assertEquals(true, started.await(3, TimeUnit.SECONDS));
      socket.close();
      assertEquals(true, closed.await(3, TimeUnit.SECONDS));
    }
  }

  @Test
  void allowsExplicitHeadStreamingWithoutWireContent() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .head(
              "/stream",
              (request, response) -> {
                response.startStream("text/plain").write("suppressed");
              });
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/stream"))
                  .method("HEAD", HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("", result.body());
    }
  }

  @Test
  void rejectsRetainedStreamingInputAfterHandlerCompletion() throws Exception {
    var retained = new AtomicReference<InputStream>();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/input",
              (request, response) -> {
                retained.set(request.input());
                response.text("ready");
              });
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/input"))
                  .POST(HttpRequest.BodyPublishers.ofString("body"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertThrows(IllegalStateException.class, retained.get()::read);
    }
  }
}
