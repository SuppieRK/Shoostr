package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.HttpStatusCodes;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import io.github.suppierk.shoostr.http.exceptions.ContentTooLargeException;
import io.github.suppierk.shoostr.http.exceptions.HttpClientException;
import io.github.suppierk.shoostr.http.exceptions.HttpException;
import io.github.suppierk.shoostr.http.exceptions.HttpServerException;
import io.github.suppierk.shoostr.http.exceptions.NotFoundException;
import io.github.suppierk.shoostr.http.exceptions.ServiceUnavailableException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class HttpExceptionHandlingTest {
  @ParameterizedTest
  @MethodSource("httpErrors")
  void replacesStagedOutputWithStatusAndSafeReasonPhrase(HttpException failure) throws Exception {
    var retainedRequest = new AtomicReference<Request>();
    var retainedResponse = new AtomicReference<Response>();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/error",
              (request, response) -> {
                retainedRequest.set(request);
                retainedResponse.set(response);
                response
                    .status(201)
                    .header("X-Leak", "secret")
                    .body("text/html", new byte[] {1, 2});
                throw failure;
              });
      app.start();
      var result =
          client.send(request(app, "/error").build(), HttpResponse.BodyHandlers.ofString());

      assertEquals(failure.statusCode().value(), result.statusCode());
      assertEquals(failure.statusCode().reasonPhrase(), result.body());
      assertTrue(result.headers().firstValue("X-Leak").isEmpty());
      assertEquals(
          "text/plain; charset=utf-8", result.headers().firstValue("Content-Type").orElseThrow());
      assertEquals(
          result.body().getBytes(StandardCharsets.UTF_8).length,
          result.headers().firstValueAsLong("Content-Length").orElseThrow());
      var closedRequest = retainedRequest.get();
      var closedResponse = retainedResponse.get();
      assertThrows(IllegalStateException.class, closedRequest::path);
      assertThrows(IllegalStateException.class, () -> closedResponse.text("too late"));
    }
  }

  @Test
  void doesNotUnwrapAnHttpExceptionCause() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/error",
              (request, response) -> {
                throw new CompletionException(new NotFoundException("secret"));
              });
      app.start();
      var result =
          client.send(request(app, "/error").build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(500, result.statusCode());
      assertEquals("Internal Server Error", result.body());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void omitsReasonPhraseWhenItExceedsTheResponseLimit(boolean httpFailure) throws Exception {
    var options = new Options("127.0.0.1", 0, 16, 1, 8, 5000);

    try (var app = new Shoostr(options);
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/error",
              (request, response) -> {
                response.text("x");
                if (httpFailure) {
                  throw new ServiceUnavailableException("secret");
                }

                throw new IllegalStateException("secret");
              });
      app.start();
      var result =
          client.send(request(app, "/error").build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(httpFailure ? 503 : 500, result.statusCode());
      assertTrue(result.body().isEmpty());
      assertEquals(0, result.headers().firstValueAsLong("Content-Length").orElseThrow());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void abortsCommittedStreamsWithoutReplacingTheirStatusOrFlushingPendingData(boolean serverError)
      throws Exception {
    var release = new CountDownLatch(1);
    var retainedStream = new AtomicReference<Response.Stream>();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/stream",
              (request, response) -> {
                var stream = response.startStream("text/plain; charset=utf-8");
                retainedStream.set(stream);
                stream.write("partial\n");
                stream.flush();
                if (!release.await(5, TimeUnit.SECONDS)) {
                  throw new IOException("test timeout");
                }

                stream.write("pending secret");
                if (serverError) {
                  throw new ServiceUnavailableException("secret");
                }

                throw new BadRequestException("secret");
              });
      app.start();

      try {
        var result =
            client.send(request(app, "/stream").build(), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, result.statusCode());

        try (var input = result.body()) {
          assertEquals("partial\n", new String(input.readNBytes(8), StandardCharsets.UTF_8));
          release.countDown();
          var received = new StringBuilder();
          assertThrows(
              IOException.class,
              () -> {
                int value;
                while ((value = input.read()) != -1) {
                  received.append((char) value);
                }
              });
          assertTrue(received.isEmpty(), "No pending bytes or replacement error body may be sent");
        }

        var closedStream = retainedStream.get();
        assertThrows(IllegalStateException.class, () -> closedStream.write("too late"));
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void handlersCanCatchBodyLimitErrorsThroughThePublicHierarchy() throws Exception {
    var options = new Options("127.0.0.1", 0, 4, 1024, 8, 5000);

    try (var app = new Shoostr(options);
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .post(
              "/body",
              (request, response) -> {
                assertThrows(ContentTooLargeException.class, request::bodyBytes);
                response.status(413).text("handled by application");
              });
      app.start();
      var body =
          HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(new byte[5]));
      var result =
          client.send(
              request(app, "/body").POST(body).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(413, result.statusCode());
      assertEquals("handled by application", result.body());
    }
  }

  @Test
  void suppressesErrorBodyForHeadRequests() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .head(
              "/error",
              (request, response) -> {
                throw new NotFoundException("secret");
              });
      app.start();
      var result =
          client.send(
              request(app, "/error").method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(404, result.statusCode());
      assertTrue(result.body().isEmpty());
      assertFalse(result.headers().firstValue("Content-Type").isEmpty());
    }
  }

  private static Stream<HttpException> httpErrors() {
    var cause = new IllegalArgumentException("internal cause");
    return Stream.of(
        new BadRequestException("secret", cause),
        new NotFoundException(),
        new ServiceUnavailableException("secret", cause),
        new HttpClientException(HttpStatusCodes.TOO_MANY_REQUESTS, "secret", cause) {},
        new HttpServerException(HttpStatusCodes.BAD_GATEWAY, "secret", cause) {},
        new HttpException(HttpStatusCodes.NETWORK_AUTHENTICATION_REQUIRED, "secret", cause) {});
  }

  private static HttpRequest.Builder request(Shoostr app, String path) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
        .timeout(Duration.ofSeconds(5));
  }
}
