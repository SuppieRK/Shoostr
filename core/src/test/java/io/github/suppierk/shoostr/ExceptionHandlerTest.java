package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.exceptions.ContentTooLargeException;
import io.github.suppierk.shoostr.http.exceptions.HttpClientException;
import io.github.suppierk.shoostr.http.exceptions.HttpException;
import io.github.suppierk.shoostr.http.exceptions.NotFoundException;
import io.github.suppierk.shoostr.http.exceptions.UnauthorizedException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class ExceptionHandlerTest {
  private Shoostr app;
  private HttpClient client;

  @BeforeEach
  void prepare() {
    app = new Shoostr(Options.defaults().withPort(0));
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  }

  @AfterEach
  void close() throws Exception {
    try {
      client.close();
    } finally {
      app.close();
    }
  }

  @Test
  void writesCustomErrorsWithALiveRequestAndCleanResponse() throws Exception {
    app.exception(
        UnauthorizedException.class,
        (failure, request, response) ->
            response
                .status(401)
                .header(HttpHeaders.WWW_AUTHENTICATE.value(), "Bearer realm=\"api\"")
                .text(request.method() + " " + request.pathParam("id") + " " + request.bodyText()));
    app.routes()
        .post(
            "/orders/{id}",
            (request, response) -> {
              response.status(201).header("X-Leak", "secret").text("discard");
              throw new UnauthorizedException("private diagnostics");
            });
    app.start();
    var result =
        client.send(
            request("/orders/42").POST(HttpRequest.BodyPublishers.ofString("payload")).build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(401, result.statusCode());
    assertEquals("POST 42 payload", result.body());
    assertEquals(
        "Bearer realm=\"api\"", result.headers().firstValue("WWW-Authenticate").orElseThrow());
    assertTrue(result.headers().firstValue("X-Leak").isEmpty());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void selectsMostSpecificSuperclass(boolean specificFirst) throws Exception {
    ExceptionHandler<HttpException> broad = (failure, request, response) -> response.text("broad");
    ExceptionHandler<HttpClientException> narrow =
        (failure, request, response) -> response.text("client");
    if (specificFirst) {
      app.exception(HttpClientException.class, narrow).exception(HttpException.class, broad);
    } else {
      app.exception(HttpException.class, broad).exception(HttpClientException.class, narrow);
    }

    app.exception(Exception.class, (failure, request, response) -> response.text("catch-all"));
    app.routes()
        .get(
            "/missing",
            (request, response) -> {
              throw new NotFoundException();
            });
    app.start();
    var result = client.send(request("/missing").build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(404, result.statusCode());
    assertEquals("client", result.body());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void fallsBackOnceWhenCustomHandlingFails(int failureMode) throws Exception {
    var calls = new AtomicInteger();
    app.exception(
        Exception.class,
        (failure, request, response) -> {
          calls.incrementAndGet();
          response.header("X-Leak", "private").text("private body");
          if (failureMode == 0) {
            throw new IllegalArgumentException("private failure");
          }

          if (failureMode == 1) {
            throw new UnauthorizedException("private failure");
          }

          response.status(204);
        });
    app.routes()
        .get(
            "/error",
            (request, response) -> {
              throw new NotFoundException();
            });
    app.start();
    var result = client.send(request("/error").build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(500, result.statusCode());
    assertEquals("Internal Server Error", result.body());
    assertTrue(result.headers().firstValue("X-Leak").isEmpty());
    assertEquals(1, calls.get());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void rejectsDuplicateOrLateRegistration(int phase) throws Exception {
    app.exception(Exception.class, (failure, request, response) -> response.text("first"));
    if (phase == 1) {
      app.start();
    } else if (phase == 2) {
      app.close();
    }

    Class<? extends RuntimeException> expected =
        phase == 0 ? IllegalArgumentException.class : IllegalStateException.class;
    assertThrows(
        expected,
        () ->
            app.exception(
                Exception.class, (failure, request, response) -> response.text("second")));
  }

  @Test
  void exposesMatchedParametersForEarlyBodyLimitFailures() throws Exception {
    app.close();
    app = new Shoostr(new Options("127.0.0.1", 0, 4, 1024, 8, 5000));
    app.exception(
        ContentTooLargeException.class,
        (failure, request, response) -> response.text("too large: " + request.pathParam("id")));
    app.routes().post("/orders/{id}", (request, response) -> response.text("must not run"));
    app.start();
    var result =
        client.send(
            request("/orders/42").POST(HttpRequest.BodyPublishers.ofString("12345")).build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(413, result.statusCode());
    assertEquals("too large: 42", result.body());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void abortsCommittedOutputWithoutRedispatch(boolean inErrorHandler) throws Exception {
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    Handler streamHandler =
        (request, response) -> {
          var stream = response.startStream("text/plain");
          stream.write("visible");
          stream.flush();
          if (!release.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Reader timed out");
          }

          stream.write("secret");
          throw new NotFoundException();
        };
    app.exception(
        Exception.class,
        (failure, request, response) -> {
          calls.incrementAndGet();
          if (inErrorHandler) {
            streamHandler.handle(request, response);
          } else {
            response.text("must not run");
          }
        });
    app.routes()
        .get(
            "/stream",
            (request, response) -> {
              if (inErrorHandler) {
                throw new NotFoundException();
              }

              streamHandler.handle(request, response);
            });
    app.start();

    try {
      var result =
          client.send(request("/stream").build(), HttpResponse.BodyHandlers.ofInputStream());
      assertEquals(inErrorHandler ? 404 : 200, result.statusCode());

      try (var input = result.body()) {
        assertEquals("visible", new String(input.readNBytes(7), StandardCharsets.UTF_8));
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
        assertTrue(received.isEmpty());
      }

      assertEquals(inErrorHandler ? 1 : 0, calls.get());
    } finally {
      release.countDown();
    }
  }

  @Test
  void registrationRacingStartupIsIncludedOrRejected() throws Exception {
    app.routes()
        .get(
            "/error",
            (request, response) -> {
              throw new NotFoundException();
            });
    var release = new CountDownLatch(1);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var registration =
          executor.submit(
              () -> {
                release.await();

                try {
                  app.exception(
                      NotFoundException.class,
                      (failure, request, response) -> response.text("registered"));
                  return true;
                } catch (IllegalStateException started) {
                  return false;
                }
              });
      var startup =
          executor.submit(
              () -> {
                release.await();
                return app.start();
              });
      release.countDown();
      startup.get(5, TimeUnit.SECONDS);
      boolean accepted = registration.get(5, TimeUnit.SECONDS);
      var result = client.send(request("/error").build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(404, result.statusCode());
      assertEquals(accepted ? "registered" : "Not Found", result.body());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {404, 405, 500})
  void doesNotMapOrdinaryStatusesOrUnwrapCauses(int status) throws Exception {
    app.exception(
        HttpException.class, (failure, request, response) -> response.text("must not run"));
    app.routes().get("/known", (request, response) -> response.text("ok"));
    app.routes()
        .get(
            "/wrapped",
            (request, response) -> {
              throw new CompletionException(new NotFoundException());
            });
    app.start();
    var outgoing =
        switch (status) {
          case 404 -> request("/absent").build();
          case 405 -> request("/known").POST(HttpRequest.BodyPublishers.noBody()).build();
          default -> request("/wrapped").build();
        };
    var result = client.send(outgoing, HttpResponse.BodyHandlers.ofString());
    assertEquals(status, result.statusCode());
    assertEquals(
        switch (status) {
          case 404 -> "Not found";
          case 405 -> "Method not allowed";
          default -> "Internal Server Error";
        },
        result.body());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void abortsFatalErrors(boolean inErrorHandler) throws Exception {
    var calls = new AtomicInteger();
    app.exception(
        Exception.class,
        (failure, request, response) -> {
          calls.incrementAndGet();
          throw new Error("fatal mapper failure");
        });
    app.routes()
        .post(
            "/fatal",
            (request, response) -> {
              if (inErrorHandler) {
                throw new NotFoundException();
              }

              throw new Error("fatal route failure");
            });
    app.start();
    assertThrows(
        IOException.class,
        () ->
            client.send(
                request("/fatal").POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()));
    assertEquals(inErrorHandler ? 1 : 0, calls.get());
  }

  @Test
  void handlesInvalidOutputDetectedAfterRouteReturn() throws Exception {
    app.exception(
        IllegalStateException.class,
        (failure, request, response) -> response.text("invalid response for " + request.path()));
    app.routes()
        .get("/invalid", (request, response) -> response.status(204).text("forbidden body"));
    app.start();
    var result = client.send(request("/invalid").build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(500, result.statusCode());
    assertEquals("invalid response for /invalid", result.body());
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
        .timeout(Duration.ofSeconds(3));
  }
}
