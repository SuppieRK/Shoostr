package io.github.suppierk.shoostr;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ServerSentEventsTest {
  @ParameterizedTest
  @ValueSource(strings = {"text/event-stream", "application/json, text/event-stream"})
  @Timeout(10)
  void emitsTheQueryParameterAsExactEventBytesForPositiveAcceptHeaders(String accept)
      throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close) {
        app.routes()
            .sse(
                "/events",
                (request, response) ->
                    response.startEventStream().send(request.queryParam("qp").orElseThrow()));

        try (var test = TestServer.start(app)) {

          var result =
              sendResultForEmitsTheQueryParameterAsExactEventBytesForPositiveAcceptHeaders(
                  test, accept);

          assertEquals(200, result.statusCode());
          assertEquals(
              "text/event-stream; charset=utf-8",
              result.headers().firstValue("Content-Type").orElseThrow());
          assertArrayEquals("data: my-qp\n\n".getBytes(StandardCharsets.UTF_8), result.body());
        }
      }
    }
  }

  private HttpResponse<byte[]>
      sendResultForEmitsTheQueryParameterAsExactEventBytesForPositiveAcceptHeaders(
          TestServer test, String accept) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/events?qp=my-qp")
                .header("Accept", accept)
                .timeout(Duration.ofSeconds(3)));
  }

  @Test
  void sendsEmptyAsciiUnicodeAndMalformedSurrogateDataAsExactUtf8Bytes() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup1 = app::close) {
        app.routes()
            .sse(
                "/events",
                (ignored, response) -> {
                  var events = response.startEventStream();
                  events.send("");
                  events.send("plain");
                  events.send("café😀");
                  events.send("\uD800");
                });

        try (var test = TestServer.start(app)) {

          var result = test.send(request -> request.path("/events"));

          assertArrayEquals(
              "data: \n\ndata: plain\n\ndata: café😀\n\ndata: ?\n\n"
                  .getBytes(StandardCharsets.UTF_8),
              result.body());
        }
      }
    }
  }

  @Test
  void rejectsNullDataWithoutWritingFrame() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup2 = app::close) {
        app.routes()
            .sse(
                "/events",
                (ignoredRequest, response) -> {
                  var events = response.startEventStream();

                  assertThrows(NullPointerException.class, () -> events.send((String) null));

                  events.send("valid");
                });

        try (var test = TestServer.start(app)) {

          var result = test.send(request -> request.path("/events"));

          assertArrayEquals("data: valid\n\n".getBytes(StandardCharsets.UTF_8), result.body());
        }
      }
    }
  }

  @Test
  void preservesInitialAutomaticExplicitAndTerminalFlushesForSingleLineEvent() throws Exception {
    var defaults = new Options();
    var options = new Options();
    options.setMaxRequestBytes(defaults.getMaxRequestBytes());
    options.setMaxResponseBytes(defaults.getMaxResponseBytes());
    options.setStreamBufferBytes(8);
    options.setMaxParameters(1000);
    options.setMultipart(MultipartOptions.defaults());
    var before = new AtomicInteger();
    var after = new AtomicInteger();

    {
      var app = new Shoostr(options);

      try (Closeable appCleanup3 = app::close) {
        app.beforeResponseFlush((ignoredRequest, ignoredResponse) -> before.incrementAndGet());
        app.afterResponseFlush((ignoredRequest, ignoredResponse) -> after.incrementAndGet());
        app.routes()
            .sse("/events", (ignoredRequest, response) -> response.startEventStream().send("abcd"));

        try (var test = TestServer.start(app)) {

          var result = test.send(request -> request.path("/events"));

          assertArrayEquals("data: abcd\n\n".getBytes(StandardCharsets.UTF_8), result.body());

          await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertEquals(4, after.get()));

          assertEquals(4, before.get());
        }
      }
    }
  }

  @Test
  void sendsLargeSingleLinePayloadAsOneExactUtf8Frame() throws Exception {
    var payload = "x".repeat(65_536);

    {
      var app = new Shoostr();

      try (Closeable appCleanup4 = app::close) {
        app.routes()
            .sse(
                "/events", (ignoredRequest, response) -> response.startEventStream().send(payload));

        try (var test = TestServer.start(app)) {

          var result = test.send(request -> request.path("/events"));

          assertArrayEquals(
              ("data: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8), result.body());
        }
      }
    }
  }

  @Test
  @Timeout(15)
  void completesOneHundredLargeUtf8EventsWithoutLossDuplicationOrReordering() throws Exception {
    var payload = "é".repeat(8_704);
    var expected = new StringBuilder();
    for (int sequence = 0; sequence < 100; sequence++) {
      expected.append("data: ").append(sequence).append(':').append(payload).append("\n\n");
    }

    {
      var app = new Shoostr();

      try (Closeable appCleanup5 = app::close) {
        app.routes()
            .sse(
                "/events",
                (ignoredRequest, response) -> {
                  var events = response.startEventStream();
                  for (int sequence = 0; sequence < 100; sequence++) {
                    events.send(sequence + ":" + payload);
                  }
                });

        try (var test = TestServer.start(app)) {

          var result = test.send(request -> request.path("/events").timeout(Duration.ofSeconds(5)));

          assertEquals(200, result.statusCode());
          assertEquals(
              "text/event-stream; charset=utf-8",
              result.headers().firstValue("Content-Type").orElseThrow());
          assertArrayEquals(expected.toString().getBytes(StandardCharsets.UTF_8), result.body());
        }
      }
    }
  }

  @Test
  void sendsUtf8MultilineDataAsOneCompleteEvent() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup6 = app::close) {
        app.routes()
            .sse(
                "/events",
                (ignoredRequest, response) -> response.startEventStream().send("café\nnext"));

        try (var test = TestServer.start(app)) {

          var result =
              test.send(request -> request.path("/events"), HttpResponse.BodyHandlers.ofString());

          assertEquals(200, result.statusCode());
          assertEquals(
              "text/event-stream; charset=utf-8",
              result.headers().firstValue("Content-Type").orElseThrow());
          assertEquals("data: café\ndata: next\n\n", result.body());
        }
      }
    }
  }

  @Test
  @Timeout(10)
  void preservesLeadingWhitespaceInMultilineEventDataOnTheWire() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup7 = app::close) {
        app.routes()
            .sse(
                "/events",
                (ignoredRequest, response) -> response.startEventStream().send("café\n next"));

        try (var test = TestServer.start(app)) {

          var result = test.send(request -> request.path("/events").timeout(Duration.ofSeconds(3)));

          assertEquals(200, result.statusCode());
          assertEquals(
              "text/event-stream; charset=utf-8",
              result.headers().firstValue("Content-Type").orElseThrow());
          assertArrayEquals(
              "data: café\ndata:  next\n\n".getBytes(StandardCharsets.UTF_8), result.body());
        }
      }
    }
  }

  @Test
  void chainsEventsCommentsAndHeartbeatsOnTheSameWriter() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup8 = app::close) {
        app.routes()
            .sse(
                "/events",
                (ignoredRequest, response) -> {
                  var events = response.startEventStream();
                  var event =
                      SseEvent.of("ready")
                          .withEvent("update")
                          .withId("cursor-7")
                          .withRetry(Duration.ofSeconds(2));

                  assertSame(events, events.send("café\nnext"));
                  assertSame(events, events.send(event));
                  assertSame(events, events.comment("connected\nnext"));
                  assertSame(events, events.heartbeat());

                  events.send("tail").send(SseEvent.of("last")).comment("done").heartbeat();
                });

        try (var test = TestServer.start(app)) {

          var result =
              test.send(request -> request.path("/events"), HttpResponse.BodyHandlers.ofString());

          assertEquals(200, result.statusCode());
          assertEquals(
              """
              data: café
              data: next

              event: update
              id: cursor-7
              retry: 2000
              data: ready

              : connected
              : next

              :

              data: tail

              data: last

              : done

              :

              """,
              result.body());
        }
      }
    }
  }

  @Test
  void rejectsFiniteOutputFromAnSseRoute() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup9 = app::close) {
        app.routes()
            .sse("/events", (ignoredRequest, response) -> response.text("not an event stream"));

        try (var test = TestServer.start(app)) {

          var result =
              test.send(request -> request.path("/events"), HttpResponse.BodyHandlers.ofString());

          assertEquals(500, result.statusCode());
        }
      }
    }
  }

  @Test
  void rejectsAnIncompatibleStreamBeforeCommittingTheSseResponse() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup10 = app::close) {
        app.routes()
            .sse(
                "/events",
                (ignoredRequest, response) ->
                    response.startStream("application/json").write("wrong"));

        try (var test = TestServer.start(app)) {

          var result =
              test.send(request -> request.path("/events"), HttpResponse.BodyHandlers.ofString());

          assertEquals(500, result.statusCode());
        }
      }
    }
  }

  @Test
  void composesAnSseRouteInsidePathScopes() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup11 = app::close) {
        app.routes()
            .path(
                "/api",
                routes ->
                    routes.sse(
                        "/events",
                        (ignoredRequest, response) -> response.startEventStream().send("scoped")));

        try (var test = TestServer.start(app)) {

          var result =
              test.send(
                  request -> request.path("/api/events"), HttpResponse.BodyHandlers.ofString());

          assertEquals("data: scoped\n\n", result.body());
        }
      }
    }
  }

  @Test
  void treatsAnSseRouteAndGetAtTheSamePathAsDuplicates() throws Exception {
    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup12 = app::close) {
        var routes = app.routes();
        routes.sse("/events", (ignoredRequest, response) -> response.startEventStream().send("ok"));

        assertThrows(
            IllegalArgumentException.class,
            () -> routes.get("/events", (ignoredRequest, response) -> response.text("other")));
      }
    }
  }

  @Test
  void allowsNoContentToStopEventSourceReconnection() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup13 = app::close) {
        app.routes().sse("/events", (ignoredRequest, response) -> response.status(204));

        try (var test = TestServer.start(app)) {

          var result =
              test.send(request -> request.path("/events"), HttpResponse.BodyHandlers.ofString());

          assertEquals(204, result.statusCode());
          assertEquals("", result.body());
        }
      }
    }
  }

  @Test
  void globalExceptionHandlerCanStreamReplacementErrorForAnSseRoute() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup14 = app::close) {
        app.exception(
            IllegalArgumentException.class,
            (ignoredFailure, ignoredRequest, response) ->
                response
                    .status(422)
                    .startStream("application/json")
                    .write("{\"error\":\"invalid\"}"));
        app.routes()
            .sse(
                "/events",
                (ignoredRequest, ignoredResponse) -> {
                  throw new IllegalArgumentException("invalid subscription");
                });

        try (var test = TestServer.start(app)) {

          var result =
              test.send(request -> request.path("/events"), HttpResponse.BodyHandlers.ofString());

          assertEquals(422, result.statusCode());
          assertEquals(
              "application/json", result.headers().firstValue("Content-Type").orElseThrow());
          assertEquals("{\"error\":\"invalid\"}", result.body());
        }
      }
    }
  }

  @Test
  @Timeout(10)
  void deliversEmptyAndSuccessiveEventsInOrderBeforeHandlerReturn() throws Exception {
    var release = new CountDownLatch(1);
    var exited = new CountDownLatch(1);

    {
      var app = new Shoostr();

      try (Closeable appCleanup15 = app::close) {
        app.routes()
            .sse(
                "/events",
                (ignoredRequest, response) -> {
                  try {
                    var events = response.startEventStream();
                    events.send("");
                    events.send("first");
                    events.send("second");
                    release.await();
                  } finally {
                    exited.countDown();
                  }
                });

        try (var test = TestServer.start(app)) {

          try {
            var result =
                test.send(
                    request -> request.path("/events"), HttpResponse.BodyHandlers.ofInputStream());

            try (var input = result.body()) {
              var frames = "data: \n\ndata: first\n\ndata: second\n\n";

              assertEquals(
                  frames,
                  new String(
                      input.readNBytes(frames.getBytes(StandardCharsets.UTF_8).length),
                      StandardCharsets.UTF_8));

              await("the event handler remains active until released")
                  .pollDelay(Duration.ZERO)
                  .pollInterval(Duration.ofMillis(10))
                  .during(Duration.ofMillis(100))
                  .atMost(Duration.ofMillis(1100))
                  .failFast("the event handler exited before release", () -> exited.getCount() == 0)
                  .until(() -> exited.getCount() > 0);
              release.countDown();

              assertEquals(-1, input.read());
              assertTrue(exited.await(3, TimeUnit.SECONDS));
            }
          } finally {
            release.countDown();
          }
        }
      }
    }
  }

  @Test
  void sendsEventNameIdAndRetryBeforeData() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup16 = app::close) {
        app.routes()
            .get(
                "/events",
                (ignoredRequest, response) -> {
                  var event =
                      SseEvent.of("ready")
                          .withEvent("update")
                          .withId("cursor-7")
                          .withRetry(Duration.ofSeconds(2));
                  response.startEventStream().send(event);
                });

        try (var test = TestServer.start(app)) {

          var result = test.send(request -> request.path("/events"));

          assertArrayEquals(
              "event: update\nid: cursor-7\nretry: 2000\ndata: ready\n\n"
                  .getBytes(StandardCharsets.UTF_8),
              result.body());
        }
      }
    }
  }

  @Test
  void prefixesEveryNormalizedDataAndCommentLineIncludingTrailingEmptyLines() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup17 = app::close) {
        app.routes()
            .get(
                "/events",
                (ignoredRequest, response) -> {
                  var events = response.startEventStream();
                  events.send(SseEvent.of("first\r\nid: forged\rtail\n").withId("good"));
                  events.comment("note\rretry: 1\n");
                  events.heartbeat();
                });

        try (var test = TestServer.start(app)) {

          var result = test.send(request -> request.path("/events"));

          assertArrayEquals(
              String.join(
                      "",
                      "id: good\ndata: first\ndata: id: forged\ndata: tail\ndata: \n\n",
                      ": note\n: retry: 1\n: \n\n:\n\n")
                  .getBytes(StandardCharsets.UTF_8),
              result.body());
        }
      }
    }
  }

  @Test
  void normalizesCrOnlyDataForStringAndMetadataEventsWithoutFieldInjection() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup18 = app::close) {
        app.routes()
            .sse(
                "/events",
                (ignoredRequest, response) -> {
                  var events = response.startEventStream();
                  var payload = "first\rid: forged\r";
                  events.send(payload);
                  events.send(SseEvent.of(payload).withEvent("update"));
                });

        try (var test = TestServer.start(app)) {

          var result = test.send(request -> request.path("/events"));

          assertArrayEquals(
              String.join(
                      "",
                      "data: first\ndata: id: forged\ndata: \n\n",
                      "event: update\ndata: first\ndata: id: forged\ndata: \n\n")
                  .getBytes(StandardCharsets.UTF_8),
              result.body());
        }
      }
    }
  }

  @Test
  void rejectsEventMetadataBeginningWithLineBreaksOrNullCharacters() {
    var event = SseEvent.of("data");
    for (var value : new String[] {"\rname", "\nname", "\0name"}) {
      assertThrows(IllegalArgumentException.class, () -> event.withEvent(value));
      assertThrows(IllegalArgumentException.class, () -> event.withId(value));
    }
  }

  @Test
  void rejectsFieldsThatCouldInjectLinesOrInvalidRetryValues() {
    var event = SseEvent.of("x");
    final var negativeRetry = Duration.ofMillis(-1);
    final var overflowingRetry = Duration.ofSeconds(Long.MAX_VALUE);

    assertThrows(IllegalArgumentException.class, () -> event.withEvent("a\nb"));
    assertThrows(IllegalArgumentException.class, () -> event.withEvent("a\rb"));
    assertThrows(IllegalArgumentException.class, () -> event.withEvent("a\0b"));
    assertThrows(IllegalArgumentException.class, () -> event.withId("a\nb"));
    assertThrows(IllegalArgumentException.class, () -> event.withId("a\rb"));
    assertThrows(IllegalArgumentException.class, () -> event.withId("a\0b"));
    assertThrows(IllegalArgumentException.class, () -> event.withRetry(negativeRetry));
    assertThrows(IllegalArgumentException.class, () -> event.withRetry(overflowingRetry));
  }

  @Test
  void exposesLastEventIdOnReconnectWithoutImplicitHistory() throws Exception {
    var lastEventId = new AtomicReference<String>();

    {
      var app = new Shoostr();

      try (Closeable appCleanup19 = app::close) {
        app.routes()
            .get(
                "/events",
                (request, response) -> {
                  var previous = request.header(HttpHeaders.LAST_EVENT_ID.value());
                  previous.ifPresent(lastEventId::set);

                  response
                      .startEventStream()
                      .send(
                          previous.isEmpty()
                              ? SseEvent.of("first").withId("cursor-1")
                              : SseEvent.of("current").withId(""));
                });

        try (var test = TestServer.start(app)) {

          var first =
              test.send(request -> request.path("/events"), HttpResponse.BodyHandlers.ofString());
          var reconnected =
              test.send(
                  request -> request.path("/events").header("Last-Event-ID", "cursor-1"),
                  HttpResponse.BodyHandlers.ofString());

          assertEquals("id: cursor-1\ndata: first\n\n", first.body());
          assertEquals("cursor-1", lastEventId.get());
          assertEquals("id: \ndata: current\n\n", reconnected.body());
        }
      }
    }
  }

  @Test
  void sendsEventsOverClearTextHttp2() throws Exception {
    var configuredOptions = new Options();
    configuredOptions.setHttp2(true);

    {
      var app = new Shoostr(configuredOptions);

      try (Closeable appCleanup20 = app::close) {

        app.routes()
            .get("/events", (ignoredRequest, response) -> response.startEventStream().send("h2"));

        try (var test =
            TestServer.start(app, client -> client.version(HttpClient.Version.HTTP_2))) {

          var result =
              test.send(request -> request.path("/events"), HttpResponse.BodyHandlers.ofString());

          assertEquals(HttpClient.Version.HTTP_2, result.version());
          assertEquals("data: h2\n\n", result.body());
        }
      }
    }
  }

  @Test
  @Timeout(10)
  @SuppressWarnings("java:S2925")
  // Heartbeats must be paced while waiting for the peer to disconnect.
  void idleClientDisconnectReleasesHandlerSubscriptionOnHeartbeat() throws Exception {
    var active = new CountDownLatch(1);
    var cleaned = new CountDownLatch(1);
    var writeFailed = new CountDownLatch(1);

    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup21 = app::close;
          var socket = new Socket()) {
        app.routes()
            .get(
                "/events",
                (ignoredRequest, response) -> {
                  var events = response.startEventStream();

                  try {
                    events.send("ready");
                    active.countDown();
                    for (int attempt = 0; attempt < 100; attempt++) {
                      Thread.sleep(50);
                      events.heartbeat();
                    }
                  } catch (IOException failure) {
                    writeFailed.countDown();
                    throw failure;
                  } finally {
                    cleaned.countDown();
                  }
                });
        app.start();
        socket.connect(new InetSocketAddress(InetAddress.getAllByName("127.0.0.1")[0], app.port()));
        socket.setSoLinger(true, 0);
        socket
            .getOutputStream()
            .write(
                "GET /events HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        await().atMost(Duration.ofSeconds(3)).until(() -> active.getCount() == 0);

        socket.close();
        await().atMost(Duration.ofSeconds(3)).until(() -> cleaned.getCount() == 0);

        assertEquals(0, writeFailed.getCount());
      }
    }
  }

  @Test
  @Timeout(12)
  void stalledClientBackpressuresSynchronousEventProduction() throws Exception {
    var firstSent = new CountDownLatch(1);
    var cleaned = new CountDownLatch(1);
    var sent = new AtomicInteger();
    var payload = "x".repeat(64 * 1024);

    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup22 = app::close;
          var socket = new Socket()) {
        app.routes()
            .get(
                "/events",
                (ignoredRequest, response) -> {
                  var events = response.startEventStream();

                  try {
                    for (int attempt = 0; attempt < 256; attempt++) {
                      events.send(payload);
                      sent.incrementAndGet();
                      firstSent.countDown();
                    }
                  } finally {
                    cleaned.countDown();
                  }
                });
        app.start();
        socket.setReceiveBufferSize(4096);
        socket.setSoLinger(true, 0);
        socket.connect(new InetSocketAddress(InetAddress.getAllByName("127.0.0.1")[0], app.port()));
        socket
            .getOutputStream()
            .write(
                "GET /events HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();

        assertTrue(firstSent.await(3, TimeUnit.SECONDS));

        await()
            .pollInterval(Duration.ofMillis(25))
            .during(Duration.ofMillis(750))
            .atMost(Duration.ofSeconds(2))
            .untilAsserted(
                () ->
                    assertTrue(
                        sent.get() < 256, "Stalled peer must not admit an unbounded event queue"));
        socket.close();
        await().atMost(Duration.ofSeconds(3)).until(() -> cleaned.getCount() == 0);
      }
    }
  }

  @Test
  @Timeout(10)
  void forcedAppCloseReleasesAnActiveEventSource() throws Exception {
    var cleaned = new CountDownLatch(1);

    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup23 = app::close;
          var client = HttpClient.newHttpClient()) {
        app.modifyServer(
            server -> {
              server.setStopTimeout(100);
              ((ServerConnector) server.getConnectors()[0]).setShutdownIdleTimeout(0);
            });
        app.routes()
            .get(
                "/events",
                (ignoredRequest, response) -> {
                  try {
                    response.startEventStream().send("ready");
                    new CountDownLatch(1).await();
                  } finally {
                    cleaned.countDown();
                  }
                });
        app.start();
        var result =
            client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/events"))
                    .build(),
                HttpResponse.BodyHandlers.ofInputStream());

        try (var body = result.body()) {
          assertEquals("data: ready\n\n", new String(body.readNBytes(13), StandardCharsets.UTF_8));

          assertThrows(IOException.class, app::close);
          assertTrue(cleaned.await(3, TimeUnit.SECONDS));
        }
      }
    }
  }
}
