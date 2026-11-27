package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import io.github.suppierk.shoostr.http.exceptions.InternalServerErrorException;
import io.github.suppierk.shoostr.http.exceptions.UnsupportedMediaTypeException;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
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
  private static Stream<Arguments> failures() {
    return Stream.of(
        Arguments.of(new BadRequestException("malformed"), 400),
        Arguments.of(new UnsupportedMediaTypeException("unsupported"), 415),
        Arguments.of(new InternalServerErrorException("internal"), 500),
        Arguments.of(new IOException("checked failure"), 500),
        Arguments.of(new IllegalStateException("unexpected failure"), 500));
  }

  @ParameterizedTest
  @MethodSource("failures")
  void preservesDecodeFailureClassification(Exception failure, int status) throws Exception {
    var codec = createCodecForPreservesDecodeFailureClassification(failure);

    {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close) {
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
  }

  private TestCodec createCodecForPreservesDecodeFailureClassification(Exception failure) {
    return new TestCodec() {
      @Override
      public <T> T read(byte[] source, Class<T> type) throws Exception {
        throw failure;
      }
    };
  }

  @ParameterizedTest
  @MethodSource("failures")
  void preservesEncodeFailureClassification(Exception failure, int status) throws Exception {
    var codec = createCodecForPreservesEncodeFailureClassification(failure);

    {
      var app = new Shoostr();

      try (Closeable appCleanup1 = app::close) {
        app.codec(codec)
            .routes()
            .get("/encode", (ignored, response) -> response.body("application/json", "Alice"));

        try (var test = TestServer.start(app)) {
          assertEquals(status, test.send(request -> request.path("/encode")).statusCode());
        }
      }
    }
  }

  private TestCodec createCodecForPreservesEncodeFailureClassification(Exception failure) {
    return new TestCodec() {
      @Override
      public byte[] write(Object value) throws Exception {
        throw failure;
      }
    };
  }

  @Test
  void passesOriginalCheckedFailureToLocalRenderer() throws Exception {
    var failure = new IOException("codec unavailable");
    var codec = createCodecForPassesOriginalCheckedFailureToLocalRenderer(failure);

    {
      var app = new Shoostr();

      try (Closeable appCleanup2 = app::close) {
        app.exception(
            IOException.class,
            (ignoredFailure, ignoredRequest, response) -> response.status(502).text("global"));
        app.codec(codec)
            .routes()
            .path(
                "/failure",
                routes -> {
                  routes.exception(
                      IOException.class,
                      (caught, ignoredRequest, response) -> {
                        assertSame(failure, caught);

                        response.status(503).text("local");
                      });
                  routes.get((ignoredTypedRequest, response) ->
                      response.body(MediaType.APPLICATION_JSON, "Alice"));
                });

        try (var test = TestServer.start(app)) {
          var result =
              test.send(request -> request.path("/failure"), HttpResponse.BodyHandlers.ofString());

          assertEquals(503, result.statusCode());
          assertEquals("local", result.body());
        }
      }
    }
  }

  private TestCodec createCodecForPassesOriginalCheckedFailureToLocalRenderer(IOException failure) {
    return new TestCodec() {
      @Override
      public byte[] write(Object value) throws IOException {
        throw failure;
      }
    };
  }

  @Test
  void returnsSafeErrorWhenTypedRendererCannotEncodeItsBody() throws Exception {
    var app = new Shoostr();
    var codec = createCodecForPreservesEncodeFailureClassification(new IOException("private"));

    try (Closeable cleanup = app::close) {
      app.codec(codec)
          .routes()
          .exception(
              BadRequestException.class,
              (failure, request, response) -> response.body(MediaType.APPLICATION_JSON, "error"))
          .get(
              "/fail",
              (request, response) -> {
                throw new BadRequestException("invalid input");
              });

      try (var test = TestServer.start(app)) {
        var response =
            test.send(request -> request.path("/fail"), HttpResponse.BodyHandlers.ofString());

        assertEquals(500, response.statusCode());
        assertEquals("Internal Server Error", response.body());
      }
    }
  }

  @Test
  void keepsStagedBodyAndHeadersWhenCodecThrows() throws Exception {
    var failure = new IOException("unavailable");
    var codec = createCodecForKeepsStagedBodyAndHeadersWhenCodecThrows(failure);

    {
      var app = new Shoostr();

      try (Closeable appCleanup3 = app::close) {
        app.codec(codec)
            .routes()
            .get(
                "/failure",
                (ignoredTypedRequest, response) -> {
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
              "text/plain; charset=utf-8",
              result.headers().firstValue("Content-Type").orElseThrow());
        }
      }
    }
  }

  private TestCodec createCodecForKeepsStagedBodyAndHeadersWhenCodecThrows(IOException failure) {
    return new TestCodec() {
      @Override
      public byte[] write(Object value) throws IOException {
        throw failure;
      }
    };
  }

  @Test
  @SuppressWarnings("NullAway")
  // A deliberately broken codec violates its nonnull output contract.
  void rejectsNullEncodedBytesAsServerFailure() throws Exception {
    var codec = createCodecForRejectsNullEncodedBytesAsServerFailure();

    {
      var app = new Shoostr();

      try (Closeable appCleanup4 = app::close) {
        app.codec(codec)
            .routes()
            .get(
                "/null",
                (ignoredTypedRequest, response) -> response.body("application/json", "Alice"));

        try (var test = TestServer.start(app)) {
          assertEquals(500, test.send(request -> request.path("/null")).statusCode());
        }
      }
    }
  }

  private TestCodec createCodecForRejectsNullEncodedBytesAsServerFailure() {
    return new TestCodec() {
      @Override
      public byte[] write(Object value) {
        return null;
      }
    };
  }

  @Test
  @SuppressWarnings("NullAway")
  // A deliberately broken codec violates its nonnull result contract.
  void rejectsNullDecodedValueAsServerFailure() throws Exception {
    var codec = createCodecForRejectsNullDecodedValueAsServerFailure();

    {
      var app = new Shoostr();

      try (Closeable appCleanup5 = app::close) {
        app.codec(codec)
            .routes()
            .post("/null", (request, response) -> response.text(request.body(String.class)));

        try (var test = TestServer.start(app)) {
          assertEquals(
              500, test.send(request -> request.path("/null").method("POST")).statusCode());
        }
      }
    }
  }

  private TestCodec createCodecForRejectsNullDecodedValueAsServerFailure() {
    return new TestCodec() {
      @Override
      public <T> T read(byte[] source, Class<T> type) {
        return null;
      }
    };
  }
}
