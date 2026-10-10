package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import io.github.suppierk.shoostr.http.exceptions.InternalServerErrorException;
import io.github.suppierk.shoostr.http.exceptions.UnsupportedMediaTypeException;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Timeout(15)
class TypedCodecFailureTest {
  @ParameterizedTest
  @MethodSource("failures")
  void preservesDecodeFailureClassification(Exception failure, int status) throws Exception {
    var codec =
        new TestCodec() {
          @Override
          public <T> T read(byte[] source, Class<T> type) throws Exception {
            throw failure;
          }
        };

    try (var app = new Shoostr()) {
      app.codec(codec)
          .routes()
          .post(
              "/decode",
              (request, response) ->
                  response.body(MediaType.APPLICATION_JSON, request.body(String.class)));

      try (var test = TestServer.start(app)) {
        assertEquals(
            status,
            test.send(request -> request.path("/decode").method("POST").body(new byte[] {1}))
                .statusCode());
      }
    }
  }

  @ParameterizedTest
  @MethodSource("failures")
  void preservesEncodeFailureClassification(Exception failure, int status) throws Exception {
    var codec =
        new TestCodec() {
          @Override
          public byte[] write(Object value) throws Exception {
            throw failure;
          }
        };

    try (var app = new Shoostr()) {
      app.codec(codec)
          .routes()
          .get("/encode", (_, response) -> response.body("application/json", "Alice"));

      try (var test = TestServer.start(app)) {
        assertEquals(status, test.send(request -> request.path("/encode")).statusCode());
      }
    }
  }

  @Test
  void passesOriginalCheckedFailureToLocalRenderer() throws Exception {
    var failure = new IOException("codec unavailable");
    var codec =
        new TestCodec() {
          @Override
          public byte[] write(Object value) throws IOException {
            throw failure;
          }
        };

    try (var app = new Shoostr()) {
      app.exception(IOException.class, (_, _, response) -> response.status(502).text("global"));
      app.codec(codec)
          .routes()
          .get(
              "/failure",
              (_, response) -> response.body(MediaType.APPLICATION_JSON, "Alice"),
              extensions ->
                  extensions.exception(
                      IOException.class,
                      (caught, _, response) -> {
                        assertSame(failure, caught);
                        response.status(503).text("local");
                      }));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(request -> request.path("/failure"), HttpResponse.BodyHandlers.ofString());
        assertEquals(503, result.statusCode());
        assertEquals("local", result.body());
      }
    }
  }

  @Test
  void keepsStagedBodyAndHeadersWhenCodecThrows() throws Exception {
    var failure = new IOException("unavailable");
    var codec =
        new TestCodec() {
          @Override
          public byte[] write(Object value) throws IOException {
            throw failure;
          }
        };

    try (var app = new Shoostr()) {
      app.codec(codec)
          .routes()
          .get(
              "/failure",
              (_, response) -> {
                response.text("kept");
                assertSame(
                    failure,
                    assertThrows(
                        IOException.class,
                        () -> response.body(MediaType.APPLICATION_JSON, "Alice")));
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(request -> request.path("/failure"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("kept", result.body());
        assertEquals(
            "text/plain; charset=utf-8", result.headers().firstValue("Content-Type").orElseThrow());
      }
    }
  }

  @Test
  @SuppressWarnings("NullAway") // A deliberately broken codec violates its nonnull output contract.
  void rejectsNullEncodedBytesAsServerFailure() throws Exception {
    var codec =
        new TestCodec() {
          @Override
          public byte[] write(Object value) {
            return null;
          }
        };

    try (var app = new Shoostr()) {
      app.codec(codec)
          .routes()
          .get("/null", (_, response) -> response.body("application/json", "Alice"));

      try (var test = TestServer.start(app)) {
        assertEquals(500, test.send(request -> request.path("/null")).statusCode());
      }
    }
  }

  @Test
  @SuppressWarnings("NullAway") // A deliberately broken codec violates its nonnull result contract.
  void rejectsNullDecodedValueAsServerFailure() throws Exception {
    var codec =
        new TestCodec() {
          @Override
          public <T> T read(byte[] source, Class<T> type) {
            return null;
          }
        };

    try (var app = new Shoostr()) {
      app.codec(codec)
          .routes()
          .post("/null", (request, response) -> response.text(request.body(String.class)));

      try (var test = TestServer.start(app)) {
        assertEquals(500, test.send(request -> request.path("/null").method("POST")).statusCode());
      }
    }
  }

  private static Stream<Arguments> failures() {
    return Stream.of(
        Arguments.of(new BadRequestException("malformed"), 400),
        Arguments.of(new UnsupportedMediaTypeException("unsupported"), 415),
        Arguments.of(new InternalServerErrorException("internal"), 500),
        Arguments.of(new IOException("checked failure"), 500),
        Arguments.of(new IllegalStateException("unexpected failure"), 500));
  }
}
