package io.github.suppierk.shoostr.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.HttpMethods;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class TestRequestPathTest {
  @Test
  void fillsWholeSegmentsAndEncodesEachComponentOnce() {
    assertEquals(
        "/%6citeral/caf%C3%A9%20%F0%9F%90%88%2B%2520%3F%23%26%3D/end/"
            + "?k%26%3D=%2B%252B&chars=azAZ09-._~%2A",
        TestRequest.buildPathFromTemplate(
            "/%6citeral/{_room-id}/end/",
            path ->
                path.pathParam("_room-id", "café 🐈+%20?#&=")
                    .queryParam("k&=", "+%2B")
                    .queryParam("chars", "azAZ09-._~*")));
  }

  @Test
  void replacesValuesForEveryOccurrenceAndClosesTheBuilderAfterSuccess() {
    var retained = new AtomicReference<TestRequest.PathBuilder>();
    assertEquals(
        "rooms/b/copies/b?tag=java",
        TestRequest.buildPathFromTemplate(
            "rooms/{room}/copies/{room}",
            path -> {
              retained.set(path);
              path.pathParam("room", "a").pathParam("room", "b").queryParam("tag", "java");
            }));
    var closedPath = retained.get();
    assertThrows(IllegalStateException.class, () -> closedPath.pathParam("room", "c"));
    assertThrows(IllegalStateException.class, () -> closedPath.queryParam("tag", "later"));
  }

  @Test
  void appendsOrderedPairsToTheOriginalRawQuery() {
    assertEquals(
        "/rooms/engineering%20west?fixed=a+b&fixed=%2b&tag=java&tag=a%2Bb&tag=",
        TestRequest.buildPathFromTemplate(
            "/rooms/{room}?fixed=a+b&fixed=%2b",
            path ->
                path.pathParam("room", "engineering west")
                    .queryParam("tag", "java")
                    .queryParam("tag", "a+b")
                    .queryParam("tag", "")));
  }

  @ParameterizedTest
  @CsvSource({
    "/search, /search?tag=a%20b",
    "/search?, /search?tag=a%20b",
    "/search?existing=a&, /search?existing=a&&tag=a%20b",
    "/search/%2B?existing=%20, /search/%2B?existing=%20&tag=a%20b"
  })
  void appendsToRawTargetsWithoutChangingTheirSpelling(String raw, String expected) {
    assertEquals(raw, TestRequest.buildPath(raw, ignored -> {}));
    assertEquals(expected, TestRequest.buildPath(raw, path -> path.queryParam("tag", "a b")));
  }

  @Test
  void reportsMissingAndUnknownParametersWithoutChangingAssignments() {
    assertThrows(
        IllegalStateException.class,
        () ->
            TestRequest.buildPathFromTemplate(
                "/{group}/{room}", path -> path.pathParam("group", "eng")));

    assertEquals(
        "/eng/lobby",
        TestRequest.buildPathFromTemplate(
            "/{group}/{room}",
            path -> {
              path.pathParam("group", "eng");
              assertThrows(IllegalArgumentException.class, () -> path.pathParam("Room", "wrong"));
              path.pathParam("room", "lobby");
              assertThrows(IllegalArgumentException.class, () -> path.pathParam("room", ".."));
            }));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", ".", "..", "room/other", "room\\other", "/host"})
  void rejectsValuesThatCannotFillOneSegment(String value) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TestRequest.buildPathFromTemplate(
                "/rooms/{room}", path -> path.pathParam("room", value)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "", "https://elsewhere.test/{room}", "//{host}/rooms", "///{room}", "/{room}#fragment",
        "/{room}?q={value}", "/{}", "/{9room}", "/{*tail}", "/{room:[0-9]+}",
        "/prefix-{room}", "/{room", "/room}/", "/{room}/%xx", "/{room}?q=%"
      })
  void rejectsMalformedTemplatesAtConstruction(String template) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TestRequest.buildPathFromTemplate(
                template, ignored -> fail("Malformed template accepted")));
  }

  @Test
  void rejectsNullArgumentsAndMalformedUnicodeBeforeAppending() {
    assertThrows(
        NullPointerException.class, () -> TestRequest.buildPathFromTemplate(null, ignored -> {}));
    assertThrows(NullPointerException.class, () -> TestRequest.buildPath("/rooms", null));
    assertThrows(
        NullPointerException.class, () -> TestRequest.buildPathFromTemplate("/rooms", null));
    assertThrows(NullPointerException.class, () -> TestRequest.buildPath(null, ignored -> {}));

    assertEquals(
        "/kept",
        TestRequest.buildPathFromTemplate(
            "/{room}",
            path -> {
              path.pathParam("room", "kept");
              assertThrows(NullPointerException.class, () -> path.pathParam(null, "value"));
              assertThrows(NullPointerException.class, () -> path.pathParam("room", null));
              assertThrows(NullPointerException.class, () -> path.queryParam(null, "value"));
              assertThrows(NullPointerException.class, () -> path.queryParam("tag", null));

              var unpairedSurrogate = String.valueOf((char) 0xD800);

              assertThrows(
                  IllegalArgumentException.class, () -> path.pathParam("room", unpairedSurrogate));
              assertThrows(
                  IllegalArgumentException.class, () -> path.queryParam("tag", unpairedSurrogate));
              assertThrows(
                  IllegalArgumentException.class,
                  () -> path.queryParam(unpairedSurrogate, "value"));
            }));
  }

  @Test
  void sendsAllPathFormsWithMethodHeadersAndBody() throws Exception {
    var app = new Shoostr();
    app.routes()
        .post("/rooms/{room}", (request, response) -> response.text(requestSummary(request)));

    try (var test = TestServer.start(app)) {
      var raw = test.send(request -> request.path("/rooms/lobby?tag=a%2Bb").method("POST"));

      assertEquals(200, raw.statusCode());
      assertEquals("lobby|a+b|-|", new String(raw.body(), StandardCharsets.UTF_8));

      assertEquals("lobby|a+b|-|", sendLiteralRequest(test).body());
      assertEquals("lobby|a+b|test|payload", sendTemplatedRequest(test).body());
    }
  }

  private static HttpResponse<String> sendLiteralRequest(TestServer test)
      throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/rooms/lobby", path -> path.queryParam("tag", "a+b"))
                .method(HttpMethods.POST),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> sendTemplatedRequest(TestServer test)
      throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .pathTemplate(
                    "/rooms/{room}",
                    path -> path.pathParam("room", "lobby").queryParam("tag", "a+b"))
                .method(HttpMethods.POST)
                .header("X-Test", "test")
                .body("payload".getBytes(StandardCharsets.UTF_8)),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String requestSummary(Request request) throws IOException {
    return request.pathParam("room").orElseThrow()
        + "|"
        + request.queryParam("tag").orElseThrow()
        + "|"
        + request.header("X-Test").orElse("-")
        + "|"
        + request.bodyText();
  }

  @Test
  void keepsThePreviousTargetAndClosesTheBuilderWhenConfigurationFails() throws Exception {
    var app = new Shoostr();
    app.routes().get("/kept", (request, response) -> response.text("kept"));
    var retained = new AtomicReference<TestRequest.PathBuilder>();
    var finished = new AtomicReference<TestRequest>();
    var expected = new IllegalArgumentException("callback failed");

    try (var test = TestServer.start(app)) {
      assertEquals("kept", sendAfterPathFailures(test, retained, finished, expected).body());
    }

    var closedPath = retained.get();
    var closedRequest = finished.get();
    assertThrows(IllegalStateException.class, () -> closedPath.queryParam("tag", "late"));
    assertThrows(
        IllegalStateException.class,
        () -> closedRequest.path("/late", ignored -> fail("Closed request invoked path callback")));
    assertThrows(
        IllegalStateException.class,
        () ->
            closedRequest.pathTemplate(
                "/late/{room}", ignored -> fail("Closed request invoked template callback")));
  }

  private static HttpResponse<String> sendAfterPathFailures(
      TestServer test,
      AtomicReference<TestRequest.PathBuilder> retained,
      AtomicReference<TestRequest> finished,
      IllegalArgumentException expected)
      throws IOException, InterruptedException {
    return test.send(
        request -> {
          finished.set(request);
          request.path("/kept");

          assertThrows(
              IllegalStateException.class,
              () -> request.pathTemplate("/missing/{room}", ignored -> {}));
          assertSame(
              expected,
              assertThrows(
                  IllegalArgumentException.class,
                  () ->
                      request.path(
                          "/discarded",
                          builder -> {
                            retained.set(builder);
                            builder.queryParam("tag", "discarded");
                            throw expected;
                          })));
        },
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void concurrentConstructionsUseIndependentParameters() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> roomPath("first"));
      var second = executor.submit(() -> roomPath("second"));

      assertEquals("/rooms/first?tag=first", first.get(3, TimeUnit.SECONDS));
      assertEquals("/rooms/second?tag=second", second.get(3, TimeUnit.SECONDS));
    }
  }

  private static String roomPath(String room) {
    return TestRequest.buildPathFromTemplate(
        "/rooms/{room}", path -> path.pathParam("room", room).queryParam("tag", room));
  }

  @Test
  void sendsTheSameEncodedTargetThroughHttpAndWebSocket() throws Exception {
    var upgrade = new AtomicReference<String>();
    var app = new Shoostr();
    app.routes()
        .get("/rooms/{room}", (request, response) -> response.text(receivedTarget(request)))
        .websocket(
            "/rooms/{room}",
            socket ->
                socket
                    .beforeUpgrade((request, response) ->
                        upgrade.set(
                            receivedTarget(request) + "|" + request.header("X-Test").orElseThrow()))
                    .onOpen(connection -> connection.send(upgrade.get())));

    var expected =
        "/rooms/caf%C3%A9%20%2B%20room?existing=a+b&tag=a%26b%3Dc&tag=a%2Bb"
            + "|café + room|[a&b=c, a+b]";

    try (var test = TestServer.start(app);
        var socket = openRoom(test)) {
      var response = sendRoomRequest(test);

      assertEquals(200, response.statusCode());
      assertEquals(expected, response.body());
      assertEquals(expected + "|configured", socket.receiveText(Duration.ofSeconds(3)));
    }
  }

  private static HttpResponse<String> sendRoomRequest(TestServer test)
      throws IOException, InterruptedException {
    return test.send(
        request ->
            request.pathTemplate("/rooms/{room}?existing=a+b", TestRequestPathTest::configureRoom),
        HttpResponse.BodyHandlers.ofString());
  }

  private static TestWebSocket openRoom(TestServer test) throws IOException, InterruptedException {
    return test.openWebSocket(
        "/rooms/{room}?existing=a+b",
        TestRequestPathTest::configureRoom,
        handshake -> handshake.header("X-Test", "configured"));
  }

  private static void configureRoom(TestRequest.PathBuilder path) {
    path.pathParam("room", "café + room").queryParam("tag", "a&b=c").queryParam("tag", "a+b");
  }

  private static String receivedTarget(Request request) {
    return URI.create(request.url()).getRawPath()
        + "?"
        + request.queryString().orElseThrow()
        + "|"
        + request.pathParam("room").orElseThrow()
        + "|"
        + request.queryParams("tag");
  }
}
