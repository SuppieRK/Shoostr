package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.http.HttpFields;
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
  private HttpClient client;
  private String base;

  HttpContractTest() {
    retainedResponse = new AtomicReference<>();
    retainedRequest = new AtomicReference<>();
    staged = new CountDownLatch(1);
    releaseFinite = new CountDownLatch(1);
    releaseStream = new CountDownLatch(1);
  }

  @BeforeEach
  void startServer() throws Exception {
    app = new Shoostr(new Options("127.0.0.1", 0, 16, 1024, 8, 5000));
    client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    app.routes().get("/plain", (req, res) -> res.text("hello"));
    app.routes()
        .get(
            "/virtual",
            (req, res) -> res.text(Boolean.toString(Thread.currentThread().isVirtual())));
    app.routes().post("/echo", (req, res) -> res.text(req.bodyText()));
    app.routes()
        .get(
            "/failure",
            (req, res) -> {
              res.status(201).header("X-Leak", "secret").text("must not escape");
              throw new IllegalArgumentException("secret details");
            });
    app.routes()
        .get(
            "/finite",
            (req, res) -> {
              res.text("staged");
              retainedResponse.set(res);
              retainedRequest.set(req);
              staged.countDown();
              if (!releaseFinite.await(5, TimeUnit.SECONDS)) {
                throw new IOException("test timeout");
              }

              res.text("finished");
            });
    app.routes()
        .get(
            "/stream",
            (req, res) -> {
              var stream = res.startStream("text/plain; charset=utf-8");
              stream.write("first\n");
              stream.flush();
              if (!releaseStream.await(5, TimeUnit.SECONDS)) {
                throw new IOException("test timeout");
              }

              stream.write("last\n");
            });
    app.routes()
        .get(
            "/broken-stream",
            (req, res) -> {
              var stream = res.startStream("text/plain; charset=utf-8");
              stream.write("partial\n");
              stream.flush();
              throw new IOException("failed after commitment");
            });
    app.routes().get("/close", (req, res) -> res.close());
    app.routes()
        .get(
            "/cross-thread",
            (req, res) -> {
              var failure = new AtomicReference<Throwable>();
              var thread =
                  Thread.startVirtualThread(
                      () -> {
                        try {
                          res.text("bad");
                        } catch (Throwable error) {
                          failure.set(error);
                        }
                      });
              thread.join();
              res.text(Boolean.toString(failure.get() instanceof IllegalStateException));
            });
    app.routes().get("/empty", (req, res) -> res.status(204));
    app.routes().get("/invalid-empty", (req, res) -> res.status(204).text("bad"));
    app.routes()
        .get(
            "/invalid-type", (req, res) -> res.body("text/plain\r\nX-Leak: injected", new byte[0]));
    app.routes()
        .get("/invalid-stream-type", (req, res) -> res.startStream("text/plain\nX-Leak: injected"));
    app.routes().get("/invalid-header", (req, res) -> res.header("Bad Header", "value"));
    app.routes().get("/invalid-framing", (req, res) -> res.header("content-length", "12"));
    app.routes()
        .get("/custom-type", (req, res) -> res.body("application/vnd.example+json", new byte[0]));
    app.start();
    base = "http://127.0.0.1:" + app.port();
  }

  @AfterEach
  void closeServerAndClient() throws Exception {
    releaseFinite.countDown();
    releaseStream.countDown();

    try {
      if (client != null) {
        client.close();
      }
    } finally {
      if (app != null) {
        app.close();
      }
    }
  }

  @Test
  void sendsFiniteBody() throws Exception {
    assertEquals("hello", send("/plain").body());
  }

  @Test
  void dispatchesOnVirtualThread() throws Exception {
    assertEquals("true", send("/virtual").body());
  }

  @Test
  void returnsNotFoundForUnknownRoute() throws Exception {
    assertEquals(404, send("/missing").statusCode());
  }

  @Test
  void reportsAllowedMethods() throws Exception {
    var result =
        client.send(
            request(base + "/plain").POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(405, result.statusCode());
    assertEquals("GET", result.headers().firstValue("Allow").orElseThrow());
  }

  @Test
  void discardsStagedSuccessOnHandlerFailure() throws Exception {
    var result = send("/failure");
    assertEquals(500, result.statusCode());
    assertEquals("Internal Server Error", result.body());
    assertTrue(result.headers().firstValue("X-Leak").isEmpty());
  }

  @Test
  void stagesFiniteBodyUntilHandlerReturnsAndClosesApplicationAccess() throws Exception {
    var finite =
        client.sendAsync(
            request(base + "/finite").build(), HttpResponse.BodyHandlers.ofInputStream());
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

  @Test
  void streamsBeforeHandlerReturnsAndFlushesRemainderOnReturn() throws Exception {
    var result =
        client
            .sendAsync(request(base + "/stream").build(), HttpResponse.BodyHandlers.ofInputStream())
            .get(3, TimeUnit.SECONDS);

    try (var input = result.body()) {
      assertEquals("first\n", new String(input.readNBytes(6), StandardCharsets.UTF_8));
      releaseStream.countDown();
      assertEquals("last\n", new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  @Test
  void abortsCommittedStreamOnFailure() {
    assertThrows(IOException.class, () -> send("/broken-stream"));
  }

  @Test
  void rejectsApplicationClosingResponse() throws Exception {
    assertEquals(500, send("/close").statusCode());
  }

  @Test
  void rejectsResponseAccessFromAnotherThread() throws Exception {
    assertEquals("true", send("/cross-thread").body());
  }

  @Test
  void sendsBodylessStatus() throws Exception {
    assertEquals(204, send("/empty").statusCode());
  }

  @Test
  void rejectsBodyForBodylessStatusBeforeCommit() throws Exception {
    assertEquals(500, send("/invalid-empty").statusCode());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"/invalid-type", "/invalid-stream-type", "/invalid-header", "/invalid-framing"})
  void rejectsInvalidHeadersBeforeCommit(String path) throws Exception {
    var result = send(path);
    assertEquals(500, result.statusCode());
    assertTrue(result.headers().firstValue("X-Leak").isEmpty());
  }

  @Test
  void preservesCustomContentType() throws Exception {
    assertEquals(
        "application/vnd.example+json",
        send("/custom-type").headers().firstValue("Content-Type").orElseThrow());
  }

  @Test
  void readsRequestBody() throws Exception {
    var result =
        client.send(
            request(base + "/echo").POST(HttpRequest.BodyPublishers.ofString("hello")).build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals("hello", result.body());
  }

  @Test
  void readsEmptyKnownLengthRequestBody() throws Exception {
    var result =
        client.send(
            request(base + "/echo").POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, result.statusCode());
    assertEquals("", result.body());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void capsRequestBodyWithKnownOrUnknownLength(boolean chunked) throws Exception {
    var body =
        chunked
            ? HttpRequest.BodyPublishers.ofInputStream(
                () -> new ByteArrayInputStream("x".repeat(17).getBytes(StandardCharsets.UTF_8)))
            : HttpRequest.BodyPublishers.ofString("x".repeat(17));
    var result =
        client.send(
            request(base + "/echo").POST(body).build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(413, result.statusCode());
    assertEquals("Content Too Large", result.body());
  }

  @Test
  void freezesRoutesAfterStartup() {
    var routes = app.routes();
    assertThrows(IllegalStateException.class, () -> routes.get("/later", (req, res) -> {}));
  }

  @ParameterizedTest
  @CsvSource({"false, false", "true, false", "false, true", "true, true"})
  @SuppressWarnings("java:S2093") // The response must outlive the producer until its callback.
  void completesFiniteResponsesAsynchronously(boolean transportFails, boolean afterFlushFails)
      throws Exception {
    var pending = new AtomicReference<Callback>();
    var bytes = new AtomicReference<ByteBuffer>();
    var failure = new AtomicReference<Throwable>();
    var returned = new CountDownLatch(1);
    var writes = new AtomicInteger();
    var successes = new AtomicInteger();
    var failures = new AtomicInteger();
    var afterFlushes = new AtomicInteger();
    var headers = HttpFields.build();
    var sink =
        (org.eclipse.jetty.server.Response)
            Proxy.newProxyInstance(
                HttpContractTest.class.getClassLoader(),
                new Class<?>[] {org.eclipse.jetty.server.Response.class},
                (proxy, method, args) ->
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
    var completion =
        Callback.from(successes::incrementAndGet, ignored -> failures.incrementAndGet());
    var producer =
        Thread.startVirtualThread(
            () -> {
              try {
                var response = new Response(sink, Options.defaults(), completion);
                response.flushHooks(
                    () -> {},
                    () -> {
                      afterFlushes.incrementAndGet();
                      if (afterFlushFails) {
                        throw new IllegalStateException("after flush");
                      }
                    });
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

    try {
      assertTrue(
          returned.await(3, TimeUnit.SECONDS), "finite completion does not wait for transport");
      assertNull(failure.get(), "Producer failed");

      assertTrue(
          successes.get() == 0 && failures.get() == 0,
          "request remains pending until transport completion");
      assertEquals(1, afterFlushes.get(), "post-flush observation runs after submission");
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

  @Test
  void failsCompletionWhenSynchronousTransportCallbackSeesAfterFlushFailure() throws Exception {
    var events = new ArrayList<String>();
    var headers = HttpFields.build();
    var successes = new AtomicInteger();
    var failures = new AtomicInteger();
    var sink =
        (org.eclipse.jetty.server.Response)
            Proxy.newProxyInstance(
                HttpContractTest.class.getClassLoader(),
                new Class<?>[] {org.eclipse.jetty.server.Response.class},
                (proxy, method, args) ->
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
    var completion =
        Callback.from(successes::incrementAndGet, ignored -> failures.incrementAndGet());
    var response = new Response(sink, Options.defaults(), completion);
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

  private HttpResponse<String> send(String path) throws Exception {
    return client.send(request(base + path).build(), HttpResponse.BodyHandlers.ofString());
  }

  private static HttpRequest.Builder request(String uri) {
    return HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(5));
  }
}
