package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.ForwardedHeaders;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class TrustedProxyTest {
  private Shoostr app;

  @BeforeEach
  void prepare() {
    app =
        new Shoostr()
            .modifyServer(
                nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));
  }

  @AfterEach
  void close() throws Exception {
    app.close();
  }

  @Test
  void ignoresForwardingByDefault() throws Exception {
    app.routes()
        .get(
            "/default",
            (request, response) -> {
              assertFalse(request.isForwarded());
              assertEquals(request.fullUrl(), request.effectiveUrl());
              assertEquals(
                  request.remoteAddress().orElseThrow(), request.clientAddress().orElseThrow());
              assertEquals(
                  "127.0.0.1",
                  assertInstanceOf(InetSocketAddress.class, request.clientAddress().orElseThrow())
                      .getAddress()
                      .getHostAddress());

              response.text("direct");
            });

    try (var test = TestServer.start(app, client -> client.version(HttpClient.Version.HTTP_1_1))) {
      var result = sendResultForIgnoresForwardingByDefault(test);

      assertEquals(200, result.statusCode());
      assertEquals("direct", result.body());
    }
  }

  private HttpResponse<String> sendResultForIgnoresForwardingByDefault(TestServer test)
      throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/default?raw=a%2Bb")
                .timeout(Duration.ofSeconds(3))
                .header("Forwarded", "not=a=valid;for=spoofed"),
        HttpResponse.BodyHandlers.ofString());
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
                  request.clientAddress().orElseThrow().getAddress().getHostAddress());
              assertEquals(4123, request.clientAddress().orElseThrow().getPort());
              assertEquals("https://public.example/forwarded/a+b?raw=%2B", request.effectiveUrl());
              assertEquals(
                  "http://127.0.0.1:" + app.port() + "/forwarded/a+b?raw=%2B", request.fullUrl());
              assertEquals("http", request.scheme().orElseThrow());
              assertFalse(request.isSecure());
              assertEquals("a+b", request.pathParam("id").orElseThrow());
              assertEquals(
                  "127.0.0.1",
                  assertInstanceOf(InetSocketAddress.class, request.remoteAddress().orElseThrow())
                      .getAddress()
                      .getHostAddress());

              response.text("forwarded");
            });

    try (var test = TestServer.start(app, client -> client.version(HttpClient.Version.HTTP_1_1))) {
      var result = sendResultForAppliesTrustedAssertionWithoutRewritingDirectMetadata(test);

      assertEquals(200, result.statusCode());
      assertEquals("forwarded", result.body());
    }
  }

  private HttpResponse<String> sendResultForAppliesTrustedAssertionWithoutRewritingDirectMetadata(
      TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/forwarded/a+b?raw=%2B")
                .timeout(Duration.ofSeconds(3))
                .header("Forwarded", "for=\"203.0.113.7:4123\";host=public.example;proto=https"),
        HttpResponse.BodyHandlers.ofString());
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
                  InetAddress.getByName("2001:db8::7"),
                  request.clientAddress().orElseThrow().getAddress());
              assertEquals(4123, request.clientAddress().orElseThrow().getPort());
              assertEquals("https://[2001:db8::8]:8443/ipv6?raw=%2B", request.effectiveUrl());
              assertEquals("http://127.0.0.1:" + app.port() + "/ipv6?raw=%2B", request.fullUrl());
              assertEquals("raw=%2B", request.queryString().orElseThrow());
              assertEquals("127.0.0.1:" + app.port(), request.authority().orElseThrow());
              assertEquals("http", request.scheme().orElseThrow());
              assertFalse(request.isSecure());
              assertEquals(
                  InetAddress.getByAddress(new byte[] {127, 0, 0, 1}),
                  assertInstanceOf(InetSocketAddress.class, request.remoteAddress().orElseThrow())
                      .getAddress());

              response.text("ipv6");
            });

    try (var test = TestServer.start(app, client -> client.version(HttpClient.Version.HTTP_1_1))) {
      var result =
          sendResultForPreservesQuotedIpv6ClientPortAndHttpsAuthorityWithoutChangingDirectMetadata(
              test);

      assertEquals(200, result.statusCode());
      assertEquals("ipv6", result.body());
    }
  }

  private HttpResponse<String>
      sendResultForPreservesQuotedIpv6ClientPortAndHttpsAuthorityWithoutChangingDirectMetadata(
          TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/ipv6?raw=%2B")
                .timeout(Duration.ofSeconds(3))
                .header(
                    "Forwarded",
                    "for=\"[2001:db8::7]:4123\";host=\"[2001:db8::8]:8443\";proto=https"),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void selectsTheSameBoundaryForCombinedAndRepeatedForwardedLines() throws Exception {
    var trustedHop = InetAddress.getByName("192.0.2.10");
    app.trustedProxies(address -> address.isLoopbackAddress() || address.equals(trustedHop));
    app.routes()
        .get(
            "/repeated",
            (request, response) -> {
              assertTrue(request.isForwarded());
              assertEquals(
                  InetAddress.getByName("203.0.113.7"),
                  request.clientAddress().orElseThrow().getAddress());
              assertEquals(4123, request.clientAddress().orElseThrow().getPort());
              assertEquals("https://public.example/repeated?raw=%2B", request.effectiveUrl());
              assertEquals(
                  "http://127.0.0.1:" + app.port() + "/repeated?raw=%2B", request.fullUrl());
              assertFalse(request.isSecure());
              assertEquals(
                  InetAddress.getByAddress(new byte[] {127, 0, 0, 1}),
                  assertInstanceOf(InetSocketAddress.class, request.remoteAddress().orElseThrow())
                      .getAddress());

              response.text("boundary");
            });

    try (var test = TestServer.start(app, client -> client.version(HttpClient.Version.HTTP_1_1))) {
      String attacker = "for=198.51.100.99;host=evil.example;proto=http";
      String boundary = "for=\"203.0.113.7:4123\";host=public.example;proto=https";
      String ingress = "for=192.0.2.10;host=internal.example;proto=http";
      var combined =
          sendCombinedRequestForSelectsTheSameBoundaryForCombinedAndRepeatedForwardedLines(
              test, attacker, boundary, ingress);
      var repeated =
          sendRepeatedRequestForSelectsTheSameBoundaryForCombinedAndRepeatedForwardedLines(
              test, attacker, boundary, ingress);

      assertEquals(200, combined.statusCode());
      assertEquals("boundary", combined.body());
      assertEquals(200, repeated.statusCode());
      assertEquals("boundary", repeated.body());
    }
  }

  @ParameterizedTest
  @EnumSource(ForwardedHeaders.class)
  void selectsTheFirstClientAndOriginWhenTheEntireChainIsTrusted(ForwardedHeaders family)
      throws Exception {
    app.trustedProxies(InetAddress::isLoopbackAddress, family);
    app.routes()
        .get(
            "/trusted-chain",
            (request, response) ->
                response.text(
                    request.clientAddress().orElseThrow().getAddress().getHostAddress()
                        + "|"
                        + request.effectiveUrl()));

    try (var test = TestServer.start(app)) {
      var result = sendEntirelyTrustedChain(test, family);

      assertEquals(200, result.statusCode());
      assertEquals("127.0.0.2|https://first.example/trusted-chain", result.body());
    }
  }

  private HttpResponse<String> sendEntirelyTrustedChain(TestServer test, ForwardedHeaders family)
      throws IOException, InterruptedException {
    return test.send(
        request -> {
          request.path("/trusted-chain");
          if (family == ForwardedHeaders.RFC7239) {
            request.header(
                "Forwarded",
                "for=127.0.0.2;host=first.example;proto=https, "
                    + "for=127.0.0.3;host=last.example;proto=http");
          } else {
            request.header("X-Forwarded-For", "127.0.0.2, 127.0.0.3");
            request.header("X-Forwarded-Host", "first.example, last.example");
            request.header("X-Forwarded-Proto", "https, http");
          }
        },
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String>
      sendCombinedRequestForSelectsTheSameBoundaryForCombinedAndRepeatedForwardedLines(
          TestServer test, String attacker, String boundary, String ingress)
          throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/repeated?raw=%2B")
                .timeout(Duration.ofSeconds(3))
                .header("Forwarded", attacker + ", " + boundary + ", " + ingress),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String>
      sendRepeatedRequestForSelectsTheSameBoundaryForCombinedAndRepeatedForwardedLines(
          TestServer test, String attacker, String boundary, String ingress)
          throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/repeated?raw=%2B")
                .timeout(Duration.ofSeconds(3))
                .header("Forwarded", attacker)
                .header("Forwarded", boundary)
                .header("Forwarded", ingress),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void exposesTrustedMetadataToRouteGatesAndGlobalErrorHandlers() throws Exception {
    var gateInvoked = new AtomicBoolean();
    app.trustedProxies(InetAddress::isLoopbackAddress);
    app.beforeRouteHandler((request, ignoredResponse) -> {
      gateInvoked.set(true);

      assertTrue(request.isForwarded());
      assertEquals(
          "203.0.113.7", request.clientAddress().orElseThrow().getAddress().getHostAddress());
      assertEquals("https://public.example/gated", request.effectiveUrl());
    });
    app.exception(
        IllegalStateException.class,
        (ignoredFailure, request, response) ->
            response.text(
                request.clientAddress().orElseThrow().getAddress().getHostAddress()
                    + "|"
                    + request.effectiveUrl()));
    app.routes()
        .get(
            "/gated",
            (ignoredRequest, ignoredResponse) -> {
              throw new IllegalStateException();
            });

    try (var test = TestServer.start(app, client -> client.version(HttpClient.Version.HTTP_1_1))) {
      var result = sendResultForExposesTrustedMetadataToRouteGatesAndGlobalErrorHandlers(test);

      assertEquals(500, result.statusCode());
      assertEquals("203.0.113.7|https://public.example/gated", result.body());
      assertTrue(gateInvoked.get());
    }
  }

  private HttpResponse<String>
      sendResultForExposesTrustedMetadataToRouteGatesAndGlobalErrorHandlers(TestServer test)
          throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/gated")
                .timeout(Duration.ofSeconds(3))
                .header("Forwarded", "for=203.0.113.7;host=public.example;proto=https"),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void ignoresMalformedForwardingFromAnUntrustedPeer() throws Exception {
    app.trustedProxies(ignoredInetAddress -> false);
    app.routes().get("/untrusted", (request, response) -> response.text(request.effectiveUrl()));

    try (var test = TestServer.start(app, client -> client.version(HttpClient.Version.HTTP_1_1))) {
      var result = sendResultForIgnoresMalformedForwardingFromAnUntrustedPeer(test);

      assertEquals(200, result.statusCode());
      assertEquals("http://127.0.0.1:" + app.port() + "/untrusted", result.body());
    }
  }

  private HttpResponse<String> sendResultForIgnoresMalformedForwardingFromAnUntrustedPeer(
      TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/untrusted")
                .timeout(Duration.ofSeconds(3))
                .header("Forwarded", "for=localhost;host=bad.example;proto=javascript"),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void freezesProxyTrustConfigurationAtFirstRegistrationAndStartup() throws Exception {
    app.trustedProxies(InetAddress::isLoopbackAddress);

    assertThrows(
        IllegalStateException.class,
        () -> app.trustedProxies(InetAddress::isLoopbackAddress, ForwardedHeaders.X_FORWARDED));

    {
      var started =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable startedCleanup = started::close) {
        started.routes().get("/frozen", (ignoredRequest, response) -> response.text("ok"));
        started.start();

        assertThrows(
            IllegalStateException.class,
            () -> started.trustedProxies(InetAddress::isLoopbackAddress));
      }
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
                    request.clientAddress().orElseThrow().getAddress().getHostAddress()
                        + "|"
                        + request.effectiveUrl()));

    try (var test = TestServer.start(app, client -> client.version(HttpClient.Version.HTTP_1_1))) {
      var result = sendResultForAppliesOnlyTheExplicitlySelectedLegacyForwardingFamily(test);

      assertEquals(200, result.statusCode());
      assertEquals("198.51.100.7|https://public.example/legacy", result.body());
    }
  }

  private HttpResponse<String> sendResultForAppliesOnlyTheExplicitlySelectedLegacyForwardingFamily(
      TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/legacy")
                .header("X-Forwarded-For", "198.51.100.7")
                .header("X-Forwarded-Host", "public.example")
                .header("X-Forwarded-Proto", "https")
                .header("Forwarded", "for=203.0.113.9;host=ignored.example;proto=http"),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void rejectsMisalignedLegacyForwardingLists() throws Exception {
    app.trustedProxies(InetAddress::isLoopbackAddress, ForwardedHeaders.X_FORWARDED);
    app.routes().get("/legacy", (ignoredRequest, response) -> response.text("unexpected"));

    try (var test = TestServer.start(app, client -> client.version(HttpClient.Version.HTTP_1_1))) {
      var result = sendResultForRejectsMisalignedLegacyForwardingLists(test);

      assertEquals(400, result.statusCode());
    }
  }

  @Test
  void rejectsLegacyPortAssertionsWithoutHost() throws Exception {
    var calls = new AtomicInteger();
    app.trustedProxies(InetAddress::isLoopbackAddress, ForwardedHeaders.X_FORWARDED);
    app.beforeRouteHandler((ignoredRequest, ignoredResponse) -> calls.incrementAndGet());
    app.routes().get("/legacy", (ignoredRequest, response) -> response.text("unexpected"));

    try (var test = TestServer.start(app)) {
      var result = sendLegacyPortWithoutHost(test);

      assertEquals(400, result.statusCode());
      assertEquals(0, calls.get());
    }
  }

  private HttpResponse<String> sendLegacyPortWithoutHost(TestServer test)
      throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/legacy")
                .header("X-Forwarded-For", "203.0.113.7")
                .header("X-Forwarded-Port", "8443"),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> sendResultForRejectsMisalignedLegacyForwardingLists(TestServer test)
      throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/legacy")
                .header("X-Forwarded-For", "198.51.100.7, 192.0.2.10")
                .header("X-Forwarded-Host", "public.example"),
        HttpResponse.BodyHandlers.ofString());
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
                    request.clientAddress().orElseThrow().getAddress().getHostAddress()
                        + "|"
                        + request.effectiveUrl()));

    try (var test = TestServer.start(app, client -> client.version(HttpClient.Version.HTTP_1_1))) {
      var result = sendResultForSelectsTheSameLegacyBoundaryForClientAndOrigin(test);

      assertEquals(200, result.statusCode());
      assertEquals("203.0.113.7|https://public.example/legacy-chain", result.body());
    }
  }

  private HttpResponse<String> sendResultForSelectsTheSameLegacyBoundaryForClientAndOrigin(
      TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/legacy-chain")
                .header("X-Forwarded-For", "198.51.100.7, 203.0.113.7, 192.0.2.10")
                .header("X-Forwarded-Host", "evil.example, public.example, proxy.example")
                .header("X-Forwarded-Proto", "http, https, http"),
        HttpResponse.BodyHandlers.ofString());
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
                        + request.clientAddress().orElseThrow().getPort()));

    try (var test = TestServer.start(app, client -> client.version(HttpClient.Version.HTTP_1_1))) {
      var result = sendResultForAcceptsBareIpv6AndMatchingLegacyHostPort(test);

      assertEquals(200, result.statusCode());
      assertEquals("http://public.example:8443/legacy-ipv6|0", result.body());
    }
  }

  private HttpResponse<String> sendResultForAcceptsBareIpv6AndMatchingLegacyHostPort(
      TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/legacy-ipv6")
                .header("X-Forwarded-For", "2001:db8::7")
                .header("X-Forwarded-Host", "public.example:8443")
                .header("X-Forwarded-Port", "8443"),
        HttpResponse.BodyHandlers.ofString());
  }

  @ParameterizedTest
  @CsvSource(
      value = {
        "public.example,absent,public.example",
        "public.example,1,public.example:1",
        "public.example,080,public.example",
        "public.example,65535,public.example:65535",
        "public.example:8443,absent,public.example:8443",
        "public.example:8443,08443,public.example:8443",
        "[2001:db8::7],absent,[2001:db8::7]",
        "[2001:db8::7],8443,[2001:db8::7]:8443",
        "[2001:db8::7]:8443,8443,[2001:db8::7]:8443"
      },
      nullValues = "absent")
  void preservesLegacyAuthoritiesAndRawQuery(String host, String forwardedPort, String expected)
      throws Exception {
    app.trustedProxies(InetAddress::isLoopbackAddress, ForwardedHeaders.X_FORWARDED);
    app.routes().get("/authority", (request, response) -> response.text(request.effectiveUrl()));

    try (var test = TestServer.start(app, client -> client.version(HttpClient.Version.HTTP_1_1))) {
      var result = legacyAuthority(test, host, forwardedPort);

      assertEquals(200, result.statusCode());
      assertEquals("http://" + expected + "/authority?raw=%2F+value", result.body());
    }
  }

  @ParameterizedTest
  @CsvSource(
      value = {
        "public.example,0",
        "public.example,00000",
        "public.example,65536",
        "public.example,2147483648",
        "public.example,+80",
        "public.example,-1",
        "public.example,8O",
        "public.example:8443,443",
        "public.example:0,absent",
        "public.example:65536,absent",
        "::1,absent",
        "public.example:+80,absent",
        "[2001:db8::7],0",
        "[2001:db8::7]:8443,443"
      },
      nullValues = "absent")
  void rejectsMalformedLegacyAuthorities(String host, String forwardedPort) throws Exception {
    app.trustedProxies(InetAddress::isLoopbackAddress, ForwardedHeaders.X_FORWARDED);
    app.routes().get("/authority", (request, response) -> response.text("unexpected"));

    try (var test = TestServer.start(app, client -> client.version(HttpClient.Version.HTTP_1_1))) {
      assertEquals(400, legacyAuthority(test, host, forwardedPort).statusCode());
    }
  }

  private HttpResponse<String> legacyAuthority(TestServer test, String host, String forwardedPort)
      throws IOException, InterruptedException {
    return test.send(
        request -> {
          request
              .path("/authority?raw=%2F+value")
              .header("X-Forwarded-For", "198.51.100.7")
              .header("X-Forwarded-Host", host);
          if (forwardedPort != null) {
            request.header("X-Forwarded-Port", forwardedPort);
          }
        },
        HttpResponse.BodyHandlers.ofString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "65536", "2147483648"})
  void rejectsMalformedLegacyPortsOutsideTheSelectedBoundary(String invalidPort) throws Exception {
    app.trustedProxies(InetAddress::isLoopbackAddress, ForwardedHeaders.X_FORWARDED);
    app.routes().get("/authority", (request, response) -> response.text("unexpected"));

    try (var test = TestServer.start(app, client -> client.version(HttpClient.Version.HTTP_1_1))) {
      assertEquals(400, unselectedLegacyPort(test, invalidPort).statusCode());
    }
  }

  private HttpResponse<String> unselectedLegacyPort(TestServer test, String invalidPort)
      throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/authority")
                .header("X-Forwarded-For", "198.51.100.7, 192.0.2.10")
                .header("X-Forwarded-Host", "bad.example, selected.example")
                .header("X-Forwarded-Port", invalidPort + ", 443"),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void rejectsConflictingLegacyHostAndPort() throws Exception {
    app.trustedProxies(InetAddress::isLoopbackAddress, ForwardedHeaders.X_FORWARDED);
    app.routes().get("/legacy-port", (ignoredRequest, response) -> response.text("unexpected"));

    try (var test = TestServer.start(app, client -> client.version(HttpClient.Version.HTTP_1_1))) {
      var result = sendResultForRejectsConflictingLegacyHostAndPort(test);

      assertEquals(400, result.statusCode());
    }
  }

  private HttpResponse<String> sendResultForRejectsConflictingLegacyHostAndPort(TestServer test)
      throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/legacy-port")
                .header("X-Forwarded-For", "198.51.100.7")
                .header("X-Forwarded-Host", "public.example:8443")
                .header("X-Forwarded-Port", "443"),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void rejectsMalformedLegacyOriginOutsideTheSelectedBoundary() throws Exception {
    app.trustedProxies(InetAddress::isLoopbackAddress, ForwardedHeaders.X_FORWARDED);
    app.routes().get("/legacy-origin", (ignoredRequest, response) -> response.text("unexpected"));

    try (var test = TestServer.start(app, client -> client.version(HttpClient.Version.HTTP_1_1))) {
      var result = sendResultForRejectsMalformedLegacyOriginOutsideTheSelectedBoundary(test);

      assertEquals(400, result.statusCode());
    }
  }

  private HttpResponse<String> sendResultForRejectsMalformedLegacyOriginOutsideTheSelectedBoundary(
      TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/legacy-origin")
                .header("X-Forwarded-For", "198.51.100.7, 192.0.2.10")
                .header("X-Forwarded-Proto", "javascript, https"),
        HttpResponse.BodyHandlers.ofString());
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
    app.routes()
        .get("/legacy-predicate", (ignoredRequest, response) -> response.text("unexpected"));

    try (var test = TestServer.start(app, client -> client.version(HttpClient.Version.HTTP_1_1))) {
      var result = sendResultForLetsLegacyTrustPredicateFailuresReachTheApplicationErrorPath(test);

      assertEquals(500, result.statusCode());
    }
  }

  private HttpResponse<String>
      sendResultForLetsLegacyTrustPredicateFailuresReachTheApplicationErrorPath(TestServer test)
          throws IOException, InterruptedException {
    return test.send(
        request ->
            request.path("/legacy-predicate").header("X-Forwarded-For", "198.51.100.7, 192.0.2.10"),
        HttpResponse.BodyHandlers.ofString());
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
                    request.clientAddress().orElseThrow().getAddress().getHostAddress()
                        + "|"
                        + request.clientAddress().orElseThrow().getPort()
                        + "|"
                        + request.effectiveUrl()));

    try (var test = TestServer.start(app, client -> client.version(HttpClient.Version.HTTP_1_1))) {
      var result = sendResultForSelectsClientAndOriginAtTheSameTrustedBoundary(test);

      assertEquals(200, result.statusCode());
      assertEquals("203.0.113.7|0|https://public.example/chain", result.body());

      var stopped = sendStoppedRequestForSelectsClientAndOriginAtTheSameTrustedBoundary(test);

      assertEquals(200, stopped.statusCode());
      assertEquals("192.0.2.11|0|http://127.0.0.1:" + app.port() + "/chain", stopped.body());
    }
  }

  private HttpResponse<String> sendResultForSelectsClientAndOriginAtTheSameTrustedBoundary(
      TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/chain")
                .timeout(Duration.ofSeconds(3))
                .header(
                    "Forwarded",
                    "for=198.51.100.99;host=evil.example;proto=http, "
                        + "for=203.0.113.7;host=public.example;proto=https, for=192.0.2.10"),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> sendStoppedRequestForSelectsClientAndOriginAtTheSameTrustedBoundary(
      TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/chain")
                .timeout(Duration.ofSeconds(3))
                .header(
                    "Forwarded", "for=198.51.100.99;host=evil.example;proto=https, for=192.0.2.11"),
        HttpResponse.BodyHandlers.ofString());
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
        "for=203.0.113.7;extension=\"a\"b\"c\"",
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
        "for=999.0.0.1",
        "for=\"[localhost]\"",
        "for=\"[1:2:3]\"",
        "for=\"[::ffff:999.0.0.1]\"",
        "for=\"[::1\"",
        "for=\"[::1]:port\"",
        "for=\"[::1]123\"",
        "for=localhost"
      })
  void rejectsMalformedTrustedMetadata(String value) throws Exception {
    var calls = new AtomicInteger();
    app.trustedProxies(InetAddress::isLoopbackAddress);
    app.beforeRouteHandler((ignoredRequest, ignoredResponse) -> calls.incrementAndGet());
    app.routes().get("/invalid", (ignoredRequest, ignoredResponse) -> calls.incrementAndGet());

    try (var test = TestServer.start(app, client -> client.version(HttpClient.Version.HTTP_1_1))) {
      var result = sendResultForRejectsMalformedTrustedMetadata(test, value);

      assertEquals(400, result.statusCode(), value);
      assertEquals(0, calls.get());
    }
  }

  private HttpResponse<String> sendResultForRejectsMalformedTrustedMetadata(
      TestServer test, String value) throws IOException, InterruptedException {
    return test.send(
        request ->
            request.path("/invalid").timeout(Duration.ofSeconds(3)).header("Forwarded", value),
        HttpResponse.BodyHandlers.ofString());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "for=203.0.113.7;host=\"invalid.example/path\"",
        "for=203.0.113.7;host=%invalid.example",
        "for=203.0.113.7;host=%65xample.com",
        "for=203.0.113.7;by=localhost"
      })
  void rejectsMalformedRfcMetadataBeforeTheSelectedTrustBoundary(String predecessor)
      throws Exception {
    var calls = new AtomicInteger();
    app.trustedProxies(InetAddress::isLoopbackAddress);
    app.beforeRouteHandler((ignoredRequest, ignoredResponse) -> calls.incrementAndGet());
    app.routes().get("/invalid", (ignoredRequest, response) -> response.text("unexpected"));

    try (var test = TestServer.start(app)) {
      var result =
          sendResultForRejectsMalformedTrustedMetadata(
              test, predecessor + ", for=198.51.100.8;host=selected.example");

      assertEquals(400, result.statusCode());
      assertEquals(0, calls.get());
    }
  }

  @ParameterizedTest
  @CsvSource(
      value = {
        "001.002.003.004|1.2.3.4",
        "192.000.002.255|192.0.2.255",
        "[::ffff:192.000.2.1]|192.0.2.1"
      },
      delimiter = '|')
  void decodesLiteralAddressesWithDecimalLeadingZeros(String node, String expected)
      throws Exception {
    app.trustedProxies(InetAddress::isLoopbackAddress);
    app.routes()
        .get(
            "/literal",
            (request, response) ->
                response.text(request.clientAddress().orElseThrow().getAddress().getHostAddress()));

    try (var test = TestServer.start(app)) {
      var result = sendResultForDecodesLiteralAddressesWithDecimalLeadingZeros(test, node);

      assertEquals(200, result.statusCode());
      assertEquals(expected, result.body());
    }
  }

  private HttpResponse<String> sendResultForDecodesLiteralAddressesWithDecimalLeadingZeros(
      TestServer test, String node) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/literal")
                .timeout(Duration.ofSeconds(3))
                .header("Forwarded", "for=\"" + node + "\""),
        HttpResponse.BodyHandlers.ofString());
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
              assertTrue(request.clientAddress().isEmpty());
              assertEquals("https://boundary.example/unknown", request.effectiveUrl());

              response.text("unknown");
            });

    try (var test = TestServer.start(app, client -> client.version(HttpClient.Version.HTTP_1_1))) {
      var result = sendResultForStopsAtUndisclosedPredecessors(test, node);

      assertEquals(200, result.statusCode());
      assertEquals("unknown", result.body());
    }
  }

  private HttpResponse<String> sendResultForStopsAtUndisclosedPredecessors(
      TestServer test, String node) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/unknown")
                .timeout(Duration.ofSeconds(3))
                .header(
                    "Forwarded",
                    "for=198.51.100.1;host=evil.example, "
                        + node
                        + "host=boundary.example;proto=https"),
        HttpResponse.BodyHandlers.ofString());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        ",",
        ",,",
        "for=203.0.113.7,",
        ", for=203.0.113.7",
        "for=203.0.113.7,,for=198.51.100.8"
      })
  void rejectsEmptyForwardedChainElements(String value) throws Exception {
    var calls = new AtomicInteger();
    app.trustedProxies(InetAddress::isLoopbackAddress);
    app.beforeRouteHandler((ignoredRequest, ignoredResponse) -> calls.incrementAndGet());
    app.routes().get("/empty", (ignoredRequest, ignoredResponse) -> calls.incrementAndGet());

    try (var test = TestServer.start(app, client -> client.version(HttpClient.Version.HTTP_1_1))) {
      var result =
          test.send(
              request ->
                  request.path("/empty").timeout(Duration.ofSeconds(3)).header("Forwarded", value),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(400, result.statusCode(), value);
      assertEquals(0, calls.get());
    }
  }

  @Test
  void rejectsForwardedChainPastTheWorkBound() throws Exception {
    var calls = new AtomicInteger();
    app.trustedProxies(InetAddress::isLoopbackAddress);
    app.beforeRouteHandler((ignoredRequest, ignoredResponse) -> calls.incrementAndGet());
    app.routes().get("/bounded", (ignoredRequest, ignoredResponse) -> calls.incrementAndGet());

    try (var test = TestServer.start(app, client -> client.version(HttpClient.Version.HTTP_1_1))) {
      var value = String.join(",", Collections.nCopies(65, "for=203.0.113.7"));
      var result = sendResultForRejectsForwardedChainPastTheWorkBound(test, value);

      assertEquals(400, result.statusCode());
      assertEquals(0, calls.get());
    }
  }

  private HttpResponse<String> sendResultForRejectsForwardedChainPastTheWorkBound(
      TestServer test, String value) throws IOException, InterruptedException {
    return test.send(
        request ->
            request.path("/bounded").timeout(Duration.ofSeconds(3)).header("Forwarded", value),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void acceptsForwardedChainAtTheWorkBound() throws Exception {
    app.trustedProxies(InetAddress::isLoopbackAddress);
    app.routes().get("/bounded", (ignoredRequest, response) -> response.text("accepted"));

    try (var test = TestServer.start(app, client -> client.version(HttpClient.Version.HTTP_1_1))) {
      var value = String.join(",", Collections.nCopies(64, "for=203.0.113.7"));
      var result = sendResultForAcceptsForwardedChainAtTheWorkBound(test, value);

      assertEquals(200, result.statusCode());
      assertEquals("accepted", result.body());
    }
  }

  private HttpResponse<String> sendResultForAcceptsForwardedChainAtTheWorkBound(
      TestServer test, String value) throws IOException, InterruptedException {
    return test.send(
        request ->
            request.path("/bounded").timeout(Duration.ofSeconds(3)).header("Forwarded", value),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void rejectsForwardedChainPastTheWorkBoundAcrossHeaderLines() throws Exception {
    var calls = new AtomicInteger();
    app.trustedProxies(InetAddress::isLoopbackAddress);
    app.beforeRouteHandler((ignoredRequest, ignoredResponse) -> calls.incrementAndGet());
    app.routes().get("/bounded", (ignoredRequest, ignoredResponse) -> calls.incrementAndGet());

    try (var test = TestServer.start(app, client -> client.version(HttpClient.Version.HTTP_1_1))) {
      var accepted = String.join(",", Collections.nCopies(64, "for=203.0.113.7"));
      var result =
          sendResultForRejectsForwardedChainPastTheWorkBoundAcrossHeaderLines(test, accepted);

      assertEquals(400, result.statusCode());
      assertEquals(0, calls.get());
    }
  }

  private HttpResponse<String> sendResultForRejectsForwardedChainPastTheWorkBoundAcrossHeaderLines(
      TestServer test, String accepted) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/bounded")
                .timeout(Duration.ofSeconds(3))
                .header("Forwarded", accepted)
                .header("Forwarded", "for=203.0.113.8"),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void acceptsLongQuotedForwardedExtension() throws Exception {
    app.trustedProxies(InetAddress::isLoopbackAddress);
    app.routes().get("/quoted", (ignoredRequest, response) -> response.text("accepted"));

    try (var test = TestServer.start(app, client -> client.version(HttpClient.Version.HTTP_1_1))) {

      var result = sendResultForAcceptsLongQuotedForwardedExtension(test);

      assertEquals(200, result.statusCode());
      assertEquals("accepted", result.body());
    }
  }

  private HttpResponse<String> sendResultForAcceptsLongQuotedForwardedExtension(TestServer test)
      throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/quoted")
                .timeout(Duration.ofSeconds(3))
                .header("Forwarded", "for=203.0.113.7;extension=\"" + "x".repeat(4000) + "\""),
        HttpResponse.BodyHandlers.ofString());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "for=203.0.113.7;extension=\"a\\\"b\"",
        "for=203.0.113.7;extension=\"a\\\\b\"",
        "for=203.0.113.7;x=\"a\"",
        "for=203.0.113.7;extension=\"\"",
        "for=203.0.113.7;extension=\"a b\"",
        "for=203.0.113.7;extension=\"a\tb\"",
        "for=203.0.113.7;extension=\"aÿb\"",
        "for=203.0.113.7;extension=\"a\\ b\"",
        "for=203.0.113.7;extension=\"a\\\tb\"",
        "for=203.0.113.7;extension=\"a\\ÿb\""
      })
  void acceptsQuotedForwardedExtensions(String value) throws Exception {
    app.trustedProxies(InetAddress::isLoopbackAddress);
    app.routes().get("/quoted", (ignoredRequest, response) -> response.text("accepted"));

    try (var test = TestServer.start(app, client -> client.version(HttpClient.Version.HTTP_1_1))) {
      var result = sendForwardedOctets(test, value);

      assertEquals(200, result.getStatus());
      assertEquals("accepted", result.getContent());
    }
  }

  private HttpTester.Response sendForwardedOctets(TestServer test, String value)
      throws IOException {
    try (var socket = new Socket(test.baseUri().getHost(), test.baseUri().getPort())) {
      socket.setSoTimeout(3000);
      var output = socket.getOutputStream();
      output.write(forwardingRequest(value));
      output.flush();

      return HttpTester.parseResponse(HttpTester.from(socket.getInputStream()));
    }
  }

  private byte[] forwardingRequest(String value) {
    return ("GET /quoted HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nForwarded: "
            + value
            + "\r\n\r\n")
        .getBytes(StandardCharsets.ISO_8859_1);
  }

  @Test
  void rejectsEscapedClosingQuoteWithoutTerminator() throws Exception {
    app.trustedProxies(InetAddress::isLoopbackAddress);
    app.routes().get("/quoted", (ignoredRequest, response) -> response.text("unexpected"));

    try (var test = TestServer.start(app, client -> client.version(HttpClient.Version.HTTP_1_1))) {

      assertEquals(
          400,
          test.send(
                  request ->
                      request
                          .path("/quoted")
                          .timeout(Duration.ofSeconds(3))
                          .header("Forwarded", "for=203.0.113.7;extension=\"abc\\\""),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode());
    }
  }
}
