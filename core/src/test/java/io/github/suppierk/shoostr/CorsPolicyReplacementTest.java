package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CorsPolicyReplacementTest {
  @ParameterizedTest
  @ValueSource(strings = {"narrow", "empty-origins", "empty-methods", "wildcard"})
  void replacesEveryPermissionWithoutChangingTheParentOrSibling(String mode) throws Exception {
    var parent =
        CorsPolicy.builder()
            .origins(Set.of("https://parent.example"))
            .methods(Set.of(HttpMethods.GET, HttpMethods.POST))
            .allowedHeaders(Set.of(HttpHeaders.of("X-Parent")))
            .exposedHeaders(Set.of(HttpHeaders.ETAG))
            .credentials(true)
            .maxAgeSeconds(600)
            .build();
    var wildcard =
        CorsPolicy.builder()
            .origins(Set.of("*", "https://extra.example"))
            .allMethods()
            .allowedHeaders(Set.of(HttpHeaders.of("*"), HttpHeaders.CONTENT_TYPE))
            .exposedHeaders(Set.of(HttpHeaders.of("*"), HttpHeaders.ETAG))
            .maxAgeSeconds(0)
            .build();
    var replacement =
        switch (mode) {
          case "wildcard" -> wildcard;
          case "empty-origins" -> CorsPolicy.builder().origins(Set.of()).build();
          case "empty-methods" ->
              CorsPolicy.builder()
                  .origins(Set.of("https://child.example"))
                  .methods(Set.of())
                  .build();
          default ->
              CorsPolicy.builder()
                  .origins(Set.of("https://child.example"))
                  .methods(Set.of(HttpMethods.GET))
                  .allowedHeaders(Set.of(HttpHeaders.of("X-Child")))
                  .exposedHeaders(Set.of(HttpHeaders.of("X-Child")))
                  .credentials(false)
                  .maxAgeSeconds(0)
                  .build();
        };
    var cors = new Cors(parent);
    var retained = new AtomicReference<Cors.Configuration>();

    try (var app = new Shoostr()) {
      app.extensions(cors).configureExtensionsDefaults(e -> e.with(cors));
      app.routes()
          .path(
              "/leaf",
              routes ->
                  routes
                      .get("/value", (request, response) -> response.text("read"))
                      .post("/value", (request, response) -> response.text("written")),
              e -> {
                var configuration = e.with(cors).policy(wildcard).policy(replacement);
                retained.set(configuration);
              })
          .get("/sibling", (request, response) -> response.text("sibling"));
      var configuration = retained.get();
      assertThrows(IllegalStateException.class, () -> configuration.policy(parent));

      try (var test = TestServer.start(app)) {
        var origin = "wildcard".equals(mode) ? "https://unlisted.example" : "https://child.example";
        var result = preflight(test, "/leaf/value", origin, "GET", "X-Child");
        if (mode.startsWith("empty")) {
          assertEquals(403, result.statusCode());
          assertTrue(result.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
          assertEquals(
              403, test.send(r -> r.path("/leaf/value").header("Origin", origin)).statusCode());
        } else {
          assertEquals(204, result.statusCode());
          assertEquals("0", result.headers().firstValue("Access-Control-Max-Age").orElseThrow());
          assertFalse(result.headers().firstValue("Access-Control-Allow-Credentials").isPresent());
          assertEquals(
              "x-child", result.headers().firstValue("Access-Control-Allow-Headers").orElseThrow());
          var actual = test.send(r -> r.path("/leaf/value").header("Origin", origin));
          assertEquals(200, actual.statusCode());
          assertEquals(
              "wildcard".equals(mode) ? "*" : "X-Child",
              actual.headers().firstValue("Access-Control-Expose-Headers").orElseThrow());
          if ("wildcard".equals(mode)) {
            var any = preflight(test, "/leaf/value", origin, "POST", "X-Arbitrary, Authorization");
            assertEquals(204, any.statusCode());
            assertEquals(
                "POST", any.headers().firstValue("Access-Control-Allow-Methods").orElseThrow());
            assertEquals(
                "authorization, x-arbitrary",
                any.headers().firstValue("Access-Control-Allow-Headers").orElseThrow());
            assertEquals(
                "*", any.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
          } else {
            assertEquals(
                403, preflight(test, "/leaf/value", origin, "POST", "X-Child").statusCode());
            assertEquals(
                403, preflight(test, "/leaf/value", origin, "GET", "X-Parent").statusCode());
            assertEquals(
                403,
                test.send(r -> r.path("/leaf/value").header("Origin", "https://parent.example"))
                    .statusCode());
          }
        }

        var sibling = test.send(r -> r.path("/sibling").header("Origin", "https://parent.example"));
        assertEquals(200, sibling.statusCode());
        assertEquals(
            "true", sibling.headers().firstValue("Access-Control-Allow-Credentials").orElseThrow());
        assertEquals(
            "ETag", sibling.headers().firstValue("Access-Control-Expose-Headers").orElseThrow());
        assertEquals(
            403,
            test.send(r -> r.path("/sibling").header("Origin", "https://child.example"))
                .statusCode());
      }
    }

    assertEquals(Set.of("*"), wildcard.origins());
    assertEquals(Set.of(HttpHeaders.of("*")), wildcard.allowedHeaders());
    assertEquals(Set.of(HttpHeaders.of("*")), wildcard.exposedHeaders());
  }

  @Test
  void reflectsCredentialedWildcardMethodsAndRequestHeadersForARegisteredTarget() throws Exception {
    var cors =
        new Cors(
            CorsPolicy.builder()
                .origins(Set.of("https://client.example"))
                .credentials(true)
                .allMethods()
                .allowedHeaders(Set.of(HttpHeaders.of("*")))
                .build());

    try (var app = new Shoostr()) {
      app.extensions(cors).configureExtensionsDefaults(e -> e.with(cors));
      app.routes().post("/value", (request, response) -> response.text("written"));

      try (var test = TestServer.start(app)) {
        var result =
            preflight(test, "/value", "https://client.example", "POST", "Authorization, X-Client");
        assertEquals(204, result.statusCode());
        assertEquals(
            "https://client.example",
            result.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertEquals(
            "true", result.headers().firstValue("Access-Control-Allow-Credentials").orElseThrow());
        assertEquals(
            "POST", result.headers().firstValue("Access-Control-Allow-Methods").orElseThrow());
        assertEquals(
            "authorization, x-client",
            result.headers().firstValue("Access-Control-Allow-Headers").orElseThrow());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"origin", "exposed"})
  void rejectsCredentialedWildcardPoliciesAtConstruction(String field) {
    var builder = CorsPolicy.builder().origins(Set.of("https://client.example")).credentials(true);
    if ("origin".equals(field)) {
      builder.origins(Set.of("*"));
    } else {
      builder.exposedHeaders(Set.of(HttpHeaders.of("*")));
    }

    assertThrows(IllegalArgumentException.class, builder::build);
  }

  private static HttpResponse<String> preflight(
      TestServer test, String path, String origin, String method, String headers) throws Exception {
    return test.send(
        r ->
            r.path(path)
                .method("OPTIONS")
                .header("Origin", origin)
                .header("Access-Control-Request-Method", method)
                .header("Access-Control-Request-Headers", headers),
        HttpResponse.BodyHandlers.ofString());
  }
}
