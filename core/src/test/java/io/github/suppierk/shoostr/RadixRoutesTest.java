package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.http.HttpMethods;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RadixRoutesTest {
  private static final Handler GET = (request, response) -> {};
  private static final Handler POST = (request, response) -> {};

  @Test
  void distinguishesTerminalPathsFromPrefixesAndMissingMethods() {
    var tree =
        compile(
            Map.of(
                "/", Map.of(HttpMethods.GET, GET),
                "/orders", Map.of(HttpMethods.GET, GET, HttpMethods.POST, POST),
                "/orders/recent", Map.of(HttpMethods.GET, GET),
                "/other", Map.of(HttpMethods.POST, POST)));
    assertSame(GET, handler(tree, "/", HttpMethods.GET));
    assertSame(GET, handler(tree, "/orders", HttpMethods.GET));
    assertSame(POST, handler(tree, "/orders", HttpMethods.POST));
    assertSame(GET, handler(tree, "/orders/recent", HttpMethods.GET));
    assertNull(handler(tree, "/orders", HttpMethods.DELETE));
    for (String missing :
        List.of("", "/ord", "/orders/", "/orders/recent/x", "/orders-old", "/oth")) {
      assertNull(tree.match(missing, HttpMethods.GET), missing);
    }
  }

  @Test
  void preservesCaseEncodingAndUnicodeWithoutNormalization() {
    var paths = List.of("/A", "/a", "/a%2Fb", "/a%2fb", "/a/b", "/é", "/😀", "/😁");
    var routes = new HashMap<String, Map<HttpMethods, Handler>>();
    for (String captured : paths) {
      routes.put(captured, Map.of(HttpMethods.GET, (request, response) -> response.text(captured)));
    }
    var tree = compile(routes);
    for (String path : paths) {
      assertSame(
          Objects.requireNonNull(routes.get(path)).get(HttpMethods.GET),
          handler(tree, path, HttpMethods.GET));
    }
    assertNull(tree.match("/a%2FB", HttpMethods.GET));
    assertNull(tree.match("/É", HttpMethods.GET));
    assertNull(tree.match("/a?x=1", HttpMethods.GET));
  }

  @Test
  void ownsAnImmutableSnapshotOfRegistration() {
    var methods = new EnumMap<>(Map.of(HttpMethods.GET, GET));
    var routes = new HashMap<String, Map<HttpMethods, Handler>>();
    routes.put("/orders", methods);
    var tree = compile(routes);
    methods.clear();
    routes.clear();
    assertSame(GET, handler(tree, "/orders", HttpMethods.GET));
    var parameters = Objects.requireNonNull(tree.match("/orders", HttpMethods.GET)).parameters();
    assertThrows(UnsupportedOperationException.class, () -> parameters.put("id", 1));
  }

  @Test
  void handlesEmptyAndSingleRouteTables() {
    assertNull(compile(Map.of()).match("/", HttpMethods.GET));
    var tree = compile(Map.of("/only", Map.of(HttpMethods.GET, GET)));
    assertSame(GET, handler(tree, "/only", HttpMethods.GET));
    assertNull(tree.match("/on", HttpMethods.GET));
    assertNull(tree.match("/only/", HttpMethods.GET));
  }

  @Test
  void excludesMethodsAtUnregisteredInternalPrefixes() {
    var tree =
        compile(
            Map.of("/a/x", Map.of(HttpMethods.GET, GET), "/a/y", Map.of(HttpMethods.POST, POST)));
    assertNull(tree.match("/a/", HttpMethods.GET));
    assertEquals(Set.of(), tree.allowedMethods("/a/"));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 4, 5, 16, 64})
  void selectsExactChildrenAcrossSmallAndLargeFanOuts(int count) {
    var routes = new HashMap<String, Map<HttpMethods, Handler>>();
    for (int i = 0; i < count; i++) {
      String path = "/prefix/" + (char) (0x100 + i);
      routes.put(path, Map.of(HttpMethods.GET, GET));
    }
    var tree = compile(routes);
    for (String path : routes.keySet()) {
      assertSame(GET, handler(tree, path, HttpMethods.GET));
      assertNull(tree.match(path + "/missing", HttpMethods.GET));
    }
    assertNull(tree.match("/prefix/" + (char) 0xff, HttpMethods.GET));
    assertNull(tree.match("/prefix/" + (char) (0x100 + count), HttpMethods.GET));
  }

  @Test
  void agreesWithMapAcrossShuffledRegistrationAndSharedPrefixes() {
    var paths = new ArrayList<String>();
    for (int i = 0; i < 5000; i++) {
      paths.add("/api/" + (i % 31) + "/orders/" + i);
    }
    for (int seed = 0; seed < 3; seed++) {
      Collections.shuffle(paths, new Random(seed));
      var routes = new LinkedHashMap<String, Map<HttpMethods, Handler>>();
      for (String path : paths) {
        routes.put(path, Map.of(HttpMethods.GET, GET, HttpMethods.POST, POST));
      }
      var tree = compile(routes);
      for (String path : paths) {
        assertSame(
            Objects.requireNonNull(routes.get(path)).get(HttpMethods.GET),
            handler(tree, path, HttpMethods.GET));
        assertNull(tree.match(path + "/missing", HttpMethods.GET));
        String prefix = path.substring(0, path.length() - 1);
        assertSame(
            routes.containsKey(prefix)
                ? Objects.requireNonNull(routes.get(prefix)).get(HttpMethods.GET)
                : null,
            handler(tree, prefix, HttpMethods.GET));
      }
    }
  }

  private static RadixRoutes compile(Map<String, Map<HttpMethods, Handler>> routes) {
    var endpoints = new ArrayList<RadixRoutes.Endpoint>();
    routes.forEach(
        (path, methods) ->
            methods.forEach(
                (method, handler) -> endpoints.add(RadixRoutes.endpoint(method, path, handler))));
    return RadixRoutes.from(endpoints);
  }

  @SuppressWarnings("NullAway") // A missing route intentionally yields null for assertions.
  private static Handler handler(RadixRoutes tree, String path, HttpMethods method) {
    var endpoint = tree.match(path, method);
    return endpoint == null ? null : endpoint.handler();
  }
}
