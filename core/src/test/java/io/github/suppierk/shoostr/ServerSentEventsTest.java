package io.github.suppierk.shoostr;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.HttpHeaders;
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

class ServerSentEventsTest {
  @Test
  void sendsUtf8MultilineDataAsOneCompleteEvent() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .sse("/events", (request, response) -> response.startEventStream().send("café\nnext"));
      app.start();

      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/events"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(200, result.statusCode());
      assertEquals(
          "text/event-stream; charset=utf-8",
          result.headers().firstValue("Content-Type").orElseThrow());
      assertEquals("data: café\ndata: next\n\n", result.body());
    }
  }

  @Test
  void rejectsFiniteOutputFromAnSseRoute() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().sse("/events", (request, response) -> response.text("not an event stream"));
      app.start();

      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/events"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(500, result.statusCode());
    }
  }

  @Test
  void rejectsAnIncompatibleStreamBeforeCommittingTheSseResponse() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .sse(
              "/events",
              (request, response) -> response.startStream("application/json").write("wrong"));
      app.start();

      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/events"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(500, result.statusCode());
    }
  }

  @Test
  void composesAnSseRouteInsidePathScopes() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .path(
              "/api",
              routes ->
                  routes.sse(
                      "/events",
                      (request, response) -> response.startEventStream().send("scoped")));
      app.start();

      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/api/events"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals("data: scoped\n\n", result.body());
    }
  }

  @Test
  void treatsAnSseRouteAndGetAtTheSamePathAsDuplicates() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      var routes = app.routes();
      routes.sse("/events", (request, response) -> response.startEventStream().send("ok"));

      assertThrows(
          IllegalArgumentException.class,
          () -> routes.get("/events", (request, response) -> response.text("other")));
    }
  }

  @Test
  void allowsNoContentToStopEventSourceReconnection() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().sse("/events", (request, response) -> response.status(204));
      app.start();

      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/events"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(204, result.statusCode());
      assertEquals("", result.body());
    }
  }

  @Test
  void globalExceptionHandlerCanStreamAReplacementErrorForAnSseRoute() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.exception(
          IllegalArgumentException.class,
          (failure, request, response) ->
              response
                  .status(422)
                  .startStream("application/json")
                  .write("{\"error\":\"invalid\"}"));
      app.routes()
          .sse(
              "/events",
              (request, response) -> {
                throw new IllegalArgumentException("invalid subscription");
              });
      app.start();

      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/events"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(422, result.statusCode());
      assertEquals("application/json", result.headers().firstValue("Content-Type").orElseThrow());
      assertEquals("{\"error\":\"invalid\"}", result.body());
    }
  }

  @Test
  @Timeout(10)
  void deliversEmptyAndSuccessiveEventsInOrderBeforeHandlerReturn() throws Exception {
    var release = new CountDownLatch(1);
    var exited = new CountDownLatch(1);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .sse(
              "/events",
              (request, response) -> {
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
      app.start();

      try {
        var result =
            client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/events"))
                    .build(),
                HttpResponse.BodyHandlers.ofInputStream());

        try (var input = result.body()) {
          var frames = "data: \n\ndata: first\n\ndata: second\n\n";
          assertEquals(
              frames,
              new String(
                  input.readNBytes(frames.getBytes(StandardCharsets.UTF_8).length),
                  StandardCharsets.UTF_8));
          assertFalse(exited.await(100, TimeUnit.MILLISECONDS));
          release.countDown();
          assertEquals(-1, input.read());
          assertTrue(exited.await(3, TimeUnit.SECONDS));
        }
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void sendsEventNameIdAndRetryBeforeData() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/events",
              (request, response) -> {
                var event =
                    SseEvent.of("ready")
                        .withEvent("update")
                        .withId("cursor-7")
                        .withRetry(Duration.ofSeconds(2));
                response.startEventStream().send(event);
              });
      app.start();

      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/events"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals("event: update\nid: cursor-7\nretry: 2000\ndata: ready\n\n", result.body());
    }
  }

  @Test
  void prefixesEveryNormalizedDataAndCommentLineIncludingTrailingEmptyLines() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/events",
              (request, response) -> {
                var events = response.startEventStream();
                events.send(SseEvent.of("first\r\nid: forged\rtail\n").withId("good"));
                events.comment("note\rretry: 1\n");
                events.heartbeat();
              });
      app.start();

      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/events"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(
          String.join(
              "",
              "id: good\ndata: first\ndata: id: forged\ndata: tail\ndata: \n\n",
              ": note\n: retry: 1\n: \n\n:\n\n"),
          result.body());
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
    var negativeRetry = Duration.ofMillis(-1);
    var overflowingRetry = Duration.ofSeconds(Long.MAX_VALUE);
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

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/events",
              (request, response) -> {
                var previous = request.header(HttpHeaders.LAST_EVENT_ID.value());
                if (previous != null) {
                  lastEventId.set(previous);
                }

                response
                    .startEventStream()
                    .send(
                        previous == null
                            ? SseEvent.of("first").withId("cursor-1")
                            : SseEvent.of("current").withId(""));
              });
      app.start();
      var uri = URI.create("http://127.0.0.1:" + app.port() + "/events");

      var first =
          client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
      var reconnected =
          client.send(
              HttpRequest.newBuilder(uri).header("Last-Event-ID", "cursor-1").build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals("id: cursor-1\ndata: first\n\n", first.body());
      assertEquals("cursor-1", lastEventId.get());
      assertEquals("id: \ndata: current\n\n", reconnected.body());
    }
  }

  @Test
  void sendsEventsOverClearTextHttp2() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build()) {
      app.http2();
      app.routes().get("/events", (request, response) -> response.startEventStream().send("h2"));
      app.start();

      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/events"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(HttpClient.Version.HTTP_2, result.version());
      assertEquals("data: h2\n\n", result.body());
    }
  }

  @Test
  @Timeout(10)
  @SuppressWarnings(
      "java:S2925") // Heartbeats must be paced while waiting for the peer to disconnect.
  void idleClientDisconnectReleasesHandlerSubscriptionOnHeartbeat() throws Exception {
    var active = new CountDownLatch(1);
    var cleaned = new CountDownLatch(1);
    var writeFailed = new CountDownLatch(1);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var socket = new Socket()) {
      app.routes()
          .get(
              "/events",
              (request, response) -> {
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

  @Test
  @Timeout(12)
  void stalledClientBackpressuresSynchronousEventProduction() throws Exception {
    var firstSent = new CountDownLatch(1);
    var cleaned = new CountDownLatch(1);
    var sent = new AtomicInteger();
    var payload = "x".repeat(64 * 1024);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var socket = new Socket()) {
      app.routes()
          .get(
              "/events",
              (request, response) -> {
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

  @Test
  @Timeout(10)
  void forcedAppCloseReleasesAnActiveEventSource() throws Exception {
    var cleaned = new CountDownLatch(1);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.modifyServer(
          server -> {
            server.setStopTimeout(100);
            ((ServerConnector) server.getConnectors()[0]).setShutdownIdleTimeout(0);
          });
      app.routes()
          .get(
              "/events",
              (request, response) -> {
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
      assertEquals(
          "data: ready\n\n", new String(result.body().readNBytes(13), StandardCharsets.UTF_8));

      assertThrows(IOException.class, app::close);
      assertTrue(cleaned.await(3, TimeUnit.SECONDS));
      result.body().close();
    }
  }
}
