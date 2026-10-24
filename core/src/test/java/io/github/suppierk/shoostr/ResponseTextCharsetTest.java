package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.testing.TestServer;
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
  @Test
  void encodesTextWithTheSuppliedCharsetAndMatchingMetadata() throws Exception {
    try (var app = new Shoostr()) {
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

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void preservesUtf8BytesAndMetadataForBothTextOverloads(boolean explicit) throws Exception {
    try (var app = new Shoostr()) {
      app.routes()
          .get(
              "/utf8",
              (ignored2, response) -> {
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
            "text/plain; charset=utf-8", result.headers().firstValue("Content-Type").orElseThrow());
        assertArrayEquals(new byte[] {'c', 'a', 'f', (byte) 0xc3, (byte) 0xa9}, result.body());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"GET", "HEAD"})
  void retainsSelectedCharsetAndEncodedLengthForGetAndHead(String method) throws Exception {
    try (var app = new Shoostr()) {
      app.routes()
          .get("/utf16", (ignored3, response) -> response.text("café", StandardCharsets.UTF_16BE));
      app.routes()
          .head("/utf16", (ignored4, response) -> response.text("café", StandardCharsets.UTF_16BE));

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

  @Test
  void appliesTheLimitToEncodedBytesAndPreservesOutputWhenReplacementIsTooLarge() throws Exception {
    try (var app =
        new Shoostr(new Options(1024, 1, 512, 1000, MultipartOptions.defaults()))
            .modifyServer(
                nativeServer -> {
                  var nativeConnector = (ServerConnector) nativeServer.getConnectors()[0];
                  nativeConnector.setHost("127.0.0.1");
                  nativeConnector.setPort(0);
                  nativeConnector.setIdleTimeout(5000);
                })) {
      app.routes()
          .get("/latin1", (ignored5, response) -> response.text("é", StandardCharsets.ISO_8859_1));
      app.routes()
          .get(
              "/utf8",
              (ignored6, response) -> {
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

  @Test
  @SuppressWarnings("NullAway")
  void rejectsNullsAndInvalidCharsetMetadataWithoutReplacingOutput() throws Exception {
    try (var app = new Shoostr()) {
      app.routes()
          .get(
              "/invalid",
              (ignored7, response) -> {
                response.status(201).body("application/json", new byte[] {'0'});
                assertThrows(
                    NullPointerException.class, () -> response.text(null, StandardCharsets.UTF_8));
                assertThrows(NullPointerException.class, () -> response.text("late", null));
                assertThrows(
                    IllegalArgumentException.class,
                    () -> response.text("late", rejectedEncoder("X-Test:Codec")));
              });

      try (var test = TestServer.start(app)) {
        var result = test.send(request -> request.path("/invalid"));
        assertEquals(201, result.statusCode());
        assertEquals("application/json", result.headers().firstValue("Content-Type").orElseThrow());
        assertArrayEquals(new byte[] {'0'}, result.body());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"X-Test", "UTF-8"})
  void invokesTheSuppliedEncoderWithoutReplacingOutputOnFailure(String name) throws Exception {
    try (var app = new Shoostr()) {
      app.routes()
          .get(
              "/encoder",
              (ignored8, response) -> {
                response.status(201).body("application/json", new byte[] {'0'});
                assertThrows(
                    UnsupportedOperationException.class,
                    () -> response.text("late", rejectedEncoder(name)));
              });

      try (var test = TestServer.start(app)) {
        var result = test.send(request -> request.path("/encoder"));
        assertEquals(201, result.statusCode());
        assertEquals("application/json", result.headers().firstValue("Content-Type").orElseThrow());
        assertArrayEquals(new byte[] {'0'}, result.body());
      }
    }
  }

  @Test
  void rejectsForeignThreadAccessBeforeCallingTheEncoder() throws Exception {
    try (var children = Executors.newVirtualThreadPerTaskExecutor();
        var app = new Shoostr()) {
      app.routes()
          .get(
              "/thread",
              (ignored9, response) -> {
                children
                    .submit(
                        () ->
                            assertThrows(
                                IllegalStateException.class,
                                () -> response.text("late", rejectedEncoder("X-Test"))))
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

  @Test
  void rejectsCompletedAccessOnTheHandlerThreadBeforeCallingTheEncoder() throws Exception {
    var saved = new AtomicReference<Response>();

    try (var executor = Executors.newSingleThreadExecutor();
        var app = new Shoostr()) {
      app.routes()
          .get(
              "/complete",
              executor,
              (ignored10, response) -> {
                saved.set(response);
                response.text("ok", StandardCharsets.US_ASCII);
              });

      try (var test = TestServer.start(app)) {
        assertEquals(200, test.send(request -> request.path("/complete")).statusCode());
        var response = saved.get();
        executor
            .submit(
                () ->
                    assertThrows(
                        IllegalStateException.class,
                        () -> response.text("late", rejectedEncoder("X-Test"))))
            .get(5, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void rejectsFileReplacementBeforeCallingTheEncoder(@TempDir Path directory) throws Exception {
    var file = directory.resolve("body.bin");
    Files.write(file, new byte[] {42});

    try (var app = new Shoostr()) {
      app.routes()
          .get(
              "/file",
              (ignored11, response) -> {
                response.file(file, "application/octet-stream");
                assertThrows(
                    IllegalStateException.class,
                    () -> response.text("late", rejectedEncoder("X-Test")));
              });

      try (var test = TestServer.start(app)) {
        var result = test.send(request -> request.path("/file"));
        assertEquals(200, result.statusCode());
        assertArrayEquals(new byte[] {42}, result.body());
      }
    }
  }

  @Test
  void rejectsStreamingReplacementBeforeCallingTheEncoder() throws Exception {
    try (var app = new Shoostr()) {
      app.routes()
          .get(
              "/stream",
              (ignored12, response) -> {
                response.startStream("application/octet-stream").write(new byte[] {42});
                assertThrows(
                    IllegalStateException.class,
                    () -> response.text("late", rejectedEncoder("X-Test")));
              });

      try (var test = TestServer.start(app)) {
        var result = test.send(request -> request.path("/stream"));
        assertEquals(200, result.statusCode());
        assertArrayEquals(new byte[] {42}, result.body());
      }
    }
  }

  @Test
  void retainsTheTypedFluentResponseAndBypassesObjectEncoding() throws Exception {
    try (var app = new Shoostr()) {
      app.codec(new TestCodec())
          .routes()
          .get(
              "/typed",
              (ignored13, response) -> {
                TypedResponse selected = response.text("café", StandardCharsets.ISO_8859_1);
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

  @Test
  void usesReplacementBytesForMalformedAndUnmappableText() throws Exception {
    try (var app = new Shoostr()) {
      app.routes()
          .get(
              "/ascii",
              (ignored14, response) -> response.text("é\uD800", StandardCharsets.US_ASCII));
      app.routes()
          .get("/utf8", (ignored15, response) -> response.text("\uD800", StandardCharsets.UTF_8));

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
}
