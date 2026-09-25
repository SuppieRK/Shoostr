package io.github.suppierk.shoostr;

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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
  void exposesComposedRoutePatternThroughErrorHandling() throws Exception {
    var retained = new AtomicReference<Request>();
    app.exception(
        NotFoundException.class,
        (failure, request, response) -> {
          response.text(Objects.requireNonNull(request.routePattern()));
        });
    app.routes()
        .path(
            "/accounts/{accountId}",
            routes ->
                routes.get(
                    "/orders/{id}",
                    (request, response) -> {
                      retained.set(request);
                      assertEquals("/accounts/{accountId}/orders/{id}", request.routePattern());
                      assertEquals("42", request.pathParam("id"));
                      throw new NotFoundException();
                    }));
    app.start();
    var result = send("GET", "/accounts/a/orders/42");
    assertEquals(404, result.statusCode());
    assertEquals("/accounts/{accountId}/orders/{id}", result.body());
    assertThrows(IllegalStateException.class, retained.get()::routePattern);
  }

  @Test
  void ordersGatesAndStopsOnRejection() throws Exception {
    var calls = new CopyOnWriteArrayList<String>();
    app.beforeRouteHandler(
        (request, response) -> {
          calls.add("first:" + request.routePattern());
          response.header("X-Staged", "discard");
          if (request.header("Authorization") == null) {
            throw new UnauthorizedException();
          }
        });
    app.beforeRouteHandler(
        (request, response) -> {
          calls.add("second");
          response.status(202).text("staged");
        });
    app.exception(
        UnauthorizedException.class,
        (failure, request, response) -> {
          calls.add("error");
          response.header("WWW-Authenticate", "Bearer").text("denied");
        });
    app.routes()
        .get(
            "/orders/{id}",
            (request, response) -> {
              calls.add("route");
              response.text("accepted");
            });
    app.start();
    var denied = send("GET", "/orders/42");
    assertEquals(401, denied.statusCode());
    assertEquals("Bearer", denied.headers().firstValue("WWW-Authenticate").orElseThrow());
    assertTrue(denied.headers().firstValue("X-Staged").isEmpty());
    assertEquals(List.of("first:/orders/{id}", "error"), calls);
    calls.clear();
    var accepted =
        client.send(
            request("/orders/42").header("Authorization", "Bearer token").build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(202, accepted.statusCode());
    assertEquals("accepted", accepted.body());
    assertEquals(List.of("first:/orders/{id}", "second", "route"), calls);
  }

  @ParameterizedTest
  @ValueSource(strings = {"stream", "typed-stream", "close"})
  void rejectsGateStreamingBeforeCommit(String operation) throws Exception {
    var entered = new AtomicBoolean();
    app.beforeRouteHandler(
        (request, response) -> {
          switch (operation) {
            case "stream" -> response.startStream("text/plain");
            case "typed-stream" -> response.startStream(MediaType.TEXT_PLAIN);
            default -> response.close();
          }
        });
    app.exception(
        IllegalStateException.class,
        (failure, request, response) -> {
          response.startStream(MediaType.TEXT_PLAIN).write("rejected before commit");
        });
    app.routes().get("/gate", (request, response) -> entered.set(true));
    app.start();
    var result = send("GET", "/gate");
    assertEquals(500, result.statusCode());
    assertEquals("rejected before commit", result.body());
    assertFalse(entered.get());
  }

  @Test
  void observesCompletedFiniteResponse() throws Exception {
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();
    var retained = new AtomicReference<Request>();
    app.afterRequest(
        outcome -> {
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
    app.start();
    assertEquals(201, send("GET", "/orders/42").statusCode());
    var outcome = outcomes.poll(3, TimeUnit.SECONDS);
    assertNotNull(outcome);
    assertEquals("GET", outcome.method());
    assertEquals("/orders/{id}", outcome.routePattern());
    assertEquals(201, outcome.statusCode());
    assertTrue(outcome.durationNanos() > 0);
    assertNull(outcome.applicationFailure());
    assertNull(outcome.transportFailure());
    assertTrue(outcomes.isEmpty());
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
        (failure, request, response) -> {
          if (mapperFails) {
            throw secondary;
          }

          response.status(409).text("mapped");
        });
    app.routes()
        .get(
            "/failure",
            (request, response) -> {
              throw original;
            });
    app.start();
    var result = send("GET", "/failure");
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

  @Test
  void continuesAfterAnObserverThrows() throws Exception {
    var calls = new CopyOnWriteArrayList<String>();
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();
    app.afterRequest(
        outcome -> {
          calls.add("first");
          throw new IllegalStateException("broken metrics sink");
        });
    app.afterRequest(
        outcome -> {
          calls.add("second");
          outcomes.add(outcome);
        });
    app.routes().get("/ok", (request, response) -> response.text("ok"));
    app.start();
    assertEquals("ok", send("GET", "/ok").body());
    assertNotNull(outcomes.poll(3, TimeUnit.SECONDS));
    assertEquals(List.of("first", "second"), calls);
  }

  @Test
  void waitsForFiniteTransportAndReportsDisconnect() throws Exception {
    app.close();
    app = new Shoostr(new Options("127.0.0.1", 0, 1024, 16 * 1024 * 1024, 1024, 30_000));
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();
    app.afterRequest(outcomes::add);
    app.routes()
        .get(
            "/large",
            (request, response) -> {
              response.body(MediaType.APPLICATION_OCTET_STREAM, new byte[16 * 1024 * 1024]);
            });
    app.start();

    try (var socket = socket()) {
      writeRequest(socket, "/large");
      readHeaders(socket);
      assertNull(
          outcomes.poll(100, TimeUnit.MILLISECONDS), "pending bytes are not completed output");
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
            (request, response) -> {
              var stream = response.startStream(MediaType.TEXT_PLAIN);
              retained.set(stream);
              stream.write("hello");
              stream.flush();
              if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release handler");
              }

              stream.write("world");
            });
    app.start();

    try (var body =
        client.send(request("/stream").build(), HttpResponse.BodyHandlers.ofInputStream()).body()) {
      assertEquals("hello", new String(body.readNBytes(5), StandardCharsets.UTF_8));
      assertNull(outcomes.poll(100, TimeUnit.MILLISECONDS));
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

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void observesRouterMissesWithoutRunningGates(boolean wrongMethod) throws Exception {
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();
    var gates = new AtomicInteger();
    app.afterRequest(outcomes::add);
    app.beforeRouteHandler((request, response) -> gates.incrementAndGet());
    app.routes().get("/known", (request, response) -> response.text("ok"));
    app.start();
    var result = send("POST", wrongMethod ? "/known" : "/missing");
    assertEquals(wrongMethod ? 405 : 404, result.statusCode());
    var outcome = outcomes.poll(3, TimeUnit.SECONDS);
    assertNotNull(outcome);
    assertEquals(result.statusCode(), outcome.statusCode());
    assertNull(outcome.routePattern());
    assertNull(outcome.applicationFailure());
    assertNull(outcome.transportFailure());
    assertEquals(0, gates.get());
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
            (request, response) -> {
              if (committed) {
                response.startStream(MediaType.TEXT_PLAIN).write("pending");
                throw (IllegalStateException) failure;
              }

              throw (Error) failure;
            });
    app.start();
    assertThrows(IOException.class, () -> send("POST", "/abort"));
    var outcome = outcomes.poll(3, TimeUnit.SECONDS);
    assertNotNull(outcome);
    assertSame(failure, outcome.applicationFailure());
    assertEquals(committed ? 200 : 0, outcome.statusCode());
    assertNotNull(outcome.transportFailure());
    assertTrue(outcomes.isEmpty());
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
            (request, response) -> {
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
        writeRequest(socket, "/waiting");
        readHeaders(socket);
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        socket.setSoLinger(true, 0);
      }

      assertNull(outcomes.poll(100, TimeUnit.MILLISECONDS));
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
  @SuppressWarnings("NullAway") // The test checks null hook registration failures.
  void validatesRegistrationLifetime() throws Exception {
    assertThrows(NullPointerException.class, () -> app.beforeRouteHandler(null));
    assertThrows(NullPointerException.class, () -> app.afterRequest(null));
    app.start();
    assertThrows(
        IllegalStateException.class, () -> app.beforeRouteHandler((request, response) -> {}));
    assertThrows(IllegalStateException.class, () -> app.afterRequest(outcome -> {}));
    app.close();
    assertThrows(
        IllegalStateException.class, () -> app.beforeRouteHandler((request, response) -> {}));
    assertThrows(IllegalStateException.class, () -> app.afterRequest(outcome -> {}));
    app = new Shoostr();
    app.close();
    assertThrows(
        IllegalStateException.class, () -> app.beforeRouteHandler((request, response) -> {}));
    assertThrows(IllegalStateException.class, () -> app.afterRequest(outcome -> {}));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void racesRegistrationWithStartup(boolean completion) throws Exception {
    var start = new CountDownLatch(1);
    var called = new CountDownLatch(1);
    app.routes().get("/ok", (request, response) -> response.text("ok"));

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var registration =
          executor.submit(
              () -> {
                start.await();

                try {
                  if (completion) {
                    app.afterRequest(outcome -> called.countDown());
                  } else {
                    app.beforeRouteHandler((request, response) -> called.countDown());
                  }

                  return true;
                } catch (IllegalStateException _) {
                  return false;
                }
              });
      var startup =
          executor.submit(
              () -> {
                start.await();
                return app.start();
              });
      start.countDown();
      startup.get(5, TimeUnit.SECONDS);
      boolean accepted = registration.get(5, TimeUnit.SECONDS);
      assertEquals("ok", send("GET", "/ok").body());
      if (accepted) {
        assertTrue(called.await(3, TimeUnit.SECONDS));
      } else {
        assertEquals(1, called.getCount());
      }
    }
  }

  @Test
  void preservesMethodDispatchDefaults() throws Exception {
    var entered = new AtomicInteger();
    app.routes()
        .get(
            "/method",
            (request, response) -> {
              entered.incrementAndGet();
              response.text("GET");
            });
    app.start();
    for (String method : List.of("HEAD", "OPTIONS")) {
      var result = send(method, "/method");
      assertEquals(405, result.statusCode());
      assertEquals("GET", result.headers().firstValue("Allow").orElseThrow());
    }
    var result =
        client.send(
            request("/method")
                .header("X-HTTP-Method-Override", "GET")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(405, result.statusCode());
    assertEquals(0, entered.get());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void enforcesBodyLimitsAroundGates(boolean chunked) throws Exception {
    app.close();
    app = new Shoostr(new Options("127.0.0.1", 0, 4, 1024, 64, 30_000));
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();
    var entered = new AtomicBoolean();
    var gate = new AtomicBoolean();
    app.afterRequest(outcomes::add);
    app.beforeRouteHandler(
        (request, response) -> {
          gate.set(true);
          request.bodyBytes();
        });
    app.routes().post("/limit", (request, response) -> entered.set(true));
    app.start();
    var publisher =
        chunked
            ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(new byte[5]))
            : HttpRequest.BodyPublishers.ofByteArray(new byte[5]);
    var result =
        client.send(
            request("/limit").POST(publisher).build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(413, result.statusCode());
    assertEquals(chunked, gate.get());
    assertFalse(entered.get());
    var outcome = outcomes.poll(3, TimeUnit.SECONDS);
    assertNotNull(outcome);
    assertInstanceOf(ContentTooLargeException.class, outcome.applicationFailure());
    assertEquals("/limit", outcome.routePattern());
  }

  @Test
  void rejectsFromHeadersWithoutReadingBody() throws Exception {
    var entered = new AtomicBoolean();
    app.beforeRouteHandler(
        (request, response) -> {
          throw new UnauthorizedException();
        });
    app.routes().post("/auth", (request, response) -> entered.set(true));
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
        (failure, request, response) -> {
          throw secondary;
        });
    app.routes()
        .post(
            "/fatal-mapper",
            (request, response) -> {
              throw original;
            });
    app.start();
    assertThrows(IOException.class, () -> send("POST", "/fatal-mapper"));
    var outcome = outcomes.poll(3, TimeUnit.SECONDS);
    assertNotNull(outcome);
    assertEquals(0, outcome.statusCode());
    assertSame(original, outcome.applicationFailure());
    assertArrayEquals(new Throwable[] {secondary}, original.getSuppressed());
    assertNotNull(outcome.transportFailure());
  }

  @Test
  void honorsRepeatedRegistrationsAndCheckedGateFailures() throws Exception {
    var calls = new AtomicInteger();
    Handler gate =
        (request, response) -> {
          if (calls.incrementAndGet() == 2) {
            throw new IOException("checked rejection");
          }
        };
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();
    Consumer<RequestOutcome> observer = outcomes::add;
    app.beforeRouteHandler(gate).beforeRouteHandler(gate);
    app.afterRequest(observer).afterRequest(observer);
    app.exception(
        IOException.class, (failure, request, response) -> response.status(403).text("denied"));
    app.routes().get("/twice", (request, response) -> response.text("unexpected"));
    app.start();
    var result = send("GET", "/twice");
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

  @Test
  void runsLiveStagesInRequestOrderForMatchedRoutes() throws Exception {
    var calls = new CopyOnWriteArrayList<String>();
    app.onRequestHeaders((request, response) -> calls.add("headers"));
    app.onRouteMatched((request, response) -> calls.add("matched:" + request.routePattern()));
    app.beforeRouteHandler((request, response) -> calls.add("before"));
    app.routes()
        .get(
            "/stages",
            (request, response) -> {
              calls.add("handler");
              response.text("ok");
            });
    app.afterRouteHandler((request, response) -> calls.add("after"));
    app.start();

    assertEquals("ok", send("GET", "/stages").body());
    assertEquals(List.of("headers", "matched:/stages", "before", "handler", "after"), calls);
  }

  @Test
  void customizesGeneratedNotFoundWithoutRunningMatchedStages() throws Exception {
    var calls = new CopyOnWriteArrayList<String>();
    app.onRequestHeaders((request, response) -> calls.add("headers"));
    app.onRouteMatched((request, response) -> calls.add("matched"));
    app.status(404, (request, response) -> response.header("X-Status", "custom").text("missing"));
    app.start();

    var result = send("GET", "/missing");

    assertEquals(404, result.statusCode());
    assertEquals("custom", result.headers().firstValue("X-Status").orElseThrow());
    assertEquals("missing", result.body());
    assertEquals(List.of("headers"), calls);
  }

  @Test
  void customizesGeneratedMethodNotAllowedWhilePreservingAllowedMethods() throws Exception {
    app.status(
        405,
        (request, response) -> {
          response.removeHeader("Allow");
          response.header("Allow", "POST");
          response.header("X-Status", "custom-method").text("wrong method");
        });
    app.routes().get("/known", (request, response) -> response.text("ok"));
    app.start();

    var result = send("POST", "/known");

    assertEquals(405, result.statusCode());
    assertEquals("GET", result.headers().firstValue("Allow").orElseThrow());
    assertEquals("custom-method", result.headers().firstValue("X-Status").orElseThrow());
    assertEquals("wrong method", result.body());
  }

  @Test
  void observesFiniteResponseFlushAfterTheRouteHandler() throws Exception {
    var calls = new CopyOnWriteArrayList<String>();
    var flushed = new CountDownLatch(1);
    app.beforeResponseFlush((request, response) -> calls.add("before-flush"));
    app.afterResponseFlush(
        (request, response) -> {
          calls.add("after-flush:" + response.status());
          flushed.countDown();
        });
    app.routes()
        .get(
            "/finite",
            (request, response) -> {
              calls.add("handler");
              response.text("ok");
            });
    app.start();

    assertEquals("ok", send("GET", "/finite").body());
    assertTrue(flushed.await(3, TimeUnit.SECONDS));
    assertEquals(List.of("handler", "before-flush", "after-flush:200"), calls);
  }

  @Test
  void mapsBeforeFlushRejectionWithoutRetryingTheRejectingHook() throws Exception {
    var flushes = new AtomicInteger();
    app.beforeResponseFlush(
        (request, response) -> {
          if (flushes.incrementAndGet() == 1) {
            throw new UnauthorizedException();
          }
        });
    app.exception(
        UnauthorizedException.class, (failure, request, response) -> response.text("rejected"));
    app.routes().get("/flush-rejection", (request, response) -> response.text("unreachable"));
    app.start();

    var result = send("GET", "/flush-rejection");

    assertEquals(401, result.statusCode());
    assertEquals("rejected", result.body());
    assertEquals(1, flushes.get());
  }

  @Test
  void mapsCheckedBeforeFlushFailureByItsOriginalType() throws Exception {
    var checked = new IOException("checked flush rejection");
    var flushes = new AtomicInteger();
    app.beforeResponseFlush(
        (request, response) -> {
          if (flushes.incrementAndGet() == 1) {
            throw checked;
          }
        });
    app.exception(
        IOException.class,
        (failure, request, response) -> {
          assertSame(checked, failure);
          response.status(409).text("mapped");
        });
    app.routes().get("/checked-flush", (request, response) -> response.text("original"));
    app.start();

    var result = send("GET", "/checked-flush");

    assertEquals(409, result.statusCode());
    assertEquals("mapped", result.body());
    assertEquals(1, flushes.get());
  }

  @Test
  void recordsPostFlushFailureAsAnApplicationFailure() throws Exception {
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();
    app.afterRequest(outcomes::add);
    app.afterResponseFlush(
        (request, response) -> {
          throw new IOException("after flush");
        });
    app.routes().get("/after-failure", (request, response) -> response.text("submitted"));
    app.start();

    var result = send("GET", "/after-failure");
    var outcome = outcomes.poll(3, TimeUnit.SECONDS);

    assertEquals(200, result.statusCode());
    assertEquals("submitted", result.body());
    assertNotNull(outcome);
    assertInstanceOf(IOException.class, outcome.applicationFailure());
    assertNotNull(outcome.transportFailure());
  }

  @Test
  void preservesPostFlushFailureAsSecondaryDiagnosticWhileRenderingException() throws Exception {
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();
    var original = new NotFoundException("missing");
    var postFlush = new IOException("after flush");
    app.afterRequest(outcomes::add);
    app.afterResponseFlush(
        (request, response) -> {
          throw postFlush;
        });
    app.exception(
        NotFoundException.class,
        (failure, request, response) -> response.status(409).text("mapped"));
    app.routes()
        .get(
            "/mapped-after-failure",
            (request, response) -> {
              throw original;
            });
    app.start();

    var result = send("GET", "/mapped-after-failure");
    var outcome = outcomes.poll(3, TimeUnit.SECONDS);

    assertEquals(409, result.statusCode());
    assertNotNull(outcome);
    assertSame(original, outcome.applicationFailure());
    assertArrayEquals(new Throwable[] {postFlush}, original.getSuppressed());
  }

  @Test
  void disablesAlwaysFailingBeforeFlushHooksWhileRenderingMapperFallback() throws Exception {
    var flushes = new AtomicInteger();
    app.beforeResponseFlush(
        (request, response) -> {
          flushes.incrementAndGet();
          throw new IllegalStateException("flush");
        });
    app.exception(NotFoundException.class, (failure, request, response) -> response.text("mapped"));
    app.routes()
        .get(
            "/mapper-flush",
            (request, response) -> {
              throw new NotFoundException();
            });
    app.start();

    var result = send("GET", "/mapper-flush");

    assertEquals(500, result.statusCode());
    assertEquals(1, flushes.get());
  }

  @Test
  void preventsRetainedStreamsFromReenteringFlushCallbacks() throws Exception {
    var stream = new AtomicReference<Response.Stream>();
    var rejected = new AtomicBoolean();
    app.beforeResponseFlush(
        (request, response) -> {
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
            (request, response) -> {
              stream.set(response.startStream(MediaType.TEXT_PLAIN));
              stream.get().write("ok");
            });
    app.start();

    assertEquals("ok", send("GET", "/reentry").body());
    assertTrue(rejected.get());
  }

  @Test
  void prohibitsResponseMutationFromAfterFlushHooks() throws Exception {
    var rejected = new AtomicBoolean();
    var flushed = new CountDownLatch(1);
    app.afterResponseFlush(
        (request, response) -> {
          assertThrows(IllegalStateException.class, () -> response.header("X-Late", "value"));
          rejected.set(true);
          flushed.countDown();
        });
    app.routes().get("/immutable", (request, response) -> response.text("ok"));
    app.start();

    var result = send("GET", "/immutable");

    assertEquals("ok", result.body());
    assertTrue(flushed.await(3, TimeUnit.SECONDS));
    assertTrue(rejected.get());
    assertTrue(result.headers().firstValue("X-Late").isEmpty());
  }

  @Test
  void invokesFlushHooksForEachStreamingFlushAndTerminalCompletion() throws Exception {
    var calls = new CopyOnWriteArrayList<String>();
    var afterFlushes = new CountDownLatch(3);
    app.beforeResponseFlush((request, response) -> calls.add("before"));
    app.afterResponseFlush(
        (request, response) -> {
          calls.add("after");
          afterFlushes.countDown();
        });
    app.routes()
        .get(
            "/stream-flush",
            (request, response) -> {
              var stream = response.startStream(MediaType.TEXT_PLAIN);
              stream.write("first");
              stream.flush();
              stream.write("second");
            });
    app.start();

    assertEquals("firstsecond", send("GET", "/stream-flush").body());
    assertTrue(afterFlushes.await(3, TimeUnit.SECONDS));
    assertEquals(List.of("before", "after", "before", "after", "before", "after"), calls);
  }

  @Test
  void mapsHeaderMatchedPostRouteAndStatusCallbackFailures() throws Exception {
    var calls = new CopyOnWriteArrayList<String>();
    app.onRequestHeaders(
        (request, response) -> {
          calls.add("headers:" + request.path());
          if ("/header".equals(request.path())) {
            throw new IllegalStateException("header");
          }
        });
    app.onRouteMatched(
        (request, response) -> {
          calls.add("matched:" + request.path());
          if ("/matched".equals(request.path())) {
            throw new IllegalStateException("matched");
          }
        });
    app.afterRouteHandler(
        (request, response) -> {
          calls.add("after:" + request.path());
          if ("/after".equals(request.path())) {
            throw new IllegalStateException("after");
          }
        });
    app.exception(
        IllegalStateException.class,
        (failure, request, response) ->
            response.status(409).text(Objects.requireNonNull(failure.getMessage())));
    app.status(
        404,
        (request, response) -> {
          throw new IllegalStateException("status");
        });
    app.routes().get("/{stage}", (request, response) -> response.text("ok"));
    app.start();

    for (String stage : List.of("header", "matched", "after")) {
      assertEquals(stage, send("GET", "/" + stage).body());
    }
    assertEquals("status", send("GET", "/missing/path").body());
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

  @Test
  void bypassesGeneratedStatusRenderersForApplicationSelectedStatuses() throws Exception {
    var rendered = new AtomicBoolean();
    app.status(404, (request, response) -> rendered.set(true));
    app.routes().get("/selected", (request, response) -> response.status(404).text("application"));
    app.start();

    assertEquals("application", send("GET", "/selected").body());
    assertFalse(rendered.get());
  }

  @Test
  void invokesFlushHooksForFileResponses(@TempDir Path temporary) throws Exception {
    var file = Files.writeString(temporary.resolve("response.txt"), "file");
    var flushed = new CountDownLatch(1);
    app.afterResponseFlush((request, response) -> flushed.countDown());
    app.routes().get("/file", (request, response) -> response.file(file, "text/plain"));
    app.start();

    assertEquals("file", send("GET", "/file").body());
    assertTrue(flushed.await(3, TimeUnit.SECONDS));
  }

  private Socket socket() throws IOException {
    var socket = new Socket();
    socket.setReceiveBufferSize(1024);
    socket.setSoTimeout(3000);
    socket.connect(
        new InetSocketAddress(InetAddress.getAllByName("127.0.0.1")[0], app.port()), 3000);
    return socket;
  }

  private static void writeRequest(Socket socket, String path) throws IOException {
    socket
        .getOutputStream()
        .write(
            ("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
    socket.getOutputStream().flush();
  }

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

  private HttpResponse<String> send(String method, String path) throws Exception {
    return client.send(
        request(path).method(method, HttpRequest.BodyPublishers.noBody()).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
        .timeout(Duration.ofSeconds(3));
  }
}
