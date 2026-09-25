package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.http.Cookie;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class CookieTest {
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
  void readsRepeatedCookiesWithoutDecodingValues() throws Exception {
    app.routes()
        .get(
            "/cookies",
            (request, response) -> {
              assertEquals("first", request.cookie("token"));
              assertEquals(List.of("first", "second"), request.cookies("token"));
              assertEquals("upper", request.cookie("Token"));
              assertEquals("", request.cookie("empty"));
              assertEquals("a+b%2F", request.cookie("encoded"));
              assertNull(request.cookie("missing"));
              assertEquals(List.of(), request.cookies("missing"));
              assertEquals("first", request.cookieMap().get("token"));
              var cookieMap = request.cookieMap();
              var tokens = request.cookies("token");
              assertThrows(UnsupportedOperationException.class, cookieMap::clear);
              assertThrows(UnsupportedOperationException.class, () -> tokens.add("bad"));
              response.text("ok");
            });
    app.start();
    var result =
        send(
            request("/cookies")
                .header("Cookie", "token=first; Token=upper; empty=")
                .header("Cookie", "token=second; encoded=a+b%2F"));
    assertEquals(200, result.statusCode());
    assertEquals("ok", result.body());
  }

  @Test
  void snapshotsCookiesAndEnforcesRequestLifetime() throws Exception {
    var retained = new AtomicReference<Request>();
    var snapshot = new AtomicReference<Map<String, String>>();
    app.routes()
        .get(
            "/capture",
            (request, response) -> {
              retained.set(request);
              snapshot.set(request.cookieMap());
              assertEquals("quoted", request.cookie("quoted"));

              try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                executor
                    .submit(
                        () -> {
                          assertThrows(IllegalStateException.class, () -> request.cookie("quoted"));
                          assertThrows(
                              IllegalStateException.class, () -> request.cookies("quoted"));
                          assertThrows(IllegalStateException.class, request::cookieMap);
                        })
                    .get(3, TimeUnit.SECONDS);
              }

              response.text("captured");
            });
    app.routes()
        .get(
            "/empty",
            (request, response) -> {
              assertEquals(Map.of(), request.cookieMap());
              assertNull(request.cookie("quoted"));
              response.text("empty");
            });
    app.start();
    assertEquals(
        "captured",
        send(request("/capture").header("Cookie", "quoted=\"quoted\"; invalid")).body());
    assertEquals("empty", send(request("/empty")).body());
    assertEquals(Map.of("quoted", "quoted"), snapshot.get());
    var closedRequest = retained.get();
    assertThrows(IllegalStateException.class, () -> closedRequest.cookie("quoted"));
    assertThrows(IllegalStateException.class, () -> closedRequest.cookies("quoted"));
    assertThrows(IllegalStateException.class, closedRequest::cookieMap);
  }

  @Test
  void writesBasicSessionCookies() throws Exception {
    app.routes()
        .get(
            "/set",
            (request, response) -> {
              response.cookie("first", "a+b%2F").cookie(new Cookie("empty", ""));
              response.text("ok");
            });
    app.start();
    var result = send(request("/set"));
    assertEquals(200, result.statusCode());
    assertEquals(
        List.of("first=a+b%2F; Path=/", "empty=; Path=/"),
        result.headers().allValues("Set-Cookie"));
    assertEquals(List.of(), result.headers().allValues("Expires"));
    assertEquals(List.of(), result.headers().allValues("Cache-Control"));
  }

  @Test
  void writesExplicitCookieAttributes() throws Exception {
    var base = new Cookie("session", "value");
    var configured =
        base.withPath("/app")
            .withDomain(".EXAMPLE.test")
            .withMaxAge(60)
            .withExpires(Instant.parse("2030-01-01T00:00:00Z"))
            .withSecure(true)
            .withHttpOnly(true)
            .withSameSite(Cookie.SameSite.LAX);
    assertEquals("/", base.path());
    assertNull(base.domain());
    assertEquals(-1, base.maxAge());
    assertFalse(base.secure());
    assertFalse(base.httpOnly());
    assertNull(base.sameSite());
    assertNull(base.expires());
    app.routes()
        .get(
            "/attributes",
            (request, response) ->
                response.header("Expires", "Thu, 01 Jan 1970 00:00:00 GMT").cookie(configured));
    app.start();
    var result = send(request("/attributes"));
    assertEquals(200, result.statusCode());
    assertEquals(
        List.of(
            "session=value; Path=/app; Domain=example.test; Max-Age=60; Expires=Tue, 01 Jan 2030 00:00:00 GMT; Secure; HttpOnly; SameSite=Lax"),
        result.headers().allValues("Set-Cookie"));
    assertEquals(
        "Thu, 01 Jan 1970 00:00:00 GMT", result.headers().firstValue("Expires").orElseThrow());
  }

  @Test
  void replacesCookiesByNameDomainAndPath() throws Exception {
    app.routes()
        .get(
            "/replace",
            (request, response) -> {
              response
                  .cookie("id", "root")
                  .cookie(new Cookie("id", "path").withPath("/app"))
                  .cookie(new Cookie("Id", "case"))
                  .addHeader("Set-Cookie", "id=raw; Domain=.EXAMPLE.test; Path=/app")
                  .addHeader("Set-Cookie", "malformed")
                  .cookie("id", "replacement")
                  .cookie(new Cookie("id", "scoped").withPath("/app").withDomain("example.test"));
            });
    app.start();
    var result = send(request("/replace"));
    assertEquals(200, result.statusCode());
    assertEquals(
        List.of(
            "id=path; Path=/app",
            "Id=case; Path=/",
            "malformed",
            "id=replacement; Path=/",
            "id=scoped; Path=/app; Domain=example.test"),
        result.headers().allValues("Set-Cookie"));
  }

  @Test
  void deletesCookiesWithoutChangingOtherScopes() throws Exception {
    var secured =
        new Cookie("secure", "value")
            .withSecure(true)
            .withHttpOnly(true)
            .withSameSite(Cookie.SameSite.NONE);
    app.routes()
        .get(
            "/delete",
            (request, response) ->
                response
                    .cookie("id", "root")
                    .cookie(new Cookie("id", "keep").withPath("/app"))
                    .removeCookie("id")
                    .removeCookie("id", "/app", "example.test")
                    .removeCookie(secured)
                    .cookie(
                        new Cookie("zero", "value")
                            .withMaxAge(0)
                            .withExpires(Instant.parse("2030-01-01T00:00:00Z"))));
    app.start();
    var result = send(request("/delete"));
    assertEquals(200, result.statusCode());
    assertEquals(
        List.of(
            "id=keep; Path=/app",
            "id=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT",
            "id=; Path=/app; Domain=example.test; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT",
            "secure=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Secure; HttpOnly; SameSite=None",
            "zero=value; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT"),
        result.headers().allValues("Set-Cookie"));
    assertEquals("value", secured.value());
    assertEquals(-1, secured.maxAge());
  }

  @Test
  void enforcesSecureAttributesAndPrefixes() throws Exception {
    app.routes()
        .get(
            "/security",
            (request, response) -> {
              var insecure = new Cookie("id", "value");
              assertThrows(
                  IllegalArgumentException.class,
                  () -> insecure.withSameSite(Cookie.SameSite.NONE));
              for (var name : List.of("__Secure-id", "__sEcUrE-id", "__Host-id", "__HOST-id")) {
                assertThrows(IllegalArgumentException.class, () -> response.cookie(name, "value"));
              }
              var host = Cookie.secure("__Host-id", "value").withHttpOnly(true);
              assertThrows(IllegalArgumentException.class, () -> host.withPath("/app"));
              assertThrows(IllegalArgumentException.class, () -> host.withDomain("example.test"));
              assertThrows(IllegalArgumentException.class, () -> host.withSecure(false));
              var cross = Cookie.secure("cross", "value").withSameSite(Cookie.SameSite.NONE);
              assertThrows(IllegalArgumentException.class, () -> cross.withSecure(false));
              response
                  .cookie(cross)
                  .cookie(Cookie.secure("__Secure-id", "value").withPath("/app"))
                  .cookie(host)
                  .removeCookie(host);
            });
    app.start();
    var result = send(request("/security"));
    assertEquals(200, result.statusCode());
    assertEquals(
        List.of(
            "cross=value; Path=/; Secure; SameSite=None",
            "__Secure-id=value; Path=/app; Secure",
            "__Host-id=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Secure; HttpOnly"),
        result.headers().allValues("Set-Cookie"));
  }

  @Test
  @SuppressWarnings("NullAway") // The test verifies that null cookie inputs are rejected.
  void rejectsInvalidCookiesBeforeChangingHeaders() throws Exception {
    app.routes()
        .get(
            "/invalid",
            (request, response) -> {
              var base = new Cookie("id", "kept");
              response.cookie(base);
              for (var name : List.of("", "bad name", "bad=name", "bad;name", "bad\r\nname", "é")) {
                assertThrows(IllegalArgumentException.class, () -> response.cookie(name, "v"));
              }
              for (var value :
                  List.of(
                      "bad value",
                      "bad;value",
                      "bad,value",
                      "bad\\value",
                      "bad\"value",
                      "bad\r\nvalue",
                      "é",
                      "bad" + (char) 0,
                      "bad" + (char) 127)) {
                assertThrows(IllegalArgumentException.class, () -> response.cookie("id", value));
              }
              for (var domain :
                  List.of(
                      "",
                      ".",
                      "..example.test",
                      "example.test.",
                      "-bad.test",
                      "bad-.test",
                      "bad_name.test",
                      "example.test; Secure",
                      "é.test",
                      "a".repeat(64) + ".test")) {
                assertThrows(IllegalArgumentException.class, () -> base.withDomain(domain));
              }
              for (var path :
                  List.of("", "relative", "/bad; Secure", "/bad\r\n", "/é", "/trailing ")) {
                assertThrows(IllegalArgumentException.class, () -> base.withPath(path));
              }
              assertThrows(IllegalArgumentException.class, () -> base.withMaxAge(-2));
              assertThrows(IllegalArgumentException.class, () -> base.withExpires(Instant.MIN));
              assertThrows(IllegalArgumentException.class, () -> base.withExpires(Instant.MAX));
              assertThrows(NullPointerException.class, () -> response.cookie((Cookie) null));
              assertThrows(NullPointerException.class, () -> response.cookie(null, "v"));
              assertThrows(NullPointerException.class, () -> response.cookie("id", null));
              assertEquals(List.of("id=kept; Path=/"), response.headers("Set-Cookie"));
            });
    app.start();
    var result = send(request("/invalid"));
    assertEquals(200, result.statusCode());
    assertEquals(List.of("id=kept; Path=/"), result.headers().allValues("Set-Cookie"));
  }

  @Test
  void supportsExplicitQuotedCookieValues() throws Exception {
    app.routes()
        .get(
            "/quoted",
            (request, response) -> {
              response.cookie("quoted", "\"abc\"").cookie("empty", "\"\"");
              for (var value :
                  List.of("\"a b\"", "\"a;b\"", "\"a\\b\"", "\"unclosed", "\"a\"b\"")) {
                assertThrows(
                    IllegalArgumentException.class, () -> response.cookie("quoted", value));
              }
            });
    app.start();
    var result = send(request("/quoted"));
    assertEquals(200, result.statusCode());
    assertEquals(
        List.of("quoted=\"abc\"; Path=/", "empty=\"\"; Path=/"),
        result.headers().allValues("Set-Cookie"));
  }

  @Test
  void supportsIndependentExpiryAndAttributeRemoval() throws Exception {
    var expiry = Instant.parse("2030-01-01T00:00:00.999Z");
    var reset =
        new Cookie("reset", "value")
            .withDomain("example.test")
            .withDomain(null)
            .withPath("/app")
            .withPath("/")
            .withMaxAge(30)
            .withMaxAge(-1)
            .withExpires(expiry)
            .withExpires(null)
            .withSecure(true)
            .withSecure(false)
            .withHttpOnly(true)
            .withHttpOnly(false)
            .withSameSite(Cookie.SameSite.STRICT)
            .withSameSite(null);
    app.routes()
        .get(
            "/expiry",
            (request, response) ->
                response
                    .cookie(new Cookie("only", "value").withExpires(expiry))
                    .cookie(new Cookie("huge", "value").withMaxAge(Long.MAX_VALUE))
                    .cookie(reset));
    app.start();
    var result = send(request("/expiry"));
    assertEquals(200, result.statusCode());
    assertEquals(
        List.of(
            "only=value; Path=/; Expires=Tue, 01 Jan 2030 00:00:00 GMT",
            "huge=value; Path=/; Max-Age=9223372036854775807",
            "reset=value; Path=/"),
        result.headers().allValues("Set-Cookie"));
  }

  @Test
  void enforcesCookieResponseLifetimeAndErrorCleanup() throws Exception {
    var retained = new AtomicReference<Response>();
    app.exception(
        IllegalArgumentException.class,
        (failure, request, response) ->
            response
                .status(409)
                .cookie("mapped", Objects.requireNonNull(request.cookie("incoming")))
                .text("mapped"));
    app.routes()
        .get(
            "/stream",
            (request, response) -> {
              retained.set(response);
              response.cookie("before", "value");

              try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                executor
                    .submit(
                        () -> {
                          assertThrows(
                              IllegalStateException.class,
                              () -> response.cookie("foreign", "value"));
                          assertThrows(
                              IllegalStateException.class, () -> response.removeCookie("before"));
                        })
                    .get(3, TimeUnit.SECONDS);
              }

              var stream = response.startStream("text/plain");
              assertThrows(IllegalStateException.class, () -> response.cookie("late", "value"));
              assertThrows(IllegalStateException.class, () -> response.removeCookie("before"));
              stream.write("streamed");
            });
    app.routes()
        .get(
            "/failed",
            (request, response) -> {
              response.cookie("discarded", "value");
              throw new BadRequestException();
            });
    app.routes()
        .get(
            "/mapped",
            (request, response) -> {
              response.cookie("discarded", "value");
              throw new IllegalArgumentException("private");
            });
    app.routes().head("/head", (request, response) -> response.cookie("head", "value"));
    app.start();
    var streamed = send(request("/stream"));
    assertEquals("streamed", streamed.body());
    assertEquals(List.of("before=value; Path=/"), streamed.headers().allValues("Set-Cookie"));
    var closedResponse = retained.get();
    assertThrows(IllegalStateException.class, () -> closedResponse.cookie("late", "value"));
    assertThrows(IllegalStateException.class, () -> closedResponse.removeCookie("before"));
    var failed = send(request("/failed"));
    assertEquals(400, failed.statusCode());
    assertEquals(List.of(), failed.headers().allValues("Set-Cookie"));
    var mapped = send(request("/mapped").header("Cookie", "incoming=value"));
    assertEquals(409, mapped.statusCode());
    assertEquals(List.of("mapped=value; Path=/"), mapped.headers().allValues("Set-Cookie"));
    var head = send(request("/head").method("HEAD", HttpRequest.BodyPublishers.noBody()));
    assertEquals(200, head.statusCode());
    assertEquals("", head.body());
    assertEquals(List.of("head=value; Path=/"), head.headers().allValues("Set-Cookie"));
  }

  @Test
  void roundTripsAndDeletesThroughAClientCookieStore() throws Exception {
    client.close();
    client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager())
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    app.routes().get("/set", (request, response) -> response.cookie("id", "value"));
    app.routes()
        .get(
            "/read",
            (request, response) -> {
              var value = request.cookie("id");
              response.text(value == null ? "missing" : value);
            });
    app.routes().get("/delete", (request, response) -> response.removeCookie("id"));
    app.start();
    assertEquals("missing", send(request("/read")).body());
    assertEquals(200, send(request("/set")).statusCode());
    assertEquals("value", send(request("/read")).body());
    assertEquals(200, send(request("/delete")).statusCode());
    assertEquals("missing", send(request("/read")).body());
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
        .timeout(Duration.ofSeconds(3));
  }

  private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
