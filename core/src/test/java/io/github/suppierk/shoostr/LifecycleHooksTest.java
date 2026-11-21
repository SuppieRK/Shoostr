package io.github.suppierk.shoostr;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.http.exceptions.ContentTooLargeException;
import io.github.suppierk.shoostr.http.exceptions.NotFoundException;
import io.github.suppierk.shoostr.http.exceptions.UnauthorizedException;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class LifecycleHooksTest {
  private Shoostr app;

  private static void readHeaders(Socket socket) throws IOException {
    int suffix = 0;
    for (int count = 0; count < 8192; count++) {
      int value = socket.getInputStream().read();
      if (value < 0) {
        throw new IOException("Response ended before headers");
      }

      suffix = (suffix << 8) | value;
      if (suffix == 0x0D0A0D0A) {
        return;
      }
    }
    throw new IOException("Header limit exceeded");
  }

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
  void exposesComposedRoutePatternThroughErrorHandling() throws Exception {
    var retained = new AtomicReference<Request>();
    app.exception(
        NotFoundException.class,
        (ignored, request, response) -> response.text(request.routePattern().orElseThrow()));
    app.routes()
        .path(
            "/accounts/{accountId}",
            routes ->
                routes.get(
                    "/orders/{id}",
                    (request, ignoredResponse) -> {
                      retained.set(request);

                      assertEquals(
                          "/accounts/{accountId}/orders/{id}",
                          request.routePattern().orElseThrow());
                      assertEquals("42", request.pathParam("id").orElseThrow());

                      throw new NotFoundException();
                    }));

    try (var test = TestServer.start(app)) {
      var result =
          test.send(
              request -> request.path("/accounts/a/orders/42").timeout(Duration.ofSeconds(3)),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(404, result.statusCode());
      assertEquals("/accounts/{accountId}/orders/{id}", result.body());
      assertThrows(IllegalStateException.class, retained.get()::routePattern);
    }
  }

  @Test
  void exposesDecodedPathParametersToTheMatchedHookBeforeTheHandler() throws Exception {
    app.onRouteMatched((request, ignoredResponse) ->
        request.attribute("matched-id", request.pathParam("id").orElseThrow()));
    app.routes()
        .get(
            "/users/{id}",
            (request, response) ->
                response.text(request.attribute("matched-id").orElseThrow().toString()));

    try (var test = TestServer.start(app)) {

      var result =
          test.send(
              request -> request.path("/users/caf%C3%A9+team").timeout(Duration.ofSeconds(3)),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(200, result.statusCode());
      assertEquals("café+team", result.body());
    }
  }

  @Test
  void ordersGatesAndStopsOnRejection() throws Exception {
    var calls = new CopyOnWriteArrayList<String>();
    app.beforeRouteHandler((request, response) -> {
      calls.add("first:" + request.routePattern().orElseThrow());
      response.setHeader("X-Staged", "discard");
      if (request.header("Authorization").isEmpty()) {
        throw new UnauthorizedException();
      }
    });
    app.beforeRouteHandler((ignoredRequest, response) -> {
      calls.add("second");
      response.status(202).text("staged");
    });
    app.exception(
        UnauthorizedException.class,
        (ignoredFailure, ignoredRequest, response) -> {
          calls.add("error");
          response.setHeader("WWW-Authenticate", "Bearer").text("denied");
        });
    app.routes()
        .get(
            "/orders/{id}",
            (ignoredRequest, response) -> {
              calls.add("route");
              response.text("accepted");
            });

    try (var test = TestServer.start(app)) {
      var denied =
          test.send(
              request -> request.path("/orders/42").timeout(Duration.ofSeconds(3)),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(401, denied.statusCode());
      assertEquals("Bearer", denied.headers().firstValue("WWW-Authenticate").orElseThrow());
      assertTrue(denied.headers().firstValue("X-Staged").isEmpty());
      assertEquals(List.of("first:/orders/{id}", "error"), calls);

      calls.clear();
      var accepted = sendAcceptedRequestForOrdersGatesAndStopsOnRejection(test);

      assertEquals(202, accepted.statusCode());
      assertEquals("accepted", accepted.body());
      assertEquals(List.of("first:/orders/{id}", "second", "route"), calls);
    }
  }

  private HttpResponse<String> sendAcceptedRequestForOrdersGatesAndStopsOnRejection(TestServer test)
      throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/orders/42")
                .timeout(Duration.ofSeconds(3))
                .header("Authorization", "Bearer token"),
        HttpResponse.BodyHandlers.ofString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"stream", "typed-stream", "close"})
  void rejectsGateStreamingBeforeCommit(String operation) throws Exception {
    var entered = new AtomicBoolean();
    app.beforeRouteHandler((ignoredRequest, response) -> {
      switch (operation) {
        case "stream" -> response.startStream("text/plain");
        case "typed-stream" -> response.startStream(MediaType.TEXT_PLAIN);
        default -> response.close();
      }
    });
    app.exception(
        IllegalStateException.class,
        (ignoredFailure, ignoredRequest, response) ->
            response.startStream(MediaType.TEXT_PLAIN).write("rejected before commit"));
    app.routes().get("/gate", (ignoredRequest, ignoredResponse) -> entered.set(true));

    try (var test = TestServer.start(app)) {
      var result =
          test.send(
              request -> request.path("/gate").timeout(Duration.ofSeconds(3)),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(500, result.statusCode());
      assertEquals("rejected before commit", result.body());
      assertFalse(entered.get());
    }
  }

  @Test
  void observesCompletedFiniteResponse() throws Exception {
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();
    var retained = new AtomicReference<Request>();
    app.afterRequest(outcome -> {
      assertThrows(IllegalStateException.class, retained.get()::routePattern);

      outcomes.add(outcome);
    });
    app.routes()
        .get(
            "/orders/{id}",
            (request, response) -> {
              retained.set(request);
              response.status(201).text("created");
            });

    try (var test = TestServer.start(app)) {
      var requestStarted = System.nanoTime();
      assertEquals(
          201,
          test.send(
                  request -> request.path("/orders/42").timeout(Duration.ofSeconds(3)),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode());

      var outcome = outcomes.poll(3, TimeUnit.SECONDS);
      final var measuredElapsed = System.nanoTime() - requestStarted;

      assertNotNull(outcome);
      assertEquals("GET", outcome.method());
      assertEquals("/orders/{id}", outcome.routePattern());
      assertEquals(201, outcome.statusCode());
      assertTrue(outcome.durationNanos() > 0);
      assertTrue(outcome.durationNanos() <= measuredElapsed);
      assertNull(outcome.applicationFailure());
      assertNull(outcome.transportFailure());
      assertTrue(outcomes.isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void preservesApplicationFailureAfterMapping(boolean mapperFails) throws Exception {
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();
    var original = new NotFoundException("private failure");
    var secondary = new IllegalArgumentException("mapper failure");
    app.afterRequest(outcomes::add);
    app.exception(
        NotFoundException.class,
        (ignoredFailure, ignoredRequest, response) -> {
          if (mapperFails) {
            throw secondary;
          }

          response.status(409).text("mapped");
        });
    app.routes()
        .get(
            "/failure",
            (ignoredRequest, ignoredResponse) -> {
              throw original;
            });

    try (var test = TestServer.start(app)) {
      var result =
          test.send(
              request -> request.path("/failure").timeout(Duration.ofSeconds(3)),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(mapperFails ? 500 : 409, result.statusCode());

      var outcome = outcomes.poll(3, TimeUnit.SECONDS);

      assertNotNull(outcome);
      assertSame(original, outcome.applicationFailure());
      assertNull(outcome.transportFailure());
      assertEquals(result.statusCode(), outcome.statusCode());

      if (mapperFails) {
        assertArrayEquals(
            new Throwable[] {secondary},
            Objects.requireNonNull(outcome.applicationFailure()).getSuppressed());
      }
    }
  }

  @Test
  void continuesAfterAnObserverThrows() throws Exception {
    var calls = new CopyOnWriteArrayList<String>();
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();
    app.afterRequest(ignoredRequestOutcome -> {
      calls.add("first");
      throw new IllegalStateException("broken metrics sink");
    });
    app.afterRequest(outcome -> {
      calls.add("second");
      outcomes.add(outcome);
    });
    app.routes().get("/ok", (ignoredRequest, response) -> response.text("ok"));

    try (var test = TestServer.start(app)) {
      assertEquals(
          "ok",
          test.send(
                  request -> request.path("/ok").timeout(Duration.ofSeconds(3)),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertNotNull(outcomes.poll(3, TimeUnit.SECONDS));
      assertEquals(List.of("first", "second"), calls);
    }
  }

  @Test
  void waitsForFiniteTransportAndReportsDisconnect() throws Exception {
    app.close();
    var configuredOptions = new Options();
    configuredOptions.setMaxRequestBytes(1024);
    configuredOptions.setMaxResponseBytes(16 * 1024 * 1024);
    configuredOptions.setStreamBufferBytes(1024);
    configuredOptions.setMaxParameters(1000);
    configuredOptions.setMultipart(new MultipartOptions());

    app =
        new Shoostr(configuredOptions)
            .modifyServer(nativeServer -> {
              var nativeConnector = (ServerConnector) nativeServer.getConnectors()[0];
              nativeConnector.setHost("127.0.0.1");
              nativeConnector.setPort(0);
              nativeConnector.setIdleTimeout(30_000);
            });
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();
    app.afterRequest(outcomes::add);
    app.routes()
        .get(
            "/large",
            (ignoredRequest, response) ->
                response.body(MediaType.APPLICATION_OCTET_STREAM, new byte[16 * 1024 * 1024]));
    app.start();

    try (var socket = socket()) {
      socket
          .getOutputStream()
          .write(
              "GET /large HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      readHeaders(socket);
      await("pending bytes are not completed output")
          .pollDelay(Duration.ZERO)
          .pollInterval(Duration.ofMillis(10))
          .during(Duration.ofMillis(100))
          .atMost(Duration.ofMillis(1100))
          .failFast("pending transport completed prematurely", () -> !outcomes.isEmpty())
          .until(outcomes::isEmpty);
      socket.setSoLinger(true, 0);
    }

    var outcome = outcomes.poll(3, TimeUnit.SECONDS);

    assertNotNull(outcome);
    assertEquals(200, outcome.statusCode());
    assertNull(outcome.applicationFailure());
    assertNotNull(outcome.transportFailure());
    assertTrue(outcomes.isEmpty());
  }

  @Test
  void observesStreamOnlyAfterHandlerExit() throws Exception {
    var release = new CountDownLatch(1);
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();
    var retained = new AtomicReference<Response.Stream>();
    app.afterRequest(outcomes::add);
    app.routes()
        .get(
            "/stream",
            (ignoredRequest, response) -> {
              var stream = response.startStream(MediaType.TEXT_PLAIN);
              retained.set(stream);
              stream.write("hello");
              stream.flush();
              if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release handler");
              }

              stream.write("world");
            });

    try (var test = TestServer.start(app)) {

      try (var body =
          test.send(
                  request -> request.path("/stream").timeout(Duration.ofSeconds(3)),
                  HttpResponse.BodyHandlers.ofInputStream())
              .body()) {
        assertEquals("hello", new String(body.readNBytes(5), StandardCharsets.UTF_8));

        await("the stream is observed only after handler exit")
            .pollDelay(Duration.ZERO)
            .pollInterval(Duration.ofMillis(10))
            .during(Duration.ofMillis(100))
            .atMost(Duration.ofMillis(1100))
            .failFast("the stream completed before handler exit", () -> !outcomes.isEmpty())
            .until(outcomes::isEmpty);
        release.countDown();

        assertEquals("world", new String(body.readAllBytes(), StandardCharsets.UTF_8));
      } finally {
        release.countDown();
      }

      var outcome = outcomes.poll(3, TimeUnit.SECONDS);

      assertNotNull(outcome);
      assertEquals(200, outcome.statusCode());
      assertNull(outcome.transportFailure());

      var closedStream = retained.get();

      assertThrows(IllegalStateException.class, () -> closedStream.write("late"));
      assertTrue(outcomes.isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void observesRouterMissesWithoutRunningGates(boolean wrongMethod) throws Exception {
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();
    var gates = new AtomicInteger();
    app.afterRequest(outcomes::add);
    app.beforeRouteHandler((ignoredRequest, ignoredResponse) -> gates.incrementAndGet());
    app.routes().get("/known", (ignoredRequest, response) -> response.text("ok"));

    try (var test = TestServer.start(app)) {
      var result = sendResultForObservesRouterMissesWithoutRunningGates(test, wrongMethod);

      assertEquals(wrongMethod ? 405 : 404, result.statusCode());

      var outcome = outcomes.poll(3, TimeUnit.SECONDS);

      assertNotNull(outcome);
      assertEquals(result.statusCode(), outcome.statusCode());
      assertNull(outcome.routePattern());
      assertNull(outcome.applicationFailure());
      assertNull(outcome.transportFailure());
      assertEquals(0, gates.get());
    }
  }

  private HttpResponse<String> sendResultForObservesRouterMissesWithoutRunningGates(
      TestServer test, boolean wrongMethod) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path(wrongMethod ? "/known" : "/missing")
                .timeout(Duration.ofSeconds(3))
                .method("POST"),
        HttpResponse.BodyHandlers.ofString());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void observesFatalAndCommittedFailures(boolean committed) throws Exception {
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();
    Throwable failure =
        committed ? new IllegalStateException("stream failure") : new Error("fatal");
    app.afterRequest(outcomes::add);
    app.routes()
        .post(
            "/abort",
            (ignoredRequest, response) -> {
              if (committed) {
                response.startStream(MediaType.TEXT_PLAIN).write("pending");
                throw (IllegalStateException) failure;
              }

              throw (Error) failure;
            });

    try (var test = TestServer.start(app)) {
      assertThrows(
          IOException.class,
          () ->
              test.send(
                  request -> request.path("/abort").timeout(Duration.ofSeconds(3)).method("POST"),
                  HttpResponse.BodyHandlers.ofString()));

      var outcome = outcomes.poll(3, TimeUnit.SECONDS);

      assertNotNull(outcome);
      assertSame(failure, outcome.applicationFailure());
      assertEquals(committed ? 200 : 0, outcome.statusCode());
      assertNotNull(outcome.transportFailure());
      assertTrue(outcomes.isEmpty());
    }
  }

  @Test
  void waitsForApplicationFinalizationAfterDisconnect() throws Exception {
    var release = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();
    app.afterRequest(outcomes::add);
    app.routes()
        .get(
            "/waiting",
            (ignoredRequest, response) -> {
              var stream = response.startStream(MediaType.TEXT_PLAIN);
              entered.countDown();
              if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release handler");
              }

              stream.write("finished");
              stream.flush();
            });
    app.start();

    try {
      try (var socket = socket()) {
        socket
            .getOutputStream()
            .write(
                "GET /waiting HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        readHeaders(socket);

        assertTrue(entered.await(3, TimeUnit.SECONDS));

        socket.setSoLinger(true, 0);
      }

      await("completion waits for the held lifecycle stage")
          .pollDelay(Duration.ZERO)
          .pollInterval(Duration.ofMillis(10))
          .during(Duration.ofMillis(100))
          .atMost(Duration.ofMillis(1100))
          .failFast(
              "completion was reported before the held stage finished", () -> !outcomes.isEmpty())
          .until(outcomes::isEmpty);
    } finally {
      release.countDown();
    }

    var outcome = outcomes.poll(3, TimeUnit.SECONDS);

    assertNotNull(outcome);
    assertEquals(200, outcome.statusCode());
    assertNotNull(outcome.transportFailure());
    assertTrue(outcomes.isEmpty());
  }

  @Test
  @SuppressWarnings("NullAway")
  // The test checks null hook registration failures.
  void validatesRegistrationLifetime() throws Exception {
    assertThrows(NullPointerException.class, () -> app.beforeRouteHandler(null));
    assertThrows(NullPointerException.class, () -> app.afterRequest(null));

    app.start();

    assertThrows(
        IllegalStateException.class,
        () -> app.beforeRouteHandler((ignoredRequest, ignoredResponse) -> {}));
    assertThrows(IllegalStateException.class, () -> app.afterRequest(ignoredRequestOutcome -> {}));

    app.close();

    assertThrows(
        IllegalStateException.class,
        () -> app.beforeRouteHandler((ignoredRequest, ignoredResponse) -> {}));
    assertThrows(IllegalStateException.class, () -> app.afterRequest(ignoredRequestOutcome -> {}));

    app = new Shoostr();
    app.close();

    assertThrows(
        IllegalStateException.class,
        () -> app.beforeRouteHandler((ignoredRequest, ignoredResponse) -> {}));
    assertThrows(IllegalStateException.class, () -> app.afterRequest(ignoredRequestOutcome -> {}));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void racesRegistrationWithStartup(boolean completion) throws Exception {
    var start = new CountDownLatch(1);
    var called = new CountDownLatch(1);
    app.routes().get("/ok", (ignoredRequest, response) -> response.text("ok"));

    // Native startup must remain independent of the harness's additional app monitor.

    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
      var registration =
          submitRegistrationForRacesRegistrationWithStartup(executor, start, completion, called);
      var startup = submitStartupForRacesRegistrationWithStartup(executor, start);
      start.countDown();

      startup.get(5, TimeUnit.SECONDS);
      boolean accepted = registration.get(5, TimeUnit.SECONDS);

      assertEquals(
          "ok",
          client
              .send(
                  HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/ok"))
                      .timeout(Duration.ofSeconds(3))
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .body());

      if (accepted) {
        assertTrue(called.await(3, TimeUnit.SECONDS));
      } else {
        assertEquals(1, called.getCount());
      }
    }
  }

  private Future<Boolean> submitRegistrationForRacesRegistrationWithStartup(
      ExecutorService executor, CountDownLatch start, boolean completion, CountDownLatch called) {
    return executor.submit(() -> {
      start.await();

      try {
        if (completion) {
          app.afterRequest(ignoredRequestOutcome -> called.countDown());
        } else {
          app.beforeRouteHandler((ignoredRequest, ignoredResponse) -> called.countDown());
        }

        return true;
      } catch (IllegalStateException ignoredFailure) {
        return false;
      }
    });
  }

  private Future<Shoostr> submitStartupForRacesRegistrationWithStartup(
      ExecutorService executor, CountDownLatch start) {
    return executor.submit(() -> {
      start.await();

      return app.start();
    });
  }

  @Test
  void preservesMethodDispatchDefaults() throws Exception {
    var entered = new AtomicInteger();
    app.routes()
        .get(
            "/method",
            (ignoredRequest, response) -> {
              entered.incrementAndGet();
              response.text("GET");
            });

    try (var test = TestServer.start(app)) {
      for (String method : List.of("HEAD", "OPTIONS")) {
        var result =
            test.send(
                request -> request.path("/method").timeout(Duration.ofSeconds(3)).method(method),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(405, result.statusCode());
        assertEquals("GET", result.headers().firstValue("Allow").orElseThrow());
      }
      var result = sendResultForPreservesMethodDispatchDefaults(test);

      assertEquals(405, result.statusCode());
      assertEquals(0, entered.get());
    }
  }

  private HttpResponse<String> sendResultForPreservesMethodDispatchDefaults(TestServer test)
      throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/method")
                .timeout(Duration.ofSeconds(3))
                .header("X-HTTP-Method-Override", "GET")
                .method("POST"),
        HttpResponse.BodyHandlers.ofString());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void enforcesBodyLimitsAroundGates(boolean chunked) throws Exception {
    // Independent transport access is required by this test's wire/client behavior.
    try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
      app.close();
      var configuredOptions = new Options();
      configuredOptions.setMaxRequestBytes(4);
      configuredOptions.setMaxResponseBytes(1024);
      configuredOptions.setStreamBufferBytes(64);
      configuredOptions.setMaxParameters(1000);
      configuredOptions.setMultipart(new MultipartOptions());

      app =
          new Shoostr(configuredOptions)
              .modifyServer(nativeServer -> {
                var nativeConnector = (ServerConnector) nativeServer.getConnectors()[0];
                nativeConnector.setHost("127.0.0.1");
                nativeConnector.setPort(0);
                nativeConnector.setIdleTimeout(30_000);
              });
      var outcomes = new LinkedBlockingQueue<RequestOutcome>();
      var entered = new AtomicBoolean();
      var gate = new AtomicBoolean();
      app.afterRequest(outcomes::add);
      app.beforeRouteHandler((request, ignoredResponse) -> {
        gate.set(true);
        request.bodyBytes();
      });
      app.routes().post("/limit", (ignoredRequest, ignoredResponse) -> entered.set(true));
      app.start();

      var publisher =
          chunked
              ? HttpRequest.BodyPublishers.ofInputStream(
                  () -> new ByteArrayInputStream(new byte[5]))
              : HttpRequest.BodyPublishers.ofByteArray(new byte[5]);
      var result =
          client.send(request().POST(publisher).build(), HttpResponse.BodyHandlers.ofString());

      assertEquals(413, result.statusCode());
      assertEquals(chunked, gate.get());
      assertFalse(entered.get());

      var outcome = outcomes.poll(3, TimeUnit.SECONDS);

      assertNotNull(outcome);
      assertInstanceOf(ContentTooLargeException.class, outcome.applicationFailure());
      assertEquals("/limit", outcome.routePattern());
    }
  }

  @Test
  void rejectsFromHeadersWithoutReadingBody() throws Exception {
    var entered = new AtomicBoolean();
    app.beforeRouteHandler((ignoredRequest, ignoredResponse) -> {
      throw new UnauthorizedException();
    });
    app.routes().post("/auth", (ignoredRequest, ignoredResponse) -> entered.set(true));
    app.start();

    try (var socket = socket()) {
      socket
          .getOutputStream()
          .write(
              String.join(
                      "\r\n", "POST /auth HTTP/1.1", "Host: localhost", "Content-Length: 4", "", "")
                  .getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      var prefix = new String(socket.getInputStream().readNBytes(12), StandardCharsets.US_ASCII);

      assertEquals("HTTP/1.1 401", prefix.trim());
      assertFalse(entered.get());
    }
  }

  @Test
  void retainsFatalMapperFailureAsSecondaryContext() throws Exception {
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();
    var original = new NotFoundException();
    var secondary = new Error("fatal mapper");
    app.afterRequest(outcomes::add);
    app.exception(
        NotFoundException.class,
        (ignoredFailure, ignoredRequest, ignoredResponse) -> {
          throw secondary;
        });
    app.routes()
        .post(
            "/fatal-mapper",
            (ignoredRequest, ignoredResponse) -> {
              throw original;
            });

    try (var test = TestServer.start(app)) {
      assertThrows(
          IOException.class,
          () ->
              test.send(
                  request ->
                      request.path("/fatal-mapper").timeout(Duration.ofSeconds(3)).method("POST"),
                  HttpResponse.BodyHandlers.ofString()));

      var outcome = outcomes.poll(3, TimeUnit.SECONDS);

      assertNotNull(outcome);
      assertEquals(0, outcome.statusCode());
      assertSame(original, outcome.applicationFailure());
      assertArrayEquals(new Throwable[] {secondary}, original.getSuppressed());
      assertNotNull(outcome.transportFailure());
    }
  }

  @Test
  void honorsRepeatedRegistrationsAndCheckedGateFailures() throws Exception {
    var calls = new AtomicInteger();
    Handler gate = createGateForHonorsRepeatedRegistrationsAndCheckedGateFailures(calls);
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();
    Consumer<RequestOutcome> observer = outcomes::add;
    app.beforeRouteHandler(gate).beforeRouteHandler(gate);
    app.afterRequest(observer).afterRequest(observer);
    app.exception(
        IOException.class,
        (ignoredFailure, ignoredRequest, response) -> response.status(403).text("denied"));
    app.routes().get("/twice", (ignoredRequest, response) -> response.text("unexpected"));

    try (var test = TestServer.start(app)) {
      var result =
          test.send(
              request -> request.path("/twice").timeout(Duration.ofSeconds(3)),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(403, result.statusCode());
      assertEquals("denied", result.body());
      assertEquals(2, calls.get());

      var first = outcomes.poll(3, TimeUnit.SECONDS);

      assertNotNull(first);
      assertSame(first, outcomes.poll(3, TimeUnit.SECONDS));
      assertInstanceOf(IOException.class, first.applicationFailure());
      assertNull(first.transportFailure());
      assertTrue(outcomes.isEmpty());
    }
  }

  private Handler createGateForHonorsRepeatedRegistrationsAndCheckedGateFailures(
      AtomicInteger calls) {
    return (ignoredRequest, ignoredResponse) -> {
      if (calls.incrementAndGet() == 2) {
        throw new IOException("checked rejection");
      }
    };
  }

  @Test
  void runsLiveStagesInRequestOrderForMatchedRoutes() throws Exception {
    var calls = new CopyOnWriteArrayList<String>();
    app.onRequestHeaders((ignoredRequest, ignoredResponse) -> calls.add("headers"));
    app.onRouteMatched(
        (request, ignoredResponse) -> calls.add("matched:" + request.routePattern().orElseThrow()));
    app.beforeRouteHandler((ignoredRequest, ignoredResponse) -> calls.add("before"));
    app.routes()
        .get(
            "/stages",
            (ignoredRequest, response) -> {
              calls.add("handler");
              response.text("ok");
            });
    app.afterRouteHandler((ignoredRequest, ignoredResponse) -> calls.add("after"));

    try (var test = TestServer.start(app)) {

      assertEquals(
          "ok",
          test.send(
                  request -> request.path("/stages").timeout(Duration.ofSeconds(3)),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertEquals(List.of("headers", "matched:/stages", "before", "handler", "after"), calls);
    }
  }

  @Test
  void customizesGeneratedNotFoundWithoutRunningMatchedStages() throws Exception {
    var calls = new CopyOnWriteArrayList<String>();
    app.onRequestHeaders((ignoredRequest, ignoredResponse) -> calls.add("headers"));
    app.onRouteMatched((ignoredRequest, ignoredResponse) -> calls.add("matched"));
    app.status(
        404,
        (ignoredRequest, response) -> response.setHeader("X-Status", "custom").text("missing"));

    try (var test = TestServer.start(app)) {

      var result =
          test.send(
              request -> request.path("/missing").timeout(Duration.ofSeconds(3)),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(404, result.statusCode());
      assertEquals("custom", result.headers().firstValue("X-Status").orElseThrow());
      assertEquals("missing", result.body());
      assertEquals(List.of("headers"), calls);
    }
  }

  @Test
  void customizesGeneratedMethodNotAllowedWhilePreservingAllowedMethods() throws Exception {
    app.status(
        405,
        (ignoredRequest, response) -> {
          response.removeHeader("Allow");
          response.setHeader("Allow", "POST");
          response.setHeader("X-Status", "custom-method").text("wrong method");
        });
    app.routes().get("/known", (ignoredRequest, response) -> response.text("ok"));

    try (var test = TestServer.start(app)) {

      var result =
          test.send(
              request -> request.path("/known").timeout(Duration.ofSeconds(3)).method("POST"),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(405, result.statusCode());
      assertEquals("GET", result.headers().firstValue("Allow").orElseThrow());
      assertEquals("custom-method", result.headers().firstValue("X-Status").orElseThrow());
      assertEquals("wrong method", result.body());
    }
  }

  @Test
  void observesFiniteResponseFlushAfterTheRouteHandler() throws Exception {
    var calls = new CopyOnWriteArrayList<String>();
    var flushed = new CountDownLatch(1);
    app.beforeResponseFlush((ignoredRequest, ignoredResponse) -> calls.add("before-flush"));
    app.afterResponseFlush((ignoredRequest, response) -> {
      calls.add("after-flush:" + response.status());
      flushed.countDown();
    });
    app.routes()
        .get(
            "/finite",
            (ignoredRequest, response) -> {
              calls.add("handler");
              response.text("ok");
            });

    try (var test = TestServer.start(app)) {

      assertEquals(
          "ok",
          test.send(
                  request -> request.path("/finite").timeout(Duration.ofSeconds(3)),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertTrue(flushed.await(3, TimeUnit.SECONDS));
      assertEquals(List.of("handler", "before-flush", "after-flush:200"), calls);
    }
  }

  @Test
  void mapsBeforeFlushRejectionWithoutRetryingTheRejectingHook() throws Exception {
    var flushes = new AtomicInteger();
    app.beforeResponseFlush((ignoredRequest, ignoredResponse) -> {
      if (flushes.incrementAndGet() == 1) {
        throw new UnauthorizedException();
      }
    });
    app.exception(
        UnauthorizedException.class,
        (ignoredFailure, ignoredRequest, response) -> response.text("rejected"));
    app.routes()
        .get("/flush-rejection", (ignoredRequest, response) -> response.text("unreachable"));

    try (var test = TestServer.start(app)) {

      var result =
          test.send(
              request -> request.path("/flush-rejection").timeout(Duration.ofSeconds(3)),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(401, result.statusCode());
      assertEquals("rejected", result.body());
      assertEquals(1, flushes.get());
    }
  }

  @Test
  void mapsCheckedBeforeFlushFailureByItsOriginalType() throws Exception {
    var checked = new IOException("checked flush rejection");
    var flushes = new AtomicInteger();
    app.beforeResponseFlush((ignoredRequest, ignoredResponse) -> {
      if (flushes.incrementAndGet() == 1) {
        throw checked;
      }
    });
    app.exception(
        IOException.class,
        (failure, ignoredRequest, response) -> {
          assertSame(checked, failure);

          response.status(409).text("mapped");
        });
    app.routes().get("/checked-flush", (ignoredRequest, response) -> response.text("original"));

    try (var test = TestServer.start(app)) {

      var result =
          test.send(
              request -> request.path("/checked-flush").timeout(Duration.ofSeconds(3)),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(409, result.statusCode());
      assertEquals("mapped", result.body());
      assertEquals(1, flushes.get());
    }
  }

  @Test
  void recordsPostFlushFailureAsAnApplicationFailure() throws Exception {
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();
    app.afterRequest(outcomes::add);
    app.afterResponseFlush((ignoredRequest, ignoredResponse) -> {
      throw new IOException("after flush");
    });
    app.routes().get("/after-failure", (ignoredRequest, response) -> response.text("submitted"));

    try (var test = TestServer.start(app)) {

      var result =
          test.send(
              request -> request.path("/after-failure").timeout(Duration.ofSeconds(3)),
              HttpResponse.BodyHandlers.ofString());
      var outcome = outcomes.poll(3, TimeUnit.SECONDS);

      assertEquals(200, result.statusCode());
      assertEquals("submitted", result.body());
      assertNotNull(outcome);
      assertInstanceOf(IOException.class, outcome.applicationFailure());
      assertNotNull(outcome.transportFailure());
    }
  }

  @Test
  void rejectsMetadataAfterStreamingPostFlushFailureOnOwningThread() throws Exception {
    var checked = new CompletableFuture<Void>();
    var failure = new IllegalStateException("Post-flush failed");
    app.afterResponseFlush((ignoredRequest, ignoredResponse) -> {
      throw failure;
    });
    app.routes()
        .get(
            "/failed-stream",
            (ignoredRequest, response) -> {
              try {
                assertSame(
                    failure,
                    assertThrows(
                        IllegalStateException.class, () -> response.startStream("text/plain")));
                assertThrows(IllegalStateException.class, response::status);
                assertThrows(IllegalStateException.class, () -> response.header("Content-Type"));
                assertThrows(IllegalStateException.class, () -> response.headers("Content-Type"));
                assertThrows(IllegalStateException.class, response::headerMap);
                assertThrows(IllegalStateException.class, response::isCommitted);

                checked.complete(null);
              } catch (RuntimeException | Error assertionFailure) {
                checked.completeExceptionally(assertionFailure);
                throw assertionFailure;
              }
            });

    try (var test = TestServer.start(app)) {
      assertThrows(
          IOException.class,
          () ->
              test.send(request -> request.path("/failed-stream").timeout(Duration.ofSeconds(1))));
      checked.get(3, TimeUnit.SECONDS);
    }
  }

  @Test
  void preservesPostFlushFailureAsSecondaryDiagnosticWhileRenderingException() throws Exception {
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();
    final var original = new NotFoundException("missing");
    var postFlush = new IOException("after flush");
    app.afterRequest(outcomes::add);
    app.afterResponseFlush((ignoredRequest, ignoredResponse) -> {
      throw postFlush;
    });
    app.exception(
        NotFoundException.class,
        (ignoredFailure, ignoredRequest, response) -> response.status(409).text("mapped"));
    app.routes()
        .get(
            "/mapped-after-failure",
            (ignoredRequest, ignoredResponse) -> {
              throw original;
            });

    try (var test = TestServer.start(app)) {

      var result =
          test.send(
              request -> request.path("/mapped-after-failure").timeout(Duration.ofSeconds(3)),
              HttpResponse.BodyHandlers.ofString());
      var outcome = outcomes.poll(3, TimeUnit.SECONDS);

      assertEquals(409, result.statusCode());
      assertNotNull(outcome);
      assertSame(original, outcome.applicationFailure());
      assertArrayEquals(new Throwable[] {postFlush}, original.getSuppressed());
    }
  }

  @Test
  void disablesAlwaysFailingBeforeFlushHooksWhileRenderingMapperFallback() throws Exception {
    var flushes = new AtomicInteger();
    app.beforeResponseFlush((ignoredRequest, ignoredResponse) -> {
      flushes.incrementAndGet();
      throw new IllegalStateException("flush");
    });
    app.exception(
        NotFoundException.class,
        (ignoredFailure, ignoredRequest, response) -> response.text("mapped"));
    app.routes()
        .get(
            "/mapper-flush",
            (ignoredRequest, ignoredResponse) -> {
              throw new NotFoundException();
            });

    try (var test = TestServer.start(app)) {

      var result =
          test.send(
              request -> request.path("/mapper-flush").timeout(Duration.ofSeconds(3)),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(500, result.statusCode());
      assertEquals(1, flushes.get());
    }
  }

  @Test
  void preventsRetainedStreamsFromReenteringFlushCallbacks() throws Exception {
    var stream = new AtomicReference<Response.Stream>();
    var rejected = new AtomicBoolean();
    app.beforeResponseFlush((ignoredRequest, response) -> {
      var retained = stream.get();
      if (retained == null) {
        return;
      }

      assertThrows(IllegalStateException.class, () -> retained.write("late"));
      assertThrows(IllegalStateException.class, response::close);

      rejected.set(true);
    });
    app.routes()
        .get(
            "/reentry",
            (ignoredRequest, response) -> {
              stream.set(response.startStream(MediaType.TEXT_PLAIN));
              stream.get().write("ok");
            });

    try (var test = TestServer.start(app)) {

      assertEquals(
          "ok",
          test.send(
                  request -> request.path("/reentry").timeout(Duration.ofSeconds(3)),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertTrue(rejected.get());
    }
  }

  @Test
  void prohibitsResponseMutationFromAfterFlushHooks() throws Exception {
    var rejected = new AtomicBoolean();
    var flushed = new CountDownLatch(1);
    app.afterResponseFlush((ignoredRequest, response) -> {
      assertThrows(IllegalStateException.class, () -> response.setHeader("X-Late", "value"));

      rejected.set(true);
      flushed.countDown();
    });
    app.routes().get("/immutable", (ignoredRequest, response) -> response.text("ok"));

    try (var test = TestServer.start(app)) {

      var result =
          test.send(
              request -> request.path("/immutable").timeout(Duration.ofSeconds(3)),
              HttpResponse.BodyHandlers.ofString());

      assertEquals("ok", result.body());
      assertTrue(flushed.await(3, TimeUnit.SECONDS));
      assertTrue(rejected.get());
      assertTrue(result.headers().firstValue("X-Late").isEmpty());
    }
  }

  @Test
  void invokesFlushHooksForEachStreamingFlushAndTerminalCompletion() throws Exception {
    var calls = new CopyOnWriteArrayList<String>();
    var afterFlushes = new CountDownLatch(3);
    app.beforeResponseFlush((ignoredRequest, ignoredResponse) -> calls.add("before"));
    app.afterResponseFlush((ignoredRequest, ignoredResponse) -> {
      calls.add("after");
      afterFlushes.countDown();
    });
    app.routes()
        .get(
            "/stream-flush",
            (ignoredRequest, response) -> {
              var stream = response.startStream(MediaType.TEXT_PLAIN);
              stream.write("first");
              stream.flush();
              stream.write("second");
            });

    try (var test = TestServer.start(app)) {

      assertEquals(
          "firstsecond",
          test.send(
                  request -> request.path("/stream-flush").timeout(Duration.ofSeconds(3)),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertTrue(afterFlushes.await(3, TimeUnit.SECONDS));
      assertEquals(List.of("before", "after", "before", "after", "before", "after"), calls);
    }
  }

  @Test
  void mapsHeaderMatchedPostRouteAndStatusCallbackFailures() throws Exception {
    var calls = new CopyOnWriteArrayList<String>();
    app.onRequestHeaders((request, ignoredResponse) -> {
      calls.add("headers:" + request.path());
      if ("/header".equals(request.path())) {
        throw new IllegalStateException("header");
      }
    });
    app.onRouteMatched((request, ignoredResponse) -> {
      calls.add("matched:" + request.path());
      if ("/matched".equals(request.path())) {
        throw new IllegalStateException("matched");
      }
    });
    app.afterRouteHandler((request, ignoredResponse) -> {
      calls.add("after:" + request.path());
      if ("/after".equals(request.path())) {
        throw new IllegalStateException("after");
      }
    });
    app.exception(
        IllegalStateException.class,
        (failure, ignoredRequest, response) ->
            response.status(409).text(Objects.requireNonNull(failure.getMessage())));
    app.status(
        404,
        (ignoredRequest, ignoredResponse) -> {
          throw new IllegalStateException("status");
        });
    app.routes().get("/{stage}", (ignoredRequest, response) -> response.text("ok"));

    try (var test = TestServer.start(app)) {

      for (String stage : List.of("header", "matched", "after")) {
        assertEquals(
            stage,
            test.send(
                    request -> request.path("/" + stage).timeout(Duration.ofSeconds(3)),
                    HttpResponse.BodyHandlers.ofString())
                .body());
      }

      assertEquals(
          "status",
          test.send(
                  request -> request.path("/missing/path").timeout(Duration.ofSeconds(3)),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertEquals(
          List.of(
              "headers:/header",
              "headers:/matched",
              "matched:/matched",
              "headers:/after",
              "matched:/after",
              "after:/after",
              "headers:/missing/path"),
          calls);
    }
  }

  @Test
  void bypassesGeneratedStatusRenderersForApplicationSelectedStatuses() throws Exception {
    var rendered = new AtomicBoolean();
    app.status(404, (ignoredRequest, ignoredResponse) -> rendered.set(true));
    app.routes()
        .get("/selected", (ignoredRequest, response) -> response.status(404).text("application"));

    try (var test = TestServer.start(app)) {

      assertEquals(
          "application",
          test.send(
                  request -> request.path("/selected").timeout(Duration.ofSeconds(3)),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertFalse(rendered.get());
    }
  }

  @Test
  void invokesFlushHooksForFileResponses(@TempDir Path temporary) throws Exception {
    var file = Files.writeString(temporary.resolve("response.txt"), "file");
    var events = new CopyOnWriteArrayList<String>();
    var flushed = new CountDownLatch(1);
    app.beforeResponseFlush((ignoredRequest, ignoredResponse) -> events.add("before"));
    app.afterResponseFlush((ignoredRequest, ignoredResponse) -> {
      events.add("after");
      flushed.countDown();
    });
    app.routes().get("/file", (ignoredRequest, response) -> response.file(file, "text/plain"));

    try (var test = TestServer.start(app)) {

      assertEquals(
          "file",
          test.send(
                  request -> request.path("/file").timeout(Duration.ofSeconds(3)),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertTrue(flushed.await(3, TimeUnit.SECONDS));
      assertEquals(List.of("before", "after"), events);
    }
  }

  private Socket socket() throws IOException {
    var socket = new Socket();
    socket.setReceiveBufferSize(1024);
    socket.setSoTimeout(3000);
    socket.connect(
        new InetSocketAddress(InetAddress.getAllByName("127.0.0.1")[0], app.port()), 3000);

    return socket;
  }

  private HttpRequest.Builder request() {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/limit"))
        .timeout(Duration.ofSeconds(3));
  }
}
