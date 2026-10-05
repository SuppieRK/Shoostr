package io.github.suppierk.shoostr.testing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpMethods;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class TestClientTest {
  @Test
  void sendsAndReturnsExactBinaryBytes() throws Exception {
    var app = new Shoostr();
    app.routes()
        .post(
            "/echo",
            (request, response) -> response.body("application/octet-stream", request.bodyBytes()));

    try (var test = TestServer.start(app)) {
      var reply =
          test.send(
              request -> request.path("/echo").method("POST").body(new byte[] {0, 1, (byte) 255}));
      assertEquals(200, reply.statusCode());
      assertArrayEquals(new byte[] {0, 1, (byte) 255}, reply.body());
    }
  }

  @Test
  void consumesTextThroughAnExplicitJdkBodyHandler() throws Exception {
    var app = new Shoostr();
    app.routes().get("/hello", (_, response) -> response.text("café"));

    try (var test = TestServer.start(app)) {
      assertEquals(
          "café",
          test.send(request -> request.path("/hello"), HttpResponse.BodyHandlers.ofString())
              .body());
    }
  }

  @Test
  void preservesEncodedPathAndRawQueryInTheOutgoingRequest() throws Exception {
    var app = new Shoostr();
    app.routes().get("/value/{id}", (_, response) -> response.text("ok"));

    try (var test = TestServer.start(app)) {
      var reply = test.send(request -> request.path("/value/hello%20world?q=a%2Bb&x=a+b"));
      assertEquals(200, reply.statusCode());
      assertEquals("/value/hello%20world", reply.request().uri().getRawPath());
      assertEquals("q=a%2Bb&x=a+b", reply.request().uri().getRawQuery());
    }
  }

  @Test
  void appendsRepeatedHeadersAndAcceptsExistingHeaderNames() throws Exception {
    var app = new Shoostr();
    app.routes().get("/headers", (_, response) -> response.text("ok"));

    try (var test = TestServer.start(app)) {
      var reply =
          test.send(
              request ->
                  request
                      .path("/headers")
                      .header("X-Value", "first")
                      .header("X-Value", "second")
                      .header(HttpHeaders.ACCEPT, "text/plain"));
      assertEquals(List.of("first", "second"), reply.request().headers().allValues("X-Value"));
      assertEquals("text/plain", reply.request().headers().firstValue("Accept").orElseThrow());
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = HttpMethods.class,
      names = {"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD", "BASELINE_CONTROL"})
  void usesTheRegisteredMethodsWireValue(HttpMethods method) throws Exception {
    var app = new Shoostr();
    app.routes().route(method, "/method", (_, response) -> response.status(204));

    try (var test = TestServer.start(app)) {
      var reply = test.send(request -> request.path("/method").method(method));
      assertEquals(204, reply.statusCode());
      assertEquals(method.value(), reply.request().method());
    }
  }

  @Test
  void acceptsACustomMethodToken() throws Exception {
    var app = new Shoostr();
    app.routes().get("/method", (_, response) -> response.text("registered GET"));

    try (var test = TestServer.start(app)) {
      var reply = test.send(request -> request.path("/method").method("CUSTOM"));
      assertEquals("CUSTOM", reply.request().method());
      assertEquals(405, reply.statusCode());
    }
  }

  @Test
  void retainsBodyBytesWhenMethodIsConfiguredAfterTheBody() throws Exception {
    var app = new Shoostr();
    app.routes()
        .post(
            "/echo",
            (request, response) -> response.body("application/octet-stream", request.bodyBytes()));

    try (var test = TestServer.start(app)) {
      assertArrayEquals(
          new byte[] {1, 2},
          test.send(request -> request.path("/echo").body(new byte[] {1, 2}).method("POST"))
              .body());
    }
  }

  @Test
  void copiesBodyBytesBeforeTheCallerCanModifyThem() throws Exception {
    var app = new Shoostr();
    app.routes()
        .post(
            "/echo",
            (request, response) -> response.body("application/octet-stream", request.bodyBytes()));
    var bytes = new byte[] {1, 2};

    try (var test = TestServer.start(app)) {
      var reply =
          test.send(
              request -> {
                request.path("/echo").method("POST").body(bytes);
                bytes[0] = 9;
              });
      assertArrayEquals(new byte[] {1, 2}, reply.body());
    }
  }

  @Test
  void defaultsToGetWithNoBodyAndATenSecondRequestTimeout() throws Exception {
    var app = new Shoostr();
    app.routes()
        .get(
            "/default",
            (request, response) -> response.body("application/octet-stream", request.bodyBytes()));

    try (var test = TestServer.start(app)) {
      var reply = test.send(request -> request.path("/default"));
      assertEquals("GET", reply.request().method());
      assertArrayEquals(new byte[0], reply.body());
      assertEquals(Duration.ofSeconds(10), reply.request().timeout().orElseThrow());
    }
  }

  @Test
  void overridesTheRequestTimeout() throws Exception {
    var app = new Shoostr();
    app.routes().get("/value", (_, response) -> response.text("ok"));

    try (var test = TestServer.start(app)) {
      var reply = test.send(request -> request.path("/value").timeout(Duration.ofSeconds(2)));
      assertEquals(Duration.ofSeconds(2), reply.request().timeout().orElseThrow());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "http://elsewhere.test/path",
        "https://127.0.0.1/path",
        "//elsewhere.test/path",
        "///elsewhere.test/path",
        "/value#fragment"
      })
  void rejectsPathsThatCannotStayWithinTheFixtureOrigin(String path) throws Exception {
    try (var test = TestServer.start(new Shoostr())) {
      assertThrows(IllegalArgumentException.class, () -> test.send(request -> request.path(path)));
    }
  }

  @Test
  void requiresAnExplicitPathForEachSend() throws Exception {
    var app = new Shoostr();
    app.routes().get("/value", (_, response) -> response.text("ok"));

    try (var test = TestServer.start(app)) {
      assertEquals(200, test.send(request -> request.path("/value")).statusCode());
      assertThrows(IllegalStateException.class, () -> test.send(_ -> {}));
    }
  }

  @Test
  void propagatesConfigurationFailureAndClosesItsRequestConfiguration() throws Exception {
    var retained = new AtomicReference<TestRequest>();
    var expected = new IllegalArgumentException("caller configuration failed");

    try (var test = TestServer.start(new Shoostr())) {
      var failure =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  test.send(
                      request -> {
                        retained.set(request);
                        throw expected;
                      }));
      assertSame(expected, failure);
      var configuration = retained.get();
      assertThrows(IllegalStateException.class, () -> configuration.path("/late"));
    }
  }

  @Test
  void startsEachSendWithFreshMethodHeadersAndBody() throws Exception {
    var app = new Shoostr();
    app.routes().post("/write", (_, response) -> response.text("written"));
    app.routes()
        .get(
            "/read",
            (request, response) ->
                response.text(
                    request.header("X-Value").orElse("absent") + ":" + request.bodyBytes().length));

    try (var test = TestServer.start(app)) {
      test.send(
          request ->
              request
                  .path("/write")
                  .method("POST")
                  .header("X-Value", "first")
                  .body(new byte[] {1}));
      assertEquals(
          "absent:0",
          test.send(request -> request.path("/read"), HttpResponse.BodyHandlers.ofString()).body());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"bad method", "GET\n", ""})
  void rejectsInvalidMethodTokensDuringConfiguration(String method) throws Exception {
    try (var test = TestServer.start(new Shoostr())) {
      assertThrows(
          IllegalArgumentException.class,
          () -> test.send(request -> request.path("/").method(method)));
    }
  }

  @Test
  void rejectsNonpositiveTimeoutsDuringConfiguration() throws Exception {
    try (var test = TestServer.start(new Shoostr())) {
      assertThrows(
          IllegalArgumentException.class,
          () -> test.send(request -> request.path("/").timeout(Duration.ZERO)));
    }
  }

  @Test
  void rejectsInvalidHeadersDuringConfiguration() throws Exception {
    try (var test = TestServer.start(new Shoostr())) {
      assertThrows(
          IllegalArgumentException.class,
          () -> test.send(request -> request.path("/").header("Bad Header", "value")));
    }
  }

  @Test
  void closesRequestConfigurationAfterSuccessfulSending() throws Exception {
    var app = new Shoostr();
    app.routes().get("/value", (_, response) -> response.text("ok"));
    var retained = new AtomicReference<TestRequest>();

    try (var test = TestServer.start(app)) {
      test.send(
          request -> {
            retained.set(request);
            request.path("/value");
          });
      var configuration = retained.get();
      assertThrows(IllegalStateException.class, () -> configuration.header("X-Late", "no"));
    }
  }
}
