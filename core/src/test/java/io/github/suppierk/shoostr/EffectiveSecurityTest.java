package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.http.ForwardedHeaders;
import io.github.suppierk.shoostr.testing.TestRequest;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.InetAddress;
import java.net.Socket;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class EffectiveSecurityTest {
  @ParameterizedTest
  @CsvSource({
    "RFC7239, https, true", "RFC7239, http, false", "RFC7239, HtTpS, true",
        "RFC7239, missing, false",
    "X_FORWARDED, https, true", "X_FORWARDED, http, false", "X_FORWARDED, HtTpS, true",
        "X_FORWARDED, missing, false"
  })
  void usesOnlyAnExplicitTrustedProtocolOnPlainTransport(
      ForwardedHeaders family, String protocol, boolean effective) throws Exception {
    try (var test = TestServer.start(application(family, true))) {
      var response =
          test.send(
              request -> forwarding(request.path("/security"), family, protocol),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("false:" + effective, response.body());
    }
  }

  @ParameterizedTest
  @EnumSource(ForwardedHeaders.class)
  void ignoresUntrustedAssertionsAndFallsBackWhenNoHeadersExist(ForwardedHeaders family)
      throws Exception {
    try (var test = TestServer.start(application(family, false))) {
      var supplied =
          test.send(
              request -> forwarding(request.path("/security"), family, "https"),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, supplied.statusCode());
      assertEquals("false:false", supplied.body());
      var absent =
          test.send(request -> request.path("/security"), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, absent.statusCode());
      assertEquals("false:false", absent.body());
    }
  }

  @ParameterizedTest
  @EnumSource(ForwardedHeaders.class)
  void selectsTheBoundaryProtocolInsteadOfTheNearestProxyProtocol(ForwardedHeaders family)
      throws Exception {
    try (var test = TestServer.start(application(family, true))) {
      var response =
          test.send(
              request -> {
                request.path("/security");
                if (family == ForwardedHeaders.RFC7239) {
                  request.header(
                      "Forwarded", "for=203.0.113.7;proto=http, for=127.0.0.2;proto=https");
                } else {
                  request
                      .header("X-Forwarded-For", "203.0.113.7, 127.0.0.2")
                      .header("X-Forwarded-Proto", "http, https");
                }
              },
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("false:false", response.body());
    }
  }

  @ParameterizedTest
  @CsvSource({
    "RFC7239, https, true",
    "RFC7239, http, false",
    "RFC7239, missing, true",
    "X_FORWARDED, https, true",
    "X_FORWARDED, http, false",
    "X_FORWARDED, missing, true"
  })
  void allowsTrustedHttpToOverrideBackendTlsAndOtherwiseUsesTls(
      ForwardedHeaders family, String protocol, boolean effective) throws Exception {
    var identity = identity();
    var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trust.init(identity);
    var tls = SSLContext.getInstance("TLS");
    tls.init(null, trust.getTrustManagers(), null);

    try (var app = application(family, true)) {
      app.tls(
          server -> {
            server.setKeyStore(identity);
            server.setKeyStorePassword("changeit");
          });

      try (var test = TestServer.start(app, client -> client.sslContext(tls))) {
        var response =
            test.send(
                request -> forwarding(request.path("/security"), family, protocol),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("true:" + effective, response.body());
        var noForwarding =
            test.send(request -> request.path("/security"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, noForwarding.statusCode());
        assertEquals("true:true", noForwarding.body());
      }
    }
  }

  @ParameterizedTest
  @MethodSource("unprovenHttpsTargets")
  void neverUsesAForgedTargetOrAnotherHopAsEvidenceOfHttps(ForwardedHeaders family, String headers)
      throws Exception {
    try (var test = TestServer.start(application(family, true));
        var socket =
            new Socket(
                InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), test.baseUri().getPort())) {
      socket.setSoTimeout(5000);
      socket
          .getOutputStream()
          .write(
              ("GET https://virtual.example/security HTTP/1.1\r\nHost: virtual.example\r\n"
                      + headers
                      + "Connection: close\r\n\r\n")
                  .getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      var response = HttpTester.parseResponse(HttpTester.from(socket.getInputStream()));
      assertNotNull(response);
      assertEquals(200, response.getStatus());
      assertEquals("false:false", response.getContent());
    }
  }

  @ParameterizedTest
  @EnumSource(ForwardedHeaders.class)
  void rejectsMalformedTrustedProtocolsBeforeTheHandler(ForwardedHeaders family) throws Exception {
    try (var test = TestServer.start(application(family, true))) {
      assertEquals(
          400,
          test.send(request -> forwarding(request.path("/security"), family, "ftp")).statusCode());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void guardsBothProtocolAndTransportBranchesByThreadAndLifetime(boolean asserted)
      throws Exception {
    var saved = new AtomicReference<Request>();
    var thread = new AtomicReference<Thread>();

    try (var executor = Executors.newSingleThreadExecutor();
        var children = Executors.newVirtualThreadPerTaskExecutor();
        var app = application(ForwardedHeaders.RFC7239, true)) {
      app.routes()
          .get(
              "/guard",
              executor,
              (request, response) -> {
                saved.set(request);
                thread.set(Thread.currentThread());
                assertEquals(asserted, request.isEffectivelySecure());
                children
                    .submit(
                        () ->
                            assertThrows(IllegalStateException.class, request::isEffectivelySecure))
                    .get(3, TimeUnit.SECONDS);
                response.text("checked");
              });

      try (var test = TestServer.start(app)) {
        var response =
            test.send(
                request -> {
                  request.path("/guard");
                  if (asserted) {
                    request.header("Forwarded", "for=203.0.113.7;proto=https");
                  }
                },
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("checked", response.body());
        executor
            .submit(
                () -> {
                  assertSame(thread.get(), Thread.currentThread());
                  var request = saved.get();
                  assertThrows(IllegalStateException.class, request::isEffectivelySecure);
                })
            .get(3, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void inheritsEffectiveSecurityInTypedRoutes() throws Exception {
    try (var app = new Shoostr()) {
      app.trustedProxies(InetAddress::isLoopbackAddress)
          .codec(new TestCodec())
          .routes()
          .get(
              "/security",
              (request, response) ->
                  response.text(request.isSecure() + ":" + request.isEffectivelySecure()));

      try (var test = TestServer.start(app)) {
        var response =
            test.send(
                request -> request.path("/security").header("Forwarded", "proto=https"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("false:true", response.body());
      }
    }
  }

  private static Shoostr application(ForwardedHeaders family, boolean trusted) {
    var app =
        new Shoostr()
            .modifyServer(
                nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));
    app.trustedProxies(address -> trusted && address.isLoopbackAddress(), family)
        .routes()
        .get(
            "/security",
            (request, response) ->
                response.text(request.isSecure() + ":" + request.isEffectivelySecure()));
    return app;
  }

  private static void forwarding(TestRequest request, ForwardedHeaders family, String protocol) {
    if (family == ForwardedHeaders.RFC7239) {
      request.header(
          "Forwarded",
          "for=203.0.113.7" + ("missing".equals(protocol) ? "" : ";proto=" + protocol));
    } else {
      request.header("X-Forwarded-For", "203.0.113.7");
      if (!"missing".equals(protocol)) {
        request.header("X-Forwarded-Proto", protocol);
      }
    }
  }

  private static Stream<Arguments> unprovenHttpsTargets() {
    return Stream.of(
        Arguments.of(ForwardedHeaders.RFC7239, ""),
        Arguments.of(ForwardedHeaders.RFC7239, "Forwarded: for=203.0.113.7\r\n"),
        Arguments.of(
            ForwardedHeaders.RFC7239, "Forwarded: for=203.0.113.7, for=127.0.0.2;proto=https\r\n"),
        Arguments.of(
            ForwardedHeaders.RFC7239, "Forwarded: for=203.0.113.7\r\nX-Forwarded-Proto: https\r\n"),
        Arguments.of(ForwardedHeaders.X_FORWARDED, "X-Forwarded-For: 203.0.113.7\r\n"),
        Arguments.of(ForwardedHeaders.X_FORWARDED, "X-Forwarded-Proto: https\r\n"),
        Arguments.of(
            ForwardedHeaders.X_FORWARDED,
            "X-Forwarded-For: 203.0.113.7\r\nForwarded: proto=https\r\n"));
  }

  private static KeyStore identity() throws Exception {
    var identity = KeyStore.getInstance("PKCS12");

    try (var input =
        Objects.requireNonNull(
            EffectiveSecurityTest.class.getResourceAsStream("/localhost-test.p12"))) {
      identity.load(input, "changeit".toCharArray());
    }

    return identity;
  }
}
