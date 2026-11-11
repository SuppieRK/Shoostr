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
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class ExceptionHandlerTest {
  private Shoostr app;

  @BeforeEach
  void prepare() {
    app =
        new Shoostr()
            .modifyServer(
                nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));
  }

  @AfterEach
  void close() throws Exception {
    app.close();
  }

  @Test
  void writesCustomErrorsWithLiveRequestAndCleanResponse() throws Exception {
    app.exception(
        UnauthorizedException.class,
        (ignored, request, response) ->
            response
                .status(401)
                .setHeader(HttpHeaders.WWW_AUTHENTICATE.value(), "Bearer realm=\"api\"")
                .text(
                    request.method()
                        + " "
                        + request.pathParam("id").orElseThrow()
                        + " "
                        + request.bodyText()));
    app.routes()
        .post(
            "/orders/{id}",
            (ignoredRequest, response) -> {
              response.status(201).setHeader("X-Leak", "secret").text("discard");
              throw new UnauthorizedException("private diagnostics");
            });

    try (var test = TestServer.start(app)) {
      var result = sendResultForWritesCustomErrorsWithLiveRequestAndCleanResponse(test);

      assertEquals(401, result.statusCode());
      assertEquals("POST 42 payload", result.body());
      assertEquals(
          "Bearer realm=\"api\"", result.headers().firstValue("WWW-Authenticate").orElseThrow());
      assertTrue(result.headers().firstValue("X-Leak").isEmpty());
    }
  }

  private HttpResponse<String> sendResultForWritesCustomErrorsWithLiveRequestAndCleanResponse(
      TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/orders/42")
                .timeout(Duration.ofSeconds(3))
                .method("POST")
                .body("payload".getBytes(StandardCharsets.UTF_8)),
        HttpResponse.BodyHandlers.ofString());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void selectsMostSpecificSuperclass(boolean specificFirst) throws Exception {
    ExceptionHandler<HttpException> broad =
        (ignoredFailure, ignoredRequest, response) -> response.text("broad");
    ExceptionHandler<HttpClientException> narrow =
        (ignoredFailure, ignoredRequest, response) -> response.text("client");
    if (specificFirst) {
      app.exception(HttpClientException.class, narrow).exception(HttpException.class, broad);
    } else {
      app.exception(HttpException.class, broad).exception(HttpClientException.class, narrow);
    }

    app.exception(
        Exception.class, (ignoredFailure, ignoredRequest, response) -> response.text("catch-all"));
    app.routes()
        .get(
            "/missing",
            (ignoredRequest, ignoredResponse) -> {
              throw new NotFoundException();
            });

    try (var test = TestServer.start(app)) {
      var result =
          test.send(
              request -> request.path("/missing").timeout(Duration.ofSeconds(3)),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(404, result.statusCode());
      assertEquals("client", result.body());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void fallsBackOnceWhenCustomHandlingFails(int failureMode) throws Exception {
    var calls = new AtomicInteger();
    app.exception(
        Exception.class,
        (ignoredFailure, ignoredRequest, response) -> {
          calls.incrementAndGet();
          response.setHeader("X-Leak", "private").text("private body");
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
            (ignoredRequest, ignoredResponse) -> {
              throw new NotFoundException();
            });

    try (var test = TestServer.start(app)) {
      var result =
          test.send(
              request -> request.path("/error").timeout(Duration.ofSeconds(3)),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(500, result.statusCode());
      assertEquals("Internal Server Error", result.body());
      assertTrue(result.headers().firstValue("X-Leak").isEmpty());
      assertEquals(1, calls.get());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void rejectsDuplicateOrLateRegistration(int phase) throws Exception {
    app.exception(
        Exception.class, (ignoredFailure, ignoredRequest, response) -> response.text("first"));
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
                Exception.class,
                (ignoredFailure, ignoredRequest, response) -> response.text("second")));
  }

  @Test
  void exposesMatchedParametersForEarlyBodyLimitFailures() throws Exception {
    app.close();
    var configuredOptions = new Options();
    configuredOptions.setMaxRequestBytes(4);
    configuredOptions.setMaxResponseBytes(1024);
    configuredOptions.setStreamBufferBytes(8);
    configuredOptions.setMaxParameters(1000);
    configuredOptions.setMultipart(MultipartOptions.defaults());

    app =
        new Shoostr(configuredOptions)
            .modifyServer(
                nativeServer -> {
                  var nativeConnector = (ServerConnector) nativeServer.getConnectors()[0];
                  nativeConnector.setHost("127.0.0.1");
                  nativeConnector.setPort(0);
                  nativeConnector.setIdleTimeout(5000);
                });
    app.exception(
        ContentTooLargeException.class,
        (ignoredFailure, request, response) ->
            response.text("too large: " + request.pathParam("id").orElseThrow()));
    app.routes().post("/orders/{id}", (ignoredRequest, response) -> response.text("must not run"));

    try (var test = TestServer.start(app)) {
      var result = sendResultForExposesMatchedParametersForEarlyBodyLimitFailures(test);

      assertEquals(413, result.statusCode());
      assertEquals("too large: 42", result.body());
    }
  }

  private HttpResponse<String> sendResultForExposesMatchedParametersForEarlyBodyLimitFailures(
      TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/orders/42")
                .timeout(Duration.ofSeconds(3))
                .method("POST")
                .body("12345".getBytes(StandardCharsets.UTF_8)),
        HttpResponse.BodyHandlers.ofString());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void abortsCommittedOutputWithoutRedispatch(boolean inErrorHandler) throws Exception {
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    Handler streamHandler = createStreamHandlerForAbortsCommittedOutputWithoutRedispatch(release);
    app.exception(
        Exception.class,
        (ignoredFailure, request, response) -> {
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

    try (var test = TestServer.start(app)) {

      try {
        var result =
            test.send(
                request -> request.path("/stream").timeout(Duration.ofSeconds(3)),
                HttpResponse.BodyHandlers.ofInputStream());

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
          assertEquals("", received.toString());
        }

        assertEquals(inErrorHandler ? 1 : 0, calls.get());
      } finally {
        release.countDown();
      }
    }
  }

  private Handler createStreamHandlerForAbortsCommittedOutputWithoutRedispatch(
      CountDownLatch release) throws IOException, InterruptedException {
    return (ignoredRequest, response) -> {
      var stream = response.startStream("text/plain");
      stream.write("visible");
      stream.flush();
      if (!release.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Reader timed out");
      }

      stream.write("secret");
      throw new NotFoundException();
    };
  }

  @Test
  void registrationRacingStartupIsIncludedOrRejected() throws Exception {
    app.routes()
        .get(
            "/error",
            (ignoredRequest, ignoredResponse) -> {
              throw new NotFoundException();
            });
    var release = new CountDownLatch(1);

    // Native startup must remain independent of the harness's additional app monitor.

    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
      var registration =
          submitRegistrationForRegistrationRacingStartupIsIncludedOrRejected(executor, release);
      var startup =
          submitStartupForRegistrationRacingStartupIsIncludedOrRejected(executor, release);
      release.countDown();

      startup.get(5, TimeUnit.SECONDS);
      boolean accepted = registration.get(5, TimeUnit.SECONDS);
      var result = sendResultForRegistrationRacingStartupIsIncludedOrRejected(client);

      assertEquals(404, result.statusCode());
      assertEquals(accepted ? "registered" : "Not Found", result.body());
    }
  }

  private Future<Boolean> submitRegistrationForRegistrationRacingStartupIsIncludedOrRejected(
      ExecutorService executor, CountDownLatch release) {
    return executor.submit(
        () -> {
          release.await();

          try {
            app.exception(
                NotFoundException.class,
                (ignoredFailure, ignoredRequest, response) -> response.text("registered"));

            return true;
          } catch (IllegalStateException ignoredFailure) {
            return false;
          }
        });
  }

  private Future<Shoostr> submitStartupForRegistrationRacingStartupIsIncludedOrRejected(
      ExecutorService executor, CountDownLatch release) {
    return executor.submit(
        () -> {
          release.await();

          return app.start();
        });
  }

  private HttpResponse<String> sendResultForRegistrationRacingStartupIsIncludedOrRejected(
      HttpClient client) throws IOException, InterruptedException {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/error"))
            .timeout(Duration.ofSeconds(3))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @ParameterizedTest
  @ValueSource(ints = {404, 405, 500})
  void doesNotMapOrdinaryStatusesOrUnwrapCauses(int status) throws Exception {

    app.exception(
        HttpException.class,
        (ignoredFailure, ignoredRequest, response) -> response.text("must not run"));
    app.routes().get("/known", (ignoredRequest, response) -> response.text("ok"));
    app.routes()
        .get(
            "/wrapped",
            (ignoredRequest, ignoredResponse) -> {
              throw new CompletionException(new NotFoundException());
            });

    try (var test = TestServer.start(app)) {
      var path = createPathForDoesNotMapOrdinaryStatusesOrUnwrapCauses(status);
      var result = sendResultForDoesNotMapOrdinaryStatusesOrUnwrapCauses(test, path, status);

      assertEquals(status, result.statusCode());
      assertEquals(
          switch (status) {
            case 404 -> "Not found";
            case 405 -> "Method not allowed";
            default -> "Internal Server Error";
          },
          result.body());
    }
  }

  private String createPathForDoesNotMapOrdinaryStatusesOrUnwrapCauses(int status) {
    return switch (status) {
      case 404 -> "/absent";
      case 405 -> "/known";
      default -> "/wrapped";
    };
  }

  private HttpResponse<String> sendResultForDoesNotMapOrdinaryStatusesOrUnwrapCauses(
      TestServer test, String path, int status) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path(path)
                .method(status == 405 ? "POST" : "GET")
                .timeout(Duration.ofSeconds(3)),
        HttpResponse.BodyHandlers.ofString());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void abortsFatalErrors(boolean inErrorHandler) throws Exception {
    var calls = new AtomicInteger();
    app.exception(
        Exception.class,
        (ignoredFailure, ignoredRequest, ignoredResponse) -> {
          calls.incrementAndGet();
          throw new Error("fatal mapper failure");
        });
    app.routes()
        .post(
            "/fatal",
            (ignoredRequest, ignoredResponse) -> {
              if (inErrorHandler) {
                throw new NotFoundException();
              }

              throw new Error("fatal route failure");
            });

    try (var test = TestServer.start(app)) {
      assertThrows(
          IOException.class,
          () ->
              test.send(
                  request -> request.path("/fatal").timeout(Duration.ofSeconds(3)).method("POST"),
                  HttpResponse.BodyHandlers.ofString()));
      assertEquals(inErrorHandler ? 1 : 0, calls.get());
    }
  }

  @Test
  void handlesInvalidOutputDetectedAfterRouteReturn() throws Exception {
    app.exception(
        IllegalStateException.class,
        (ignoredFailure, request, response) ->
            response.text("invalid response for " + request.path()));
    app.routes()
        .get("/invalid", (ignoredRequest, response) -> response.status(204).text("forbidden body"));

    try (var test = TestServer.start(app)) {
      var result =
          test.send(
              request -> request.path("/invalid").timeout(Duration.ofSeconds(3)),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(500, result.statusCode());
      assertEquals("invalid response for /invalid", result.body());
    }
  }
}
