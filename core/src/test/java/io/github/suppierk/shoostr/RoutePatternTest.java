package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.http.HttpMethods;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RoutePatternTest {
  private static final Handler HANDLER = (request, response) -> {};

  @Test
  void prefersLiteralSegmentsRegardlessOfRegistrationOrderForEachMethod() {
    var tree =
        compile(
            "POST /orders/latest",
            "GET /orders/{id}",
            "GET /orders/latest",
            "POST /orders/{orderId}");
    assertEquals(
        "/orders/latest",
        Objects.requireNonNull(tree.match("/orders/latest", HttpMethods.GET)).routePattern());
    assertEquals(
        "/orders/latest",
        Objects.requireNonNull(tree.match("/orders/latest", HttpMethods.POST)).routePattern());
    assertEquals(
        "/orders/{orderId}",
        Objects.requireNonNull(tree.match("/orders/123", HttpMethods.POST)).routePattern());
    assertEquals(
        "123",
        Objects.requireNonNull(tree.match("/orders/123", HttpMethods.POST))
            .parameter("/orders/123", "orderId"));
    assertEquals(Set.of(HttpMethods.GET, HttpMethods.POST), tree.allowedMethods("/orders/latest"));
  }

  @Test
  void prefersLiteralSegmentsAtEachDepthAndFallsBackAfterMethodOrSuffixMismatch() {
    var tree =
        compile(
            "GET /a/{name}/b",
            "GET /a/fixed/{id}",
            "POST /a/{name}/b",
            "GET /a/fixed/other/end",
            "GET /a/{name}/b/end");

    assertEquals(
        "/a/fixed/{id}",
        Objects.requireNonNull(tree.match("/a/fixed/b", HttpMethods.GET)).routePattern());
    assertEquals(
        "/a/{name}/b",
        Objects.requireNonNull(tree.match("/a/fixed/b", HttpMethods.POST)).routePattern());
    assertEquals(
        "/a/fixed/{id}",
        Objects.requireNonNull(tree.match("/a/fixed/other", HttpMethods.GET)).routePattern());
    assertEquals(
        "/a/fixed/other/end",
        Objects.requireNonNull(tree.match("/a/fixed/other/end", HttpMethods.GET)).routePattern());
    assertEquals(
        "/a/{name}/b/end",
        Objects.requireNonNull(tree.match("/a/fixed/b/end", HttpMethods.GET)).routePattern());
    assertEquals(Set.of(HttpMethods.GET, HttpMethods.POST), tree.allowedMethods("/a/fixed/b"));
  }

  @Test
  void fallsBackAfterSuffixOrMethodMismatchWithoutLeakingParameterNames() {
    var tree = compile("GET /a/fixed/x", "GET /a/{name}/y", "POST /a/{other}/y");
    var endpoint = Objects.requireNonNull(tree.match("/a/fixed/y", HttpMethods.GET));
    assertEquals("/a/{name}/y", endpoint.routePattern());
    assertEquals("fixed", endpoint.parameter("/a/fixed/y", "name"));
    assertThrows(IllegalArgumentException.class, () -> endpoint.parameter("/a/fixed/y", "other"));
    assertEquals(
        "/a/{other}/y",
        Objects.requireNonNull(tree.match("/a/fixed/y", HttpMethods.POST)).routePattern());
  }

  @Test
  void distinguishesEmptySegmentsTrailingSlashAndMethodFailures() {
    var tree = compile("GET /orders/{id}", "POST /orders/{id}/");
    assertNull(tree.match("/orders/", HttpMethods.GET));
    assertNull(tree.match("/orders//", HttpMethods.POST));
    assertNull(tree.match("/orders/1/", HttpMethods.GET));
    assertNull(tree.match("/orders/1/extra", HttpMethods.GET));
    assertEquals(Set.of(HttpMethods.POST), tree.allowedMethods("/orders/1/"));
    assertEquals(Set.of(HttpMethods.GET), tree.allowedMethods("/orders/1"));
    assertEquals(Set.of(), tree.allowedMethods("/unknown"));
  }

  @Test
  void decodesOnlyTheRequestedParameterOnceAndPreservesPlus() {
    var endpoint =
        Objects.requireNonNull(
            compile("GET /accounts/{accountId}/orders/{id}")
                .match("/accounts/a+b/orders/%252F", HttpMethods.GET));
    assertEquals("a+b", endpoint.parameter("/accounts/a+b/orders/%252F", "accountId"));
    assertEquals("%2F", endpoint.parameter("/accounts/a+b/orders/%252F", "id"));
    assertEquals("café +", endpoint.parameter("/accounts/x/orders/caf%C3%A9%20+", "id"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/a", "/a/very/long/prefix", "/é", "/😀"})
  void extractsParametersCorrectlyWithVariableLiteralPrefixes(String prefix) {
    var tree = compile("GET " + prefix + "/{first}/fixed/{second}/");
    String path = prefix + "/a%20b/fixed/c+d/";
    var endpoint = Objects.requireNonNull(tree.match(path, HttpMethods.GET));
    assertEquals("a b", endpoint.parameter(path, "first"));
    assertEquals("c+d", endpoint.parameter(path, "second"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "orders",
        "/{id}/{id}",
        "/{}",
        "/{bad name}",
        "/{id}.json",
        "/{id",
        "/id}",
        "/<id>",
        "/files/*",
        "/path?x=1",
        "/path#fragment"
      })
  void rejectsUnsupportedOrAmbiguousSyntax(String pattern) {
    assertThrows(
        IllegalArgumentException.class,
        () -> RadixRoutes.endpoint(HttpMethods.GET, pattern, HANDLER));
  }

  @Test
  void rejectsEquivalentPatternsForTheSameMethod() {
    assertThrows(IllegalArgumentException.class, () -> compile("GET /{id}", "GET /{name}"));
  }

  @Test
  void preservesEveryRegisteredMethodWhenCollectingOverlappingMatches() {
    var endpoints = new ArrayList<RadixRoutes.Endpoint>();
    for (var method : HttpMethods.values()) {
      endpoints.add(RadixRoutes.endpoint(method, "/a/{id}", HANDLER));
    }
    var tree = RadixRoutes.from(endpoints);
    for (var method : HttpMethods.values()) {
      assertEquals(method, Objects.requireNonNull(tree.match("/a/42", method)).method());
    }
    assertEquals(Set.of(HttpMethods.values()), tree.allowedMethods("/a/42"));
    assertEquals(Set.of(), tree.allowedMethods("/a/42/missing"));
    assertNull(tree.match("/a/42", null));
    var methods = tree.allowedMethods("/a/42");
    methods.clear();
    assertEquals(Set.of(HttpMethods.values()), tree.allowedMethods("/a/42"));
  }

  @Test
  void preservesCollectedMethodsWhenAlternativeBranchesDoNotComplete() {
    var tree =
        compile(
            "GET /a/fixed",
            "POST /a/{id}",
            "PUT /a/{id}/more",
            "DELETE /a/fixed/more",
            "HEAD /a/{id}/more/end");
    assertEquals(Set.of(HttpMethods.GET, HttpMethods.POST), tree.allowedMethods("/a/fixed"));
    assertEquals(Set.of(HttpMethods.PUT, HttpMethods.DELETE), tree.allowedMethods("/a/fixed/more"));
    assertEquals(Set.of(HttpMethods.HEAD), tree.allowedMethods("/a/fixed/more/end"));
    assertEquals(Set.of(HttpMethods.POST), tree.allowedMethods("/a/fi"));
    for (String missing : List.of("/a/", "/a/fixed/mo", "/a/fixed/more/")) {
      assertEquals(Set.of(), tree.allowedMethods(missing));
    }
  }

  @Test
  void agreesWithALiteralFirstSegmentMatcherAcrossOverlappingPatterns() {
    var specs = new ArrayList<String>();
    for (int a = 0; a < 5; a++) {
      for (int b = 0; b < 5; b++) {
        for (HttpMethods method : List.of(HttpMethods.GET, HttpMethods.POST)) {
          specs.add(method + " /" + (a == 0 ? "{a}" : a) + "/" + (b == 0 ? "{b}" : b));
        }
      }
    }
    for (int seed = 0; seed < 5; seed++) {
      Collections.shuffle(specs, new Random(seed));
      var endpoints = registrations(specs.toArray(String[]::new));
      var tree = RadixRoutes.from(endpoints);
      for (int a = 0; a < 7; a++) {
        for (int b = 0; b < 7; b++) {
          String path = "/" + a + "/" + b;
          var allowed = new HashSet<HttpMethods>();
          for (HttpMethods method :
              List.of(HttpMethods.GET, HttpMethods.POST, HttpMethods.DELETE)) {
            RadixRoutes.Endpoint expected = null;
            for (var endpoint : endpoints) {
              if (matches(endpoint.pattern(), path)) {
                allowed.add(endpoint.method());
                if (method == endpoint.method()
                    && (expected == null || moreSpecific(endpoint.pattern(), expected.pattern()))) {
                  expected = endpoint;
                }
              }
            }
            assertSame(expected, tree.match(path, method), method + " " + path);
          }
          assertEquals(allowed, tree.allowedMethods(path));
        }
      }
    }
  }

  private static RadixRoutes compile(String... specs) {
    return RadixRoutes.from(registrations(specs));
  }

  private static List<RadixRoutes.Endpoint> registrations(String... specs) {
    var endpoints = new ArrayList<RadixRoutes.Endpoint>();
    for (String spec : specs) {
      var parts = spec.split(" ", 2);
      endpoints.add(
          RadixRoutes.endpoint(HttpMethods.httpMethod(parts[0]).orElseThrow(), parts[1], HANDLER));
    }
    return endpoints;
  }

  private static boolean matches(String pattern, String path) {
    var patternSegments = pattern.split("/", -1);
    var pathSegments = path.split("/", -1);
    if (patternSegments.length != pathSegments.length) {
      return false;
    }

    for (int i = 0; i < patternSegments.length; i++) {
      if ("{}".equals(patternSegments[i])) {
        if (pathSegments[i].isEmpty()) {
          return false;
        }
      } else if (!patternSegments[i].equals(pathSegments[i])) {
        return false;
      }
    }
    return true;
  }

  private static boolean moreSpecific(String candidate, String current) {
    var candidateSegments = candidate.split("/", -1);
    var currentSegments = current.split("/", -1);
    for (int index = 0; index < candidateSegments.length; index++) {
      boolean candidateParameter = "{}".equals(candidateSegments[index]);
      boolean currentParameter = "{}".equals(currentSegments[index]);
      if (candidateParameter != currentParameter) {
        return !candidateParameter;
      }
    }

    return false;
  }
}
