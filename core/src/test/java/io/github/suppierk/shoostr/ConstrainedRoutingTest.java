package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.testing.TestServer;
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
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000))) {
      app.routes()
          .get(
              "/users/{id:[0-9]+}",
              (request, response) -> response.text(request.pathParam("id").orElseThrow()));

      try (var test = TestServer.start(app)) {

        assertEquals(
            "123",
            test.send(
                    request -> request.path("/users/123").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            404,
            test.send(
                    request -> request.path("/users/abc").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
        assertEquals(
            404,
            test.send(
                    request -> request.path("/users/123/extra").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
      }
    }
  }

  @Test
  void completedRangeEndpointDoesNotStartAnotherRange() throws Exception {
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000))) {
      app.routes().get("/letters/{id:[a-c-e]}", (_, response) -> response.text("matched"));

      try (var test = TestServer.start(app)) {

        assertEquals(
            "matched",
            test.send(
                    request -> request.path("/letters/-").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            "matched",
            test.send(
                    request -> request.path("/letters/e").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            404,
            test.send(
                    request -> request.path("/letters/d").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
      }
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
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000))) {
      app.routes().get("/users/{*tail}", (_, response) -> response.text("tail"));
      app.routes().get("/users/{name}", (_, response) -> response.text("plain"));
      app.routes().get("/users/{id:[0-9]+}", (_, response) -> response.text("constrained"));
      app.routes().get("/users/123", (_, response) -> response.text("literal"));
      app.routes().post("/users/{id:[0-9]+}", (_, response) -> response.text("post"));

      try (var test = TestServer.start(app)) {

        assertEquals(
            "literal",
            test.send(
                    request -> request.path("/users/123").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            "constrained",
            test.send(
                    request -> request.path("/users/456").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            "plain",
            test.send(
                    request -> request.path("/users/abc").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            "tail",
            test.send(
                    request -> request.path("/users/456/more").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            "post",
            test.send(
                    request ->
                        request.path("/users/456").timeout(Duration.ofSeconds(5)).method("POST"),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            405,
            test.send(
                    request ->
                        request.path("/users/456").timeout(Duration.ofSeconds(5)).method("DELETE"),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
        assertEquals(
            "GET, POST",
            test.send(
                    request ->
                        request.path("/users/456").timeout(Duration.ofSeconds(5)).method("DELETE"),
                    HttpResponse.BodyHandlers.ofString())
                .headers()
                .firstValue("Allow")
                .orElseThrow());
      }
    }
  }

  @Test
  void firstMatchingConstrainedRouteForTheRequestedMethodWins() throws Exception {
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000))) {
      app.routes().post("/items/{id:[0-9]+}", (_, response) -> response.text("first-post"));
      app.routes().get("/items/{id:[0-9]}", (_, response) -> response.text("first-get"));
      app.routes().get("/items/{id:[0-9]+}", (_, response) -> response.text("later-get"));

      try (var test = TestServer.start(app)) {

        assertEquals(
            "first-get",
            test.send(
                    request -> request.path("/items/1").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            "later-get",
            test.send(
                    request -> request.path("/items/12").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            "first-post",
            test.send(
                    request ->
                        request.path("/items/1").timeout(Duration.ofSeconds(5)).method("POST"),
                    HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void constraintTestsCanonicalEncodedSegmentBeforePathParameterDecoding() throws Exception {
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000))) {
      app.routes().get("/users/{id:[0-9]+}", (_, response) -> response.text("digits"));
      app.routes()
          .get(
              "/digits/{id:[0-9]+}",
              (request, response) -> response.text(request.pathParam("id").orElseThrow()));
      app.routes()
          .get(
              "/users/{value}",
              (request, response) -> response.text(request.pathParam("value").orElseThrow()));

      try (var test = TestServer.start(app)) {

        assertEquals(
            "digits",
            test.send(
                    request -> request.path("/users/1").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            "digits",
            test.send(
                    request -> request.path("/users/%31").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            "1",
            test.send(
                    request -> request.path("/digits/%31").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            "a b",
            test.send(
                    request -> request.path("/users/a%20b").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .body());
      }
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
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000))) {
      var routes = app.routes();
      routes.path(
          "/api",
          nestedRoutes ->
              nestedRoutes.get("/users/{id:[0-9]{1,3}}", (_, response) -> response.text("ok")));
      assertThrows(
          IllegalArgumentException.class,
          () -> routes.get("/api/users/{other:[0-9]{1,3}}", (_, _) -> {}));

      try (var test = TestServer.start(app)) {

        assertEquals(
            "ok",
            test.send(
                    request -> request.path("/api/users/123").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            404,
            test.send(
                    request -> request.path("/api/users/1234").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
      }
    }
  }

  @Test
  void serverSentEventRouteUsesTheSameConstraint() throws Exception {
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000))) {
      app.routes()
          .sse(
              "/events/{id:[0-9]+}",
              (request, response) ->
                  response.startEventStream().send(request.pathParam("id").orElseThrow()));

      try (var test = TestServer.start(app)) {

        assertEquals(
            "data: 42\n\n",
            test.send(
                    request -> request.path("/events/42").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            404,
            test.send(
                    request -> request.path("/events/topic").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
      }
    }
  }

  @Test
  void composesConstrainedSegmentsBeforeAndAfterLiteralSuffixes() throws Exception {
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000))) {
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

      try (var test = TestServer.start(app)) {

        assertEquals(
            "42:Ab:/orders/{orderId:[0-9]+}/items/{itemCode:[A-Za-z]+}",
            test.send(
                    request -> request.path("/orders/42/items/Ab").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            404,
            test.send(
                    request -> request.path("/orders/abc/items/Ab").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
        assertEquals(
            404,
            test.send(
                    request -> request.path("/orders/42/items/12").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
      }
    }
  }

  @Test
  void simpleRepetitionNeverMatchesAnEmptySegment() throws Exception {
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000))) {
      app.routes()
          .get(
              "/flags/{letter:[A-Z]?}",
              (request, response) -> response.text(request.pathParam("letter").orElseThrow()));
      app.routes()
          .get(
              "/slugs/{slug:[A-Za-z0-9_-]*}",
              (request, response) -> response.text(request.pathParam("slug").orElseThrow()));

      try (var test = TestServer.start(app)) {

        assertEquals(
            "A",
            test.send(
                    request -> request.path("/flags/A").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            404,
            test.send(
                    request -> request.path("/flags/AB").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
        assertEquals(
            404,
            test.send(
                    request -> request.path("/flags/").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
        assertEquals(
            "a-B_9",
            test.send(
                    request -> request.path("/slugs/a-B_9").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            404,
            test.send(
                    request -> request.path("/slugs/").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
      }
    }
  }
}
