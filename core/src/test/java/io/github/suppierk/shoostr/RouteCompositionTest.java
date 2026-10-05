package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class RouteCompositionTest {
  private final AtomicReference<Routes> retainedScope;
  private final AtomicReference<Request> retainedRequest;
  private Shoostr app;
  private TestServer test;

  RouteCompositionTest() {
    retainedScope = new AtomicReference<>();
    retainedRequest = new AtomicReference<>();
  }

  @BeforeEach
  void start() throws Exception {
    app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000));
    app.onRequestHeaders(
        (request, _) -> {
          assertTrue(request.pathParam("id").isEmpty());
          assertTrue(request.routePattern().isEmpty());
        });
    app.routes()
        .path(
            "/api",
            api -> {
              retainedScope.set(api);
              api.path("orders", RouteCompositionTest::orders);
              api.path(
                  "/accounts/{accountId}",
                  account ->
                      account.path(
                          "orders/{id}",
                          order ->
                              order.get(
                                  (req, res) -> {
                                    retainedRequest.set(req);
                                    res.text(
                                        req.pathParam("accountId").orElseThrow()
                                            + ":"
                                            + req.pathParam("id").orElseThrow());
                                  })));
            });
    app.routes().path("v2/orders/", RouteCompositionTest::orders);
    app.routes()
        .get(
            "/flat/{id}",
            (req, res) -> {
              assertTrue(req.pathParam("missing").isEmpty());
              res.text(req.pathParam("id").orElseThrow());
            });
    app.routes()
        .path(
            "/order",
            group -> {
              group.get(
                  "/{id}",
                  (req, res) -> res.text("parameter:" + req.pathParam("id").orElseThrow()));
              group.get("/latest", (_, res) -> res.text("literal"));
              group.post("/latest", (_, res) -> res.text("post"));
            });
    app.routes()
        .path(
            "/reverse-order",
            group -> {
              group.get("/latest", (_, res) -> res.text("literal"));
              group.get(
                  "/{id}",
                  (req, res) -> res.text("parameter:" + req.pathParam("id").orElseThrow()));
            });
    app.routes().get("/fallback/fixed/x", (_, res) -> res.text("x"));
    app.routes()
        .get("/fallback/{name}/y", (req, res) -> res.text(req.pathParam("name").orElseThrow()));
    app.routes().route(HttpMethods.PROPFIND, "/properties", (req, res) -> res.text(req.method()));
    app.routes().route("PROPFIND", "/string-properties", (req, res) -> res.text(req.method()));
    app.routes()
        .path(
            "/string",
            group -> {
              group.route("GET", (_, res) -> res.text("group"));
              group.route(
                  "POST", "/{id}", (req, res) -> res.text(req.pathParam("id").orElseThrow()));
              group.route(HttpMethods.DELETE, (_, res) -> res.status(204));
            });
    app.routes().path("/", root -> root.get((_, res) -> res.text("root")));
    app.routes()
        .get(
            "/unknown-param",
            (req, res) -> {
              assertEquals(Optional.empty(), req.pathParam("missing"));
              res.text("checked");
            });
    test = TestServer.start(app);
  }

  @AfterEach
  void close() throws Exception {
    if (test != null) {
      test.close();
    } else if (app != null) {
      app.close();
    }
  }

  @Test
  void composesAndReusesGroupsWithOptionalBoundarySlashes() throws Exception {
    assertEquals(
        "root",
        test.send(request -> request.path("/"), HttpResponse.BodyHandlers.ofString()).body());
    for (String prefix : new String[] {"/api/orders", "/v2/orders"}) {
      assertEquals(
          "list",
          test.send(request -> request.path(prefix), HttpResponse.BodyHandlers.ofString()).body());
      assertEquals(
          "create",
          test.send(
                  request -> request.path(prefix).method(HttpMethods.POST),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertEquals(
          "7",
          test.send(request -> request.path(prefix + "/7"), HttpResponse.BodyHandlers.ofString())
              .body());
      assertEquals(
          "update:7",
          test.send(
                  request -> request.path(prefix + "/7").method(HttpMethods.PATCH),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertEquals(
          204,
          test.send(request -> request.path(prefix + "/7").method(HttpMethods.DELETE))
              .statusCode());
      assertEquals(
          "slash",
          test.send(request -> request.path(prefix + "/"), HttpResponse.BodyHandlers.ofString())
              .body());
    }
  }

  @Test
  void exposesParametersFromParentAndChildScopesWithHandlerLifetime() throws Exception {
    assertEquals(
        "a:42",
        test.send(
                request -> request.path("/api/accounts/a/orders/42"),
                HttpResponse.BodyHandlers.ofString())
            .body());
    var closedRequest = retainedRequest.get();
    assertThrows(IllegalStateException.class, () -> closedRequest.pathParam("id"));
    assertEquals(
        "checked",
        test.send(request -> request.path("/unknown-param"), HttpResponse.BodyHandlers.ofString())
            .body());
  }

  @Test
  void groupedAndFlatRoutesExposeTheSameDecodedValues() throws Exception {
    for (String value : new String[] {"abc", "a+b", "a%2Bb", "hello%20world", "caf%C3%A9"}) {
      var flat =
          test.send(
              request -> request.path("/flat/" + value), HttpResponse.BodyHandlers.ofString());
      var grouped =
          test.send(
              request -> request.path("/api/orders/" + value),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, flat.statusCode());
      assertEquals(200, grouped.statusCode());
      assertEquals(flat.body(), grouped.body());
    }
    assertEquals(
        "hello world",
        test.send(
                request -> request.path("/flat/hello%20world"),
                HttpResponse.BodyHandlers.ofString())
            .body());
    assertEquals(
        "a+b",
        test.send(request -> request.path("/flat/a+b"), HttpResponse.BodyHandlers.ofString())
            .body());
    assertEquals(
        "café",
        test.send(request -> request.path("/flat/caf%C3%A9"), HttpResponse.BodyHandlers.ofString())
            .body());
  }

  @Test
  void retainsTransportRejectionOfAmbiguousPaths() throws Exception {
    for (var path : new String[] {"/flat/%252F", "/api/accounts//orders/42"}) {
      var reply =
          test.httpClient()
              .send(
                  HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
                      .timeout(Duration.ofSeconds(5))
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      assertEquals(400, reply.statusCode());
    }
  }

  @Test
  void prefersLiteralRoutesAndContinuesAfterAPathOrMethodMismatch() throws Exception {
    assertEquals(
        "literal",
        test.send(request -> request.path("/order/latest"), HttpResponse.BodyHandlers.ofString())
            .body());
    assertEquals(
        "literal",
        test.send(
                request -> request.path("/reverse-order/latest"),
                HttpResponse.BodyHandlers.ofString())
            .body());
    assertEquals(
        "post",
        test.send(
                request -> request.path("/order/latest").method(HttpMethods.POST),
                HttpResponse.BodyHandlers.ofString())
            .body());
    assertEquals(
        "fixed",
        test.send(
                request -> request.path("/fallback/fixed/y"), HttpResponse.BodyHandlers.ofString())
            .body());
  }

  @Test
  void distinguishesNotFoundFromMethodNotAllowedAndIncludesAllMatchingMethods() throws Exception {
    assertEquals(404, test.send(request -> request.path("/api/accounts/a/orders")).statusCode());
    var result = test.send(request -> request.path("/order/latest").method(HttpMethods.DELETE));
    assertEquals(405, result.statusCode());
    assertEquals("GET, POST", result.headers().firstValue("Allow").orElseThrow());
    var unknown =
        test.send(
            request -> request.path("/order/latest").method("CUSTOM"),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(405, unknown.statusCode());
    assertEquals("GET, POST", unknown.headers().firstValue("Allow").orElseThrow());
    assertEquals(
        "PROPFIND",
        test.send(
                request -> request.path("/properties").method(HttpMethods.PROPFIND),
                HttpResponse.BodyHandlers.ofString())
            .body());
  }

  @Test
  void freezesRetainedScopesAndRejectsEvenEmptyGroupsAfterStartup() {
    var scope = retainedScope.get();
    var routes = app.routes();
    assertThrows(IllegalStateException.class, () -> scope.get("later", (_, _) -> {}));
    assertThrows(IllegalStateException.class, () -> scope.path("later", _ -> {}));
    assertThrows(IllegalStateException.class, () -> routes.path("later", _ -> {}));
    assertThrows(IllegalStateException.class, () -> routes.route("GET", "/later", (_, _) -> {}));
  }

  @Test
  void exposesOneRootAndRoutesStringMethodsThroughGroupedAndFlatPaths() throws Exception {
    var rootRoutes = app.routes();
    assertSame(rootRoutes, app.routes());
    assertEquals(
        "PROPFIND",
        test.send(
                request -> request.path("/string-properties").method(HttpMethods.PROPFIND),
                HttpResponse.BodyHandlers.ofString())
            .body());
    assertEquals(
        "group",
        test.send(request -> request.path("/string"), HttpResponse.BodyHandlers.ofString()).body());
    assertEquals(
        "42",
        test.send(
                request -> request.path("/string/42").method(HttpMethods.POST),
                HttpResponse.BodyHandlers.ofString())
            .body());
    assertEquals(
        204, test.send(request -> request.path("/string").method(HttpMethods.DELETE)).statusCode());
    var rejected = test.send(request -> request.path("/string").method(HttpMethods.PUT));
    assertEquals(405, rejected.statusCode());
    assertEquals("DELETE, GET", rejected.headers().firstValue("Allow").orElseThrow());
  }

  @Test
  void rootCallbackRegistersFlatAndComposedRoutesAndReturnsApplication() throws Exception {
    try (var candidate = new Shoostr()) {
      assertSame(
          candidate,
          candidate.routes(
              routes -> {
                assertSame(candidate.routes(), routes);
                routes.get("/health", (_, response) -> response.text("ok"));
                routes.path(
                    "/orders",
                    orders -> {
                      orders.get(
                          "/{id}",
                          (request, response) ->
                              response.text("order:" + request.pathParam("id").orElseThrow()));
                      orders.get("/latest", (_, response) -> response.text("latest"));
                    });
              }));

      try (var fixture = TestServer.start(candidate)) {
        assertEquals(
            "ok",
            fixture
                .send(request -> request.path("/health"), HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            "latest",
            fixture
                .send(
                    request -> request.path("/orders/latest"), HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            "order:42",
            fixture
                .send(request -> request.path("/orders/42"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void rootCallbackPropagatesFailureAndKeepsEarlierRegistrations() throws Exception {
    try (var candidate = new Shoostr()) {
      var failure = new IllegalArgumentException("registration failed");
      assertSame(
          failure,
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  candidate.routes(
                      routes -> {
                        routes.get("/before", (_, response) -> response.text("before"));
                        throw failure;
                      })));

      assertSame(
          candidate,
          candidate.routes(
              routes -> routes.get("/after", (_, response) -> response.text("after"))));

      try (var fixture = TestServer.start(candidate)) {
        assertEquals(
            "before",
            fixture
                .send(request -> request.path("/before"), HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            "after",
            fixture
                .send(request -> request.path("/after"), HttpResponse.BodyHandlers.ofString())
                .body());
        assertThrows(IllegalStateException.class, () -> candidate.routes(_ -> {}));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"/}id", "/<id", "/>id"})
  void rejectsIsolatedUnsupportedSymbolsDuringRouteRegistration(String pattern) throws Exception {
    try (var candidate = new Shoostr()) {
      var routes = candidate.routes();
      assertThrows(IllegalArgumentException.class, () -> routes.get(pattern, (_, _) -> {}));
    }
  }

  @ParameterizedTest
  @EnumSource(HttpMethods.class)
  void treatsStringAndEnumRegistrationsAsTheSameMethod(HttpMethods method) throws Exception {
    try (var candidate = new Shoostr()) {
      var routes = candidate.routes();
      assertSame(routes, routes.route(method.value(), "/{id}", (_, _) -> {}));
      assertThrows(
          IllegalArgumentException.class, () -> routes.route(method, "/{name}", (_, _) -> {}));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "get", "Get", " GET", "GET ", "CUSTOM", "*", "BASELINE_CONTROL", "GET\r\n"})
  void rejectsUnrecognizedWireMethodsDuringRegistration(String method) throws Exception {
    try (var candidate = new Shoostr()) {
      var routes = candidate.routes();
      assertThrows(IllegalArgumentException.class, () -> routes.route(method, "/", (_, _) -> {}));
    }
  }

  @ParameterizedTest
  @NullSource
  void rejectsNullStringMethod(String method) throws Exception {
    try (var candidate = new Shoostr()) {
      var routes = candidate.routes();
      assertThrows(NullPointerException.class, () -> routes.route(method, "/", (_, _) -> {}));
    }
  }

  @Test
  void validatesComposedParametersAndEquivalentRouteShapes() throws Exception {
    try (var candidate = new Shoostr()) {
      var routes = candidate.routes();
      Consumer<Routes> duplicateParameter = group -> group.get("/{id}", (_, _) -> {});
      assertThrows(IllegalArgumentException.class, () -> routes.path("/{id}", duplicateParameter));
      routes.path("/api", group -> group.get("/{id}", (_, _) -> {}));
      assertThrows(IllegalArgumentException.class, () -> routes.get("/api/{name}", (_, _) -> {}));
      routes.post("/api/{name}", (_, _) -> {});
      candidate
          .routes()
          .path("/api", _ -> assertThrows(IllegalStateException.class, candidate::start));
      candidate.routes().get("/after", (_, _) -> {});
    }
  }

  @Test
  void rejectsPathParameterAccessFromAnotherThread() throws Exception {
    try (var candidate = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000));
        var candidateClient = HttpClient.newHttpClient()) {
      candidate
          .routes()
          .get(
              "/{id}",
              (req, res) -> {
                var failure = new AtomicReference<Throwable>();
                var thread =
                    Thread.startVirtualThread(
                        () -> {
                          try {
                            req.pathParam("id");
                          } catch (Throwable error) {
                            failure.set(error);
                          }
                        });
                thread.join();
                assertInstanceOf(IllegalStateException.class, failure.get());
                res.text("checked");
              });
      candidate.start();
      var result =
          candidateClient.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + candidate.port() + "/42"))
                  .timeout(Duration.ofSeconds(5))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals("checked", result.body());
    }
  }

  private static void orders(Routes orders) {
    orders.get((_, res) -> res.text("list"));
    orders.post((_, res) -> res.text("create"));
    orders.get("/", (_, res) -> res.text("slash"));
    orders.path(
        "/{id}",
        order -> {
          order.get((req, res) -> res.text(req.pathParam("id").orElseThrow()));
          order.patch((req, res) -> res.text("update:" + req.pathParam("id").orElseThrow()));
          order.delete((_, res) -> res.status(204));
        });
  }
}
