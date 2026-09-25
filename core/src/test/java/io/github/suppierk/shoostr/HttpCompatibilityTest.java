package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpMethods;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class HttpCompatibilityTest {
  private Shoostr app;
  private HttpClient client;

  @BeforeEach
  void prepare() {
    app = new Shoostr(new Options("127.0.0.1", 0, 16, 1024, 8, 5000));
    client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build();
  }

  @AfterEach
  void close() throws Exception {
    try {
      client.close();
    } finally {
      app.close();
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = HttpMethods.class,
      names = {"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"})
  void dispatchesEveryExplicitlyRegisteredVerb(HttpMethods method) throws Exception {
    app.routes()
        .route(
            method,
            "/dispatch",
            (request, response) ->
                response.header("X-Handled-Method", request.method()).text(request.method()));
    app.start();
    var response =
        send(
            request("/dispatch")
                .method(method.value(), HttpRequest.BodyPublishers.noBody())
                .build());
    assertEquals(200, response.statusCode());
    assertEquals(method.value(), response.headers().firstValue("X-Handled-Method").orElseThrow());
    assertEquals(method == HttpMethods.HEAD ? "" : method.value(), response.body());
  }

  @ParameterizedTest
  @ValueSource(strings = {"/versions/v2.1", "/jobs/name:run", "/search/a+b", "/price/$value"})
  void matchesLiteralPunctuationWithoutInterpretingItAsPatternSyntax(String path) throws Exception {
    app.routes().get(path, (request, response) -> response.text(request.path()));
    app.start();
    var response = send(request(path + "?ignored=/another/path").build());
    assertEquals(200, response.statusCode());
    assertEquals(path, response.body());
    assertEquals(404, send(request(path + "-extra").build()).statusCode());
  }

  @ParameterizedTest
  @ValueSource(strings = {"MiXeD", "M%C3%BCnchen", "one+two"})
  void preservesParameterValuesAtTheHttpBoundary(String raw) throws Exception {
    app.routes()
        .get("/values/{item}", (request, response) -> response.text(request.pathParam("item")));
    app.start();
    String expected =
        switch (raw) {
          case "M%C3%BCnchen" -> "München";
          default -> raw;
        };
    var response = send(request("/values/" + raw).build());
    assertEquals(200, response.statusCode());
    assertEquals(expected, response.body());
    assertEquals(404, send(request("/VALUES/" + raw).build()).statusCode());
  }

  @Test
  void rejectsEncodedPercentBeforeInvokingTheHandler() throws Exception {
    var invoked = new AtomicBoolean();
    app.routes()
        .get(
            "/values/{item}",
            (request, response) -> {
              invoked.set(true);
              response.text(request.pathParam("item"));
            });
    app.start();
    assertEquals(400, send(request("/values/rate%25done").build()).statusCode());
    assertFalse(invoked.get());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "repeatable", "café"})
  void cachesRequestBytesWithoutExposingMutableStorage(String text) throws Exception {
    byte[] expected = text.getBytes(StandardCharsets.UTF_8);
    app.routes()
        .post(
            "/body",
            (request, response) -> {
              var first = request.bodyBytes();
              assertArrayEquals(expected, first);
              if (first.length > 0) {
                first[0] ^= 0x7f;
              }

              assertArrayEquals(expected, request.bodyBytes());
              assertEquals(text, request.bodyText());
              response.body("application/octet-stream", request.bodyBytes());
            });
    app.start();
    var response =
        client.send(
            request("/body").POST(HttpRequest.BodyPublishers.ofByteArray(expected)).build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, response.statusCode());
    assertArrayEquals(expected, response.body());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void acceptsTheByteLimitAndRejectsOneAdditionalByte(boolean chunked) throws Exception {
    app.routes()
        .post(
            "/bounded",
            (request, response) -> response.body("application/octet-stream", request.bodyBytes()));
    app.start();
    byte[] atLimit = "é".repeat(8).getBytes(StandardCharsets.UTF_8);
    for (byte[] bytes :
        new byte[][] {atLimit, ("é".repeat(8) + "x").getBytes(StandardCharsets.UTF_8)}) {
      var publisher =
          chunked
              ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes))
              : HttpRequest.BodyPublishers.ofByteArray(bytes);
      var response =
          client.send(
              request("/bounded").POST(publisher).build(), HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(bytes.length == 16 ? 200 : 413, response.statusCode());
      if (bytes.length == 16) {
        assertArrayEquals(atLimit, response.body());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"X-Request-Value", "x-request-value", "X-REQUEST-VALUE"})
  void readsAndReplacesHeadersCaseInsensitively(String spelling) throws Exception {
    app.routes()
        .get(
            "/headers",
            (request, response) -> {
              assertNull(request.header("X-Absent"));
              response
                  .header("X-Result", "obsolete")
                  .header("x-result", Objects.requireNonNull(request.header("x-ReQuEsT-vAlUe")))
                  .status(201)
                  .text("café");
            });
    app.start();
    var response = send(request("/headers").header(spelling, "current").build());
    assertEquals(201, response.statusCode());
    assertEquals(List.of("current"), response.headers().allValues("X-Result"));
    assertEquals("café", response.body());
    assertEquals(
        5, response.headers().firstValueAsLong(HttpHeaders.CONTENT_LENGTH.value()).orElseThrow());
  }

  @Test
  void carriesAnApplicationDefinedHeaderThroughRequestAndResponse() throws Exception {
    var tenant = HttpHeaders.of("X-Tenant");
    app.routes()
        .get(
            "/tenant",
            (request, response) ->
                response
                    .header(tenant.value(), Objects.requireNonNull(request.header(tenant.value())))
                    .text("ok"));
    app.start();

    var response = send(request("/tenant").header("x-tenant", "revolut").build());

    assertEquals("revolut", response.headers().firstValue(tenant.value()).orElseThrow());
    assertEquals("ok", response.body());
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
        .timeout(Duration.ofSeconds(3));
  }

  private HttpResponse<String> send(HttpRequest request) throws Exception {
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
