package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class RequestBodyCharsetTest {
  @Test
  void decodesUsingTheSuppliedCharsetInsteadOfDeclaredMetadata() throws Exception {
    try (var app = new Shoostr()) {
      app.routes()
          .post(
              "/text",
              (request, response) -> response.text(request.bodyText(StandardCharsets.ISO_8859_1)));

      try (var test = TestServer.start(app)) {
        var response =
            test.send(
                request ->
                    request
                        .path("/text")
                        .method("POST")
                        .header("Content-Type", "text/plain; charset=UTF-16")
                        .body(new byte[] {'c', 'a', 'f', (byte) 0xe9}),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("café", response.body());
      }
    }
  }

  @Test
  void decodesCachedBytesIndependentlyForEachCharset() throws Exception {
    try (var app = new Shoostr()) {
      app.routes()
          .post(
              "/text",
              (request, response) -> {
                assertEquals("Ã©", request.bodyText(StandardCharsets.ISO_8859_1));
                assertEquals("é", request.bodyText());
                var copy = request.bodyBytes();
                copy[0] = 0;
                assertEquals("Ã©", request.bodyText(StandardCharsets.ISO_8859_1));
                assertArrayEquals(new byte[] {(byte) 0xc3, (byte) 0xa9}, request.bodyBytes());
                response.text("checked");
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/text")
                        .method("POST")
                        .body(new byte[] {(byte) 0xc3, (byte) 0xa9}),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("checked", result.body());
      }
    }
  }

  @Test
  @SuppressWarnings("NullAway")
  void rejectsNullBeforeClaimingOrReadingTheBody() throws Exception {
    try (var app = new Shoostr()) {
      app.routes()
          .post(
              "/text",
              (request, response) -> {
                assertThrows(NullPointerException.class, () -> request.bodyText(null));

                try (var input = request.input()) {
                  assertArrayEquals(new byte[] {1, 2, 3}, input.readAllBytes());
                }

                response.text("checked");
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request -> request.path("/text").method("POST").body(new byte[] {1, 2, 3}),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("checked", result.body());
      }
    }
  }

  @Test
  void preservesDefaultUtf8AndReplacementDecoding() throws Exception {
    try (var app = new Shoostr()) {
      app.routes().post("/default", (request, response) -> response.text(request.bodyText()));
      app.routes()
          .post(
              "/utf16",
              (request, response) -> response.text(request.bodyText(StandardCharsets.UTF_16BE)));

      try (var test = TestServer.start(app)) {
        var defaultResult =
            test.send(
                request ->
                    request
                        .path("/default")
                        .method("POST")
                        .header("Content-Type", "text/plain; charset=ISO-8859-1")
                        .body(new byte[] {(byte) 0xc3, (byte) 0xa9}),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, defaultResult.statusCode());
        assertEquals("é", defaultResult.body());
        var malformed =
            test.send(
                request -> request.path("/utf16").method("POST").body(new byte[] {0x00}),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, malformed.statusCode());
        assertEquals("\uFFFD", malformed.body());
        var empty =
            test.send(
                request -> request.path("/utf16").method("POST"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, empty.statusCode());
        assertEquals("", empty.body());
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"4, false, 200", "6, false, 413", "4, true, 200", "6, true, 413"})
  void enforcesByteLimitsForFixedAndUnknownLengthUtf16(int size, boolean chunked, int status)
      throws Exception {
    var bytes = new byte[size];
    for (int i = 1; i < size; i += 2) {
      bytes[i] = 'a';
    }
    var publisher =
        chunked
            ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes))
            : HttpRequest.BodyPublishers.ofByteArray(bytes);

    try (var app =
            new Shoostr(new Options(4, 1024, 8, 1000, MultipartOptions.defaults()))
                .modifyServer(
                    nativeServer -> {
                      var nativeConnector = (ServerConnector) nativeServer.getConnectors()[0];
                      nativeConnector.setHost("127.0.0.1");
                      nativeConnector.setPort(0);
                      nativeConnector.setIdleTimeout(5000);
                    });
        var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
      app.routes()
          .post(
              "/text",
              (request, response) -> response.text(request.bodyText(StandardCharsets.UTF_16BE)));

      try (var test = TestServer.start(app)) {
        var response =
            client.send(
                HttpRequest.newBuilder(test.baseUri().resolve("/text"))
                    .timeout(Duration.ofSeconds(3))
                    .POST(publisher)
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode());
        if (status == 200) {
          assertEquals("aa", response.body());
        }
      }
    }
  }

  @Test
  void inheritsCharsetReadsInTypedRoutesWithoutInvokingTheCodec() throws Exception {
    try (var app = new Shoostr()) {
      app.codec(new TestCodec())
          .routes()
          .post(
              "/text",
              (request, response) -> response.text(request.bodyText(StandardCharsets.ISO_8859_1)));

      try (var test = TestServer.start(app)) {
        var response =
            test.send(
                request -> request.path("/text").method("POST").body(new byte[] {(byte) 0xe9}),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("é", response.body());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "application/x-www-form-urlencoded",
        "application/x-www-form-urlencoded; charset=ISO-8859-1"
      })
  void doesNotRelaxFormDecodingWhenAnotherTextCharsetWasUsed(String contentType) throws Exception {
    try (var app = new Shoostr()) {
      app.routes()
          .post(
              "/form",
              (request, response) -> {
                assertEquals("tag=é", request.bodyText(StandardCharsets.ISO_8859_1));
                response.text(request.formParam("tag").orElseThrow());
              });

      try (var test = TestServer.start(app)) {
        var response =
            test.send(
                request ->
                    request
                        .path("/form")
                        .method("POST")
                        .header("Content-Type", contentType)
                        .body(new byte[] {'t', 'a', 'g', '=', (byte) 0xe9}),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(contentType.contains("charset") ? 415 : 400, response.statusCode());
      }
    }
  }

  @Test
  void preservesBodyModeExclusionInBothDirections() throws Exception {
    try (var app = new Shoostr()) {
      app.routes()
          .post(
              "/buffered",
              (request, response) -> {
                request.bodyText(StandardCharsets.ISO_8859_1);
                assertThrows(IllegalStateException.class, request::input);
                assertThrows(IllegalStateException.class, request::files);
                response.text("checked");
              });
      app.routes()
          .post(
              "/streamed",
              (request, response) -> {
                try (var input = request.input()) {
                  assertThrows(
                      IllegalStateException.class,
                      () -> request.bodyText(StandardCharsets.ISO_8859_1));
                  input.transferTo(java.io.OutputStream.nullOutputStream());
                }

                response.text("checked");
              });
      app.routes()
          .post(
              "/multipart",
              (request, response) -> {
                request.files();
                assertThrows(
                    IllegalStateException.class,
                    () -> request.bodyText(StandardCharsets.ISO_8859_1));
                response.text("checked");
              });

      try (var test = TestServer.start(app)) {
        for (var path : new String[] {"/buffered", "/streamed", "/multipart"}) {
          var response =
              test.send(
                  request ->
                      request
                          .path(path)
                          .method("POST")
                          .header("Content-Type", "multipart/form-data; boundary=x")
                          .body("--x--\r\n".getBytes(StandardCharsets.US_ASCII)),
                  HttpResponse.BodyHandlers.ofString());
          assertEquals(200, response.statusCode());
          assertEquals("checked", response.body());
        }
      }
    }
  }

  @Test
  void rejectsForeignThreadAndCompletedAccessToCachedText() throws Exception {
    var saved = new AtomicReference<Request>();
    var thread = new AtomicReference<Thread>();

    try (var executor = Executors.newSingleThreadExecutor();
        var children = Executors.newVirtualThreadPerTaskExecutor();
        var app = new Shoostr()) {
      app.routes()
          .post(
              "/text",
              executor,
              (request, response) -> {
                saved.set(request);
                thread.set(Thread.currentThread());
                assertEquals("a", request.bodyText(StandardCharsets.ISO_8859_1));
                children
                    .submit(
                        () ->
                            assertThrows(
                                IllegalStateException.class,
                                () -> request.bodyText(StandardCharsets.ISO_8859_1)))
                    .get(3, TimeUnit.SECONDS);
                response.text("checked");
              });

      try (var test = TestServer.start(app)) {
        var response =
            test.send(
                request -> request.path("/text").method("POST").body(new byte[] {'a'}),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("checked", response.body());
        executor
            .submit(
                () -> {
                  assertSame(thread.get(), Thread.currentThread());
                  var request = saved.get();
                  assertThrows(
                      IllegalStateException.class,
                      () -> request.bodyText(StandardCharsets.ISO_8859_1));
                })
            .get(3, TimeUnit.SECONDS);
      }
    }
  }
}
