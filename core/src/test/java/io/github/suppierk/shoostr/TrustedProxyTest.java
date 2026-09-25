package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.ForwardedHeaders;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class TrustedProxyTest {
  private Shoostr app;
  private HttpClient client;

  @BeforeEach
  void prepare() {
    app = new Shoostr(Options.defaults().withPort(0));
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

  @Test
  void ignoresForwardingByDefault() throws Exception {
    app.routes()
        .get(
            "/default",
            (request, response) -> {
              assertFalse(request.isForwarded());
              assertEquals(request.fullUrl(), request.effectiveUrl());
              assertEquals(request.remoteAddress(), request.clientAddress());
              assertEquals(
                  "127.0.0.1",
                  assertInstanceOf(InetSocketAddress.class, request.clientAddress())
                      .getAddress()
                      .getHostAddress());
              response.text("direct");
            });
    app.start();
    var result = send("/default?raw=a%2Bb", "not=a=valid;for=spoofed");
    assertEquals(200, result.statusCode());
    assertEquals("direct", result.body());
  }

  @Test
  void appliesTrustedAssertionWithoutRewritingDirectMetadata() throws Exception {
    app.trustedProxies(InetAddress::isLoopbackAddress);
    app.routes()
        .get(
            "/forwarded/{id}",
            (request, response) -> {
              assertTrue(request.isForwarded());
              assertEquals(
                  "203.0.113.7",
                  Objects.requireNonNull(request.clientAddress()).getAddress().getHostAddress());
              assertEquals(4123, Objects.requireNonNull(request.clientAddress()).getPort());
              assertEquals("https://public.example/forwarded/a+b?raw=%2B", request.effectiveUrl());
              assertEquals(
                  "http://127.0.0.1:" + app.port() + "/forwarded/a+b?raw=%2B", request.fullUrl());
              assertEquals("http", request.scheme());
              assertFalse(request.isSecure());
              assertEquals("a+b", request.pathParam("id"));
              assertEquals(
                  "127.0.0.1",
                  assertInstanceOf(InetSocketAddress.class, request.remoteAddress())
                      .getAddress()
                      .getHostAddress());
              response.text("forwarded");
            });
    app.start();
    var result =
        send("/forwarded/a+b?raw=%2B", "for=\"203.0.113.7:4123\";host=public.example;proto=https");
    assertEquals(200, result.statusCode());
    assertEquals("forwarded", result.body());
  }

  @Test
  void preservesQuotedIpv6ClientPortAndHttpsAuthorityWithoutChangingDirectMetadata()
      throws Exception {
    app.trustedProxies(InetAddress::isLoopbackAddress);
    app.routes()
        .get(
            "/ipv6",
            (request, response) -> {
              assertTrue(request.isForwarded());
              assertEquals(
                  InetAddress.ofLiteral("2001:db8::7"),
                  Objects.requireNonNull(request.clientAddress()).getAddress());
              assertEquals(4123, Objects.requireNonNull(request.clientAddress()).getPort());
              assertEquals("https://[2001:db8::8]:8443/ipv6?raw=%2B", request.effectiveUrl());
              assertEquals("http://127.0.0.1:" + app.port() + "/ipv6?raw=%2B", request.fullUrl());
              assertEquals("raw=%2B", request.queryString());
              assertEquals("127.0.0.1:" + app.port(), request.authority());
              assertEquals("http", request.scheme());
              assertFalse(request.isSecure());
              assertEquals(
                  InetAddress.ofLiteral("127.0.0.1"),
                  assertInstanceOf(InetSocketAddress.class, request.remoteAddress()).getAddress());
              response.text("ipv6");
            });
    app.start();
    var result =
        send("/ipv6?raw=%2B", "for=\"[2001:db8::7]:4123\";host=\"[2001:db8::8]:8443\";proto=https");
    assertEquals(200, result.statusCode());
    assertEquals("ipv6", result.body());
  }

  @Test
  void selectsTheSameBoundaryForCombinedAndRepeatedForwardedLines() throws Exception {
    app.trustedProxies(
        address ->
            address.isLoopbackAddress() || address.equals(InetAddress.ofLiteral("192.0.2.10")));
    app.routes()
        .get(
            "/repeated",
            (request, response) -> {
              assertTrue(request.isForwarded());
              assertEquals(
                  InetAddress.ofLiteral("203.0.113.7"),
                  Objects.requireNonNull(request.clientAddress()).getAddress());
              assertEquals(4123, Objects.requireNonNull(request.clientAddress()).getPort());
              assertEquals("https://public.example/repeated?raw=%2B", request.effectiveUrl());
              assertEquals(
                  "http://127.0.0.1:" + app.port() + "/repeated?raw=%2B", request.fullUrl());
              assertFalse(request.isSecure());
              assertEquals(
                  InetAddress.ofLiteral("127.0.0.1"),
                  assertInstanceOf(InetSocketAddress.class, request.remoteAddress()).getAddress());
              response.text("boundary");
            });
    app.start();
    String attacker = "for=198.51.100.99;host=evil.example;proto=http";
    String boundary = "for=\"203.0.113.7:4123\";host=public.example;proto=https";
    String ingress = "for=192.0.2.10;host=internal.example;proto=http";
    var combined = send("/repeated?raw=%2B", attacker + ", " + boundary + ", " + ingress);
    var repeated = send("/repeated?raw=%2B", attacker, boundary, ingress);
    assertEquals(200, combined.statusCode());
    assertEquals("boundary", combined.body());
    assertEquals(200, repeated.statusCode());
    assertEquals("boundary", repeated.body());
  }

  @Test
  void exposesTrustedMetadataToRouteGatesAndGlobalErrorHandlers() throws Exception {
    var gateInvoked = new AtomicBoolean();
    app.trustedProxies(InetAddress::isLoopbackAddress);
    app.beforeRouteHandler(
        (request, response) -> {
          gateInvoked.set(true);
          assertTrue(request.isForwarded());
          assertEquals(
              "203.0.113.7",
              Objects.requireNonNull(request.clientAddress()).getAddress().getHostAddress());
          assertEquals("https://public.example/gated", request.effectiveUrl());
        });
    app.exception(
        IllegalStateException.class,
        (failure, request, response) ->
            response.text(
                Objects.requireNonNull(request.clientAddress()).getAddress().getHostAddress()
                    + "|"
                    + request.effectiveUrl()));
    app.routes()
        .get(
            "/gated",
            (request, response) -> {
              throw new IllegalStateException();
            });
    app.start();
    var result = send("/gated", "for=203.0.113.7;host=public.example;proto=https");
    assertEquals(500, result.statusCode());
    assertEquals("203.0.113.7|https://public.example/gated", result.body());
    assertTrue(gateInvoked.get());
  }

  @Test
  void ignoresMalformedForwardingFromAnUntrustedPeer() throws Exception {
    app.trustedProxies(address -> false);
    app.routes().get("/untrusted", (request, response) -> response.text(request.effectiveUrl()));
    app.start();
    var result = send("/untrusted", "for=localhost;host=bad.example;proto=javascript");
    assertEquals(200, result.statusCode());
    assertEquals("http://127.0.0.1:" + app.port() + "/untrusted", result.body());
  }

  @Test
  void freezesProxyTrustConfigurationAtFirstRegistrationAndStartup() throws Exception {
    app.trustedProxies(InetAddress::isLoopbackAddress);
    assertThrows(
        IllegalStateException.class,
        () -> app.trustedProxies(InetAddress::isLoopbackAddress, ForwardedHeaders.X_FORWARDED));

    try (var started = new Shoostr(Options.defaults().withPort(0))) {
      started.routes().get("/frozen", (request, response) -> response.text("ok"));
      started.start();
      assertThrows(
          IllegalStateException.class,
          () -> started.trustedProxies(InetAddress::isLoopbackAddress));
    }
  }

  @Test
  void appliesOnlyTheExplicitlySelectedLegacyForwardingFamily() throws Exception {
    app.trustedProxies(InetAddress::isLoopbackAddress, ForwardedHeaders.X_FORWARDED);
    app.routes()
        .get(
            "/legacy",
            (request, response) ->
                response.text(
                    Objects.requireNonNull(request.clientAddress()).getAddress().getHostAddress()
                        + "|"
                        + request.effectiveUrl()));
    app.start();
    var result =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/legacy"))
                .header("X-Forwarded-For", "198.51.100.7")
                .header("X-Forwarded-Host", "public.example")
                .header("X-Forwarded-Proto", "https")
                .header("Forwarded", "for=203.0.113.9;host=ignored.example;proto=http")
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, result.statusCode());
    assertEquals("198.51.100.7|https://public.example/legacy", result.body());
  }

  @Test
  void rejectsMisalignedLegacyForwardingLists() throws Exception {
    app.trustedProxies(InetAddress::isLoopbackAddress, ForwardedHeaders.X_FORWARDED);
    app.routes().get("/legacy", (request, response) -> response.text("unexpected"));
    app.start();
    var result =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/legacy"))
                .header("X-Forwarded-For", "198.51.100.7, 192.0.2.10")
                .header("X-Forwarded-Host", "public.example")
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(400, result.statusCode());
  }

  @Test
  void selectsTheSameLegacyBoundaryForClientAndOrigin() throws Exception {
    app.trustedProxies(
        address -> address.isLoopbackAddress() || "192.0.2.10".equals(address.getHostAddress()),
        ForwardedHeaders.X_FORWARDED);
    app.routes()
        .get(
            "/legacy-chain",
            (request, response) ->
                response.text(
                    Objects.requireNonNull(request.clientAddress()).getAddress().getHostAddress()
                        + "|"
                        + request.effectiveUrl()));
    app.start();
    var result =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/legacy-chain"))
                .header("X-Forwarded-For", "198.51.100.7, 203.0.113.7, 192.0.2.10")
                .header("X-Forwarded-Host", "evil.example, public.example, proxy.example")
                .header("X-Forwarded-Proto", "http, https, http")
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, result.statusCode());
    assertEquals("203.0.113.7|https://public.example/legacy-chain", result.body());
  }

  @Test
  void acceptsBareIpv6AndMatchingLegacyHostPort() throws Exception {
    app.trustedProxies(InetAddress::isLoopbackAddress, ForwardedHeaders.X_FORWARDED);
    app.routes()
        .get(
            "/legacy-ipv6",
            (request, response) ->
                response.text(
                    request.effectiveUrl()
                        + "|"
                        + Objects.requireNonNull(request.clientAddress()).getPort()));
    app.start();
    var result =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/legacy-ipv6"))
                .header("X-Forwarded-For", "2001:db8::7")
                .header("X-Forwarded-Host", "public.example:8443")
                .header("X-Forwarded-Port", "8443")
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, result.statusCode());
    assertEquals("http://public.example:8443/legacy-ipv6|0", result.body());
  }

  @Test
  void rejectsConflictingLegacyHostAndPort() throws Exception {
    app.trustedProxies(InetAddress::isLoopbackAddress, ForwardedHeaders.X_FORWARDED);
    app.routes().get("/legacy-port", (request, response) -> response.text("unexpected"));
    app.start();
    var result =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/legacy-port"))
                .header("X-Forwarded-For", "198.51.100.7")
                .header("X-Forwarded-Host", "public.example:8443")
                .header("X-Forwarded-Port", "443")
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(400, result.statusCode());
  }

  @Test
  void rejectsMalformedLegacyOriginOutsideTheSelectedBoundary() throws Exception {
    app.trustedProxies(InetAddress::isLoopbackAddress, ForwardedHeaders.X_FORWARDED);
    app.routes().get("/legacy-origin", (request, response) -> response.text("unexpected"));
    app.start();
    var result =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/legacy-origin"))
                .header("X-Forwarded-For", "198.51.100.7, 192.0.2.10")
                .header("X-Forwarded-Proto", "javascript, https")
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(400, result.statusCode());
  }

  @Test
  void letsLegacyTrustPredicateFailuresReachTheApplicationErrorPath() throws Exception {
    app.trustedProxies(
        address -> {
          if ("192.0.2.10".equals(address.getHostAddress())) {
            throw new IllegalArgumentException("proxy configuration failed");
          }

          return address.isLoopbackAddress();
        },
        ForwardedHeaders.X_FORWARDED);
    app.routes().get("/legacy-predicate", (request, response) -> response.text("unexpected"));
    app.start();
    var result =
        client.send(
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + app.port() + "/legacy-predicate"))
                .header("X-Forwarded-For", "198.51.100.7, 192.0.2.10")
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(500, result.statusCode());
  }

  @Test
  void selectsClientAndOriginAtTheSameTrustedBoundary() throws Exception {
    app.trustedProxies(
        address -> address.isLoopbackAddress() || "192.0.2.10".equals(address.getHostAddress()));
    app.routes()
        .get(
            "/chain",
            (request, response) ->
                response.text(
                    Objects.requireNonNull(request.clientAddress()).getAddress().getHostAddress()
                        + "|"
                        + Objects.requireNonNull(request.clientAddress()).getPort()
                        + "|"
                        + request.effectiveUrl()));
    app.start();
    var result =
        send(
            "/chain",
            "for=198.51.100.99;host=evil.example;proto=http, "
                + "for=203.0.113.7;host=public.example;proto=https, for=192.0.2.10");
    assertEquals(200, result.statusCode());
    assertEquals("203.0.113.7|0|https://public.example/chain", result.body());
    var stopped = send("/chain", "for=198.51.100.99;host=evil.example;proto=https, for=192.0.2.11");
    assertEquals(200, stopped.statusCode());
    assertEquals("192.0.2.11|0|http://127.0.0.1:" + app.port() + "/chain", stopped.body());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "for=203.0.113.7;For=198.51.100.9",
        "for=203.0.113.7;host=good.example;HOST=evil.example",
        "for=203.0.113.7;proto=https;PROTO=http",
        "for=203.0.113.7;ext=a;EXT=b",
        "for=203.0.113.7;proto=javascript",
        "for=203.0.113.7;host=\"good.example/path\"",
        "for=203.0.113.7;host=\"user@good.example\"",
        "for=203.0.113.7;host=\"good.example:99999\"",
        "for=203.0.113.7;host=\"good.example:\"",
        "for=203.0.113.7;host=\"\"",
        "for=203.0.113.7;host=\"good.example?query\"",
        "for=203.0.113.7;proto=\"https trailing\"",
        "for=203.0.113.7;extension=bare space",
        "for=203.0.113.7;extension=\"quoted\"suffix",
        "for=203.0.113.7;extension=\"unterminated",
        "for=203.0.113.7;extension=",
        "for=203.0.113.7;;proto=https",
        "for=203.0.113.7;",
        "for=203.0.113.7:1234",
        "for=\"203.0.113.7:-1\"",
        "for=\"203.0.113.7:65536\"",
        "for=\"203.0.113.7:port\"",
        "for=\"[2001:db8::1%25zone]\"",
        "for=127.1",
        "for=2130706433",
        "for=localhost"
      })
  void rejectsMalformedTrustedMetadata(String value) throws Exception {
    var calls = new AtomicInteger();
    app.trustedProxies(InetAddress::isLoopbackAddress);
    app.beforeRouteHandler((request, response) -> calls.incrementAndGet());
    app.routes().get("/invalid", (request, response) -> calls.incrementAndGet());
    app.start();
    var result = send("/invalid", value);
    assertEquals(400, result.statusCode(), value);
    assertEquals(0, calls.get());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"for=unknown;", "for=UnKnOwN;", "for=_hidden;", "for=\"_hidden:_port\";", ""})
  void stopsAtUndisclosedPredecessors(String node) throws Exception {
    app.trustedProxies(InetAddress::isLoopbackAddress);
    app.routes()
        .get(
            "/unknown",
            (request, response) -> {
              assertTrue(request.isForwarded());
              assertNull(request.clientAddress());
              assertEquals("https://boundary.example/unknown", request.effectiveUrl());
              response.text("unknown");
            });
    app.start();
    var result =
        send(
            "/unknown",
            "for=198.51.100.1;host=evil.example, " + node + "host=boundary.example;proto=https");
    assertEquals(200, result.statusCode());
    assertEquals("unknown", result.body());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", ",", ",,", "for=203.0.113.7,"})
  void rejectsEmptyForwardedChainElements(String value) throws Exception {
    var calls = new AtomicInteger();
    app.trustedProxies(InetAddress::isLoopbackAddress);
    app.beforeRouteHandler((request, response) -> calls.incrementAndGet());
    app.routes().get("/empty", (request, response) -> calls.incrementAndGet());
    app.start();
    var result = send("/empty", value);
    assertEquals(400, result.statusCode(), value);
    assertEquals(0, calls.get());
  }

  @Test
  void rejectsForwardedChainPastTheWorkBound() throws Exception {
    var calls = new AtomicInteger();
    app.trustedProxies(InetAddress::isLoopbackAddress);
    app.beforeRouteHandler((request, response) -> calls.incrementAndGet());
    app.routes().get("/bounded", (request, response) -> calls.incrementAndGet());
    app.start();
    var value = String.join(",", Collections.nCopies(65, "for=203.0.113.7"));
    var result = send("/bounded", value);
    assertEquals(400, result.statusCode());
    assertEquals(0, calls.get());
  }

  @Test
  void acceptsForwardedChainAtTheWorkBound() throws Exception {
    app.trustedProxies(InetAddress::isLoopbackAddress);
    app.routes().get("/bounded", (request, response) -> response.text("accepted"));
    app.start();
    var value = String.join(",", Collections.nCopies(64, "for=203.0.113.7"));
    var result = send("/bounded", value);
    assertEquals(200, result.statusCode());
    assertEquals("accepted", result.body());
  }

  @Test
  void rejectsForwardedChainPastTheWorkBoundAcrossHeaderLines() throws Exception {
    var calls = new AtomicInteger();
    app.trustedProxies(InetAddress::isLoopbackAddress);
    app.beforeRouteHandler((request, response) -> calls.incrementAndGet());
    app.routes().get("/bounded", (request, response) -> calls.incrementAndGet());
    app.start();
    var accepted = String.join(",", Collections.nCopies(64, "for=203.0.113.7"));
    var result = send("/bounded", accepted, "for=203.0.113.8");
    assertEquals(400, result.statusCode());
    assertEquals(0, calls.get());
  }

  @Test
  void acceptsLongQuotedForwardedExtension() throws Exception {
    app.trustedProxies(InetAddress::isLoopbackAddress);
    app.routes().get("/quoted", (request, response) -> response.text("accepted"));
    app.start();

    var result = send("/quoted", "for=203.0.113.7;extension=\"" + "x".repeat(4000) + "\"");
    assertEquals(200, result.statusCode());
    assertEquals("accepted", result.body());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"for=203.0.113.7;extension=\"a\\\"b\"", "for=203.0.113.7;extension=\"a\\\\b\""})
  void acceptsEscapedQuotedForwardedExtensions(String value) throws Exception {
    app.trustedProxies(InetAddress::isLoopbackAddress);
    app.routes().get("/quoted", (request, response) -> response.text("accepted"));
    app.start();

    assertEquals(200, send("/quoted", value).statusCode());
  }

  @Test
  void rejectsEscapedClosingQuoteWithoutTerminator() throws Exception {
    app.trustedProxies(InetAddress::isLoopbackAddress);
    app.routes().get("/quoted", (request, response) -> response.text("unexpected"));
    app.start();

    assertEquals(400, send("/quoted", "for=203.0.113.7;extension=\"abc\\\"").statusCode());
  }

  private HttpResponse<String> send(String path, String... forwarded) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
            .timeout(Duration.ofSeconds(3));
    for (var value : forwarded) {
      request.header("Forwarded", value);
    }

    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
