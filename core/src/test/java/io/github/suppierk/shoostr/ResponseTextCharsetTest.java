package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class ResponseTextCharsetTest {
  private static Charset rejectedEncoder(String name) {
    return new Charset(name, new String[0]) {
      @Override
      public boolean contains(Charset other) {
        return equals(other);
      }

      @Override
      public CharsetDecoder newDecoder() {
        throw new UnsupportedOperationException("Decoder must not be called");
      }

      @Override
      public CharsetEncoder newEncoder() {
        throw new UnsupportedOperationException("Encoder was called");
      }
    };
  }

  @Test
  void encodesTextWithTheSuppliedCharsetAndMatchingMetadata() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close) {
        app.routes()
            .get(
                "/latin1",
                (ignored, response) -> {
                  response.status(201).body("application/json", new byte[] {'0'});

                  assertSame(response, response.text("café", StandardCharsets.ISO_8859_1));
                  assertFalse(response.isCommitted());
                });

        try (var test = TestServer.start(app)) {
          var result = test.send(request -> request.path("/latin1"));

          assertEquals(201, result.statusCode());
          assertEquals(
              "text/plain; charset=ISO-8859-1",
              result.headers().firstValue("Content-Type").orElseThrow());
          assertArrayEquals(new byte[] {'c', 'a', 'f', (byte) 0xe9}, result.body());
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void preservesUtf8BytesAndMetadataForBothTextOverloads(boolean explicit) throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup1 = app::close) {
        app.routes()
            .get(
                "/utf8",
                (ignoredRequest, response) -> {
                  if (explicit) {
                    response.text("café", StandardCharsets.UTF_8);
                  } else {
                    response.text("café");
                  }
                });

        try (var test = TestServer.start(app)) {
          var result = test.send(request -> request.path("/utf8"));

          assertEquals(200, result.statusCode());
          assertEquals(
              "text/plain; charset=utf-8",
              result.headers().firstValue("Content-Type").orElseThrow());
          assertArrayEquals(new byte[] {'c', 'a', 'f', (byte) 0xc3, (byte) 0xa9}, result.body());
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"GET", "HEAD"})
  void retainsSelectedCharsetAndEncodedLengthForGetAndHead(String method) throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup2 = app::close) {
        app.routes()
            .get(
                "/utf16",
                (ignoredRequest, response) -> response.text("café", StandardCharsets.UTF_16BE));
        app.routes()
            .head(
                "/utf16",
                (ignoredRequest, response) -> response.text("café", StandardCharsets.UTF_16BE));

        try (var test = TestServer.start(app)) {
          var result = test.send(request -> request.path("/utf16").method(method));

          assertEquals(200, result.statusCode());
          assertEquals(
              "text/plain; charset=UTF-16BE",
              result.headers().firstValue("Content-Type").orElseThrow());
          assertEquals("8", result.headers().firstValue("Content-Length").orElseThrow());
          assertArrayEquals(
              "HEAD".equals(method)
                  ? new byte[0]
                  : new byte[] {0, 'c', 0, 'a', 0, 'f', 0, (byte) 0xe9},
              result.body());
        }
      }
    }
  }

  @Test
  void appliesTheLimitToEncodedBytesAndPreservesOutputWhenReplacementIsTooLarge() throws Exception {
    var configuredOptions = new Options();
    configuredOptions.setMaxRequestBytes(1024);
    configuredOptions.setMaxResponseBytes(1);
    configuredOptions.setStreamBufferBytes(512);
    configuredOptions.setMaxParameters(1000);
    configuredOptions.setMultipart(new MultipartOptions());

    {
      var app =
          createAppForAppliesTheLimitToEncodedBytesAndPreservesOutputWhenReplacementIsTooLarge(
              configuredOptions);

      try (Closeable appCleanup3 = app::close) {
        app.routes()
            .get(
                "/latin1",
                (ignoredRequest, response) -> response.text("é", StandardCharsets.ISO_8859_1));
        app.routes()
            .get(
                "/utf8",
                (ignoredRequest, response) -> {
                  response.status(201).body("application/octet-stream", new byte[] {42});

                  assertThrows(
                      IllegalArgumentException.class,
                      () -> response.text("é", StandardCharsets.UTF_8));
                });

        try (var test = TestServer.start(app)) {
          var latin1 = test.send(request -> request.path("/latin1"));

          assertEquals(200, latin1.statusCode());
          assertArrayEquals(new byte[] {(byte) 0xe9}, latin1.body());

          var rejected = test.send(request -> request.path("/utf8"));

          assertEquals(201, rejected.statusCode());
          assertEquals(
              "application/octet-stream",
              rejected.headers().firstValue("Content-Type").orElseThrow());
          assertArrayEquals(new byte[] {42}, rejected.body());
        }
      }
    }
  }

  private Shoostr
      createAppForAppliesTheLimitToEncodedBytesAndPreservesOutputWhenReplacementIsTooLarge(
          Options configuredOptions) {
    return new Shoostr(configuredOptions)
        .modifyServer(nativeServer -> {
          var nativeConnector = (ServerConnector) nativeServer.getConnectors()[0];
          nativeConnector.setHost("127.0.0.1");
          nativeConnector.setPort(0);
          nativeConnector.setIdleTimeout(5000);
        });
  }

  @Test
  @SuppressWarnings("NullAway")
  void rejectsNullsAndInvalidCharsetMetadataWithoutReplacingOutput() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup4 = app::close) {
        app.routes()
            .get(
                "/invalid",
                (ignoredRequest, response) -> {
                  response.status(201).body("application/json", new byte[] {'0'});

                  assertThrows(
                      NullPointerException.class,
                      () -> response.text(null, StandardCharsets.UTF_8));
                  assertThrows(NullPointerException.class, () -> response.text("late", null));

                  var charset = rejectedEncoder("X-Test:Codec");

                  assertThrows(
                      IllegalArgumentException.class, () -> response.text("late", charset));
                });

        try (var test = TestServer.start(app)) {
          var result = test.send(request -> request.path("/invalid"));

          assertEquals(201, result.statusCode());
          assertEquals(
              "application/json", result.headers().firstValue("Content-Type").orElseThrow());
          assertArrayEquals(new byte[] {'0'}, result.body());
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"X-Test", "UTF-8"})
  void invokesTheSuppliedEncoderWithoutReplacingOutputOnFailure(String name) throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup5 = app::close) {
        app.routes()
            .get(
                "/encoder",
                (ignoredRequest, response) -> {
                  response.status(201).body("application/json", new byte[] {'0'});
                  var charset = rejectedEncoder(name);

                  assertThrows(
                      UnsupportedOperationException.class, () -> response.text("late", charset));
                });

        try (var test = TestServer.start(app)) {
          var result = test.send(request -> request.path("/encoder"));

          assertEquals(201, result.statusCode());
          assertEquals(
              "application/json", result.headers().firstValue("Content-Type").orElseThrow());
          assertArrayEquals(new byte[] {'0'}, result.body());
        }
      }
    }
  }

  @Test
  void rejectsForeignThreadAccessBeforeCallingTheEncoder() throws Exception {
    try (var children = Executors.newVirtualThreadPerTaskExecutor()) {
      var app = new Shoostr();

      try (Closeable appCleanup6 = app::close) {
        app.routes()
            .get(
                "/thread",
                (ignoredRequest, response) -> {
                  var charset = rejectedEncoder("X-Test");
                  children
                      .submit(() ->
                          assertThrows(
                              IllegalStateException.class, () -> response.text("late", charset)))
                      .get(5, TimeUnit.SECONDS);
                  response.text("ok", StandardCharsets.US_ASCII);
                });

        try (var test = TestServer.start(app)) {
          var result = test.send(request -> request.path("/thread"));

          assertEquals(200, result.statusCode());
          assertArrayEquals(new byte[] {'o', 'k'}, result.body());
        }
      }
    }
  }

  @Test
  void rejectsCompletedAccessOnTheHandlerThreadBeforeCallingTheEncoder() throws Exception {
    var saved = new AtomicReference<Response>();

    try (var executor = Executors.newSingleThreadExecutor()) {
      var app = new Shoostr();

      try (Closeable appCleanup7 = app::close) {
        app.routes()
            .get(
                "/complete",
                executor,
                (ignoredRequest, response) -> {
                  saved.set(response);
                  response.text("ok", StandardCharsets.US_ASCII);
                });

        try (var test = TestServer.start(app)) {
          assertEquals(200, test.send(request -> request.path("/complete")).statusCode());

          var response = saved.get();
          var charset = rejectedEncoder("X-Test");
          executor
              .submit(() ->
                  assertThrows(IllegalStateException.class, () -> response.text("late", charset)))
              .get(5, TimeUnit.SECONDS);
        }
      }
    }
  }

  @Test
  void rejectsFileReplacementBeforeCallingTheEncoder(@TempDir Path directory) throws Exception {
    var file = directory.resolve("body.bin");
    Files.write(file, new byte[] {42});

    {
      var app = new Shoostr();

      try (Closeable appCleanup8 = app::close) {
        app.routes()
            .get(
                "/file",
                (ignoredRequest, response) -> {
                  response.file(file, "application/octet-stream");
                  var charset = rejectedEncoder("X-Test");

                  assertThrows(IllegalStateException.class, () -> response.text("late", charset));
                });

        try (var test = TestServer.start(app)) {
          var result = test.send(request -> request.path("/file"));

          assertEquals(200, result.statusCode());
          assertArrayEquals(new byte[] {42}, result.body());
        }
      }
    }
  }

  @Test
  void rejectsStreamingReplacementBeforeCallingTheEncoder() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup9 = app::close) {
        app.routes()
            .get(
                "/stream",
                (ignoredRequest, response) -> {
                  response.startStream("application/octet-stream").write(new byte[] {42});
                  var charset = rejectedEncoder("X-Test");

                  assertThrows(IllegalStateException.class, () -> response.text("late", charset));
                });

        try (var test = TestServer.start(app)) {
          var result = test.send(request -> request.path("/stream"));

          assertEquals(200, result.statusCode());
          assertArrayEquals(new byte[] {42}, result.body());
        }
      }
    }
  }

  @Test
  void retainsTheTypedFluentResponseAndBypassesObjectEncoding() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup10 = app::close) {
        app.codec(new TestCodec())
            .routes()
            .get(
                "/typed",
                (ignoredTypedRequest, response) -> {
                  Response.Typed selected = response.text("café", StandardCharsets.ISO_8859_1);

                  assertSame(response, selected);
                  assertFalse(selected.isCommitted());
                });

        try (var test = TestServer.start(app)) {
          var result = test.send(request -> request.path("/typed"));

          assertEquals(200, result.statusCode());
          assertEquals(
              "text/plain; charset=ISO-8859-1",
              result.headers().firstValue("Content-Type").orElseThrow());
          assertArrayEquals(new byte[] {'c', 'a', 'f', (byte) 0xe9}, result.body());
        }
      }
    }
  }

  @Test
  void usesReplacementBytesForMalformedAndUnmappableText() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup11 = app::close) {
        app.routes()
            .get(
                "/ascii",
                (ignoredRequest, response) -> response.text("é\uD800", StandardCharsets.US_ASCII));
        app.routes()
            .get(
                "/utf8",
                (ignoredRequest, response) -> response.text("\uD800", StandardCharsets.UTF_8));

        try (var test = TestServer.start(app)) {
          var ascii = test.send(request -> request.path("/ascii"));

          assertEquals(200, ascii.statusCode());
          assertArrayEquals(new byte[] {'?', '?'}, ascii.body());

          var utf8 = test.send(request -> request.path("/utf8"));

          assertEquals(200, utf8.statusCode());
          assertArrayEquals(new byte[] {'?'}, utf8.body());
        }
      }
    }
  }
}
