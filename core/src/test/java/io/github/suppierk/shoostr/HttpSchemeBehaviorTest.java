package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.http.ForwardedHeaders;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.net.InetAddress;
import java.net.http.HttpResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class HttpSchemeBehaviorTest {
  @ParameterizedTest
  @CsvSource({
    "RFC7239,hTtP,http",
    "RFC7239,HtTpS,https",
    "X_FORWARDED,hTtP,http",
    "X_FORWARDED,HtTpS,https"
  })
  void normalizesHttpSchemesFromEitherForwardingFamily(
      ForwardedHeaders family, String supplied, String expected) throws Exception {
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.trustedProxies(InetAddress::isLoopbackAddress, family);
      app.routes().get("/origin", (request, response) -> response.text(request.effectiveUrl()));

      try (var test = TestServer.start(app)) {
        var result = forwardedOrigin(test, family, supplied);

        assertEquals(200, result.statusCode());
        assertEquals(expected + "://public.example/origin", result.body());
      }
    }
  }

  @ParameterizedTest
  @EnumSource(ForwardedHeaders.class)
  void rejectsWebSocketSchemeInForwardingMetadata(ForwardedHeaders family) throws Exception {
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.trustedProxies(InetAddress::isLoopbackAddress, family);
      app.routes().get("/origin", (request, response) -> response.text("unexpected"));

      try (var test = TestServer.start(app)) {
        assertEquals(400, forwardedOrigin(test, family, "wss").statusCode());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"HTTP://client.example", "HTTPS://client.example"})
  void configuredOriginsRequireLowercaseSchemes(String origin) {
    assertThrows(
        IllegalArgumentException.class, () -> CorsPolicy.builder().origins(origin).build());
    assertThrows(
        IllegalArgumentException.class, () -> Csrf.builder().trustedOrigins(origin).build());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/relative?raw=%2F",
        "mailto:ops@example.test",
        "HtTp://example.test/next",
        "HtTpS://example.test/next"
      })
  void redirectsPreserveRelativeNonHttpAndMixedCaseHttpReferences(String destination)
      throws Exception {
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.routes().get("/redirect", (request, response) -> response.redirect(destination));

      try (var test = TestServer.start(app)) {
        var result = test.send(request -> request.path("/redirect"));

        assertEquals(302, result.statusCode());
        assertEquals(destination, result.headers().firstValue("Location").orElseThrow());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"HtTp:///missing-host", "HtTpS:///missing-host"})
  void malformedMixedCaseHttpRedirectDoesNotChangeStagedOutput(String destination)
      throws Exception {
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.routes().get("/redirect", (request, response) -> rejectRedirect(response, destination));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(request -> request.path("/redirect"), HttpResponse.BodyHandlers.ofString());

        assertEquals(202, result.statusCode());
        assertEquals("/original", result.headers().firstValue("Location").orElseThrow());
        assertEquals("kept", result.body());
      }
    }
  }

  private void rejectRedirect(Response response, String destination) {
    response.status(202).setHeader("Location", "/original").text("kept");

    assertThrows(IllegalArgumentException.class, () -> response.redirect(destination));
  }

  private HttpResponse<String> forwardedOrigin(
      TestServer test, ForwardedHeaders family, String protocol) throws Exception {
    return test.send(
        request -> {
          request.path("/origin");
          if (family == ForwardedHeaders.RFC7239) {
            request.header("Forwarded", "for=203.0.113.7;host=public.example;proto=" + protocol);
          } else {
            request
                .header("X-Forwarded-For", "203.0.113.7")
                .header("X-Forwarded-Host", "public.example")
                .header("X-Forwarded-Proto", protocol);
          }
        },
        HttpResponse.BodyHandlers.ofString());
  }
}
