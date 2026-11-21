package io.github.suppierk.shoostr.pac4j;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.ForwardedHeaders;
import io.github.suppierk.shoostr.testing.TestRequest;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.http.client.direct.DirectBasicAuthClient;

@Timeout(15)
class Pac4jProxySecurityTest {
  private static Stream<Arguments> metadataSources() {
    return Stream.of("host", "port", "scheme")
        .flatMap(accessor ->
            Stream.concat(
                    Stream.of("direct"), Arrays.stream(ForwardedHeaders.values()).map(Enum::name))
                .map(source -> Arguments.of(accessor, source)));
  }

  private static Shoostr application(
      ForwardedHeaders family, boolean trusted, AtomicReference<String> scheme) {
    var provider = createProviderForApplication(scheme);
    var app =
        new Shoostr()
            .modifyServer(
                nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));
    var auth = new Pac4j(provider, "Basic", (context, ignored, ignoredList) -> context.isSecure());
    app.trustedProxies(address -> trusted && address.isLoopbackAddress(), family)
        .routes()
        .get(
            "/secure",
            (ignoredRequest, response) -> response.text("private"),
            lifecycle -> lifecycle.withExtension(auth));

    return app;
  }

  private static DirectBasicAuthClient createProviderForApplication(
      AtomicReference<String> scheme) {
    return new DirectBasicAuthClient((context, supplied) -> {
      scheme.set(context.webContext().getScheme());
      var profile = new CommonProfile();
      profile.setId("alice");
      supplied.setUserProfile(profile);

      return Optional.of(supplied);
    });
  }

  private static TestRequest authorized(TestRequest request) {
    return request.path("/secure").header("Authorization", "Basic YWxpY2U6Y29ycmVjdA==");
  }

  private static void forwarding(TestRequest request, ForwardedHeaders family, String protocol) {
    if (family == ForwardedHeaders.RFC7239) {
      request.header(
          "Forwarded", "for=203.0.113.7" + (protocol.isEmpty() ? "" : ";proto=" + protocol));
    } else {
      request.header("X-Forwarded-For", "203.0.113.7");
      if (!protocol.isEmpty()) {
        request.header("X-Forwarded-Proto", protocol);
      }
    }
  }

  @ParameterizedTest
  @MethodSource("metadataSources")
  void readsOriginMetadataWithoutParsingTheOpaqueRawQuery(String accessor, String source)
      throws Exception {
    var observed = new AtomicReference<String>();
    var fullUrl = new AtomicReference<String>();
    var provider =
        createProviderForReadsOriginMetadataWithoutParsingTheOpaqueRawQuery(
            observed, accessor, fullUrl);

    {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close) {
        var family = "direct".equals(source) ? null : ForwardedHeaders.valueOf(source);
        if (family != null) {
          app.trustedProxies(InetAddress::isLoopbackAddress, family);
        }

        var auth = new Pac4j(provider, "Basic");
        app.routes()
            .get(
                "/metadata",
                (request, response) -> response.text(request.principal().orElseThrow().getName()),
                lifecycle -> lifecycle.withExtension(auth));

        try (var test = TestServer.start(app);
            var socket =
                new Socket(InetAddress.getAllByName("127.0.0.1")[0], test.baseUri().getPort());
            var reader =
                new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))) {
          socket.setSoTimeout(5000);
          var forwarded =
              createForwardedForReadsOriginMetadataWithoutParsingTheOpaqueRawQuery(family);
          var request =
              createRequestForReadsOriginMetadataWithoutParsingTheOpaqueRawQuery(test, forwarded);
          socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));

          assertEquals("HTTP/1.1 200 OK", reader.readLine());

          var expected =
              createExpectedForReadsOriginMetadataWithoutParsingTheOpaqueRawQuery(
                  accessor, family, test);

          assertEquals(expected, observed.get());
          assertEquals(
              (family == null
                      ? "http://127.0.0.1:" + test.baseUri().getPort()
                      : "https://public.example:9443")
                  + "/metadata?bad=%GG",
              fullUrl.get());
        }
      }
    }
  }

  private DirectBasicAuthClient createProviderForReadsOriginMetadataWithoutParsingTheOpaqueRawQuery(
      AtomicReference<String> observed, String accessor, AtomicReference<String> fullUrl) {
    return new DirectBasicAuthClient((context, supplied) -> {
      var web = context.webContext();
      observed.set(
          switch (accessor) {
            case "host" -> web.getServerName();
            case "port" -> Integer.toString(web.getServerPort());
            default -> web.getScheme();
          });
      fullUrl.set(web.getFullRequestURL());
      var profile = new CommonProfile();
      profile.setId("alice");
      supplied.setUserProfile(profile);

      return Optional.of(supplied);
    });
  }

  private String createForwardedForReadsOriginMetadataWithoutParsingTheOpaqueRawQuery(
      ForwardedHeaders family) {
    return family == null
        ? ""
        : family == ForwardedHeaders.RFC7239
            ? "Forwarded: for=203.0.113.7;proto=https;host=\"public.example:9443\"\r\n"
            : "X-Forwarded-For: 203.0.113.7\r\nX-Forwarded-Proto: "
                + "https\r\nX-Forwarded-Host: public.example:9443\r\n"
                + "X-Forwarded-Port: 9443\r\n";
  }

  private String createRequestForReadsOriginMetadataWithoutParsingTheOpaqueRawQuery(
      TestServer test, String forwarded) {
    return "GET /metadata?bad=%GG HTTP/1.1\r\nHost: 127.0.0.1:"
        + test.baseUri().getPort()
        + "\r\nAuthorization: Basic YWxpY2U6Y29ycmVjdA==\r\n"
        + forwarded
        + "Connection: close\r\n\r\n";
  }

  private String createExpectedForReadsOriginMetadataWithoutParsingTheOpaqueRawQuery(
      String accessor, ForwardedHeaders family, TestServer test) {
    return switch (accessor) {
      case "host" -> family == null ? "127.0.0.1" : "public.example";
      case "port" -> family == null ? Integer.toString(test.baseUri().getPort()) : "9443";
      default -> family == null ? "http" : "https";
    };
  }

  @ParameterizedTest
  @EnumSource(ForwardedHeaders.class)
  void authorizesHttpsAssertedByTrustedProxyOnPlainBackendConnection(ForwardedHeaders family)
      throws Exception {
    var scheme = new AtomicReference<String>();

    try (var test = TestServer.start(application(family, true, scheme))) {
      var response =
          test.send(
              request -> forwarding(authorized(request), family, "https"),
              HttpResponse.BodyHandlers.ofString());

      assertEquals("https", scheme.get());
      assertEquals(200, response.statusCode());
      assertEquals("private", response.body());
    }
  }

  @ParameterizedTest
  @EnumSource(ForwardedHeaders.class)
  void rejectsHttpsAssertedByAnUntrustedPeer(ForwardedHeaders family) throws Exception {
    var scheme = new AtomicReference<String>();

    try (var test = TestServer.start(application(family, false, scheme))) {
      var response = test.send(request -> forwarding(authorized(request), family, "https"));

      assertEquals("http", scheme.get());
      assertEquals(403, response.statusCode());
    }
  }

  @ParameterizedTest
  @EnumSource(ForwardedHeaders.class)
  void rejectsForgedHttpsTargetsWhenTheTrustedProxyAssertsNoProtocol(ForwardedHeaders family)
      throws Exception {
    var scheme = new AtomicReference<String>();

    try (var test = TestServer.start(application(family, true, scheme));
        var socket =
            new Socket(
                InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), test.baseUri().getPort())) {
      var header =
          family == ForwardedHeaders.RFC7239
              ? "Forwarded: for=203.0.113.7\r\n"
              : "X-Forwarded-For: 203.0.113.7\r\n";
      socket.setSoTimeout(5000);
      socket
          .getOutputStream()
          .write(
              ("GET https://virtual.example/secure HTTP/1.1\r\nHost: virtual.example\r\n"
                      + "Authorization: Basic YWxpY2U6Y29ycmVjdA==\r\n"
                      + header
                      + "Connection: close\r\n\r\n")
                  .getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      var reader =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));

      assertEquals("HTTP/1.1 403 Forbidden", reader.readLine());
      assertEquals("https", scheme.get());
    }
  }

  @ParameterizedTest
  @EnumSource(ForwardedHeaders.class)
  void rejectsTrustedHttpDespiteBackendTlsAndFallsBackToTlsWithoutProtocol(ForwardedHeaders family)
      throws Exception {
    var identity = KeyStore.getInstance("PKCS12");

    try (var input =
        Objects.requireNonNull(getClass().getResourceAsStream("/localhost-test.p12"))) {
      identity.load(input, "changeit".toCharArray());
    }

    var tls = createTrustContext(identity);

    var scheme = new AtomicReference<String>();

    {
      var app = application(family, true, scheme);

      try (Closeable appCleanup1 = app::close) {
        app.tls(server -> {
          server.setKeyStore(identity);
          server.setKeyStorePassword("changeit");
        });

        try (var test = TestServer.start(app, client -> configureTlsClient(client, tls))) {
          var denied = test.send(request -> forwarding(authorized(request), family, "http"));

          assertEquals("http", scheme.get());
          assertEquals(403, denied.statusCode());

          var allowed =
              test.send(
                  request -> forwarding(authorized(request), family, ""),
                  HttpResponse.BodyHandlers.ofString());

          assertEquals("https", scheme.get());
          assertEquals(200, allowed.statusCode());
          assertEquals("private", allowed.body());
          assertEquals(200, test.send(Pac4jProxySecurityTest::authorized).statusCode());
        }
      }
    }
  }

  private SSLContext createTrustContext(KeyStore identity) throws GeneralSecurityException {
    var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trust.init(identity);
    var context = SSLContext.getInstance("TLS");
    context.init(null, trust.getTrustManagers(), null);

    return context;
  }

  private void configureTlsClient(HttpClient.Builder client, SSLContext context) {
    client.sslContext(context);
  }
}
