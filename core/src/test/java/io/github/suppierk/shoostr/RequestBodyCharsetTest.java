package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
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
  private static Charset mutatingCharset(String name, AtomicReference<ByteBuffer> exposed) {
    // A custom decoder can use a standard name; the name must not grant cache access.
    return new Charset(name, new String[0]) {
      @Override
      public boolean contains(Charset charset) {
        return false;
      }

      @Override
      public CharsetDecoder newDecoder() {
        return new CharsetDecoder(this, 1, 1) {
          @Override
          protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
            while (input.hasRemaining() && output.hasRemaining()) {
              output.put(Character.toUpperCase((char) Byte.toUnsignedInt(input.get())));
            }
            exposed.set(input);
            if (!input.isReadOnly() && input.limit() > 0) {
              input.put(0, (byte) 'X');
            }

            return input.hasRemaining() ? CoderResult.OVERFLOW : CoderResult.UNDERFLOW;
          }
        };
      }

      @Override
      public CharsetEncoder newEncoder() {
        throw new UnsupportedOperationException("Decoding fixture only");
      }
    };
  }

  @Test
  void decodesUsingTheSuppliedCharsetInsteadOfDeclaredMetadata() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close) {
        app.routes()
            .post(
                "/text",
                (request, response) ->
                    response.text(request.bodyText(StandardCharsets.ISO_8859_1)));

        try (var test = TestServer.start(app)) {
          var response =
              sendResponseForDecodesUsingTheSuppliedCharsetInsteadOfDeclaredMetadata(test);

          assertEquals(200, response.statusCode());
          assertEquals("café", response.body());
        }
      }
    }
  }

  private HttpResponse<String>
      sendResponseForDecodesUsingTheSuppliedCharsetInsteadOfDeclaredMetadata(TestServer test)
          throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/text")
                .method("POST")
                .header("Content-Type", "text/plain; charset=UTF-16")
                .body(new byte[] {'c', 'a', 'f', (byte) 0xe9}),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void decodesCachedBytesIndependentlyForEachCharset() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup1 = app::close) {
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
          var result = sendResultForDecodesCachedBytesIndependentlyForEachCharset(test);

          assertEquals(200, result.statusCode());
          assertEquals("checked", result.body());
        }
      }
    }
  }

  private HttpResponse<String> sendResultForDecodesCachedBytesIndependentlyForEachCharset(
      TestServer test) throws IOException, InterruptedException {
    return test.send(
        request -> request.path("/text").method("POST").body(new byte[] {(byte) 0xc3, (byte) 0xa9}),
        HttpResponse.BodyHandlers.ofString());
  }

  @ParameterizedTest
  @CsvSource({
    "UTF-8,Aé,41c3a9", "ISO-8859-1,Aé,41e9", "US-ASCII,Az,417a",
    "UTF-16,Aé,feff004100e9", "UTF-16BE,Aé,004100e9", "UTF-16LE,Aé,4100e900"
  })
  void preservesCachedBytesAndDefensiveCopiesForEveryStandardCharset(
      String name, String expected, String hex) throws Exception {
    var charset =
        createCharsetForPreservesCachedBytesAndDefensiveCopiesForEveryStandardCharset(name);
    var encoded = HexFormat.of().parseHex(hex);

    {
      var app = new Shoostr();

      try (Closeable appCleanup2 = app::close) {
        app.routes()
            .post(
                "/text",
                (request, response) -> {
                  assertEquals(expected, request.bodyText(charset));

                  var copy = request.bodyBytes();
                  copy[0] ^= 0x7f;

                  assertArrayEquals(encoded, request.bodyBytes());
                  assertEquals(expected, request.bodyText(charset));

                  response.text("checked");
                });

        try (var server = TestServer.start(app)) {
          var result =
              server.send(
                  request -> request.path("/text").method("POST").body(encoded),
                  HttpResponse.BodyHandlers.ofString());

          assertEquals(200, result.statusCode());
          assertEquals("checked", result.body());
        }
      }
    }
  }

  private Charset createCharsetForPreservesCachedBytesAndDefensiveCopiesForEveryStandardCharset(
      String name) {
    return switch (name) {
      case "UTF-8" -> StandardCharsets.UTF_8;
      case "ISO-8859-1" -> StandardCharsets.ISO_8859_1;
      case "US-ASCII" -> StandardCharsets.US_ASCII;
      case "UTF-16" -> StandardCharsets.UTF_16;
      case "UTF-16BE" -> StandardCharsets.UTF_16BE;
      case "UTF-16LE" -> StandardCharsets.UTF_16LE;
      default -> throw new IllegalArgumentException(name);
    };
  }

  @ParameterizedTest
  @ValueSource(strings = {"UTF-8", "x-mutating"})
  void isolatesCachedBytesFromCustomDecoderThatMutatesAndRetainsItsInput(String name)
      throws Exception {
    var exposed = new AtomicReference<ByteBuffer>();
    var charset = mutatingCharset(name, exposed);

    {
      var app = new Shoostr();

      try (Closeable appCleanup3 = app::close) {
        app.routes()
            .post(
                "/text",
                (request, response) -> {
                  assertEquals("SAFE", request.bodyText(charset));

                  var retained = exposed.get();
                  if (retained != null && !retained.isReadOnly()) {
                    for (int index = 0; index < retained.limit(); index++) {
                      retained.put(index, (byte) 'Y');
                    }
                  }

                  assertArrayEquals(new byte[] {'s', 'a', 'f', 'e'}, request.bodyBytes());
                  assertEquals("safe", request.bodyText());

                  response.text("checked");
                });

        try (var server = TestServer.start(app)) {
          var result =
              server.send(
                  request ->
                      request.path("/text").method("POST").body(new byte[] {'s', 'a', 'f', 'e'}),
                  HttpResponse.BodyHandlers.ofString());

          assertEquals(200, result.statusCode());
          assertEquals("checked", result.body());
        }
      }
    }
  }

  @Test
  @SuppressWarnings("NullAway")
  void rejectsNullBeforeClaimingOrReadingTheBody() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup4 = app::close) {
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
  }

  @Test
  void preservesDefaultUtf8AndReplacementDecoding() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup5 = app::close) {
        app.routes().post("/default", (request, response) -> response.text(request.bodyText()));
        app.routes()
            .post(
                "/utf16",
                (request, response) -> response.text(request.bodyText(StandardCharsets.UTF_16BE)));

        try (var test = TestServer.start(app)) {
          var defaultResult =
              sendDefaultResultRequestForPreservesDefaultUtf8AndReplacementDecoding(test);

          assertEquals(200, defaultResult.statusCode());
          assertEquals("é", defaultResult.body());

          var malformed =
              test.send(
                  request -> request.path("/utf16").method("POST").body(new byte[] {0x00}),
                  HttpResponse.BodyHandlers.ofString());

          assertEquals(200, malformed.statusCode());
          assertEquals("�", malformed.body());

          var empty =
              test.send(
                  request -> request.path("/utf16").method("POST"),
                  HttpResponse.BodyHandlers.ofString());

          assertEquals(200, empty.statusCode());
          assertEquals("", empty.body());
        }
      }
    }
  }

  private HttpResponse<String>
      sendDefaultResultRequestForPreservesDefaultUtf8AndReplacementDecoding(TestServer test)
          throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/default")
                .method("POST")
                .header("Content-Type", "text/plain; charset=ISO-8859-1")
                .body(new byte[] {(byte) 0xc3, (byte) 0xa9}),
        HttpResponse.BodyHandlers.ofString());
  }

  @ParameterizedTest
  @CsvSource({"4, false, 200", "6, false, 413", "4, true, 200", "6, true, 413"})
  void enforcesByteLimitsForFixedAndUnknownLengthUtf16(int size, boolean chunked, int status)
      throws Exception {
    var bytes = new byte[size];
    for (int index = 1; index < size; index += 2) {
      bytes[index] = 'a';
    }

    var publisher =
        chunked
            ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes))
            : HttpRequest.BodyPublishers.ofByteArray(bytes);

    var configuredOptions = new Options();
    configuredOptions.setMaxRequestBytes(4);
    configuredOptions.setMaxResponseBytes(1024);
    configuredOptions.setStreamBufferBytes(8);
    configuredOptions.setMaxParameters(1000);
    configuredOptions.setMultipart(new MultipartOptions());

    {
      var app = createAppForEnforcesByteLimitsForFixedAndUnknownLengthUtf16(configuredOptions);

      try (Closeable appCleanup6 = app::close;
          var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
        app.routes()
            .post(
                "/text",
                (request, response) -> response.text(request.bodyText(StandardCharsets.UTF_16BE)));

        try (var test = TestServer.start(app)) {
          var response =
              sendResponseForEnforcesByteLimitsForFixedAndUnknownLengthUtf16(
                  client, test, publisher);

          assertEquals(status, response.statusCode());

          if (status == 200) {
            assertEquals("aa", response.body());
          }
        }
      }
    }
  }

  private Shoostr createAppForEnforcesByteLimitsForFixedAndUnknownLengthUtf16(
      Options configuredOptions) {
    return new Shoostr(configuredOptions)
        .modifyServer(nativeServer -> {
          var nativeConnector = (ServerConnector) nativeServer.getConnectors()[0];
          nativeConnector.setHost("127.0.0.1");
          nativeConnector.setPort(0);
          nativeConnector.setIdleTimeout(5000);
        });
  }

  private HttpResponse<String> sendResponseForEnforcesByteLimitsForFixedAndUnknownLengthUtf16(
      HttpClient client, TestServer test, BodyPublisher publisher)
      throws IOException, InterruptedException {
    return client.send(
        HttpRequest.newBuilder(test.baseUri().resolve("/text"))
            .timeout(Duration.ofSeconds(3))
            .POST(publisher)
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void inheritsCharsetReadsInTypedRoutesWithoutInvokingTheCodec() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup7 = app::close) {
        app.codec(new TestCodec())
            .routes()
            .post(
                "/text",
                (request, response) ->
                    response.text(request.bodyText(StandardCharsets.ISO_8859_1)));

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
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "application/x-www-form-urlencoded",
        "application/x-www-form-urlencoded; charset=ISO-8859-1"
      })
  void doesNotRelaxFormDecodingWhenAnotherTextCharsetWasUsed(String contentType) throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup8 = app::close) {
        app.routes()
            .post(
                "/form",
                (request, response) -> {
                  assertEquals("tag=é", request.bodyText(StandardCharsets.ISO_8859_1));

                  response.text(request.formParam("tag").orElseThrow());
                });

        try (var test = TestServer.start(app)) {
          var response =
              sendResponseForDoesNotRelaxFormDecodingWhenAnotherTextCharsetWasUsed(
                  test, contentType);

          assertEquals(contentType.contains("charset") ? 415 : 400, response.statusCode());
        }
      }
    }
  }

  private HttpResponse<String> sendResponseForDoesNotRelaxFormDecodingWhenAnotherTextCharsetWasUsed(
      TestServer test, String contentType) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/form")
                .method("POST")
                .header("Content-Type", contentType)
                .body(new byte[] {'t', 'a', 'g', '=', (byte) 0xe9}),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void preservesBodyModeExclusionInBothDirections() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup9 = app::close) {
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

                    input.transferTo(OutputStream.nullOutputStream());
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
            var response = sendResponseForPreservesBodyModeExclusionInBothDirections(test, path);

            assertEquals(200, response.statusCode());
            assertEquals("checked", response.body());
          }
        }
      }
    }
  }

  private HttpResponse<String> sendResponseForPreservesBodyModeExclusionInBothDirections(
      TestServer test, String path) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path(path)
                .method("POST")
                .header("Content-Type", "multipart/form-data; boundary=x")
                .body("--x--\r\n".getBytes(StandardCharsets.US_ASCII)),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void rejectsForeignThreadAndCompletedAccessToCachedText() throws Exception {
    var saved = new AtomicReference<Request>();
    var thread = new AtomicReference<Thread>();

    try (var executor = Executors.newSingleThreadExecutor();
        var children = Executors.newVirtualThreadPerTaskExecutor()) {
      var app = new Shoostr();

      try (Closeable appCleanup10 = app::close) {
        app.routes()
            .post(
                "/text",
                executor,
                (request, response) -> {
                  saved.set(request);
                  thread.set(Thread.currentThread());

                  assertEquals("a", request.bodyText(StandardCharsets.ISO_8859_1));

                  children
                      .submit(() ->
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
              .submit(() -> {
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
}
