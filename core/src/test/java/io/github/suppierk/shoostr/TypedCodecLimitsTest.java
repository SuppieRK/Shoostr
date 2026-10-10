package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class TypedCodecLimitsTest {
  @Test
  void rejectsOversizedRequestBeforeDecoding() throws Exception {
    var reads = new AtomicInteger();
    var codec =
        new TestCodec() {
          @Override
          public <T> T read(byte[] source, Class<T> type) throws Exception {
            reads.incrementAndGet();
            return super.read(source, type);
          }
        };

    try (var app = new Shoostr(new Options("127.0.0.1", 0, 4, 256, 8, 3000))) {
      app.codec(codec)
          .routes()
          .post(
              "/body",
              (request, response) ->
                  response.body(MediaType.APPLICATION_JSON, request.body(String.class)));

      try (var test = TestServer.start(app)) {
        assertEquals(
            413,
            test.send(request -> request.path("/body").method("POST").body(new byte[5]))
                .statusCode());
        assertEquals(0, reads.get());
      }
    }
  }

  @Test
  void decodesRequestAtExactByteLimit() throws Exception {
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 4, 256, 8, 3000))) {
      app.codec(new TestCodec())
          .routes()
          .post(
              "/body",
              (request, response) ->
                  response.body(MediaType.APPLICATION_JSON, request.body(String.class)));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/body")
                        .method("POST")
                        .body("éé".getBytes(StandardCharsets.UTF_8)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("\"éé\"", result.body());
      }
    }
  }

  @Test
  void enforcesFiniteLimitAfterEncodingWithoutReplacingStagedBody() throws Exception {
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 64, 8, 8, 3000))) {
      app.codec(new TestCodec())
          .routes()
          .get(
              "/body",
              (_, response) -> {
                response.text("kept");
                assertThrows(
                    IllegalArgumentException.class,
                    () -> response.body(MediaType.APPLICATION_JSON, "0123456789"));
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(request -> request.path("/body"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("kept", result.body());
        assertEquals(
            "text/plain; charset=utf-8", result.headers().firstValue("Content-Type").orElseThrow());
      }
    }
  }

  @Test
  void isolatesCodecMutationFromCachedRequestBytes() throws Exception {
    var reads = new AtomicInteger();
    var codec =
        new TestCodec() {
          @Override
          public <T> T read(byte[] source, Class<T> type) throws Exception {
            reads.incrementAndGet();
            var decoded = super.read(source, type);
            source[0] = '!';
            return decoded;
          }
        };

    try (var app = new Shoostr()) {
      app.codec(codec)
          .routes()
          .post(
              "/body",
              (request, response) -> {
                assertEquals("Alice", request.body(String.class));
                assertEquals("Alice", request.body(String.class));
                response.text(request.bodyText());
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/body")
                        .method("POST")
                        .body("Alice".getBytes(StandardCharsets.UTF_8)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals("Alice", result.body());
        assertEquals(2, reads.get());
      }
    }
  }

  @Test
  void copiesCodecOutputBeforeCallerMutation() throws Exception {
    var bytes = "kept".getBytes(StandardCharsets.UTF_8);
    var codec =
        new TestCodec() {
          @Override
          public byte[] write(Object value) {
            return bytes;
          }
        };

    try (var app = new Shoostr()) {
      app.codec(codec)
          .routes()
          .get(
              "/body",
              (_, response) -> {
                response.body("application/json", "anything");
                bytes[0] = '!';
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(request -> request.path("/body"), HttpResponse.BodyHandlers.ofString());
        assertEquals("kept", result.body());
      }
    }
  }
}
