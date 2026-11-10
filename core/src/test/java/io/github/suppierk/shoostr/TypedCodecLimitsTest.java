package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class TypedCodecLimitsTest {
  @Test
  void rejectsOversizedRequestBeforeDecoding() throws Exception {
    var reads = new AtomicInteger();
    var codec = createCodecForRejectsOversizedRequestBeforeDecoding(reads);

    var configuredOptions = new Options();
    configuredOptions.setMaxRequestBytes(4);
    configuredOptions.setMaxResponseBytes(256);
    configuredOptions.setStreamBufferBytes(8);
    configuredOptions.setMaxParameters(1000);
    configuredOptions.setMultipart(MultipartOptions.defaults());

    {
      var app = createAppForRejectsOversizedRequestBeforeDecoding(configuredOptions);

      try (Closeable appCleanup = app::close) {
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
  }

  private TestCodec createCodecForRejectsOversizedRequestBeforeDecoding(AtomicInteger reads) {
    return new TestCodec() {
      @Override
      public <T> T read(byte[] source, Class<T> type) throws Exception {
        reads.incrementAndGet();

        return super.read(source, type);
      }
    };
  }

  private Shoostr createAppForRejectsOversizedRequestBeforeDecoding(Options configuredOptions) {
    return new Shoostr(configuredOptions)
        .modifyServer(
            nativeServer -> {
              var nativeConnector = (ServerConnector) nativeServer.getConnectors()[0];
              nativeConnector.setHost("127.0.0.1");
              nativeConnector.setPort(0);
              nativeConnector.setIdleTimeout(3000);
            });
  }

  @Test
  void decodesRequestAtExactByteLimit() throws Exception {
    var configuredOptions = new Options();
    configuredOptions.setMaxRequestBytes(4);
    configuredOptions.setMaxResponseBytes(256);
    configuredOptions.setStreamBufferBytes(8);
    configuredOptions.setMaxParameters(1000);
    configuredOptions.setMultipart(MultipartOptions.defaults());

    {
      var app = createAppForDecodesRequestAtExactByteLimit(configuredOptions);

      try (Closeable appCleanup1 = app::close) {
        app.codec(new TestCodec())
            .routes()
            .post(
                "/body",
                (request, response) ->
                    response.body(MediaType.APPLICATION_JSON, request.body(String.class)));

        try (var test = TestServer.start(app)) {
          var result = sendResultForDecodesRequestAtExactByteLimit(test);

          assertEquals(200, result.statusCode());
          assertEquals("\"éé\"", result.body());
        }
      }
    }
  }

  private Shoostr createAppForDecodesRequestAtExactByteLimit(Options configuredOptions) {
    return new Shoostr(configuredOptions)
        .modifyServer(
            nativeServer -> {
              var nativeConnector = (ServerConnector) nativeServer.getConnectors()[0];
              nativeConnector.setHost("127.0.0.1");
              nativeConnector.setPort(0);
              nativeConnector.setIdleTimeout(3000);
            });
  }

  private HttpResponse<String> sendResultForDecodesRequestAtExactByteLimit(TestServer test)
      throws IOException, InterruptedException {
    return test.send(
        request -> request.path("/body").method("POST").body("éé".getBytes(StandardCharsets.UTF_8)),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void enforcesFiniteLimitAfterEncodingWithoutReplacingStagedBody() throws Exception {
    var configuredOptions = new Options();
    configuredOptions.setMaxRequestBytes(64);
    configuredOptions.setMaxResponseBytes(8);
    configuredOptions.setStreamBufferBytes(8);
    configuredOptions.setMaxParameters(1000);
    configuredOptions.setMultipart(MultipartOptions.defaults());

    {
      var app =
          createAppForEnforcesFiniteLimitAfterEncodingWithoutReplacingStagedBody(configuredOptions);

      try (Closeable appCleanup2 = app::close) {
        app.codec(new TestCodec())
            .routes()
            .get(
                "/body",
                (ignored, response) -> {
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
              "text/plain; charset=utf-8",
              result.headers().firstValue("Content-Type").orElseThrow());
        }
      }
    }
  }

  private Shoostr createAppForEnforcesFiniteLimitAfterEncodingWithoutReplacingStagedBody(
      Options configuredOptions) {
    return new Shoostr(configuredOptions)
        .modifyServer(
            nativeServer -> {
              var nativeConnector = (ServerConnector) nativeServer.getConnectors()[0];
              nativeConnector.setHost("127.0.0.1");
              nativeConnector.setPort(0);
              nativeConnector.setIdleTimeout(3000);
            });
  }

  @Test
  void isolatesCodecMutationFromCachedRequestBytes() throws Exception {
    var reads = new AtomicInteger();
    var codec = createCodecForIsolatesCodecMutationFromCachedRequestBytes(reads);

    {
      var app = new Shoostr();

      try (Closeable appCleanup3 = app::close) {
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
          var result = sendResultForIsolatesCodecMutationFromCachedRequestBytes(test);

          assertEquals("Alice", result.body());
          assertEquals(2, reads.get());
        }
      }
    }
  }

  private TestCodec createCodecForIsolatesCodecMutationFromCachedRequestBytes(AtomicInteger reads) {
    return new TestCodec() {
      @Override
      public <T> T read(byte[] source, Class<T> type) throws Exception {
        reads.incrementAndGet();
        var decoded = super.read(source, type);
        source[0] = '!';

        return decoded;
      }
    };
  }

  private HttpResponse<String> sendResultForIsolatesCodecMutationFromCachedRequestBytes(
      TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request.path("/body").method("POST").body("Alice".getBytes(StandardCharsets.UTF_8)),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void copiesCodecOutputBeforeCallerMutation() throws Exception {
    var bytes = "kept".getBytes(StandardCharsets.UTF_8);
    var codec = createCodecForCopiesCodecOutputBeforeCallerMutation(bytes);

    {
      var app = new Shoostr();

      try (Closeable appCleanup4 = app::close) {
        app.codec(codec)
            .routes()
            .get(
                "/body",
                (ignoredTypedRequest, response) -> {
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

  private TestCodec createCodecForCopiesCodecOutputBeforeCallerMutation(byte[] bytes) {
    return new TestCodec() {
      @Override
      public byte[] write(Object value) {
        return bytes;
      }
    };
  }
}
