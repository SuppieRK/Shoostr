package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.eclipse.jetty.compression.gzip.GzipCompression;
import org.eclipse.jetty.compression.server.CompressionConfig;
import org.eclipse.jetty.compression.server.CompressionHandler;
import org.eclipse.jetty.server.handler.GracefulHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ResponseCompressionTest {
  private static final String ALTERNATE_ENCODING = "aaa";

  @Test
  void wildcardExclusionRejectsImplicitIdentityForAnExplicitlyUncompressedResponse()
      throws Exception {
    var app = compressionApplication();

    try (Closeable appCleanup = app::close) {
      app.routes()
          .get("/data", (request, response) -> response.disableCompression().text("payload"));

      try (var test = TestServer.start(app)) {
        var accepted = encodedResponse(test, "*;q=1");
        assertEquals(200, accepted.statusCode());
        assertEquals("payload", new String(accepted.body(), StandardCharsets.UTF_8));

        var rejected = encodedResponse(test, "*;q=0");
        assertEquals(406, rejected.statusCode());
        assertEquals(0, rejected.body().length);
      }
    }
  }

  @Test
  void selectsGzipForAnAcceptedWildcardWhenIdentityIsForbidden() throws Exception {
    var payload = "wildcard-content-".repeat(100);
    var app = compressionApplication();

    try (Closeable appCleanup = app::close) {
      app.routes().get("/data", (ignoredRequest, response) -> response.text(payload));

      try (var test = TestServer.start(app)) {
        var result = encodedResponse(test, "*;q=1, identity;q=0");

        assertGzipResponse(payload, result);
      }
    }
  }

  @Test
  void negotiatesStreamingCompressionBeforeTheFinalLengthIsKnown() throws Exception {
    var payload = "stream-content-".repeat(100);
    var app = compressionApplication();

    try (Closeable appCleanup = app::close) {
      app.routes()
          .get(
              "/data",
              (ignoredRequest, response) -> {
                var firstWriteSize = 31;
                var stream = response.startStream("text/plain");
                stream.write(payload.substring(0, firstWriteSize));
                stream.flush();
                stream.write(payload.substring(firstWriteSize));
              });

      try (var test = TestServer.start(app)) {
        var result = encodedResponse(test, "gzip;q=1, identity;q=0");

        assertGzipResponse(payload, result);
      }
    }
  }

  @ParameterizedTest
  @CsvSource({
    "200, false, tiny, 406, ''",
    "200, true, tiny, 406, ''",
    "300, false, tiny, 300, tiny",
    "300, true, tiny, 300, tiny",
    "200, false, '', 406, ''"
  })
  void rejectsForbiddenIdentityForSuccessfulRepresentationsOnly(
      int status, boolean explicitIdentity, String body, int expectedStatus, String expectedBody)
      throws Exception {
    var app = compressionApplication();

    try (Closeable appCleanup = app::close) {
      app.routes()
          .get(
              "/data",
              (ignoredRequest, response) -> {
                response.status(status);
                if (explicitIdentity) {
                  response.disableCompression();
                }

                response.text(body);
              });

      try (var test = TestServer.start(app)) {
        var result = encodedResponse(test, "gzip;q=1, identity;q=0");

        assertEquals(expectedStatus, result.statusCode());
        assertEquals(expectedBody, new String(result.body(), StandardCharsets.UTF_8));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {32, 33})
  void acceptsGzipAtAndAboveTheNativeMinimumSize(int size) throws Exception {
    var payload = "a".repeat(size);
    var app = compressionApplication();

    try (Closeable appCleanup = app::close) {
      app.routes().get("/data", (ignoredRequest, response) -> response.status(200).text(payload));

      try (var test = TestServer.start(app)) {
        var result = encodedResponse(test, "gzip;q=1, identity;q=0");

        assertGzipResponse(payload, result);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void respectsNativeCompressorPreferencesAndInclusionsWhenIdentityIsForbidden(
      boolean includeOnlyGzip) throws Exception {
    var payload = "preferred-content-".repeat(100);
    var app = compressionApplication();

    try (Closeable appCleanup = app::close) {
      configureCompressorSelection(app, includeOnlyGzip);
      app.routes().get("/data", (ignoredRequest, response) -> response.text(payload));

      var acceptEncoding = includeOnlyGzip ? "aaa, gzip, identity;q=0" : "*;q=1, identity;q=0";

      try (var test = TestServer.start(app)) {
        var result = encodedResponse(test, acceptEncoding);

        assertGzipResponse(payload, result);
      }
    }
  }

  private void configureCompressorSelection(Shoostr app, boolean includeOnlyGzip) {
    app.modifyServer(server -> {
      var graceful = assertInstanceOf(GracefulHandler.class, server.getHandler());
      var handler = assertInstanceOf(CompressionHandler.class, graceful.getHandler());
      handler.putCompression(new GzipCompression());
      var alternate = alternateCompression();
      alternate.setMinCompressSize(10000);
      handler.putCompression(alternate);

      var configuration = CompressionConfig.builder().defaults().decompressExcludePath("/*");
      if (includeOnlyGzip) {
        configuration
            .compressPreferredEncodings(List.of("aaa", "gzip"))
            .compressIncludeEncoding("gzip");
      } else {
        configuration.compressPreferredEncodings(List.of("gzip", "aaa"));
      }

      handler.putConfiguration("/*", configuration.build());
    });
  }

  private GzipCompression alternateCompression() {
    return new GzipCompression() {
      @Override
      public String getEncodingName() {
        return ALTERNATE_ENCODING;
      }
    };
  }

  private Shoostr compressionApplication() {
    var options = new Options();
    options.setCompression(true);

    return new Shoostr(options);
  }

  private HttpResponse<byte[]> encodedResponse(TestServer test, String acceptEncoding)
      throws IOException, InterruptedException {
    return test.send(request ->
        request
            .path("/data")
            .timeout(Duration.ofSeconds(1))
            .header("Accept-Encoding", acceptEncoding));
  }

  private void assertGzipResponse(String payload, HttpResponse<byte[]> result) throws IOException {
    assertEquals(200, result.statusCode());
    assertEquals("gzip", result.headers().firstValue("Content-Encoding").orElseThrow());

    try (var gzip = new GZIPInputStream(new ByteArrayInputStream(result.body()))) {
      assertEquals(payload, new String(gzip.readAllBytes(), StandardCharsets.UTF_8));
    }
  }
}
