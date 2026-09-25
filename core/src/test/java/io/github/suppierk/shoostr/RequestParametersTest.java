package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import io.github.suppierk.shoostr.http.exceptions.ContentTooLargeException;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
  void readsFirstAndRepeatedQueryValues() throws Exception {
    app.routes()
        .get(
            "/query",
            (request, response) ->
                response.text(
                    request.queryParam("name")
                        + "|"
                        + request.queryParams("name")
                        + "|"
                        + request.queryParam("token")));
    app.start();
    var result =
        send(request("/query?na%6De=M%C3%BCnchen+city&name=one%2Btwo&token=a=b%2526").build());
    assertEquals(200, result.statusCode());
    assertEquals("München city|[München city, one+two]|a=b%26", result.body());
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
              assertNull(request.queryParam("NAME"));
              assertEquals(List.of(), request.queryParams("absent"));
              assertEquals("first", request.queryParam("name"));
              response.text("ok");
            });
    app.start();
    var result = send(request("/query?flag&empty=&=anon&name=first&name=&Name=case&&").build());
    assertEquals(200, result.statusCode());
    assertEquals("ok", result.body());
  }

  @ParameterizedTest
  @ValueSource(strings = {"%FF", "%C3%28", "%E2%82", "%C0%AF", "%ED%A0%80", "%F4%90%80%80"})
  void rejectsMalformedQueryEncoding(String encoded) throws Exception {
    app.exception(
        BadRequestException.class,
        (failure, request, response) -> {
          assertThrows(BadRequestException.class, request::queryParamMap);
          response.header("X-Handled", "bad-query").text("bad input");
        });
    app.routes()
        .get(
            "/query",
            (request, response) ->
                response.text(Objects.requireNonNull(request.queryParam("good"))));
    app.start();
    var result = send(request("/query?good=first&bad=" + encoded).build());
    assertEquals(400, result.statusCode());
    assertEquals("bad input", result.body());
    assertEquals("bad-query", result.headers().firstValue("X-Handled").orElseThrow());
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
            (request, response) -> {
              response.text(
                  Integer.toString(
                      request.queryParamMap().values().stream().mapToInt(List::size).sum()));
            });
    app.start();
    String query = repeated ? "a=1&a=2" : "a=1&b=2";
    var accepted = send(request("/query?" + query).build());
    assertEquals(200, accepted.statusCode());
    assertEquals("2", accepted.body());
    var rejected = send(request("/query?" + query + (repeated ? "&a=3" : "&c=3")).build());
    assertEquals(400, rejected.statusCode());
    assertEquals("Bad Request", rejected.body());
  }

  @Test
  void readsImmutableRepeatedHeadersCaseInsensitively() throws Exception {
    app.routes()
        .get(
            "/headers",
            (request, response) -> {
              assertEquals(List.of("first,second", "third"), request.headers("x-values"));
              assertEquals("first,second", request.header("X-VALUES"));
              assertEquals(List.of(), request.headers("Absent"));
              var values = request.headers("X-Values");
              assertThrows(UnsupportedOperationException.class, () -> values.add("x"));
              response.text("ok");
            });
    app.start();
    var result =
        send(
            request("/headers")
                .header("X-Values", "first,second")
                .header("X-Values", "third")
                .build());
    assertEquals(200, result.statusCode());
    assertEquals("ok", result.body());
  }

  @Test
  void readsImmutableFormValuesWithoutConsumingBodyAccess() throws Exception {
    String encoded = "name=M%C3%BCnchen+city&name=one%2Btwo&flag&empty=&=anon&token=a=b%2526&&";
    app.routes()
        .post(
            "/form",
            (request, response) -> {
              assertEquals(encoded, request.bodyText());
              assertEquals("München city", request.formParam("name"));
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
              assertNull(request.formParam("Name"));
              assertEquals(List.of(), request.formParams("missing"));
              var form = request.formParamMap();
              var formNames = request.formParams("name");
              assertThrows(UnsupportedOperationException.class, form::clear);
              assertThrows(UnsupportedOperationException.class, () -> formNames.add("x"));
              assertEquals("query", request.queryParam("name"));
              assertArrayEquals(encoded.getBytes(StandardCharsets.UTF_8), request.bodyBytes());
              response.text("ok");
            });
    app.start();
    var result =
        send(
            request("/form?name=query")
                .header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED.value())
                .POST(HttpRequest.BodyPublishers.ofString(encoded))
                .build());
    assertEquals(200, result.statusCode());
    assertEquals("ok", result.body());
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
            "/form",
            (request, response) ->
                response.text(Objects.requireNonNull(request.formParam("name"))));
    app.start();
    var outgoing = request("/form").POST(HttpRequest.BodyPublishers.ofString("name=value"));
    if (contentType != null) {
      outgoing.header("Content-Type", contentType);
    }

    var result = send(outgoing.build());
    assertEquals(415, result.statusCode());
    assertEquals("Unsupported Media Type", result.body());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectsMalformedFormUtf8WithoutPartialValues(boolean encoded) throws Exception {
    app.exception(
        BadRequestException.class,
        (failure, request, response) -> {
          assertThrows(BadRequestException.class, request::formParamMap);
          response.text("invalid form");
        });
    app.routes()
        .post(
            "/form",
            (request, response) ->
                response.text(Objects.requireNonNull(request.formParam("good"))));
    app.start();
    byte[] invalid =
        encoded
            ? "good=first&bad=%FF".getBytes(StandardCharsets.UTF_8)
            : new byte[] {'g', 'o', 'o', 'd', '=', '1', '&', 'b', 'a', 'd', '=', (byte) 0xFF};
    var result =
        send(
            request("/form")
                .header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED.value())
                .POST(HttpRequest.BodyPublishers.ofByteArray(invalid))
                .build());
    assertEquals(400, result.statusCode());
    assertEquals("invalid form", result.body());
  }

  @Test
  void keepsOversizedChunkedBodyRejectedOnRepeatedAccess() throws Exception {
    app.close();
    app = new Shoostr(new Options("127.0.0.1", 0, 7, 1024, 64, 30_000));
    app.exception(
        ContentTooLargeException.class,
        (failure, request, response) -> {
          assertThrows(ContentTooLargeException.class, request::bodyBytes);
          assertThrows(ContentTooLargeException.class, request::formParamMap);
          response.text("still oversized");
        });
    app.routes()
        .post(
            "/form",
            (request, response) -> response.text(Objects.requireNonNull(request.formParam("a"))));
    app.start();
    var result =
        send(
            request("/form")
                .header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED.value())
                .POST(
                    HttpRequest.BodyPublishers.ofInputStream(
                        () ->
                            new ByteArrayInputStream("a=123456".getBytes(StandardCharsets.UTF_8))))
                .build());
    assertEquals(413, result.statusCode());
    assertEquals("still oversized", result.body());
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
              response.text(Objects.requireNonNull(request.formParam("city")));
            });
    app.start();
    var result =
        send(
            request("/form")
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString("city=München"))
                .build());
    assertEquals(200, result.statusCode());
    assertEquals("München", result.body());
  }

  @ParameterizedTest
  @ValueSource(strings = {"%", "%0", "%GG", "%C3", "%C3%28", "%E2%82", "%ED%A0%80"})
  void rejectsMalformedFormEscapes(String encoded) throws Exception {
    app.routes()
        .post(
            "/form",
            (request, response) ->
                response.text(Objects.requireNonNull(request.formParam("name"))));
    app.start();
    var result =
        send(
            request("/form")
                .header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED.value())
                .POST(HttpRequest.BodyPublishers.ofString("name=" + encoded))
                .build());
    assertEquals(400, result.statusCode());
    assertEquals("Bad Request", result.body());
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
    app.start();
    var accepted =
        send(
            request("/form?a=q1&a=q2")
                .header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED.value())
                .POST(HttpRequest.BodyPublishers.ofString("a=f1&a=f2&&"))
                .build());
    assertEquals(200, accepted.statusCode());
    assertEquals("[f1, f2]", accepted.body());
    var rejected =
        send(
            request("/form?a=q1&a=q2")
                .header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED.value())
                .POST(HttpRequest.BodyPublishers.ofString("a=f1&a=f2&a=f3"))
                .build());
    assertEquals(400, rejected.statusCode());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void enforcesFormByteBoundary(boolean chunked) throws Exception {
    app.close();
    app = new Shoostr(new Options("127.0.0.1", 0, 7, 1024, 64, 30_000));
    app.routes()
        .post(
            "/form",
            (request, response) -> response.text(Objects.requireNonNull(request.formParam("a"))));
    app.start();
    for (String body : List.of("a=12345", "a=123456")) {
      var publisher =
          chunked
              ? HttpRequest.BodyPublishers.ofInputStream(
                  () -> new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
              : HttpRequest.BodyPublishers.ofString(body);
      var result =
          send(
              request("/form")
                  .header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED.value())
                  .POST(publisher)
                  .build());
      assertEquals(body.length() == 7 ? 200 : 413, result.statusCode());
      assertEquals(body.length() == 7 ? "12345" : "Content Too Large", result.body());
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
              assertNull(request.queryParam("missing"));
              assertNull(request.formParam("missing"));
              response.text("ok");
            });
    app.start();
    var result =
        send(
            request("/form")
                .header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED.value())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    assertEquals(200, result.statusCode());
    assertEquals("ok", result.body());
  }

  @Test
  void parsesOnlyWhenParameterAccessIsRequested() throws Exception {
    app.routes().post("/raw", (request, response) -> response.text(request.bodyText()));
    app.start();
    var result =
        send(
            request("/raw?bad=%FF")
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("bad=%FF"))
                .build());
    assertEquals(200, result.statusCode());
    assertEquals("bad=%FF", result.body());
  }

  private HttpResponse<String> send(HttpRequest request) throws Exception {
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
        .timeout(Duration.ofSeconds(3));
  }
}
