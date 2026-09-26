package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.HttpMethods;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
  private HttpClient client;
  private String base;

  RouteCompositionTest() {
    retainedScope = new AtomicReference<>();
    retainedRequest = new AtomicReference<>();
  }

  @BeforeEach
  void start() throws Exception {
    app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000));
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
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
                                        req.pathParam("accountId") + ":" + req.pathParam("id"));
                                  })));
            });
    app.routes().path("v2/orders/", RouteCompositionTest::orders);
    app.routes().get("/flat/{id}", (req, res) -> res.text(req.pathParam("id")));
    app.routes()
        .path(
            "/order",
            group -> {
              group.get("/{id}", (req, res) -> res.text("parameter:" + req.pathParam("id")));
              group.get("/latest", (req, res) -> res.text("literal"));
              group.post("/latest", (req, res) -> res.text("post"));
            });
    app.routes()
        .path(
            "/reverse-order",
            group -> {
              group.get("/latest", (req, res) -> res.text("literal"));
              group.get("/{id}", (req, res) -> res.text("parameter:" + req.pathParam("id")));
            });
    app.routes().get("/fallback/fixed/x", (req, res) -> res.text("x"));
    app.routes().get("/fallback/{name}/y", (req, res) -> res.text(req.pathParam("name")));
    app.routes().route(HttpMethods.PROPFIND, "/properties", (req, res) -> res.text(req.method()));
    app.routes().route("PROPFIND", "/string-properties", (req, res) -> res.text(req.method()));
    app.routes()
        .path(
            "/string",
            group -> {
              group.route("GET", (req, res) -> res.text("group"));
              group.route("POST", "/{id}", (req, res) -> res.text(req.pathParam("id")));
              group.route(HttpMethods.DELETE, (req, res) -> res.status(204));
            });
    app.routes().path("/", root -> root.get((req, res) -> res.text("root")));
    app.routes()
        .get(
            "/unknown-param",
            (req, res) -> {
              assertThrows(IllegalArgumentException.class, () -> req.pathParam("missing"));
              res.text("checked");
            });
    app.start();
    base = "http://127.0.0.1:" + app.port();
  }

  @AfterEach
  void close() throws Exception {
    try {
      if (client != null) {
        client.close();
      }
    } finally {
      if (app != null) {
        app.close();
      }
    }
  }

  @Test
  void composesAndReusesGroupsWithOptionalBoundarySlashes() throws Exception {
    assertEquals("root", send("/", HttpMethods.GET).body());
    for (String prefix : new String[] {"/api/orders", "/v2/orders"}) {
      assertEquals("list", send(prefix, HttpMethods.GET).body());
      assertEquals("create", send(prefix, HttpMethods.POST).body());
      assertEquals("7", send(prefix + "/7", HttpMethods.GET).body());
      assertEquals("update:7", send(prefix + "/7", HttpMethods.PATCH).body());
      assertEquals(204, send(prefix + "/7", HttpMethods.DELETE).statusCode());
      assertEquals("slash", send(prefix + "/", HttpMethods.GET).body());
    }
  }

  @Test
  void exposesParametersFromParentAndChildScopesWithHandlerLifetime() throws Exception {
    assertEquals("a:42", send("/api/accounts/a/orders/42", HttpMethods.GET).body());
    var closedRequest = retainedRequest.get();
    assertThrows(IllegalStateException.class, () -> closedRequest.pathParam("id"));
    assertEquals("checked", send("/unknown-param", HttpMethods.GET).body());
  }

  @Test
  void groupedAndFlatRoutesExposeTheSameDecodedValues() throws Exception {
    for (String value : new String[] {"abc", "a+b", "a%2Bb", "hello%20world", "caf%C3%A9"}) {
      var flat = send("/flat/" + value, HttpMethods.GET);
      var grouped = send("/api/orders/" + value, HttpMethods.GET);
      assertEquals(200, flat.statusCode());
      assertEquals(200, grouped.statusCode());
      assertEquals(flat.body(), grouped.body());
    }
    assertEquals("hello world", send("/flat/hello%20world", HttpMethods.GET).body());
    assertEquals("a+b", send("/flat/a+b", HttpMethods.GET).body());
    assertEquals("café", send("/flat/caf%C3%A9", HttpMethods.GET).body());
  }

  @Test
  void retainsTransportRejectionOfAmbiguousPaths() throws Exception {
    assertEquals(400, send("/flat/%252F", HttpMethods.GET).statusCode());
    assertEquals(400, send("/api/accounts//orders/42", HttpMethods.GET).statusCode());
  }

  @Test
  void prefersLiteralRoutesAndContinuesAfterAPathOrMethodMismatch() throws Exception {
    assertEquals("literal", send("/order/latest", HttpMethods.GET).body());
    assertEquals("literal", send("/reverse-order/latest", HttpMethods.GET).body());
    assertEquals("post", send("/order/latest", HttpMethods.POST).body());
    assertEquals("fixed", send("/fallback/fixed/y", HttpMethods.GET).body());
  }

  @Test
  void distinguishesNotFoundFromMethodNotAllowedAndIncludesAllMatchingMethods() throws Exception {
    assertEquals(404, send("/api/accounts/a/orders", HttpMethods.GET).statusCode());
    var result = send("/order/latest", HttpMethods.DELETE);
    assertEquals(405, result.statusCode());
    assertEquals("GET, POST", result.headers().firstValue("Allow").orElseThrow());
    var unknown =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/order/latest"))
                .method("CUSTOM", HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(405, unknown.statusCode());
    assertEquals("GET, POST", unknown.headers().firstValue("Allow").orElseThrow());
    assertEquals("PROPFIND", send("/properties", HttpMethods.PROPFIND).body());
  }

  @Test
  void freezesRetainedScopesAndRejectsEvenEmptyGroupsAfterStartup() {
    var scope = retainedScope.get();
    var routes = app.routes();
    assertThrows(IllegalStateException.class, () -> scope.get("later", (req, res) -> {}));
    assertThrows(IllegalStateException.class, () -> scope.path("later", group -> {}));
    assertThrows(IllegalStateException.class, () -> routes.path("later", group -> {}));
    assertThrows(
        IllegalStateException.class, () -> routes.route("GET", "/later", (req, res) -> {}));
  }

  @Test
  void exposesOneRootAndRoutesStringMethodsThroughGroupedAndFlatPaths() throws Exception {
    assertSame(app.routes(), app.routes());
    assertEquals("PROPFIND", send("/string-properties", HttpMethods.PROPFIND).body());
    assertEquals("group", send("/string", HttpMethods.GET).body());
    assertEquals("42", send("/string/42", HttpMethods.POST).body());
    assertEquals(204, send("/string", HttpMethods.DELETE).statusCode());
    var rejected = send("/string", HttpMethods.PUT);
    assertEquals(405, rejected.statusCode());
    assertEquals("DELETE, GET", rejected.headers().firstValue("Allow").orElseThrow());
  }

  @ParameterizedTest
  @ValueSource(strings = {"/}id", "/<id", "/>id"})
  void rejectsIsolatedUnsupportedSymbolsDuringRouteRegistration(String pattern) throws Exception {
    try (var candidate = new Shoostr()) {
      var routes = candidate.routes();
      assertThrows(
          IllegalArgumentException.class, () -> routes.get(pattern, (request, response) -> {}));
    }
  }

  @ParameterizedTest
  @EnumSource(HttpMethods.class)
  void treatsStringAndEnumRegistrationsAsTheSameMethod(HttpMethods method) throws Exception {
    try (var candidate = new Shoostr()) {
      var routes = candidate.routes();
      assertSame(routes, routes.route(method.value(), "/{id}", (req, res) -> {}));
      assertThrows(
          IllegalArgumentException.class, () -> routes.route(method, "/{name}", (req, res) -> {}));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "get", "Get", " GET", "GET ", "CUSTOM", "*", "BASELINE_CONTROL", "GET\r\n"})
  void rejectsUnrecognizedWireMethodsDuringRegistration(String method) throws Exception {
    try (var candidate = new Shoostr()) {
      var routes = candidate.routes();
      assertThrows(
          IllegalArgumentException.class, () -> routes.route(method, "/", (req, res) -> {}));
    }
  }

  @ParameterizedTest
  @NullSource
  void rejectsNullStringMethod(String method) throws Exception {
    try (var candidate = new Shoostr()) {
      var routes = candidate.routes();
      assertThrows(NullPointerException.class, () -> routes.route(method, "/", (req, res) -> {}));
    }
  }

  @Test
  void validatesComposedParametersAndEquivalentRouteShapes() throws Exception {
    try (var candidate = new Shoostr()) {
      var routes = candidate.routes();
      Consumer<Routes> duplicateParameter = group -> group.get("/{id}", (req, res) -> {});
      assertThrows(IllegalArgumentException.class, () -> routes.path("/{id}", duplicateParameter));
      routes.path("/api", group -> group.get("/{id}", (req, res) -> {}));
      assertThrows(
          IllegalArgumentException.class, () -> routes.get("/api/{name}", (req, res) -> {}));
      routes.post("/api/{name}", (req, res) -> {});
      candidate
          .routes()
          .path("/api", group -> assertThrows(IllegalStateException.class, candidate::start));
      candidate.routes().get("/after", (req, res) -> {});
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
                assertTrue(failure.get() instanceof IllegalStateException);
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

  private HttpResponse<String> send(String path, HttpMethods method) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(5))
            .method(method.value(), HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static void orders(Routes orders) {
    orders.get((req, res) -> res.text("list"));
    orders.post((req, res) -> res.text("create"));
    orders.get("/", (req, res) -> res.text("slash"));
    orders.path(
        "/{id}",
        order -> {
          order.get((req, res) -> res.text(req.pathParam("id")));
          order.patch((req, res) -> res.text("update:" + req.pathParam("id")));
          order.delete((req, res) -> res.status(204));
        });
  }
}
