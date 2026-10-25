package io.github.suppierk.shoostr.example.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.ForwardedHeaders;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.InetAddress;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

@Timeout(10)
class TrustedProxyExampleTest {
  @ParameterizedTest
  @EnumSource(ForwardedHeaders.class)
  void readsSelectedForwardingMetadataWithoutClaimingThatTheTransportUsesTls(
      ForwardedHeaders family) throws Exception {
    try (var test =
        TestServer.start(
            TrustedProxyExample.application(
                Set.of(InetAddress.getByAddress(new byte[] {127, 0, 0, 1})), family))) {
      var response =
          test.send(
              request -> {
                request.path("/metadata?q=one");
                if (family == ForwardedHeaders.RFC7239) {
                  request.header(
                      "Forwarded", "for=203.0.113.7;proto=https;host=\"api.example:8443\"");
                } else {
                  request
                      .header("X-Forwarded-For", "203.0.113.7")
                      .header("X-Forwarded-Proto", "https")
                      .header("X-Forwarded-Host", "api.example:8443");
                }
              },
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals(
          """
          client=203.0.113.7
          transportSecure=false
          effectiveSecure=true
          url=https://api.example:8443/metadata?q=one
          """
              .stripTrailing(),
          response.body());
    }
  }

  @ParameterizedTest
  @CsvSource({"false, 200", "true, 400"})
  void inspectsForwardingSyntaxOnlyAfterThePhysicalPeerIsTrusted(boolean trusted, int status)
      throws Exception {
    Set<InetAddress> peers =
        trusted ? Set.of(InetAddress.getByAddress(new byte[] {127, 0, 0, 1})) : Set.of();

    try (var test =
        TestServer.start(TrustedProxyExample.application(peers, ForwardedHeaders.RFC7239))) {
      var response =
          test.send(
              request -> request.path("/metadata").header("Forwarded", "for=not-an-ip;proto=https"),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(status, response.statusCode());
      if (!trusted) {
        assertTrue(response.body().contains("effectiveSecure=false\n"));
        assertTrue(response.body().endsWith("url=" + test.baseUri().resolve("/metadata")));
      }
    }
  }

  @ParameterizedTest
  @EnumSource(ForwardedHeaders.class)
  void ignoresTheUnselectedForwardingFamilyRatherThanMergingAssertions(ForwardedHeaders family)
      throws Exception {
    try (var test =
        TestServer.start(
            TrustedProxyExample.application(
                Set.of(InetAddress.getByAddress(new byte[] {127, 0, 0, 1})), family))) {
      var response =
          test.send(
              request ->
                  request
                      .path("/metadata")
                      .header("Forwarded", "for=203.0.113.7;proto=https;host=\"api.example:8443\"")
                      .header("X-Forwarded-For", "198.51.100.8")
                      .header("X-Forwarded-Proto", "http")
                      .header("X-Forwarded-Host", "other.example:8080"),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      var client = family == ForwardedHeaders.RFC7239 ? "203.0.113.7" : "198.51.100.8";
      var url =
          family == ForwardedHeaders.RFC7239
              ? "https://api.example:8443/metadata"
              : "http://other.example:8080/metadata";
      assertTrue(response.body().startsWith("client=" + client + "\n"));
      assertTrue(response.body().endsWith("url=" + url));
    }
  }

  @Test
  void stopsAtTheFirstUntrustedNodeInsteadOfAcceptingTheSpoofedLeftmostEntry() throws Exception {
    try (var test =
        TestServer.start(
            TrustedProxyExample.application(
                Set.of(
                    InetAddress.getByAddress(new byte[] {127, 0, 0, 1}),
                    InetAddress.getByName("192.0.2.10")),
                ForwardedHeaders.RFC7239))) {
      var response =
          test.send(
              request ->
                  request
                      .path("/metadata")
                      .header(
                          "Forwarded",
                          String.join(
                              ", ",
                              "for=198.51.100.200;proto=http;host=evil.example",
                              "for=203.0.113.7;proto=https;host=\"api.example:8443\"",
                              "for=192.0.2.10;proto=https;host=internal.example")),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertTrue(response.body().startsWith("client=203.0.113.7\n"));
      assertTrue(response.body().endsWith("url=https://api.example:8443/metadata"));
    }
  }

  @Test
  void rejectsLegacyForwardingListsThatDoNotDescribeTheSameNumberOfHops() throws Exception {
    try (var test =
        TestServer.start(
            TrustedProxyExample.application(
                Set.of(InetAddress.getByAddress(new byte[] {127, 0, 0, 1})),
                ForwardedHeaders.X_FORWARDED))) {
      var response =
          test.send(
              request ->
                  request
                      .path("/metadata")
                      .header("X-Forwarded-For", "203.0.113.7, 192.0.2.10")
                      .header("X-Forwarded-Proto", "https"));
      assertEquals(400, response.statusCode());
    }
  }

  @Test
  void keepsItsPeerSnapshotWhenTheCallerLaterChangesTheInputSet() throws Exception {
    var peers = new HashSet<InetAddress>();
    var app = TrustedProxyExample.application(peers, ForwardedHeaders.RFC7239);
    peers.add(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));

    try (var test = TestServer.start(app)) {
      var response =
          test.send(
              request ->
                  request.path("/metadata").header("Forwarded", "for=203.0.113.7;proto=https"),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertFalse(response.body().contains("203.0.113.7"));
      assertTrue(response.body().contains("effectiveSecure=false\n"));
    }
  }
}
