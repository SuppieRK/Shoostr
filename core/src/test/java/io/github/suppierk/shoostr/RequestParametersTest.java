package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import io.github.suppierk.shoostr.http.exceptions.ContentTooLargeException;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.http.HttpTester;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class RequestParametersTest {
  private Shoostr app;

  @BeforeEach
  void prepare() {
    app = new Shoostr(Options.defaults().withPort(0));
  }

  @AfterEach
  void close() throws Exception {
    app.close();
  }

  @Test
  void readsFirstAndRepeatedQueryValues() throws Exception {
    app.routes()
        .get(
            "/query",
            (request, response) ->
                response.text(
                    request.queryParam("name").orElseThrow()
                        + "|"
                        + request.queryParams("name")
                        + "|"
                        + request.queryParam("token").orElseThrow()));

    try (var test = TestServer.start(app)) {
      var result =
          test.send(
              request ->
                  request
                      .path("/query?na%6De=M%C3%BCnchen+city&name=one%2Btwo&token=a=b%2526")
                      .timeout(Duration.ofSeconds(3)),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("München city|[München city, one+two]|a=b%26", result.body());
    }
  }

  @Test
  void exposesImmutableQueryValuesIncludingEmptyEntries() throws Exception {
    app.routes()
        .get(
            "/query",
            (request, response) -> {
              var values = request.queryParamMap();
              assertEquals(
                  Map.of(
                      "flag",
                      List.of(""),
                      "empty",
                      List.of(""),
                      "",
                      List.of("anon"),
                      "name",
                      List.of("first", ""),
                      "Name",
                      List.of("case")),
                  values);
              var injected = List.of("x");
              assertThrows(
                  UnsupportedOperationException.class, () -> values.put("injected", injected));
              var names = Objects.requireNonNull(values.get("name"));
              var queryNames = request.queryParams("name");
              assertThrows(UnsupportedOperationException.class, () -> names.add("injected"));
              assertThrows(UnsupportedOperationException.class, queryNames::clear);
              assertEquals(Optional.empty(), request.queryParam("NAME"));
              assertEquals(List.of(), request.queryParams("absent"));
              assertEquals(Optional.of("first"), request.queryParam("name"));
              assertEquals(Optional.of(""), request.queryParam("flag"));
              assertEquals(Optional.of(""), request.queryParam("empty"));
              response.text("ok");
            });

    try (var test = TestServer.start(app)) {
      var result =
          test.send(
              request ->
                  request
                      .path("/query?flag&empty=&=anon&name=first&name=&Name=case&&")
                      .timeout(Duration.ofSeconds(3)),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("ok", result.body());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"%FF", "%C3%28", "%E2%82", "%C0%AF", "%ED%A0%80", "%F4%90%80%80"})
  void rejectsMalformedQueryEncoding(String encoded) throws Exception {
    app.exception(
        BadRequestException.class,
        (_, request, response) -> {
          assertThrows(BadRequestException.class, request::queryParamMap);
          response.setHeader("X-Handled", "bad-query").text("bad input");
        });
    app.routes()
        .get(
            "/query",
            (request, response) -> response.text(request.queryParam("good").orElseThrow()));

    try (var test = TestServer.start(app)) {
      var result =
          test.send(
              request ->
                  request.path("/query?good=first&bad=" + encoded).timeout(Duration.ofSeconds(3)),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(400, result.statusCode());
      assertEquals("bad input", result.body());
      assertEquals("bad-query", result.headers().firstValue("X-Handled").orElseThrow());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void enforcesParameterCountIncludingRepeatedNames(boolean repeated) throws Exception {
    assertEquals(1000, Options.defaults().maxParameters());
    var defaults = Options.defaults();
    assertThrows(IllegalArgumentException.class, () -> defaults.withMaxParameters(0));
    assertThrows(IllegalArgumentException.class, () -> defaults.withMaxParameters(-1));
    app.close();
    app = new Shoostr(Options.defaults().withMaxParameters(2).withPort(0));
    app.routes()
        .get(
            "/query",
            (request, response) ->
                response.text(
                    Integer.toString(
                        request.queryParamMap().values().stream().mapToInt(List::size).sum())));

    try (var test = TestServer.start(app)) {
      String query = repeated ? "a=1&a=2" : "a=1&b=2";
      var accepted =
          test.send(
              request -> request.path("/query?" + query).timeout(Duration.ofSeconds(3)),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, accepted.statusCode());
      assertEquals("2", accepted.body());
      var rejected =
          test.send(
              request ->
                  request
                      .path("/query?" + query + (repeated ? "&a=3" : "&c=3"))
                      .timeout(Duration.ofSeconds(3)),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(400, rejected.statusCode());
      assertEquals("Bad Request", rejected.body());
    }
  }

  @Test
  void readsImmutableRepeatedHeadersCaseInsensitively() throws Exception {
    app.routes()
        .get(
            "/headers",
            (request, response) -> {
              assertEquals(List.of("first,second", "third"), request.headers("x-values"));
              assertEquals("first,second", request.header("X-VALUES").orElseThrow());
              assertEquals(Optional.of(""), request.header("X-Empty"));
              assertTrue(request.header("Absent").isEmpty());
              assertEquals(List.of(), request.headers("Absent"));
              var values = request.headers("X-Values");
              assertThrows(UnsupportedOperationException.class, () -> values.add("x"));
              response.text("ok");
            });

    try (var test = TestServer.start(app)) {
      var result =
          test.send(
              request ->
                  request
                      .path("/headers")
                      .timeout(Duration.ofSeconds(3))
                      .header("X-Values", "first,second")
                      .header("X-Values", "third")
                      .header("X-Empty", ""),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("ok", result.body());
    }
  }

  @Test
  void readsImmutableFormValuesWithoutConsumingBodyAccess() throws Exception {
    String encoded = "name=M%C3%BCnchen+city&name=one%2Btwo&flag&empty=&=anon&token=a=b%2526&&";
    app.routes()
        .post(
            "/form",
            (request, response) -> {
              assertEquals(encoded, request.bodyText());
              assertEquals("München city", request.formParam("name").orElseThrow());
              assertEquals(Optional.of(""), request.formParam("flag"));
              assertEquals(Optional.of(""), request.formParam("empty"));
              assertEquals(List.of("München city", "one+two"), request.formParams("name"));
              assertEquals(
                  Map.of(
                      "name",
                      List.of("München city", "one+two"),
                      "flag",
                      List.of(""),
                      "empty",
                      List.of(""),
                      "",
                      List.of("anon"),
                      "token",
                      List.of("a=b%26")),
                  request.formParamMap());
              assertTrue(request.formParam("Name").isEmpty());
              assertEquals(List.of(), request.formParams("missing"));
              var form = request.formParamMap();
              var formNames = request.formParams("name");
              assertThrows(UnsupportedOperationException.class, form::clear);
              assertThrows(UnsupportedOperationException.class, () -> formNames.add("x"));
              assertEquals("query", request.queryParam("name").orElseThrow());
              assertArrayEquals(encoded.getBytes(StandardCharsets.UTF_8), request.bodyBytes());
              response.text("ok");
            });

    try (var test = TestServer.start(app)) {
      var result =
          test.send(
              request ->
                  request
                      .path("/form?name=query")
                      .timeout(Duration.ofSeconds(3))
                      .header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED.value())
                      .method("POST")
                      .body(encoded.getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("ok", result.body());
    }
  }

  @Test
  void preservesOriginalBodyBytesWhenFormFieldsAreParsedFirst() throws Exception {
    var encoded = "name=M%C3%BCnchen+city&name=one%2Btwo&token=a=b%2526&empty=";
    app.routes()
        .post(
            "/form",
            (request, response) -> {
              assertEquals(
                  Map.of(
                      "name", List.of("München city", "one+two"),
                      "token", List.of("a=b%26"),
                      "empty", List.of("")),
                  request.formParamMap());
              assertEquals(encoded, request.bodyText());
              response.body(MediaType.APPLICATION_OCTET_STREAM, request.bodyBytes());
            });

    try (var test = TestServer.start(app)) {
      var result =
          test.send(
              request ->
                  request
                      .path("/form")
                      .timeout(Duration.ofSeconds(3))
                      .header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED.value())
                      .method("POST")
                      .body(encoded.getBytes(StandardCharsets.UTF_8)));

      assertEquals(200, result.statusCode());
      assertArrayEquals(encoded.getBytes(StandardCharsets.UTF_8), result.body());
    }
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "text/plain",
        "application/json",
        "application/x-www-form-urlencoded-extra",
        "application/x-www-form-urlencoded; charset=ISO-8859-1",
        "application/x-www-form-urlencoded; charset=unknown-charset",
        "application/x-www-form-urlencoded; charset=",
        "application/x-www-form-urlencoded; charset",
        "application/x-www-form-urlencoded; charset=\"UTF-8"
      })
  void rejectsUnsupportedFormRepresentations(String contentType) throws Exception {

    app.routes()
        .post(
            "/form", (request, response) -> response.text(request.formParam("name").orElseThrow()));

    try (var test = TestServer.start(app)) {

      var result =
          test.send(
              request -> {
                request
                    .path("/form")
                    .timeout(Duration.ofSeconds(3))
                    .method("POST")
                    .body("name=value".getBytes(StandardCharsets.UTF_8));
                if (contentType != null) {
                  request.header("Content-Type", contentType);
                }
              },
              HttpResponse.BodyHandlers.ofString());
      assertEquals(415, result.statusCode());
      assertEquals("Unsupported Media Type", result.body());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectsMalformedFormUtf8WithoutPartialValues(boolean encoded) throws Exception {
    app.exception(
        BadRequestException.class,
        (_, request, response) -> {
          assertThrows(BadRequestException.class, request::formParamMap);
          response.text("invalid form");
        });
    app.routes()
        .post(
            "/form", (request, response) -> response.text(request.formParam("good").orElseThrow()));

    try (var test = TestServer.start(app)) {
      byte[] invalid =
          encoded
              ? "good=first&bad=%FF".getBytes(StandardCharsets.UTF_8)
              : new byte[] {'g', 'o', 'o', 'd', '=', '1', '&', 'b', 'a', 'd', '=', (byte) 0xFF};
      var result =
          test.send(
              request ->
                  request
                      .path("/form")
                      .timeout(Duration.ofSeconds(3))
                      .header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED.value())
                      .method("POST")
                      .body(invalid),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(400, result.statusCode());
      assertEquals("invalid form", result.body());
    }
  }

  @Test
  void keepsOversizedChunkedBodyRejectedOnRepeatedAccess() throws Exception {
    // Independent transport access is required by this test's wire/client behavior.
    try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
      app.close();
      app = new Shoostr(new Options("127.0.0.1", 0, 7, 1024, 64, 30_000));
      app.exception(
          ContentTooLargeException.class,
          (_, request, response) -> {
            assertThrows(ContentTooLargeException.class, request::bodyBytes);
            assertThrows(ContentTooLargeException.class, request::formParamMap);
            response.text("still oversized");
          });
      app.routes()
          .post(
              "/form", (request, response) -> response.text(request.formParam("a").orElseThrow()));
      app.start();
      var result =
          client.send(
              request("/form")
                  .header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED.value())
                  .POST(
                      HttpRequest.BodyPublishers.ofInputStream(
                          () ->
                              new ByteArrayInputStream(
                                  "a=123456".getBytes(StandardCharsets.UTF_8))))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(413, result.statusCode());
      assertEquals("still oversized", result.body());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "application/x-www-form-urlencoded",
        "APPLICATION/X-WWW-FORM-URLENCODED",
        "application/x-www-form-urlencoded; CHARSET=\"utf-8\"",
        "application/x-www-form-urlencoded; charset=UTF8"
      })
  void acceptsUtf8FormRepresentations(String contentType) throws Exception {
    app.routes()
        .post(
            "/form",
            (request, response) -> {
              assertEquals("city=München", request.bodyText());
              response.text(request.formParam("city").orElseThrow());
            });

    try (var test = TestServer.start(app)) {
      var result =
          test.send(
              request ->
                  request
                      .path("/form")
                      .timeout(Duration.ofSeconds(3))
                      .header("Content-Type", contentType)
                      .method("POST")
                      .body("city=München".getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("München", result.body());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"%", "%0", "%GG", "%C3", "%C3%28", "%E2%82", "%ED%A0%80"})
  void rejectsMalformedFormEscapes(String encoded) throws Exception {
    app.routes()
        .post(
            "/form", (request, response) -> response.text(request.formParam("name").orElseThrow()));

    try (var test = TestServer.start(app)) {
      var result =
          test.send(
              request ->
                  request
                      .path("/form")
                      .timeout(Duration.ofSeconds(3))
                      .header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED.value())
                      .method("POST")
                      .body(("name=" + encoded).getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(400, result.statusCode());
      assertEquals("Bad Request", result.body());
    }
  }

  @Test
  void limitsFormPairsIndependentlyFromQueryPairs() throws Exception {
    app.close();
    app = new Shoostr(Options.defaults().withMaxParameters(2).withPort(0));
    app.routes()
        .post(
            "/form",
            (request, response) -> {
              assertEquals(List.of("q1", "q2"), request.queryParams("a"));
              response.text(request.formParams("a").toString());
            });

    try (var test = TestServer.start(app)) {
      var accepted =
          test.send(
              request ->
                  request
                      .path("/form?a=q1&a=q2")
                      .timeout(Duration.ofSeconds(3))
                      .header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED.value())
                      .method("POST")
                      .body("a=f1&a=f2&&".getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, accepted.statusCode());
      assertEquals("[f1, f2]", accepted.body());
      var rejected =
          test.send(
              request ->
                  request
                      .path("/form?a=q1&a=q2")
                      .timeout(Duration.ofSeconds(3))
                      .header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED.value())
                      .method("POST")
                      .body("a=f1&a=f2&a=f3".getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(400, rejected.statusCode());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void enforcesFormByteBoundary(boolean chunked) throws Exception {
    // Independent transport access is required by this test's wire/client behavior.
    try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
      app.close();
      app = new Shoostr(new Options("127.0.0.1", 0, 7, 1024, 64, 30_000));
      app.routes()
          .post(
              "/form", (request, response) -> response.text(request.formParam("a").orElseThrow()));
      app.start();
      for (String body : List.of("a=12345", "a=123456")) {
        var publisher =
            chunked
                ? HttpRequest.BodyPublishers.ofInputStream(
                    () -> new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
                : HttpRequest.BodyPublishers.ofString(body);
        var result =
            client.send(
                request("/form")
                    .header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED.value())
                    .POST(publisher)
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(body.length() == 7 ? 200 : 413, result.statusCode());
        assertEquals(body.length() == 7 ? "12345" : "Content Too Large", result.body());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "&", "&&"})
  void exposesEmptyParameterCollections(String body) throws Exception {
    app.routes()
        .post(
            "/form",
            (request, response) -> {
              assertEquals(Map.of(), request.queryParamMap());
              assertEquals(Map.of(), request.formParamMap());
              assertTrue(request.queryParam("missing").isEmpty());
              assertTrue(request.formParam("missing").isEmpty());
              response.text("ok");
            });

    try (var test = TestServer.start(app)) {
      var result =
          test.send(
              request ->
                  request
                      .path("/form")
                      .timeout(Duration.ofSeconds(3))
                      .header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED.value())
                      .method("POST")
                      .body(body.getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("ok", result.body());
    }
  }

  @Test
  void parsesOnlyWhenParameterAccessIsRequested() throws Exception {
    app.routes().post("/raw", (request, response) -> response.text(request.bodyText()));

    try (var test = TestServer.start(app)) {
      var result =
          test.send(
              request ->
                  request
                      .path("/raw?bad=%FF")
                      .timeout(Duration.ofSeconds(3))
                      .header("Content-Type", "text/plain")
                      .method("POST")
                      .body("bad=%FF".getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("bad=%FF", result.body());
    }
  }

  @Test
  void preservesSemicolonsInsideQueryValuesRatherThanSplittingPairs() throws Exception {
    app.routes()
        .get(
            "/query",
            (request, response) -> {
              assertEquals("first=one;second=two&third=three", request.queryString().orElseThrow());
              assertEquals(
                  Map.of("first", List.of("one;second=two"), "third", List.of("three")),
                  request.queryParamMap());
              response.text(request.queryParam("first").orElseThrow());
            });
    app.start();
    HttpTester.Response result;

    try (var socket = new Socket()) {
      socket.connect(
          new InetSocketAddress(InetAddress.getAllByName("127.0.0.1")[0], app.port()), 3000);
      socket.setSoTimeout(3000);
      var outgoing =
          String.join(
              "\r\n",
              "GET " + "/query?first=one;second=two&third=three" + " HTTP/1.1",
              "Host: example.test",
              "Connection: close",
              "",
              "");
      socket.getOutputStream().write(outgoing.getBytes(StandardCharsets.US_ASCII));
      result =
          HttpTester.parseResponse(
              new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
      assertNotNull(result);
    }

    assertEquals(200, result.getStatus());
    assertEquals("one;second=two", result.getContent());
  }

  @Test
  void rejectsARawQueryFragmentBeforeInvokingTheEndpoint() throws Exception {
    var calls = new AtomicInteger();
    app.routes()
        .get(
            "/query",
            (request, response) -> {
              calls.incrementAndGet();
              assertEquals("value=one%23fragment", request.queryString().orElseThrow());
              response.text(request.queryParam("value").orElseThrow());
            });
    app.start();
    HttpTester.Response encoded;

    try (var socket = new Socket()) {
      socket.connect(
          new InetSocketAddress(InetAddress.getAllByName("127.0.0.1")[0], app.port()), 3000);
      socket.setSoTimeout(3000);
      var outgoing =
          String.join(
              "\r\n",
              "GET " + "/query?value=one%23fragment" + " HTTP/1.1",
              "Host: example.test",
              "Connection: close",
              "",
              "");
      socket.getOutputStream().write(outgoing.getBytes(StandardCharsets.US_ASCII));
      encoded =
          HttpTester.parseResponse(
              new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
      assertNotNull(encoded);
    }

    assertEquals(200, encoded.getStatus());
    assertEquals("one#fragment", encoded.getContent());
    assertEquals(1, calls.get());
    HttpTester.Response rejected;

    try (var socket = new Socket()) {
      socket.connect(
          new InetSocketAddress(InetAddress.getAllByName("127.0.0.1")[0], app.port()), 3000);
      socket.setSoTimeout(3000);
      var outgoing =
          String.join(
              "\r\n",
              "GET " + "/query?value=one#fragment" + " HTTP/1.1",
              "Host: example.test",
              "Connection: close",
              "",
              "");
      socket.getOutputStream().write(outgoing.getBytes(StandardCharsets.US_ASCII));
      rejected =
          HttpTester.parseResponse(
              new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
      assertNotNull(rejected);
    }

    assertEquals(400, rejected.getStatus());
    assertEquals(1, calls.get());
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
        .timeout(Duration.ofSeconds(3));
  }
}
