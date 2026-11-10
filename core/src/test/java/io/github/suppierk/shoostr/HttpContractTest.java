package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpFields.Mutable;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class HttpContractTest {
  private final AtomicReference<Response> retainedResponse;
  private final AtomicReference<Request> retainedRequest;
  private final CountDownLatch staged;
  private final CountDownLatch releaseFinite;
  private final CountDownLatch releaseStream;
  private Shoostr app;
  private TestServer test;

  HttpContractTest() {
    this.retainedResponse = new AtomicReference<>();
    this.retainedRequest = new AtomicReference<>();
    this.staged = new CountDownLatch(1);
    this.releaseFinite = new CountDownLatch(1);
    this.releaseStream = new CountDownLatch(1);
  }

  @BeforeEach
  void startServer() throws Exception {
    var configuredOptions = new Options();
    configuredOptions.setMaxRequestBytes(16);
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

    app.routes().get("/plain", (ignoredRequest, response) -> response.text("hello"));
    app.routes()
        .get(
            "/thread",
            (ignoredRequest, response) ->
                response.text(Boolean.toString(Thread.currentThread().isVirtual())));
    app.routes().post("/echo", (request, response) -> response.text(request.bodyText()));
    app.routes()
        .get(
            "/failure",
            (ignoredRequest, response) -> {
              response.status(201).setHeader("X-Leak", "secret").text("must not escape");
              throw new IllegalArgumentException("secret details");
            });
    app.routes()
        .get(
            "/finite",
            (request, response) -> {
              response.text("staged");
              retainedResponse.set(response);
              retainedRequest.set(request);
              staged.countDown();
              if (!releaseFinite.await(5, TimeUnit.SECONDS)) {
                throw new IOException("test timeout");
              }

              response.text("finished");
            });
    app.routes()
        .get(
            "/stream",
            (ignoredRequest, response) -> {
              var stream = response.startStream("text/plain; charset=utf-8");
              stream.write("first\n");
              stream.flush();
              if (!releaseStream.await(5, TimeUnit.SECONDS)) {
                throw new IOException("test timeout");
              }

              stream.write("last\n");
            });
    app.routes()
        .get(
            "/fluent-stream",
            (ignoredRequest, response) -> {
              var stream = response.startStream("text/plain; charset=utf-8");

              assertSame(stream, stream.write("café"));
              assertSame(stream, stream.write(new byte[] {'!'}));
              assertSame(stream, stream.write(""));
              assertSame(stream, stream.write(new byte[0]));
              assertSame(stream, stream.flush());

              stream.write("012345678").flush().write("tail");
            });
    app.routes()
        .get(
            "/broken-stream",
            (ignoredRequest, response) -> {
              var stream = response.startStream("text/plain; charset=utf-8");
              stream.write("partial\n");
              stream.flush();
              throw new IOException("failed after commitment");
            });
    app.routes().get("/close", (ignoredRequest, response) -> response.close());
    app.routes()
        .get(
            "/cross-thread",
            (ignoredRequest, response) -> {
              var failure = new AtomicReference<Throwable>();
              var thread = createThreadForStartServer(response, failure);
              thread.join();
              response.text(Boolean.toString(failure.get() instanceof IllegalStateException));
            });
    app.routes().get("/empty", (ignoredRequest, response) -> response.status(204));
    app.routes()
        .get("/invalid-empty", (ignoredRequest, response) -> response.status(204).text("bad"));
    app.routes()
        .get(
            "/invalid-type",
            (ignoredRequest, response) ->
                response.body("text/plain\r\nX-Leak: injected", new byte[0]));
    app.routes()
        .get(
            "/invalid-stream-type",
            (ignoredRequest, response) -> response.startStream("text/plain\nX-Leak: injected"));
    app.routes()
        .get(
            "/invalid-header",
            (ignoredRequest, response) -> response.setHeader("Bad Header", "value"));
    app.routes()
        .get(
            "/invalid-framing",
            (ignoredRequest, response) -> response.setHeader("content-length", "12"));
    app.routes()
        .get(
            "/custom-type",
            (ignoredRequest, response) ->
                response.body("application/vnd.example+json", new byte[0]));
    test = TestServer.start(app, client -> client.version(HttpClient.Version.HTTP_1_1));
  }

  private Thread createThreadForStartServer(Response response, AtomicReference<Throwable> failure) {
    return Thread.startVirtualThread(
        () -> {
          try {
            response.text("bad");
          } catch (Throwable error) {
            failure.set(error);
          }
        });
  }

  @AfterEach
  void closeServerAndClient() throws Exception {
    releaseFinite.countDown();
    releaseStream.countDown();

    try {
      if (test != null) {
        test.close();
      }
    } finally {
      if (app != null) {
        app.close();
      }
    }
  }

  @Test
  void sendsFiniteBody() throws Exception {
    assertEquals(
        "hello",
        test.send(
                request -> request.path("/plain").timeout(Duration.ofSeconds(5)),
                HttpResponse.BodyHandlers.ofString())
            .body());
  }

  @Test
  void dispatchesOnPlatformThreadByDefault() throws Exception {
    assertEquals(
        "false",
        test.send(
                request -> request.path("/thread").timeout(Duration.ofSeconds(5)),
                HttpResponse.BodyHandlers.ofString())
            .body());
  }

  @Test
  void returnsNotFoundForUnknownRoute() throws Exception {
    assertEquals(
        404,
        test.send(
                request -> request.path("/missing").timeout(Duration.ofSeconds(5)),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
  }

  @Test
  void reportsAllowedMethods() throws Exception {
    var result =
        test.send(
            request -> request.path("/plain").timeout(Duration.ofSeconds(5)).method("POST"),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(405, result.statusCode());
    assertEquals("GET", result.headers().firstValue("Allow").orElseThrow());
  }

  @Test
  void discardsStagedSuccessOnHandlerFailure() throws Exception {
    var result =
        test.send(
            request -> request.path("/failure").timeout(Duration.ofSeconds(5)),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(500, result.statusCode());
    assertEquals("Internal Server Error", result.body());
    assertTrue(result.headers().firstValue("X-Leak").isEmpty());
  }

  @Test
  void stagesFiniteBodyUntilHandlerReturnsAndClosesApplicationAccess() throws Exception {
    try (var testRequests = Executors.newVirtualThreadPerTaskExecutor()) {
      var finite =
          submitFiniteForStagesFiniteBodyUntilHandlerReturnsAndClosesApplicationAccess(
              testRequests);

      assertTrue(staged.await(3, TimeUnit.SECONDS), "finite handler entered");
      assertFalse(finite.isDone(), "finite response remains staged");

      releaseFinite.countDown();

      try (var input = finite.get(3, TimeUnit.SECONDS).body()) {
        assertEquals("finished", new String(input.readAllBytes(), StandardCharsets.UTF_8));
      }

      var closedResponse = retainedResponse.get();
      var closedRequest = retainedRequest.get();

      assertThrows(IllegalStateException.class, () -> closedResponse.text("too late"));
      assertThrows(IllegalStateException.class, closedRequest::path);
    }
  }

  private Future<HttpResponse<InputStream>>
      submitFiniteForStagesFiniteBodyUntilHandlerReturnsAndClosesApplicationAccess(
          ExecutorService testRequests) {
    return testRequests.submit(
        () ->
            test.send(
                request -> request.path("/finite").timeout(Duration.ofSeconds(5)),
                HttpResponse.BodyHandlers.ofInputStream()));
  }

  @Test
  void streamsBeforeHandlerReturnsAndFlushesRemainderOnReturn() throws Exception {
    try (var testRequests = Executors.newVirtualThreadPerTaskExecutor()) {
      var result =
          sendResultForStreamsBeforeHandlerReturnsAndFlushesRemainderOnReturn(testRequests);

      try (var input = result.body()) {
        assertEquals("first\n", new String(input.readNBytes(6), StandardCharsets.UTF_8));

        releaseStream.countDown();

        assertEquals("last\n", new String(input.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
  }

  private HttpResponse<InputStream>
      sendResultForStreamsBeforeHandlerReturnsAndFlushesRemainderOnReturn(
          ExecutorService testRequests)
          throws InterruptedException, ExecutionException, TimeoutException {
    return testRequests
        .submit(
            () ->
                test.send(
                    request -> request.path("/stream").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofInputStream()))
        .get(3, TimeUnit.SECONDS);
  }

  @Test
  void chainsStreamWritesAndFlushesOnTheSameWriter() throws Exception {
    var result =
        test.send(
            request -> request.path("/fluent-stream").timeout(Duration.ofSeconds(5)),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, result.statusCode());
    assertEquals("café!012345678tail", result.body());
  }

  @Test
  void abortsCommittedStreamOnFailure() {
    assertThrows(
        IOException.class,
        () ->
            test.send(
                request -> request.path("/broken-stream").timeout(Duration.ofSeconds(5)),
                HttpResponse.BodyHandlers.ofString()));
  }

  @Test
  void rejectsApplicationClosingResponse() throws Exception {
    assertEquals(
        500,
        test.send(
                request -> request.path("/close").timeout(Duration.ofSeconds(5)),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
  }

  @Test
  void rejectsResponseAccessFromAnotherThread() throws Exception {
    assertEquals(
        "true",
        test.send(
                request -> request.path("/cross-thread").timeout(Duration.ofSeconds(5)),
                HttpResponse.BodyHandlers.ofString())
            .body());
  }

  @Test
  void sendsBodylessStatus() throws Exception {
    assertEquals(
        204,
        test.send(
                request -> request.path("/empty").timeout(Duration.ofSeconds(5)),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
  }

  @Test
  void rejectsBodyForBodylessStatusBeforeCommit() throws Exception {
    assertEquals(
        500,
        test.send(
                request -> request.path("/invalid-empty").timeout(Duration.ofSeconds(5)),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"/invalid-type", "/invalid-stream-type", "/invalid-header", "/invalid-framing"})
  void rejectsInvalidHeadersBeforeCommit(String path) throws Exception {
    var result =
        test.send(
            request -> request.path(path).timeout(Duration.ofSeconds(5)),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(500, result.statusCode());
    assertTrue(result.headers().firstValue("X-Leak").isEmpty());
  }

  @Test
  void preservesCustomContentType() throws Exception {
    assertEquals(
        "application/vnd.example+json",
        test.send(
                request -> request.path("/custom-type").timeout(Duration.ofSeconds(5)),
                HttpResponse.BodyHandlers.ofString())
            .headers()
            .firstValue("Content-Type")
            .orElseThrow());
  }

  @Test
  void readsRequestBody() throws Exception {
    var result = sendResultForReadsRequestBody();

    assertEquals("hello", result.body());
  }

  private HttpResponse<String> sendResultForReadsRequestBody()
      throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/echo")
                .timeout(Duration.ofSeconds(5))
                .method("POST")
                .body("hello".getBytes(StandardCharsets.UTF_8)),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void readsEmptyKnownLengthRequestBody() throws Exception {
    var result =
        test.send(
            request -> request.path("/echo").timeout(Duration.ofSeconds(5)).method("POST"),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, result.statusCode());
    assertEquals("", result.body());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void capsRequestBodyWithKnownOrUnknownLength(boolean chunked) throws Exception {
    // The unknown-length variant requires a native chunked body publisher.
    try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {

      var body =
          chunked
              ? HttpRequest.BodyPublishers.ofInputStream(
                  () -> new ByteArrayInputStream("x".repeat(17).getBytes(StandardCharsets.UTF_8)))
              : HttpRequest.BodyPublishers.ofString("x".repeat(17));
      var result =
          client.send(
              request(test.baseUri().resolve("/echo").toString()).POST(body).build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(413, result.statusCode());
      assertEquals("Content Too Large", result.body());
    }
  }

  @Test
  void freezesRoutesAfterStartup() {
    var routes = app.routes();

    assertThrows(
        IllegalStateException.class,
        () -> routes.get("/later", (ignoredRequest, ignoredResponse) -> {}));
  }

  @ParameterizedTest
  @CsvSource({
    "false, false, false",
    "true, false, false",
    "false, true, false",
    "true, true, false",
    "false, true, true",
    "true, true, true"
  })
  @SuppressWarnings("java:S2093") // The response must outlive the producer until its callback.
  void completesFiniteResponsesAsynchronously(
      boolean transportFails, boolean hasAfterFlush, boolean afterFlushFails) throws Exception {
    var pending = new AtomicReference<Callback>();
    var bytes = new AtomicReference<ByteBuffer>();
    var failure = new AtomicReference<Throwable>();
    var returned = new CountDownLatch(1);
    var writes = new AtomicInteger();
    var successes = new AtomicInteger();
    var failures = new AtomicInteger();
    var afterFlushes = new AtomicInteger();
    var headers = HttpFields.build();
    var sink = createSinkForCompletesFiniteResponsesAsynchronously(headers, writes, bytes, pending);
    var completion =
        Callback.from(successes::incrementAndGet, ignored -> failures.incrementAndGet());
    var producer =
        createProducerForCompletesFiniteResponsesAsynchronously(
            sink, completion, hasAfterFlush, afterFlushes, afterFlushFails, failure, returned);

    try {
      assertTrue(
          returned.await(3, TimeUnit.SECONDS), "finite completion does not wait for transport");
      assertNull(failure.get(), "Producer failed");

      assertTrue(
          successes.get() == 0 && failures.get() == 0,
          "request remains pending until transport completion");
      assertEquals(
          hasAfterFlush ? 1 : 0,
          afterFlushes.get(),
          "configured post-flush observation runs after submission");
      assertEquals(
          "finished",
          StandardCharsets.UTF_8.decode(bytes.get()).toString(),
          "in-flight bytes retain defensive ownership");

      if (transportFails) {
        pending.get().failed(new IOException("simulated disconnect"));
      } else {
        pending.get().succeeded();
      }

      assertTrue(
          writes.get() == 1
              && successes.get() == (transportFails || afterFlushFails ? 0 : 1)
              && failures.get() == (transportFails || afterFlushFails ? 1 : 0),
          "transport completes request exactly once");
    } finally {
      if (producer.isAlive()) {
        if (pending.get() != null) {
          pending.get().failed(new IOException("test cleanup"));
        }

        producer.interrupt();
        producer.join(3000);
      }
    }
  }

  private org.eclipse.jetty.server.Response createSinkForCompletesFiniteResponsesAsynchronously(
      Mutable headers,
      AtomicInteger writes,
      AtomicReference<ByteBuffer> bytes,
      AtomicReference<Callback> pending) {
    return (org.eclipse.jetty.server.Response)
        Proxy.newProxyInstance(
            HttpContractTest.class.getClassLoader(),
            new Class<?>[] {org.eclipse.jetty.server.Response.class},
            (ignoredObject, method, args) ->
                switch (method.getName()) {
                  case "getStatus" -> 200;
                  case "getHeaders" -> headers;
                  case "isCommitted" -> writes.get() != 0;
                  case "write" -> {
                    writes.incrementAndGet();
                    bytes.set((ByteBuffer) args[1]);
                    pending.set((Callback) args[2]);
                    yield null;
                  }
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  private Thread createProducerForCompletesFiniteResponsesAsynchronously(
      org.eclipse.jetty.server.Response sink,
      Callback completion,
      boolean hasAfterFlush,
      AtomicInteger afterFlushes,
      boolean afterFlushFails,
      AtomicReference<Throwable> failure,
      CountDownLatch returned) {
    return Thread.startVirtualThread(
        () -> {
          try {
            var response = response(sink, completion);
            if (hasAfterFlush) {
              response.flushHooks(
                  () -> {},
                  () -> {
                    afterFlushes.incrementAndGet();
                    if (afterFlushFails) {
                      throw new IllegalStateException("after flush");
                    }
                  });
            }

            var original = "finished".getBytes(StandardCharsets.UTF_8);
            response.body("text/plain;charset=utf-8", original);
            original[0] = '!';
            response.complete();

            assertThrows(IllegalStateException.class, () -> response.text("too late"));
          } catch (Throwable error) {
            failure.set(error);
          } finally {
            returned.countDown();
          }
        });
  }

  @Test
  void failsCompletionWhenSynchronousTransportCallbackSeesAfterFlushFailure() throws Exception {
    var events = new ArrayList<String>();
    var headers = HttpFields.build();
    var successes = new AtomicInteger();
    var failures = new AtomicInteger();
    var sink =
        createSinkForFailsCompletionWhenSynchronousTransportCallbackSeesAfterFlushFailure(headers);
    var completion =
        Callback.from(successes::incrementAndGet, ignored -> failures.incrementAndGet());
    var response = response(sink, completion);
    response.flushHooks(
        () -> events.add("before"),
        () -> {
          events.add("after");
          throw new IllegalStateException("after flush");
        });

    response.text("finished");
    response.complete();

    assertEquals(List.of("before", "after"), events);
    assertEquals(0, successes.get());
    assertEquals(1, failures.get());
  }

  private org.eclipse.jetty.server.Response
      createSinkForFailsCompletionWhenSynchronousTransportCallbackSeesAfterFlushFailure(
          Mutable headers) {
    return (org.eclipse.jetty.server.Response)
        Proxy.newProxyInstance(
            HttpContractTest.class.getClassLoader(),
            new Class<?>[] {org.eclipse.jetty.server.Response.class},
            (ignoredObject, method, args) ->
                switch (method.getName()) {
                  case "getStatus" -> 200;
                  case "getHeaders" -> headers;
                  case "isCommitted" -> false;
                  case "write" -> {
                    ((Callback) args[2]).succeeded();
                    yield null;
                  }
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  private static Response response(org.eclipse.jetty.server.Response sink, Callback completion) {
    var nativeRequest = createNativeRequestForResponse();

    return Request.create(nativeRequest, sink, new Options(), completion).response();
  }

  private static org.eclipse.jetty.server.Request createNativeRequestForResponse() {
    return (org.eclipse.jetty.server.Request)
        Proxy.newProxyInstance(
            HttpContractTest.class.getClassLoader(),
            new Class<?>[] {org.eclipse.jetty.server.Request.class},
            (ignoredObject, method, ignoredObjectValues) ->
                switch (method.getName()) {
                  case "getMethod" -> "GET";
                  case "getHeaders" -> HttpFields.EMPTY;
                  case "getLength" -> 0L;
                  case "consumeAvailable" -> true;
                  case "addHttpStreamWrapper" -> null;
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  private static HttpRequest.Builder request(String uri) {
    return HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(5));
  }
}
