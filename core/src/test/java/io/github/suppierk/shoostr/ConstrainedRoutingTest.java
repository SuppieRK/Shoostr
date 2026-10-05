package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class ConstrainedRoutingTest {
  @Test
  void dispatchesByNamedSegmentConstraintAndExposesItsCapture() throws Exception {
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/users/{id:[0-9]+}",
              (request, response) -> response.text(request.pathParam("id").orElseThrow()));
      app.start();

      assertEquals(
          "123",
          client
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + app.port() + "/users/123"))
                      .timeout(Duration.ofSeconds(5))
                      .method("GET", HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertEquals(
          404,
          client
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + app.port() + "/users/abc"))
                      .timeout(Duration.ofSeconds(5))
                      .method("GET", HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode());
      assertEquals(
          404,
          client
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + app.port() + "/users/123/extra"))
                      .timeout(Duration.ofSeconds(5))
                      .method("GET", HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode());
    }
  }

  @Test
  void completedRangeEndpointDoesNotStartAnotherRange() throws Exception {
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000));
        var client = HttpClient.newHttpClient()) {
      app.routes().get("/letters/{id:[a-c-e]}", (_, response) -> response.text("matched"));
      app.start();

      assertEquals(
          "matched",
          client
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + app.port() + "/letters/-"))
                      .timeout(Duration.ofSeconds(5))
                      .method("GET", HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertEquals(
          "matched",
          client
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + app.port() + "/letters/e"))
                      .timeout(Duration.ofSeconds(5))
                      .method("GET", HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertEquals(
          404,
          client
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + app.port() + "/letters/d"))
                      .timeout(Duration.ofSeconds(5))
                      .method("GET", HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode());
    }
  }

  @Test
  void rejectsAnInvalidConstraintAtRegistration() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      var routes = app.routes();
      assertThrows(
          IllegalArgumentException.class, () -> routes.get("/users/{id:[0-9+}", (_, _) -> {}));
    }
  }

  @Test
  void prefersLiteralThenConstrainedThenPlainThenCatchAllAndFallsBackByMethod() throws Exception {
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000));
        var client = HttpClient.newHttpClient()) {
      app.routes().get("/users/{*tail}", (_, response) -> response.text("tail"));
      app.routes().get("/users/{name}", (_, response) -> response.text("plain"));
      app.routes().get("/users/{id:[0-9]+}", (_, response) -> response.text("constrained"));
      app.routes().get("/users/123", (_, response) -> response.text("literal"));
      app.routes().post("/users/{id:[0-9]+}", (_, response) -> response.text("post"));
      app.start();

      assertEquals(
          "literal",
          client
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + app.port() + "/users/123"))
                      .timeout(Duration.ofSeconds(5))
                      .method("GET", HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertEquals(
          "constrained",
          client
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + app.port() + "/users/456"))
                      .timeout(Duration.ofSeconds(5))
                      .method("GET", HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertEquals(
          "plain",
          client
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + app.port() + "/users/abc"))
                      .timeout(Duration.ofSeconds(5))
                      .method("GET", HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertEquals(
          "tail",
          client
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + app.port() + "/users/456/more"))
                      .timeout(Duration.ofSeconds(5))
                      .method("GET", HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertEquals(
          "post",
          client
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + app.port() + "/users/456"))
                      .timeout(Duration.ofSeconds(5))
                      .method("POST", HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertEquals(
          405,
          client
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + app.port() + "/users/456"))
                      .timeout(Duration.ofSeconds(5))
                      .method("DELETE", HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode());
      assertEquals(
          "GET, POST",
          client
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + app.port() + "/users/456"))
                      .timeout(Duration.ofSeconds(5))
                      .method("DELETE", HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .headers()
              .firstValue("Allow")
              .orElseThrow());
    }
  }

  @Test
  void firstMatchingConstrainedRouteForTheRequestedMethodWins() throws Exception {
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000));
        var client = HttpClient.newHttpClient()) {
      app.routes().post("/items/{id:[0-9]+}", (_, response) -> response.text("first-post"));
      app.routes().get("/items/{id:[0-9]}", (_, response) -> response.text("first-get"));
      app.routes().get("/items/{id:[0-9]+}", (_, response) -> response.text("later-get"));
      app.start();

      assertEquals(
          "first-get",
          client
              .send(
                  HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/items/1"))
                      .timeout(Duration.ofSeconds(5))
                      .method("GET", HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertEquals(
          "later-get",
          client
              .send(
                  HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/items/12"))
                      .timeout(Duration.ofSeconds(5))
                      .method("GET", HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertEquals(
          "first-post",
          client
              .send(
                  HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/items/1"))
                      .timeout(Duration.ofSeconds(5))
                      .method("POST", HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .body());
    }
  }

  @Test
  void constraintTestsCanonicalEncodedSegmentBeforePathParameterDecoding() throws Exception {
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000));
        var client = HttpClient.newHttpClient()) {
      app.routes().get("/users/{id:[0-9]+}", (_, response) -> response.text("digits"));
      app.routes()
          .get(
              "/digits/{id:[0-9]+}",
              (request, response) -> response.text(request.pathParam("id").orElseThrow()));
      app.routes()
          .get(
              "/users/{value}",
              (request, response) -> response.text(request.pathParam("value").orElseThrow()));
      app.start();

      assertEquals(
          "digits",
          client
              .send(
                  HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/users/1"))
                      .timeout(Duration.ofSeconds(5))
                      .method("GET", HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertEquals(
          "digits",
          client
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + app.port() + "/users/%31"))
                      .timeout(Duration.ofSeconds(5))
                      .method("GET", HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertEquals(
          "1",
          client
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + app.port() + "/digits/%31"))
                      .timeout(Duration.ofSeconds(5))
                      .method("GET", HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertEquals(
          "a b",
          client
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + app.port() + "/users/a%20b"))
                      .timeout(Duration.ofSeconds(5))
                      .method("GET", HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .body());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/users/{id:(a+)+}",
        "/users/{id:[0-9]+[A-Z]+}",
        "/users/{id:[0-9]{1,}}",
        "/users/{id:[0-9]{999999}}",
        "/users/{id:[z-a]+}",
        "/users/{id:[--z]}",
        "/users/{id:[A-z]+}",
        "/users/{id:[]+}",
        "/users/{id:[0-9]{0}}",
        "/users/{id:[0-9]+",
        "/users/{bad name:[0-9]+}"
      })
  void rejectsUnsupportedOrMalformedConstraintSyntax(String pattern) throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      var routes = app.routes();
      assertThrows(IllegalArgumentException.class, () -> routes.get(pattern, (_, _) -> {}));
    }
  }

  @Test
  void acceptsBoundedRepetitionAndRejectsEquivalentConstraintShapes() throws Exception {
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000));
        var client = HttpClient.newHttpClient()) {
      var routes = app.routes();
      routes.path(
          "/api",
          nestedRoutes ->
              nestedRoutes.get("/users/{id:[0-9]{1,3}}", (_, response) -> response.text("ok")));
      assertThrows(
          IllegalArgumentException.class,
          () -> routes.get("/api/users/{other:[0-9]{1,3}}", (_, _) -> {}));
      app.start();

      assertEquals(
          "ok",
          client
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + app.port() + "/api/users/123"))
                      .timeout(Duration.ofSeconds(5))
                      .method("GET", HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertEquals(
          404,
          client
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + app.port() + "/api/users/1234"))
                      .timeout(Duration.ofSeconds(5))
                      .method("GET", HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode());
    }
  }

  @Test
  void serverSentEventRouteUsesTheSameConstraint() throws Exception {
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .sse(
              "/events/{id:[0-9]+}",
              (request, response) ->
                  response.startEventStream().send(request.pathParam("id").orElseThrow()));
      app.start();

      assertEquals(
          "data: 42\n\n",
          client
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + app.port() + "/events/42"))
                      .timeout(Duration.ofSeconds(5))
                      .method("GET", HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertEquals(
          404,
          client
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + app.port() + "/events/topic"))
                      .timeout(Duration.ofSeconds(5))
                      .method("GET", HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode());
    }
  }

  @Test
  void composesConstrainedSegmentsBeforeAndAfterLiteralSuffixes() throws Exception {
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .path(
              "/orders/{orderId:[0-9]+}",
              order ->
                  order.get(
                      "/items/{itemCode:[A-Za-z]+}",
                      (request, response) ->
                          response.text(
                              request.pathParam("orderId").orElseThrow()
                                  + ":"
                                  + request.pathParam("itemCode").orElseThrow()
                                  + ":"
                                  + request.routePattern().orElseThrow())));
      app.start();

      assertEquals(
          "42:Ab:/orders/{orderId:[0-9]+}/items/{itemCode:[A-Za-z]+}",
          client
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + app.port() + "/orders/42/items/Ab"))
                      .timeout(Duration.ofSeconds(5))
                      .method("GET", HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertEquals(
          404,
          client
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + app.port() + "/orders/abc/items/Ab"))
                      .timeout(Duration.ofSeconds(5))
                      .method("GET", HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode());
      assertEquals(
          404,
          client
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + app.port() + "/orders/42/items/12"))
                      .timeout(Duration.ofSeconds(5))
                      .method("GET", HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode());
    }
  }

  @Test
  void simpleRepetitionNeverMatchesAnEmptySegment() throws Exception {
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/flags/{letter:[A-Z]?}",
              (request, response) -> response.text(request.pathParam("letter").orElseThrow()));
      app.routes()
          .get(
              "/slugs/{slug:[A-Za-z0-9_-]*}",
              (request, response) -> response.text(request.pathParam("slug").orElseThrow()));
      app.start();

      assertEquals(
          "A",
          client
              .send(
                  HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/flags/A"))
                      .timeout(Duration.ofSeconds(5))
                      .method("GET", HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertEquals(
          404,
          client
              .send(
                  HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/flags/AB"))
                      .timeout(Duration.ofSeconds(5))
                      .method("GET", HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode());
      assertEquals(
          404,
          client
              .send(
                  HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/flags/"))
                      .timeout(Duration.ofSeconds(5))
                      .method("GET", HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode());
      assertEquals(
          "a-B_9",
          client
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + app.port() + "/slugs/a-B_9"))
                      .timeout(Duration.ofSeconds(5))
                      .method("GET", HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertEquals(
          404,
          client
              .send(
                  HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/slugs/"))
                      .timeout(Duration.ofSeconds(5))
                      .method("GET", HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode());
    }
  }
}
