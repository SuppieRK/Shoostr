package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.MediaType;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.eclipse.jetty.http.HttpFields;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Timeout(15)
class MediaTypeResponseTest {
  @ParameterizedTest
  @MethodSource("nullAdditionalAcceptCases")
  void treatsNullAdditionalCandidatesAsOnlyTheFirstCandidate(
      String accept, int status, String body, List<String> vary) throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/null-additional",
              (_, response) -> {
                var selected = response.negotiate(MediaType.APPLICATION_JSON, (MediaType[]) null);
                assertSame(MediaType.APPLICATION_JSON, selected);
                response.body(selected, selected.value().getBytes(StandardCharsets.UTF_8));
              });
      app.start();
      var outgoing =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/null-additional"))
              .timeout(Duration.ofSeconds(3));
      if (!accept.isEmpty()) {
        outgoing.header("Accept", accept);
      }

      var result = client.send(outgoing.build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(status, result.statusCode());
      assertEquals(body, result.body());
      assertEquals(vary, result.headers().allValues("Vary"));
      if (status == 200) {
        assertEquals("application/json", result.headers().firstValue("Content-Type").orElseThrow());
      }
    }
  }

  @Test
  @SuppressWarnings("NullAway") // Deliberately verifies invalid inputs at the public API boundary.
  void rejectsNullNegotiationInputsWithoutChangingStagedResponse() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/invalid-candidates",
              (_, response) -> {
                response
                    .status(202)
                    .setHeader("Vary", "Origin")
                    .body(MediaType.APPLICATION_JSON, "\"kept\"".getBytes(StandardCharsets.UTF_8));
                assertThrows(
                    NullPointerException.class, () -> response.negotiate((MediaType) null));
                assertThrows(
                    NullPointerException.class,
                    () ->
                        response.negotiate(
                            MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN, (MediaType) null));
              });
      app.start();
      var result =
          client.send(request(app, "/invalid-candidates"), HttpResponse.BodyHandlers.ofString());
      assertEquals(202, result.statusCode());
      assertEquals("application/json", result.headers().firstValue("Content-Type").orElseThrow());
      assertEquals(List.of("Origin"), result.headers().allValues("Vary"));
      assertEquals("\"kept\"", result.body());
    }
  }

  @Test
  void keepsConcurrentRequestNegotiationIndependent() throws Exception {
    var ready = new CountDownLatch(2);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/concurrent",
              (_, response) -> {
                ready.countDown();
                if (!ready.await(3, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("Requests did not overlap");
                }

                var selected = response.negotiate(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN);
                response.body(selected, selected.value().getBytes(StandardCharsets.UTF_8));
              });
      app.start();
      var uri = URI.create("http://127.0.0.1:" + app.port() + "/concurrent");
      var json =
          client.sendAsync(
              HttpRequest.newBuilder(uri)
                  .header("Accept", "application/json")
                  .timeout(Duration.ofSeconds(5))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      var text =
          client.sendAsync(
              HttpRequest.newBuilder(uri)
                  .header("Accept", "text/plain")
                  .timeout(Duration.ofSeconds(5))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      var jsonResult = json.get(5, TimeUnit.SECONDS);
      var textResult = text.get(5, TimeUnit.SECONDS);

      assertEquals(200, jsonResult.statusCode());
      assertEquals("application/json", jsonResult.body());
      assertEquals(
          "application/json", jsonResult.headers().firstValue("Content-Type").orElseThrow());
      assertEquals(200, textResult.statusCode());
      assertEquals("text/plain", textResult.body());
      assertEquals("text/plain", textResult.headers().firstValue("Content-Type").orElseThrow());
    }
  }

  @Test
  void negotiatesUsingItsBoundRequestHeaderSnapshot() throws Exception {
    var transportHeaders = new AtomicReference<HttpFields.Mutable>();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.modifyHttpConfiguration(
          configuration ->
              configuration.addCustomizer(
                  (nativeRequest, _) -> {
                    var fields = HttpFields.build(nativeRequest.getHeaders());
                    transportHeaders.set(fields);
                    return new org.eclipse.jetty.server.Request.Wrapper(nativeRequest) {
                      @Override
                      public HttpFields getHeaders() {
                        return fields;
                      }
                    };
                  }));
      app.onRequestHeaders((_, _) -> transportHeaders.get().put("Accept", "text/plain"));
      app.routes()
          .get(
              "/bound",
              (_, response) -> {
                var selected = response.negotiate(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN);
                response.body(selected, selected.value().getBytes(StandardCharsets.UTF_8));
              });
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/bound"))
                  .header("Accept", "application/json")
                  .timeout(Duration.ofSeconds(3))
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(200, result.statusCode());
      assertEquals("application/json", result.body());
      assertEquals("application/json", result.headers().firstValue("Content-Type").orElseThrow());
      assertEquals(List.of("Accept"), result.headers().allValues("Vary"));
    }
  }

  @Test
  void negotiatesMetadataWithoutTranscodingCallerBytes() throws Exception {
    var latin = MediaType.TEXT_PLAIN.withCharset(StandardCharsets.ISO_8859_1);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/bytes",
              (_, response) -> {
                var type = response.negotiate(latin, MediaType.APPLICATION_JSON);
                var bytes = type.equals(latin) ? new byte[] {(byte) 0xe9} : new byte[] {'{', '}'};
                response.body(type, bytes);
                bytes[0] = 0;
              });
      app.start();
      var outgoing =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/bytes"))
              .header("Accept", "text/plain; charset=iso-8859-1")
              .build();
      var result = client.send(outgoing, HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(200, result.statusCode());
      assertEquals(
          "text/plain; charset=ISO-8859-1",
          result.headers().firstValue("Content-Type").orElseThrow());
      assertArrayEquals(new byte[] {(byte) 0xe9}, result.body());
    }
  }

  @Test
  void mergesAcceptIntoVaryAndPreservesAnExistingWildcard() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/merged",
              (_, response) -> {
                response.addHeader("Vary", "Origin, aCcEpT");
                response.addHeader("Vary", "User-Agent");
                var type = response.negotiate(MediaType.APPLICATION_JSON);
                response.body(type, new byte[] {1});
              });
      app.routes()
          .get(
              "/wildcard",
              (_, response) -> {
                response.setHeader("Vary", "*");
                var type = response.negotiate(MediaType.APPLICATION_JSON);
                response.body(type, new byte[] {1});
              });
      app.start();
      var merged = client.send(request(app, "/merged"), HttpResponse.BodyHandlers.discarding());
      var wildcard = client.send(request(app, "/wildcard"), HttpResponse.BodyHandlers.discarding());
      assertEquals(List.of("Origin, aCcEpT", "User-Agent"), merged.headers().allValues("Vary"));
      assertEquals(List.of("*"), wildcard.headers().allValues("Vary"));
    }
  }

  @Test
  void readsRepeatedAcceptFieldsAsOnePreferenceList() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().get("/negotiated", MediaTypeResponseTest::negotiate);
      app.start();
      var outgoing =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/negotiated"))
              .header("Accept", "application/json;q=0.1")
              .header("Accept", "text/plain")
              .build();
      var result = client.send(outgoing, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("text/plain", result.body());
      assertEquals("text/plain", result.headers().firstValue("Content-Type").orElseThrow());
    }
  }

  @Test
  void ignoresEmptyAcceptListElementsAndParameterSegments() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().get("/negotiated", MediaTypeResponseTest::negotiate);
      app.start();
      var outgoing =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/negotiated"))
              .header("Accept", ", text/plain; ;, ")
              .build();
      var result = client.send(outgoing, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("text/plain", result.body());
    }
  }

  @ParameterizedTest
  @MethodSource("emptyAcceptFields")
  void rejectsAnExplicitEmptyAcceptList(String accept) throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().get("/negotiated", MediaTypeResponseTest::negotiate);
      app.start();
      var outgoing =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/negotiated"))
              .header("Accept", accept)
              .build();
      var result = client.send(outgoing, HttpResponse.BodyHandlers.ofString());
      assertEquals(406, result.statusCode());
      assertEquals("Accept", result.headers().firstValue("Vary").orElseThrow());
    }
  }

  @Test
  void rejectsTheExactRepresentationWhenAWildcardWithParametersHasHigherQuality() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().get("/negotiated", MediaTypeResponseTest::negotiateUtf8Text);
      app.start();
      var outgoing =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/negotiated"))
              .header("Accept", "text/plain;q=0, text/*;charset=utf-8;q=1")
              .build();
      var result = client.send(outgoing, HttpResponse.BodyHandlers.ofString());
      assertEquals(406, result.statusCode());
    }
  }

  @Test
  void selectsTheExactRepresentationOverAnExcludedWildcardWithParameters() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().get("/negotiated", MediaTypeResponseTest::negotiateUtf8Text);
      app.start();
      var outgoing =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/negotiated"))
              .header("Accept", "text/plain;q=1, text/*;charset=utf-8;q=0")
              .build();
      var result = client.send(outgoing, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
    }
  }

  @Test
  void permitsTabsInsideQuotedParameters() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().get("/negotiated", MediaTypeResponseTest::negotiate);
      app.start();
      var outgoing =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/negotiated"))
              .header("Accept", "application/json, text/plain;note=\"a\tb\"")
              .build();
      var result = client.send(outgoing, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("application/json", result.body());
    }
  }

  @ParameterizedTest
  @MethodSource("acceptCases")
  void appliesAcceptWildcardsWeightsSpecificityParametersAndCallerOrder(
      String accept, MediaType expected) throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().get("/negotiated", MediaTypeResponseTest::negotiate);
      app.start();
      var outgoing =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/negotiated"))
              .header("Accept", accept)
              .build();
      var result = client.send(outgoing, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals(expected.value(), result.body());
      assertEquals(expected.value(), result.headers().firstValue("Content-Type").orElseThrow());
      assertEquals("Accept", result.headers().firstValue("Vary").orElseThrow());
    }
  }

  @ParameterizedTest
  @MethodSource("malformedAcceptFields")
  void rejectsMalformedAcceptWithoutVary(String accept) throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().get("/negotiated", MediaTypeResponseTest::negotiate);
      app.start();
      var outgoing =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/negotiated"))
              .header("Accept", accept)
              .build();
      var result = client.send(outgoing, HttpResponse.BodyHandlers.ofString());
      assertEquals(400, result.statusCode());
      assertEquals("Bad Request", result.body());
      assertTrue(result.headers().firstValue("Vary").isEmpty());
    }
  }

  @Test
  void rejectsNegotiationAfterStreamingHasCommittedHeaders() throws Exception {
    var failure = new AtomicReference<Throwable>();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/stream",
              (_, response) -> {
                response.startStream(MediaType.TEXT_PLAIN).write("ready");
                failure.set(
                    assertThrows(
                        IllegalStateException.class,
                        () -> response.negotiate(MediaType.TEXT_PLAIN)));
              });
      app.start();
      var result = client.send(request(app, "/stream"), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("ready", result.body());
      assertEquals(IllegalStateException.class, failure.get().getClass());
    }
  }

  @Test
  void rejectsARepresentationExcludedByASpecificZeroQualityRange() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/negotiated",
              (_, response) -> {
                response.addHeader("Vary", "Origin");
                var type = response.negotiate(MediaType.APPLICATION_JSON);
                response.body(type, new byte[] {1});
              });
      app.start();
      var outgoing =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/negotiated"))
              .header("Accept", "*/*;q=1, application/json;q=0")
              .build();
      var result = client.send(outgoing, HttpResponse.BodyHandlers.ofString());
      assertEquals(406, result.statusCode());
      assertEquals("Not Acceptable", result.body());
      assertEquals(List.of("Origin", "Accept"), result.headers().allValues("Vary"));
    }
  }

  @Test
  void negotiatesTheHighestWeightedAcceptCandidate() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/negotiated",
              (_, response) -> {
                var type = response.negotiate(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN);
                response.body(type, type.value().getBytes(StandardCharsets.UTF_8));
              });
      app.start();
      var outgoing =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/negotiated"))
              .header("Accept", "application/json;q=0.5, text/plain")
              .build();
      var result = client.send(outgoing, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("text/plain", result.body());
      assertEquals("text/plain", result.headers().firstValue("Content-Type").orElseThrow());
      assertEquals("Accept", result.headers().firstValue("Vary").orElseThrow());
    }
  }

  @Test
  void negotiatesTheFirstCandidateWithoutAcceptAndAddsVary() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/negotiated",
              (_, response) -> {
                var type = response.negotiate(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN);
                response.body(type, type.value().getBytes(StandardCharsets.UTF_8));
              });
      app.start();
      var result = client.send(request(app, "/negotiated"), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("application/json", result.body());
      assertEquals("application/json", result.headers().firstValue("Content-Type").orElseThrow());
      assertEquals("Accept", result.headers().firstValue("Vary").orElseThrow());
    }
  }

  @Test
  void sendsFiniteBytesWithTheSelectedCharset() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/latin",
              (_, response) -> {
                var bytes = new byte[] {(byte) 0xe9};
                response.body(MediaType.TEXT_PLAIN.withCharset(StandardCharsets.ISO_8859_1), bytes);
                bytes[0] = 0;
              });
      app.start();
      var result = client.send(request(app, "/latin"), HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(200, result.statusCode());
      assertEquals(
          "text/plain; charset=ISO-8859-1",
          result.headers().firstValue("Content-Type").orElseThrow());
      assertArrayEquals(new byte[] {(byte) 0xe9}, result.body());
    }
  }

  @Test
  void streamsTypedContentWithinTheHandlerLifetime() throws Exception {
    var release = new CountDownLatch(1);
    var retained = new AtomicReference<Response.Stream>();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/stream",
              (_, response) -> {
                var stream =
                    response.startStream(MediaType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8));
                retained.set(stream);
                stream.write("first");
                stream.flush();
                if (!release.await(5, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("Timed out waiting for reader");
                }

                stream.write("last");
              });
      app.start();

      try {
        var result =
            client.send(request(app, "/stream"), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(
            "text/plain; charset=UTF-8", result.headers().firstValue("Content-Type").orElseThrow());

        try (var input = result.body()) {
          assertEquals("first", new String(input.readNBytes(5), StandardCharsets.UTF_8));
          release.countDown();
          assertEquals("last", new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }

        var closedStream = retained.get();
        assertThrows(IllegalStateException.class, () -> closedStream.write("late"));
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void retainsParameterizedStringContentTypes() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/custom",
              (_, response) ->
                  response.body("application/example; note=\"a b\"", new byte[] {1, 2, 3}));
      app.start();
      var result = client.send(request(app, "/custom"), HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(
          "application/example; note=\"a b\"",
          result.headers().firstValue("Content-Type").orElseThrow());
      assertArrayEquals(new byte[] {1, 2, 3}, result.body());
    }
  }

  private static HttpRequest request(Shoostr app, String path) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
        .timeout(Duration.ofSeconds(3))
        .build();
  }

  private static void negotiate(Request request, Response response) {
    var type =
        response.negotiate(
            MediaType.APPLICATION_JSON,
            MediaType.TEXT_PLAIN,
            MediaType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8));
    response.body(type, type.value().getBytes(StandardCharsets.UTF_8));
  }

  private static void negotiateUtf8Text(Request request, Response response) {
    var type = response.negotiate(MediaType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8));
    response.body(type, type.value().getBytes(StandardCharsets.UTF_8));
  }

  private static Stream<Arguments> nullAdditionalAcceptCases() {
    return Stream.of(
        Arguments.of("", 200, "application/json", List.of("Accept")),
        Arguments.of("application/json", 200, "application/json", List.of("Accept")),
        Arguments.of("application/json;q=0", 406, "Not Acceptable", List.of("Accept")),
        Arguments.of("bad", 400, "Bad Request", List.of()));
  }

  private static Stream<Arguments> acceptCases() {
    return Stream.of(
        Arguments.of("*/*", MediaType.APPLICATION_JSON),
        Arguments.of("text/*", MediaType.TEXT_PLAIN),
        Arguments.of("application/*;q=0.5, text/*;q=0.7", MediaType.TEXT_PLAIN),
        Arguments.of("application/json;q=0.8, */*;q=0.9", MediaType.TEXT_PLAIN),
        Arguments.of("application/json;q=0.5, text/plain;q=0.5", MediaType.APPLICATION_JSON),
        Arguments.of(
            "text/plain; charset=utf-8", MediaType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8)),
        Arguments.of(
            "text/plain; charset=\"UTF-8\"",
            MediaType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8)),
        Arguments.of("application/json;q=1., text/plain;q=0.", MediaType.APPLICATION_JSON));
  }

  private static Stream<Arguments> malformedAcceptFields() {
    return Stream.of(
        Arguments.of("text"),
        Arguments.of("text/plain;q=1.0000"),
        Arguments.of("text/plain;q=\"0.5\""),
        Arguments.of("text/plain;q=1;q=0"),
        Arguments.of("text /plain"),
        Arguments.of("text/plain;charset =utf-8"),
        Arguments.of(";text/plain"),
        Arguments.of(";;text/plain"));
  }

  private static Stream<Arguments> emptyAcceptFields() {
    return Stream.of(Arguments.of(""), Arguments.of(","));
  }
}
